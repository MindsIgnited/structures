package org.kinotic.structures.auth.api.config;

import java.util.List;
import java.util.Map;

import org.kinotic.structures.auth.api.domain.OidcProvider;

/**
 * The OIDC configuration served, unauthenticated, to the frontend at
 * {@link OidcSecurityServiceProperties#getFrontendConfigurationPath()}.
 * <p>
 * This is an allow-list of what the frontend reads. Settings that only the backend uses, such as the
 * audience, required roles, roles claim path and JWKS fetch settings, are not sent, and a new setting is
 * not sent unless it is added here. Disabled providers are not sent either.
 */
public record OidcFrontendConfiguration(boolean enabled,
                                        boolean debug,
                                        String frontendConfigurationPath,
                                        List<Provider> oidcProviders) {

    public static OidcFrontendConfiguration from(OidcSecurityServiceProperties properties) {
        List<Provider> providers = properties.getOidcProviders() == null
                ? List.of()
                : properties.getOidcProviders().stream()
                            .filter(OidcProvider::isEnabled)
                            .map(Provider::from)
                            .toList();
        return new OidcFrontendConfiguration(properties.isEnabled(),
                                             properties.isDebug(),
                                             properties.getFrontendConfigurationPath(),
                                             providers);
    }

    /**
     * @param domains   email domains that route a user to this provider on the login page
     * @param metadata  may carry endpoint overrides the frontend passes to its OIDC client
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
                                provider.getMetadata());
        }
    }
}
