
package org.kinotic.structures.auth.internal.services;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

import org.kinotic.structures.auth.api.config.OidcSecurityServiceProperties;
import org.kinotic.structures.auth.api.domain.OidcProvider;
import org.kinotic.structures.auth.api.services.JwksService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;

import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.io.Parser;
import io.jsonwebtoken.security.Jwks;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Fetches and caches OIDC discovery documents and JWKS key sets.
 * <p>
 * Only issuers that match an enabled provider's authority are ever fetched from, so a token cannot make this
 * service call out to a URL of its choosing. Every fetch is bounded by the configured timeouts, failed fetches
 * are not cached, and concurrent lookups of the same document share one fetch.
 * <p>
 * Cached documents are refreshed rather than expired: once due, the next lookup starts a refresh and is served
 * the cached document meanwhile, and a failed refresh keeps it. So an IdP outage only fails lookups that need
 * something not already cached, such as a key id that is not in the cached key set.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "oidc-security-service", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DefaultJwksService implements JwksService {

    private static final Duration KEY_SET_REFRESH = Duration.ofHours(1);
    // how long a key set may be served while every refresh fails, after which lookups need a successful fetch
    private static final Duration KEY_SET_MAX_STALENESS = Duration.ofHours(24);
    private static final Duration WELL_KNOWN_REFRESH = Duration.ofHours(24);

    private final OidcSecurityServiceProperties properties;
    private final WebClient webClient;
    private final WebClient insecureWebClient;
    private final ObjectMapper objectMapper;
    private final Parser<Jwk<?>> jwkParser;
    // keyed by JWKS url
    private final AsyncLoadingCache<String, KeySet> keySetCache;
    // keyed by JWKS url, when an unknown key id last caused a refresh
    private final Map<String, Long> lastUnknownKidRefreshNanos = new ConcurrentHashMap<>();
    // keyed by issuer
    private final AsyncLoadingCache<String, JsonNode> wellKnownCache;

    public DefaultJwksService(DefaultCaffeineCacheFactory cacheFactory, OidcSecurityServiceProperties properties) {
        this.properties = properties;
        this.webClient = createWebClient(false);
        this.insecureWebClient = createWebClient(true);
        this.objectMapper = JsonMapper.builder().build();
        this.jwkParser = Jwks.parser().build();

        // A failed load completes its future exceptionally, and Caffeine drops those, so failures are not cached.
        // Caffeine also logs each failed load and refresh, with the cause, so fetchJson does not log them again.
        this.keySetCache = cacheFactory.<String, KeySet>newBuilder()
                .name("jwksKeySetCache")
                .refreshAfterWrite(KEY_SET_REFRESH)
                .expireAfterWrite(KEY_SET_MAX_STALENESS)
                .maximumSize(100)
                .buildAsync((jwksUrl, executor) -> fetchKeySet(jwksUrl));

        // Only issuers of enabled providers are ever loaded, so entries do not need to expire
        this.wellKnownCache = cacheFactory.<String, JsonNode>newBuilder()
                .name("jwksWellKnownCache")
                .refreshAfterWrite(WELL_KNOWN_REFRESH)
                .maximumSize(100)
                .buildAsync((issuer, executor) -> fetchWellKnownConfiguration(issuer));
    }

    /**
     * Discovery and JWKS documents are fetched a few times an hour at most, so connections are not pooled.
     * A pooled connection would sit idle until the next fetch and could be silently dropped by a NAT, load
     * balancer or conntrack table in the meantime, and a request written to such a connection waits on TCP
     * retransmission, around 15 minutes on Linux, before failing.
     * <p>
     * The insecure client trusts all certificates. WARNING: Only for development with .local domains!
     */
    private WebClient createWebClient(boolean insecure) {
        HttpClient httpClient = HttpClient.create(ConnectionProvider.newConnection())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.getJwksConnectTimeout().toMillis())
                .responseTimeout(properties.getJwksRequestTimeout());
        if (insecure) {
            try {
                SslContext sslContext = SslContextBuilder
                        .forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .build();
                httpClient = httpClient.secure(spec -> spec.sslContext(sslContext));
            } catch (SSLException e) {
                log.warn("Failed to create insecure WebClient, falling back to default", e);
            }
        }
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * Get the appropriate WebClient based on the URL.
     * Uses insecure client for .local hosts (development only).
     */
    private WebClient getWebClientForUrl(URI uri) {
        String host = uri.getHost();
        if (host != null && host.endsWith(".local")) {
            log.debug("Using insecure WebClient for .local host: {}", uri);
            return insecureWebClient;
        }
        return webClient;
    }

    /**
     * Get the well-known configuration for an OIDC issuer
     */
    public CompletableFuture<JsonNode> getWellKnownConfiguration(String issuer) {
        if (properties.findEnabledProviders(issuer).isEmpty()) {
            return CompletableFuture.failedFuture(notConfigured(issuer));
        }
        // copy, so a caller cannot complete or cancel the future that is shared through the cache
        return wellKnownCache.get(issuer).copy();
    }

    private CompletableFuture<JsonNode> fetchWellKnownConfiguration(String issuer) {
        String wellKnownUrl = stripTrailingSlash(issuer) + "/.well-known/openid-configuration";
        return fetchJson(wellKnownUrl, "OIDC discovery document")
                .thenApply(config -> {
                    // Checked before the document is cached, so a broken document is fetched again next time
                    JsonNode jwksUri = config.get("jwks_uri");
                    if (jwksUri == null || !jwksUri.isString() || jwksUri.asString().isBlank()) {
                        throw new JwksFetchException("No jwks_uri in the OIDC discovery document at " + wellKnownUrl);
                    }
                    JsonNode documentIssuer = config.get("issuer");
                    if (documentIssuer == null || !issuer.equals(documentIssuer.asString())) {
                        log.warn("OIDC discovery document at {} names issuer {}, expected {}",
                                 wellKnownUrl, documentIssuer, issuer);
                    }
                    return config;
                });
    }

    /**
     * Get the JWKS URL for an issuer, from the provider's jwksUri when configured, otherwise from the
     * well-known configuration
     */
    public CompletableFuture<String> getJwksUrl(String issuer) {
        List<OidcProvider> providers = properties.findEnabledProviders(issuer);
        if (providers.isEmpty()) {
            return CompletableFuture.failedFuture(notConfigured(issuer));
        }
        Optional<String> configured = providers.stream()
                                               .map(OidcProvider::getJwksUri)
                                               .filter(uri -> uri != null && !uri.isBlank())
                                               .findFirst();
        if (configured.isPresent()) {
            return CompletableFuture.completedFuture(configured.get());
        }
        return getWellKnownConfiguration(issuer).thenApply(config -> config.get("jwks_uri").asString());
    }

    /**
     * Get a key by its key ID (kid)
     */
    public CompletableFuture<Jwk<? extends Key>> getKey(String issuer, String kid) {
        if (kid == null) {
            return CompletableFuture.failedFuture(new JwksFetchException("No key id (kid) given for issuer: " + issuer));
        }
        return getJwksUrl(issuer).thenCompose(jwksUrl -> keySetCache.get(jwksUrl).thenCompose(keySet -> {
            Jwk<? extends Key> jwk = keySet.keys().get(kid);
            if (jwk != null) {
                return CompletableFuture.completedFuture(jwk);
            }
            if (!unknownKidMayRefresh(jwksUrl, keySet)) {
                return CompletableFuture.failedFuture(keyNotFound(issuer, kid));
            }
            // The provider may have rotated its keys. Concurrent refreshes of one key set share a fetch, the
            // cached set keeps serving other lookups meanwhile, and stays cached if the refresh fails.
            return keySetCache.synchronous().refresh(jwksUrl).thenCompose(refreshed -> {
                Jwk<? extends Key> rotated = refreshed.keys().get(kid);
                return rotated != null
                        ? CompletableFuture.completedFuture(rotated)
                        : CompletableFuture.failedFuture(keyNotFound(issuer, kid));
            });
        }));
    }

    /**
     * An unknown key id refreshes a key set at most once per cooldown, counted from the last fetch of the set
     * or the last refresh an unknown key id started, whichever is later, so failed refreshes are limited too
     */
    private boolean unknownKidMayRefresh(String jwksUrl, KeySet keySet) {
        long cooldownNanos = properties.getJwksRefreshCooldown().toNanos();
        long now = System.nanoTime();
        if (now - keySet.fetchedAtNanos() < cooldownNanos) {
            return false;
        }
        boolean[] allowed = {false};
        lastUnknownKidRefreshNanos.compute(jwksUrl, (url, last) -> {
            if (last == null || now - last >= cooldownNanos) {
                allowed[0] = true;
                return now;
            }
            return last;
        });
        return allowed[0];
    }

    private CompletableFuture<KeySet> fetchKeySet(String jwksUrl) {
        return fetchJson(jwksUrl, "JWKS")
                .thenApply(jwks -> {
                    JsonNode keys = jwks.get("keys");
                    if (keys == null || !keys.isArray()) {
                        throw new JwksFetchException("Invalid JWKS at " + jwksUrl + ": no keys array found");
                    }
                    Map<String, Jwk<? extends Key>> keysByKid = new HashMap<>();
                    for (JsonNode key : keys) {
                        JsonNode keyKid = key.get("kid");
                        JsonNode use = key.get("use");
                        if (keyKid == null || !keyKid.isString() || (use != null && "enc".equals(use.asString()))) {
                            continue;
                        }
                        try {
                            keysByKid.put(keyKid.asString(), jwkParser.parse(objectMapper.writeValueAsString(key)));
                        } catch (Exception e) {
                            log.debug("Skipping key {} in JWKS at {} that could not be parsed", keyKid.asString(), jwksUrl, e);
                        }
                    }
                    // An empty set is treated as a failure, so it is not cached for the full TTL
                    if (keysByKid.isEmpty()) {
                        throw new JwksFetchException("No usable signing keys in JWKS at " + jwksUrl);
                    }
                    return new KeySet(Map.copyOf(keysByKid), System.nanoTime());
                });
    }

    private CompletableFuture<JsonNode> fetchJson(String url, String description) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(new JwksFetchException("Invalid " + description + " URL: " + url, e));
        }
        Duration timeout = properties.getJwksRequestTimeout();
        return getWebClientForUrl(uri).get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .switchIfEmpty(Mono.error(() -> new JwksFetchException("Empty " + description + " from " + url)))
                // responseTimeout only covers waiting for the response headers, so the whole fetch is bounded too
                .timeout(timeout)
                .map(objectMapper::readTree)
                .onErrorMap(e -> !(e instanceof JwksFetchException),
                            e -> new JwksFetchException(e instanceof TimeoutException
                                                                ? "Timed out after " + timeout + " fetching " + description + " from " + url
                                                                : "Failed to fetch " + description + " from " + url + ": " + e, e))
                .toFuture();
    }

    /**
     * Extract the issuer and key ID from a JWT token header
     */
    public CompletableFuture<Jwk<? extends Key>> getKeyFromToken(String token) {
        try {
            // Parse the JWT header to get the key ID
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new JwksFetchException("Invalid JWT token format");
            }

            // Decode the header
            JsonNode header = objectMapper.readTree(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            String kid = header.get("kid") != null ? header.get("kid").asString() : null;
            if (kid == null) {
                throw new JwksFetchException("JWT token does not contain a key ID (kid)");
            }

            // Parse the payload to get the issuer
            JsonNode payload = objectMapper.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            String issuer = payload.get("iss") != null ? payload.get("iss").asString() : null;
            if (issuer == null) {
                throw new JwksFetchException("JWT token does not contain an issuer (iss)");
            }
            return getKey(issuer, kid);
        } catch (Exception e) {
            log.debug("Failed to extract key information from JWT token", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Clear all caches (useful for testing or manual cache invalidation)
     */
    public void clearCaches() {
        keySetCache.synchronous().invalidateAll();
        wellKnownCache.synchronous().invalidateAll();
        lastUnknownKidRefreshNanos.clear();
    }

    private static JwksFetchException notConfigured(String issuer) {
        log.warn("Rejecting token from issuer {}, no enabled OIDC provider has it as its authority", issuer);
        return new JwksFetchException("No enabled OIDC provider configured for issuer: " + issuer);
    }

    private static JwksFetchException keyNotFound(String issuer, String kid) {
        return new JwksFetchException("Key with kid '" + kid + "' not found in JWKS for issuer: " + issuer);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record KeySet(Map<String, Jwk<? extends Key>> keys, long fetchedAtNanos) {
        @Override
        public String toString() {
            return "KeySet" + keys.keySet();
        }
    }

    private static class JwksFetchException extends RuntimeException {
        JwksFetchException(String message) {
            super(message);
        }

        JwksFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
