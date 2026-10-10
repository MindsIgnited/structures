package org.kinotic.structures.tests.frontend;

import org.junit.jupiter.api.Test;
import org.kinotic.structures.auth.api.config.OidcFrontendConfiguration;
import org.kinotic.structures.auth.api.config.OidcSecurityServiceProperties;
import org.kinotic.structures.auth.api.domain.OidcProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The frontend configuration is served unauthenticated, so it must hold only what the frontend reads.
 */
class OidcFrontendConfigurationTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void onlyFieldsTheFrontendReadsAreSerialized() {
        OidcProvider provider = new OidcProvider()
                .setEnabled(true)
                .setProvider("keycloak")
                .setDisplayName("Keycloak")
                .setClientId("structures-client")
                .setAuthority("https://auth.example.com/realms/structures")
                .setJwksUri("http://keycloak.auth.svc:8080/realms/structures/protocol/openid-connect/certs")
                .setRedirectUri("https://structures.example.com/login")
                .setPostLogoutRedirectUri("https://structures.example.com")
                .setSilentRedirectUri("https://structures.example.com/login/silent-renew")
                .setDomains(List.of("example.com"))
                .setAllowAnyDomain(true)
                .setAudience("structures-api")
                .setRoles(List.of("admin"))
                .setFrontEndRoles(List.of("admin"))
                .setMetadata(Map.of("end_session_endpoint", "https://auth.example.com/logout",
                                    "team", "backend-only"))
                .setRolesClaimPath("realm_access.roles")
                .setAdditionalScopes("groups");
        OidcSecurityServiceProperties properties = new OidcSecurityServiceProperties()
                .setEnabled(true)
                .setDebug(true)
                .setTenantIdFieldName("tenant")
                .setOidcProviders(List.of(provider));

        JsonNode json = objectMapper.valueToTree(OidcFrontendConfiguration.from(properties));

        assertEquals(Set.of("enabled", "debug", "frontendConfigurationPath", "oidcProviders"), fieldNames(json));
        JsonNode served = json.get("oidcProviders").get(0);
        assertEquals(Set.of("enabled", "provider", "displayName", "clientId", "authority", "redirectUri",
                            "postLogoutRedirectUri", "silentRedirectUri", "domains", "frontEndRoles",
                            "additionalScopes", "metadata"),
                     fieldNames(served));
        assertEquals("structures-client", served.get("clientId").asString());
        assertEquals("example.com", served.get("domains").get(0).asString());
        assertEquals(Set.of("end_session_endpoint"), fieldNames(served.get("metadata")),
                     "metadata other than the endpoint overrides was sent");
    }

    @Test
    void nullMetadataValuesAreSkipped() {
        Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put("end_session_endpoint", null);
        metadata.put("token_endpoint", "https://auth.example.com/token");
        OidcSecurityServiceProperties properties = new OidcSecurityServiceProperties()
                .setEnabled(true)
                .setOidcProviders(List.of(new OidcProvider().setEnabled(true).setProvider("kc").setMetadata(metadata)));

        assertEquals(Map.of("token_endpoint", "https://auth.example.com/token"),
                     OidcFrontendConfiguration.from(properties).oidcProviders().getFirst().metadata());
    }

    @Test
    void metadataWithoutEndpointKeysIsNotSent() {
        // the frontend treats any metadata object as a full set of endpoint overrides
        OidcSecurityServiceProperties properties = new OidcSecurityServiceProperties()
                .setEnabled(true)
                .setOidcProviders(List.of(new OidcProvider().setEnabled(true).setProvider("kc")
                                                            .setMetadata(Map.of("team", "backend-only"))));

        assertNull(OidcFrontendConfiguration.from(properties).oidcProviders().getFirst().metadata());
    }

    @Test
    void disabledProvidersAreStillSent() {
        // the frontend offers basic auth only when no providers are configured, enabled or not
        OidcSecurityServiceProperties properties = new OidcSecurityServiceProperties()
                .setEnabled(true)
                .setOidcProviders(List.of(new OidcProvider().setEnabled(true).setProvider("on"),
                                          new OidcProvider().setEnabled(false).setProvider("off")));

        List<OidcFrontendConfiguration.Provider> providers = OidcFrontendConfiguration.from(properties).oidcProviders();

        assertEquals(List.of("on", "off"), providers.stream().map(OidcFrontendConfiguration.Provider::provider).toList());
        assertFalse(providers.get(1).enabled());
    }

    @Test
    void noProvidersConfigured() {
        OidcSecurityServiceProperties properties = new OidcSecurityServiceProperties().setEnabled(true);

        assertEquals(List.of(), OidcFrontendConfiguration.from(properties).oidcProviders());
    }

    private static Set<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> iterator = node.propertyNames().iterator();
        iterator.forEachRemaining(names::add);
        return Set.copyOf(names);
    }
}
