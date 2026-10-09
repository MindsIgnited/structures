package org.kinotic.structures.tests.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.Jwks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kinotic.continuum.api.exceptions.AuthenticationException;
import org.kinotic.continuum.api.security.Participant;
import org.kinotic.structures.auth.api.config.OidcSecurityServiceProperties;
import org.kinotic.structures.auth.api.domain.OidcProvider;
import org.kinotic.structures.auth.internal.services.DefaultCaffeineCacheFactory;
import org.kinotic.structures.auth.internal.services.DefaultJwksService;
import org.kinotic.structures.auth.internal.services.OidcSecurityService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link DefaultJwksService} against a fake identity provider that can be told to hang, fail or rotate
 * keys, so the failure modes of discovery and JWKS fetching are tested without Keycloak.
 * <p>
 * Each call is given {@link #DEADLINE}. A call that has not completed by then is a hang, which is what production
 * sees as a request or STOMP CONNECT that never returns.
 */
class JwksResilienceTest {

    private static final Duration DEADLINE = Duration.ofSeconds(15);
    private static final String AUDIENCE = "structures-client";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private FakeIdp idp;
    private FakeIdp rogueIdp;
    private KeyPair keyPair1;
    private KeyPair keyPair2;

    @BeforeEach
    void setUp() throws IOException {
        idp = new FakeIdp();
        rogueIdp = new FakeIdp();
        keyPair1 = Jwts.SIG.RS256.keyPair().build();
        keyPair2 = Jwts.SIG.RS256.keyPair().build();
        idp.serveDiscovery();
        idp.serveKeys(jwks(Map.of("k1", keyPair1)));
        rogueIdp.serveDiscovery();
        rogueIdp.serveKeys(jwks(Map.of("k1", keyPair1)));
    }

    @AfterEach
    void tearDown() {
        idp.close();
        rogueIdp.close();
    }

    @Test
    void jwksEndpointThatNeverRespondsDoesNotHang() {
        idp.jwks = FakeIdp.hang();
        DefaultJwksService service = newService(properties());

        Outcome<Jwk<? extends Key>> outcome = await(service.getKey(idp.issuer, "k1"));

        assertFalse(outcome.hung(), "getKey hung for " + DEADLINE + " on a JWKS endpoint that never responds");
        assertNotNull(outcome.error());
    }

    @Test
    void discoveryEndpointThatNeverRespondsDoesNotHang() {
        idp.wellKnown = FakeIdp.hang();
        DefaultJwksService service = newService(properties());

        Outcome<Jwk<? extends Key>> outcome = await(service.getKey(idp.issuer, "k1"));

        assertFalse(outcome.hung(), "getKey hung for " + DEADLINE + " on a discovery endpoint that never responds");
        assertNotNull(outcome.error());
    }

    @Test
    void authenticateDoesNotHangWhenTheIdpStopsResponding() {
        idp.jwks = FakeIdp.hang();
        OidcSecurityServiceProperties properties = properties();
        OidcSecurityService securityService = new OidcSecurityService(properties, newService(properties));

        Outcome<Participant> outcome = await(securityService.authenticate(
                Map.of("authorization", "Bearer " + token(idp.issuer, "k1", keyPair1))));

        assertFalse(outcome.hung(), "authenticate hung for " + DEADLINE + " while the IdP was not responding");
        assertNotNull(outcome.error());
    }

    @Test
    void tokenFromAnUnconfiguredIssuerIsRejectedWithoutFetchingAnything() {
        // The rogue issuer is not configured, and it hangs, like an IdP that is not reachable from the cluster
        rogueIdp.wellKnown = FakeIdp.hang();
        DefaultJwksService service = newService(properties());

        Outcome<Jwk<? extends Key>> outcome = await(service.getKeyFromToken(token(rogueIdp.issuer, "k1", keyPair1)));

        assertFalse(outcome.hung(), "getKeyFromToken hung on a token from an issuer that is not configured");
        assertNotNull(outcome.error(), "a key was returned for an issuer that is not configured");
        assertEquals(0, rogueIdp.totalHits(), "fetched from an issuer that is not configured");
    }

    @Test
    void tokenFromTheIssuerOfADisabledProviderIsNotAllowed() throws Exception {
        OidcSecurityServiceProperties properties = properties();
        properties.getOidcProviders().getFirst().setEnabled(false);
        OidcSecurityService securityService = new OidcSecurityService(properties, newService(properties));

        // what the gateway sees, it reports an AuthenticationException's message and wraps anything else
        Throwable error = securityService.authenticate(Map.of("authorization", "Bearer " + token(idp.issuer, "k1", keyPair1)))
                                         .handle((participant, throwable) -> throwable)
                                         .get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);

        assertInstanceOf(AuthenticationException.class, error);
        assertEquals("Issuer not allowed: " + idp.issuer, error.getMessage());
        assertEquals(0, idp.totalHits(), "fetched from the issuer of a disabled provider");
    }

    @Test
    void discoveryDocumentWithoutJwksUriIsNotCached() throws Exception {
        // A misconfigured or half started IdP serves discovery without jwks_uri, then recovers
        idp.wellKnown = FakeIdp.ok(objectMapper.writeValueAsString(Map.of("issuer", idp.issuer)));
        DefaultJwksService service = newService(properties());

        assertNotNull(await(service.getKey(idp.issuer, "k1")).error());

        idp.serveDiscovery();
        Outcome<Jwk<? extends Key>> recovered = await(service.getKey(idp.issuer, "k1"));

        assertNull(recovered.error(), "the broken discovery document was cached: " + recovered.error());
        assertEquals("k1", recovered.value().getId());
    }

    @Test
    void failedJwksFetchIsNotCached() throws Exception {
        idp.jwks = FakeIdp.status(503);
        DefaultJwksService service = newService(properties());

        assertNotNull(await(service.getKey(idp.issuer, "k1")).error());

        idp.serveKeys(jwks(Map.of("k1", keyPair1)));
        Outcome<Jwk<? extends Key>> recovered = await(service.getKey(idp.issuer, "k1"));

        assertNull(recovered.error(), "the failed JWKS fetch was cached: " + recovered.error());
    }

    @Test
    void failedFirstFetchIsRetriedAfterTheBackoff() throws Exception {
        idp.jwks = FakeIdp.status(503);
        OidcSecurityServiceProperties properties = properties().setJwksRetryBackoff(Duration.ofMillis(500));
        DefaultJwksService service = newService(properties);

        assertNotNull(await(service.getKey(idp.issuer, "k1")).error());
        for (int i = 0; i < 10; i++) {
            assertNotNull(await(service.getKey(idp.issuer, "k1")).error());
        }
        assertEquals(1, idp.jwksHits.get(), "lookups within the backoff fetched again");

        idp.serveKeys(jwks(Map.of("k1", keyPair1)));
        Thread.sleep(600);
        Outcome<Jwk<? extends Key>> recovered = await(service.getKey(idp.issuer, "k1"));

        assertNull(recovered.error(), "not retried after the backoff: " + recovered.error());
    }

    @Test
    void burstOfTokensSignedWithARotatedKeyShareOneRefresh() throws Exception {
        DefaultJwksService service = newService(properties());
        assertNull(await(service.getKey(idp.issuer, "k1")).error());

        idp.jwks = FakeIdp.delayed(Duration.ofMillis(300), jwks(Map.of("k1", keyPair1, "k2", keyPair2)));
        Thread.sleep(500); // past the refresh cooldown used by these tests
        List<CompletableFuture<Jwk<? extends Key>>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(service.getKey(idp.issuer, "k2"));
        }

        for (CompletableFuture<Jwk<? extends Key>> future : futures) {
            Outcome<Jwk<? extends Key>> outcome = await(future);
            assertNull(outcome.error(), "a token signed with the rotated key was rejected: " + outcome.error());
        }
        assertEquals(2, idp.jwksHits.get(), "JWKS fetches for the first load and one refresh");
    }

    @Test
    void concurrentColdLookupsShareOneFetch() throws Exception {
        idp.jwks = FakeIdp.delayed(Duration.ofMillis(500), jwks(Map.of("k1", keyPair1)));
        DefaultJwksService service = newService(properties());

        List<CompletableFuture<Jwk<? extends Key>>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(service.getKey(idp.issuer, "k1"));
        }
        for (CompletableFuture<Jwk<? extends Key>> future : futures) {
            assertNull(await(future).error());
        }

        assertEquals(1, idp.wellKnownHits.get(), "discovery fetches for 20 concurrent cold lookups");
        assertEquals(1, idp.jwksHits.get(), "JWKS fetches for 20 concurrent cold lookups");
    }

    @Test
    void unknownKeyIdsDoNotEachTriggerAFetch() {
        DefaultJwksService service = newService(properties());
        assertNull(await(service.getKey(idp.issuer, "k1")).error());

        for (int i = 0; i < 25; i++) {
            assertNotNull(await(service.getKey(idp.issuer, "unknown-" + UUID.randomUUID())).error());
        }

        assertTrue(idp.jwksHits.get() <= 2,
                   "25 tokens with unknown key ids caused " + idp.jwksHits.get() + " JWKS fetches");
    }

    @Test
    void unknownKeyIdWhileTheIdpIsDownKeepsServingCachedKeys() throws Exception {
        DefaultJwksService service = newService(properties());
        assertNull(await(service.getKey(idp.issuer, "k1")).error());

        idp.jwks = FakeIdp.hang();
        Thread.sleep(500); // past the refresh cooldown used by these tests
        CompletableFuture<Jwk<? extends Key>> bogus = service.getKey(idp.issuer, "unknown-" + UUID.randomUUID());

        // While that refresh hangs, and after it fails, the cached key keeps working without waiting on it
        long start = System.nanoTime();
        assertNull(await(service.getKey(idp.issuer, "k1")).error());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofMillis(500)) < 0,
                   "a cached key waited on the refresh an unknown key id started");
        assertNotNull(await(bogus).error());
        Outcome<Jwk<? extends Key>> afterFailedRefresh = await(service.getKey(idp.issuer, "k1"));
        assertNull(afterFailedRefresh.error(), "the failed refresh dropped the cached keys: " + afterFailedRefresh.error());
    }

    @Test
    void rotatedKeyIsPickedUp() throws Exception {
        DefaultJwksService service = newService(properties());
        assertNull(await(service.getKey(idp.issuer, "k1")).error());

        idp.serveKeys(jwks(Map.of("k1", keyPair1, "k2", keyPair2)));
        Thread.sleep(500); // past the refresh cooldown used by these tests
        Outcome<Jwk<? extends Key>> rotated = await(service.getKey(idp.issuer, "k2"));

        assertNull(rotated.error(), "rotated key was not found: " + rotated.error());
        assertEquals("k2", rotated.value().getId());
    }

    @Test
    void configuredJwksUriSkipsDiscovery() {
        // The issuer's discovery endpoint is unreachable, the provider points at a JWKS that is reachable
        idp.wellKnown = FakeIdp.hang();
        OidcSecurityServiceProperties properties = properties();
        properties.getOidcProviders().getFirst().setJwksUri(idp.jwksUri);
        DefaultJwksService service = newService(properties);

        Outcome<Jwk<? extends Key>> outcome = await(service.getKey(idp.issuer, "k1"));

        assertNull(outcome.error(), "lookup with a configured jwksUri failed: " + outcome.error());
        assertEquals(0, idp.wellKnownHits.get(), "discovery was fetched although jwksUri is configured");
    }

    @Test
    void validTokenAuthenticates() {
        OidcSecurityServiceProperties properties = properties();
        OidcSecurityService securityService = new OidcSecurityService(properties, newService(properties));

        Outcome<Participant> outcome = await(securityService.authenticate(
                Map.of("authorization", "Bearer " + token(idp.issuer, "k1", keyPair1))));

        assertNull(outcome.error(), "valid token failed: " + outcome.error());
        assertEquals("user-1", outcome.value().getId());
    }

    // ---------------------------------------------------------------------------------------------------------------

    private OidcSecurityServiceProperties properties() {
        OidcProvider provider = new OidcProvider()
                .setEnabled(true)
                .setProvider("fake")
                .setAuthority(idp.issuer)
                .setAudience(AUDIENCE)
                .setClientId(AUDIENCE)
                .setAllowAnyDomain(true);
        return new OidcSecurityServiceProperties()
                .setEnabled(true)
                .setOidcProviders(List.of(provider))
                .setJwksConnectTimeout(Duration.ofSeconds(1))
                .setJwksRequestTimeout(Duration.ofSeconds(2))
                .setJwksRefreshCooldown(Duration.ofMillis(300))
                // tests of the backoff set their own, the others retry right away
                .setJwksRetryBackoff(Duration.ZERO);
    }

    private DefaultJwksService newService(OidcSecurityServiceProperties properties) {
        return new DefaultJwksService(new DefaultCaffeineCacheFactory(Optional.empty()), properties);
    }

    private String jwks(Map<String, KeyPair> keys) {
        List<Jwk<PublicKey>> jwkList = new ArrayList<>();
        keys.forEach((kid, pair) -> jwkList.add(Jwks.builder().key(pair.getPublic()).id(kid).build()));
        return objectMapper.writeValueAsString(Map.of("keys", jwkList));
    }

    private static String token(String issuer, String kid, KeyPair keyPair) {
        return Jwts.builder()
                   .header().keyId(kid).and()
                   .issuer(issuer)
                   .subject("user-1")
                   .audience().add(AUDIENCE).and()
                   .expiration(new Date(System.currentTimeMillis() + 300_000))
                   .signWith(keyPair.getPrivate())
                   .compact();
    }

    private static <T> Outcome<T> await(CompletableFuture<T> future) {
        try {
            return new Outcome<>(future.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS), null, false);
        } catch (TimeoutException e) {
            return new Outcome<>(null, null, true);
        } catch (ExecutionException e) {
            return new Outcome<>(null, e.getCause(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record Outcome<T>(T value, Throwable error, boolean hung) {}

    /**
     * A minimal OIDC provider: a discovery document and a JWKS endpoint, each with a swappable behavior.
     */
    private static class FakeIdp implements AutoCloseable {

        interface Behavior {
            void handle(HttpExchange exchange) throws Exception;
        }

        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final String issuer;
        final String jwksUri;
        final AtomicInteger wellKnownHits = new AtomicInteger();
        final AtomicInteger jwksHits = new AtomicInteger();
        final CountDownLatch released = new CountDownLatch(1);
        volatile Behavior wellKnown;
        volatile Behavior jwks;

        FakeIdp() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/test";
            jwksUri = issuer + "/protocol/openid-connect/certs";
            server.createContext("/realms/test/.well-known/openid-configuration", exchange -> {
                wellKnownHits.incrementAndGet();
                run(wellKnown, exchange);
            });
            server.createContext("/realms/test/protocol/openid-connect/certs", exchange -> {
                jwksHits.incrementAndGet();
                run(jwks, exchange);
            });
            server.start();
        }

        void serveDiscovery() {
            wellKnown = ok("{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + jwksUri + "\"}");
        }

        void serveKeys(String jwksJson) {
            jwks = ok(jwksJson);
        }

        int totalHits() {
            return wellKnownHits.get() + jwksHits.get();
        }

        static Behavior ok(String body) {
            return exchange -> respond(exchange, 200, body);
        }

        static Behavior status(int status) {
            return exchange -> respond(exchange, status, "{\"error\":\"unavailable\"}");
        }

        static Behavior delayed(Duration delay, String body) {
            return exchange -> {
                Thread.sleep(delay.toMillis());
                respond(exchange, 200, body);
            };
        }

        /** Reads the request and never answers, like a dead peer or a stalled proxy. */
        static Behavior hang() {
            return exchange -> {
                // released on close, so the handler threads do not outlive the test
                FakeIdp idp = (FakeIdp) exchange.getHttpContext().getAttributes().get("idp");
                idp.released.await();
            };
        }

        private void run(Behavior behavior, HttpExchange exchange) throws IOException {
            exchange.getHttpContext().getAttributes().put("idp", this);
            try {
                behavior.handle(exchange);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            } finally {
                exchange.close();
            }
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            released.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
