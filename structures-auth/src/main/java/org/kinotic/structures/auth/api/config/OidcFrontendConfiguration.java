package org.kinotic.structures.auth.api.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.kinotic.structures.auth.api.domain.OidcProvider;

/**
 * The OIDC configuration served, unauthenticated, to the frontend at
 * {@link OidcSecurityServiceProperties#getFrontendConfigurationPath()}.
 * <p>
 * This is an allow-list of what the frontend reads. Settings that only the backend uses, such as the
 * audience, required roles, roles claim path and JWKS fetch settings, are not sent, and a new setting is
 * not sent unless it is added here. Disabled providers are sent, with enabled false, since the frontend
 * decides whether to offer basic auth from whether any providers are configured.
 */
public record OidcFrontendConfiguration(boolean enabled,
                                        boolean debug,
                                        String frontendConfigurationPath,
                                        List<Provider> oidcProviders) {

    /**
     * The provider metadata keys the frontend reads, as endpoint overrides for its OIDC client. The backend
     * also adds a provider's metadata to each Participant's, so other keys are not sent.
     */
    private static final Set<String> FRONTEND_METADATA_KEYS = Set.of("authorization_endpoint",
                                                                     "token_endpoint",
                                                                     "userinfo_endpoint",
                                                                     "end_session_endpoint",
                                                                     "jwks_uri");

    public static OidcFrontendConfiguration from(OidcSecurityServiceProperties properties) {
        List<Provider> providers = properties.getOidcProviders() == null
                ? List.of()
                : properties.getOidcProviders().stream()
                            .map(Provider::from)
                            .toList();
        return new OidcFrontendConfiguration(properties.isEnabled(),
                                             properties.isDebug(),
                                             properties.getFrontendConfigurationPath(),
                                             providers);
    }

    /**
     * @param domains   email domains that route a user to this provider on the login page
     * @param metadata  endpoint overrides the frontend passes to its OIDC client, null when there are none
     */
    public record Provider(boolean enabled,
                           String provider,
                           String displayName,
                           String clientId,
                           String authority,
                           String redirectUri,
                           String postLogoutRedirectUri,
                           String silentRedirectUri,
                           List<String> domains,
                           List<String> frontEndRoles,
                           String additionalScopes,
                           Map<String, String> metadata) {

        static Provider from(OidcProvider provider) {
            return new Provider(provider.isEnabled(),
                                provider.getProvider(),
                                provider.getDisplayName(),
                                provider.getClientId(),
                                provider.getAuthority(),
                                provider.getRedirectUri(),
                                provider.getPostLogoutRedirectUri(),
                                provider.getSilentRedirectUri(),
                                provider.getDomains(),
                                provider.getFrontEndRoles(),
                                provider.getAdditionalScopes(),
                                endpointMetadata(provider.getMetadata()));
        }

        /**
         * Null rather than empty when there are no endpoint keys, since the frontend treats any metadata object
         * as a full set of endpoint overrides
         */
        private static Map<String, String> endpointMetadata(Map<String, String> metadata) {
            if (metadata == null) {
                return null;
            }
            Map<String, String> endpoints = metadata.entrySet().stream()
                                                    .filter(entry -> FRONTEND_METADATA_KEYS.contains(entry.getKey()))
                                                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            return endpoints.isEmpty() ? null : endpoints;
        }
    }
}
