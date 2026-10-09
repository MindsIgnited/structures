
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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import javax.net.ssl.SSLException;

import org.kinotic.continuum.api.exceptions.AuthenticationException;
import org.kinotic.structures.auth.api.config.OidcSecurityServiceProperties;
import org.kinotic.structures.auth.api.domain.OidcProvider;
import org.kinotic.structures.auth.api.services.JwksService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
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
 * the cached document meanwhile, and a failed refresh keeps it until the next one is due. So an IdP outage only
 * fails lookups that need something not already cached, such as a key id that is not in the cached key set.
 * A load that fails, with nothing cached, is retried no sooner than
 * {@link OidcSecurityServiceProperties#getJwksRetryBackoff()} later; lookups meanwhile fail with its error.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "oidc-security-service", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DefaultJwksService implements JwksService {

    private static final Duration KEY_SET_REFRESH = Duration.ofHours(1);
    // how long a key set may be served while every refresh fails, after which lookups need a successful fetch
    private static final Duration KEY_SET_MAX_STALENESS = Duration.ofHours(24);
    private static final Duration WELL_KNOWN_REFRESH = Duration.ofHours(24);
    // rejected tokens are logged at warn at most this often, at debug otherwise, since anyone can send them
    private static final Duration REJECTION_WARN_INTERVAL = Duration.ofMinutes(1);
    private static final int MAX_LOGGED_CLAIM_LENGTH = 200;
    private static final String KEY_SET = "JWKS";
    private static final String WELL_KNOWN = "OIDC discovery document";

    private final OidcSecurityServiceProperties properties;
    private final WebClient webClient;
    private final WebClient insecureWebClient;
    private final ObjectMapper objectMapper;
    private final Parser<Jwk<?>> jwkParser;
    // keyed by JWKS url
    private final AsyncLoadingCache<String, KeySet> keySetCache;
    // keyed by JWKS url, the refresh an unknown key id last started
    private final Map<String, UnknownKidRefresh> unknownKidRefreshes = new ConcurrentHashMap<>();
    // keyed by issuer
    private final AsyncLoadingCache<String, JsonNode> wellKnownCache;
    // keyed by description and cache key, the last load that failed
    private final Map<String, FailedLoad> failedLoads = new ConcurrentHashMap<>();
    // keyed by description and cache key, the error of the last refresh that failed and kept the cached value
    private final Map<String, Throwable> failedRefreshes = new ConcurrentHashMap<>();
    private final AtomicLong nextRejectionWarnNanos = new AtomicLong(System.nanoTime());

    public DefaultJwksService(DefaultCaffeineCacheFactory cacheFactory, OidcSecurityServiceProperties properties) {
        this.properties = properties;
        this.webClient = createWebClient(false);
        this.insecureWebClient = createWebClient(true);
        this.objectMapper = JsonMapper.builder().build();
        this.jwkParser = Jwks.parser().build();

        // KEY_SET_MAX_STALENESS is checked in getKey, since a failed refresh keeping the old set resets its write time
        this.keySetCache = cacheFactory.<String, KeySet>newBuilder()
                .name("jwksKeySetCache")
                .refreshAfterWrite(KEY_SET_REFRESH)
                .maximumSize(100)
                .buildAsync(loader(KEY_SET, this::fetchKeySet));

        // Only issuers of enabled providers are ever loaded, so entries do not need to expire
        this.wellKnownCache = cacheFactory.<String, JsonNode>newBuilder()
                .name("jwksWellKnownCache")
                .refreshAfterWrite(WELL_KNOWN_REFRESH)
                .maximumSize(100)
                .buildAsync(loader(WELL_KNOWN, this::fetchWellKnownConfiguration));
    }

    /**
     * A failed load completes exceptionally, which Caffeine does not cache, and logs with its cause, and is
     * remembered for {@link #getCached}. A failed refresh keeps the cached value, until the next refresh is due,
     * and its error is remembered so a lookup that needed the refresh can report it.
     */
    private <V> AsyncCacheLoader<String, V> loader(String description, Function<String, CompletableFuture<V>> fetch) {
        return new AsyncCacheLoader<>() {
            @Override
            public CompletableFuture<V> asyncLoad(String key, Executor executor) {
                String failureKey = description + " " + key;
                return fetch.apply(key).whenComplete((value, error) -> {
                    if (error != null) {
                        failedLoads.put(failureKey, new FailedLoad(System.nanoTime(), CompletionErrors.unwrap(error)));
                    } else {
                        failedLoads.remove(failureKey);
                    }
                });
            }

            @Override
            public CompletableFuture<V> asyncReload(String key, V oldValue, Executor executor) {
                String failureKey = description + " " + key;
                return fetch.apply(key).handle((value, error) -> {
                    if (error == null) {
                        failedRefreshes.remove(failureKey);
                        return value;
                    }
                    Throwable cause = CompletionErrors.unwrap(error);
                    failedRefreshes.put(failureKey, cause);
                    log.warn("Keeping the cached {} for {}, refreshing it failed: {}", description, key, cause.getMessage());
                    return oldValue;
                });
            }
        };
    }

    /**
     * The cached value, loading it if absent, unless the last load of it failed within the retry backoff
     */
    private <V> CompletableFuture<V> getCached(AsyncLoadingCache<String, V> cache, String description, String key) {
        FailedLoad failed = failedLoads.get(description + " " + key);
        if (failed != null
                && System.nanoTime() - failed.atNanos() < properties.getJwksRetryBackoff().toNanos()
                && cache.getIfPresent(key) == null) {
            return CompletableFuture.failedFuture(failed.error());
        }
        return cache.get(key);
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
     * Uses insecure client for .local and .localhost hosts (development only).
     */
    private WebClient getWebClientForUrl(URI uri) {
        String host = uri.getHost();
        if (host != null && (host.endsWith(".local") || host.endsWith(".localhost"))) {
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
        return getCached(wellKnownCache, WELL_KNOWN, issuer).copy();
    }

    private CompletableFuture<JsonNode> fetchWellKnownConfiguration(String issuer) {
        String wellKnownUrl = StringUtils.trimTrailingCharacter(issuer, '/') + "/.well-known/openid-configuration";
        return fetchJson(wellKnownUrl, WELL_KNOWN)
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
        return getCached(wellKnownCache, WELL_KNOWN, issuer).thenApply(config -> config.get("jwks_uri").asString());
    }

    /**
     * Get a key by its key ID (kid)
     */
    public CompletableFuture<Jwk<? extends Key>> getKey(String issuer, String kid) {
        if (kid == null) {
            return CompletableFuture.failedFuture(new JwksFetchException("No key id (kid) given for issuer: " + issuer));
        }
        return getJwksUrl(issuer).thenCompose(jwksUrl -> {
            evictIfTooStale(jwksUrl);
            return getCached(keySetCache, KEY_SET, jwksUrl).thenCompose(keySet -> findKey(issuer, kid, jwksUrl, keySet));
        });
    }

    /**
     * Evicts the key set if every refresh has failed for longer than {@link #KEY_SET_MAX_STALENESS}, so the
     * lookup loads it, or fails. Checked before the lookup, which would otherwise start a refresh that is then
     * thrown away. Compares by value, since a failed refresh stores the same set in a new future.
     */
    private void evictIfTooStale(String jwksUrl) {
        KeySet present = keySetCache.synchronous().policy().getIfPresentQuietly(jwksUrl);
        if (present != null && System.nanoTime() - present.fetchedAtNanos() > KEY_SET_MAX_STALENESS.toNanos()) {
            // a no-op if a concurrent lookup already replaced it
            keySetCache.synchronous().asMap().remove(jwksUrl, present);
        }
    }

    private CompletableFuture<Jwk<? extends Key>> findKey(String issuer, String kid, String jwksUrl, KeySet keySet) {
        Jwk<? extends Key> jwk = keySet.keys().get(kid);
        if (jwk != null) {
            return CompletableFuture.completedFuture(jwk);
        }
        CompletableFuture<KeySet> refresh = refreshForUnknownKid(jwksUrl, keySet);
        if (refresh == null) {
            return CompletableFuture.failedFuture(keyNotFound(issuer, kid));
        }
        return refresh.thenCompose(refreshed -> {
            Jwk<? extends Key> rotated = refreshed.keys().get(kid);
            if (rotated != null) {
                return CompletableFuture.completedFuture(rotated);
            }
            // The same set back means the refresh failed and kept it, so report why rather than a missing key
            Throwable refreshError = refreshed == keySet ? failedRefreshes.get(KEY_SET + " " + jwksUrl) : null;
            return CompletableFuture.failedFuture(refreshError != null
                                                          ? new JwksFetchException("Could not refresh the JWKS to find key id '"
                                                                                           + sanitize(kid) + "': " + refreshError.getMessage(), refreshError)
                                                          : keyNotFound(issuer, kid));
        });
    }

    /**
     * The provider may have rotated its keys, so an unknown key id refreshes the key set, at most once per
     * cooldown, and not when the set was fetched within the cooldown. Lookups within the cooldown of a refresh
     * use that refresh, so a burst of tokens signed with a new key all wait on the one fetch. The cached set
     * keeps serving known key ids meanwhile, and stays cached if the refresh fails.
     *
     * @return the refresh to look the key id up in, or null when the key set may not be refreshed yet
     */
    private CompletableFuture<KeySet> refreshForUnknownKid(String jwksUrl, KeySet keySet) {
        long cooldown = properties.getJwksRefreshCooldown().toNanos();
        long now = System.nanoTime();
        CompletableFuture<KeySet> started = new CompletableFuture<>();
        UnknownKidRefresh refresh = unknownKidRefreshes.compute(jwksUrl, (url, last) -> {
            if ((last != null && now - last.startedNanos() < cooldown) || now - keySet.fetchedAtNanos() < cooldown) {
                return last;
            }
            return new UnknownKidRefresh(now, started);
        });
        if (refresh != null && refresh.future() == started) {
            // started here, outside compute, so the fetch is not set up while holding the map's lock
            keySetCache.synchronous().refresh(jwksUrl).whenComplete((refreshed, error) -> {
                if (error != null) {
                    started.completeExceptionally(error);
                } else {
                    started.complete(refreshed);
                }
            });
        }
        // started after now (by a concurrent lookup) is fine too, the difference is then negative
        return refresh != null && now - refresh.startedNanos() < cooldown ? refresh.future() : null;
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
        unknownKidRefreshes.clear();
        failedLoads.clear();
        failedRefreshes.clear();
    }

    /**
     * The issuer is not the authority of any enabled provider, either none is configured for it or it is
     * disabled. An {@link AuthenticationException}, so the gateway reports this message to the client.
     */
    private AuthenticationException notConfigured(String issuer) {
        String safeIssuer = sanitize(issuer);
        logRejection("Rejecting token from issuer {}, no enabled OIDC provider has it as its authority", safeIssuer);
        return new AuthenticationException("Issuer not allowed: " + safeIssuer);
    }

    private JwksFetchException keyNotFound(String issuer, String kid) {
        String safeKid = sanitize(kid);
        logRejection("Rejecting token from issuer {}, its key id {} is not in the issuer's JWKS", issuer, safeKid);
        return new JwksFetchException("Key with kid '" + safeKid + "' not found in JWKS for issuer: " + issuer);
    }

    /**
     * Logs at warn at most once per {@link #REJECTION_WARN_INTERVAL} and at debug otherwise, since the tokens
     * being rejected are unverified and anyone can send them
     */
    private void logRejection(String format, Object... args) {
        long now = System.nanoTime();
        long next = nextRejectionWarnNanos.get();
        if (now - next >= 0 && nextRejectionWarnNanos.compareAndSet(next, now + REJECTION_WARN_INTERVAL.toNanos())) {
            log.warn(format + " (further rejections within " + REJECTION_WARN_INTERVAL + " are logged at debug)", args);
        } else {
            log.debug(format, args);
        }
    }

    /**
     * A claim from an unverified token, made safe to log or return: shortened, with control characters replaced
     */
    private static String sanitize(String claim) {
        if (claim == null) {
            return null;
        }
        String shortened = claim.length() > MAX_LOGGED_CLAIM_LENGTH
                ? claim.substring(0, MAX_LOGGED_CLAIM_LENGTH) + "..."
                : claim;
        StringBuilder safe = new StringBuilder(shortened.length());
        shortened.codePoints().forEach(c -> safe.appendCodePoint(Character.isISOControl(c) ? '?' : c));
        return safe.toString();
    }

    private record KeySet(Map<String, Jwk<? extends Key>> keys, long fetchedAtNanos) {
        @Override
        public String toString() {
            return "KeySet" + keys.keySet();
        }
    }

    private record UnknownKidRefresh(long startedNanos, CompletableFuture<KeySet> future) {}

    private record FailedLoad(long atNanos, Throwable error) {}

    private static class JwksFetchException extends RuntimeException {
        JwksFetchException(String message) {
            super(message);
        }

        JwksFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
