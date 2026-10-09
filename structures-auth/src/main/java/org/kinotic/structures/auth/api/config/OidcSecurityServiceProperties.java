
package org.kinotic.structures.auth.api.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import org.kinotic.structures.auth.api.domain.OidcProvider;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Configuration class for OIDC authentication.
 * 
 * The OIDC verifier will:
 * 1. Extract the JWT token from the Authorization header
 * 2. Verify the token signature using JWKS from the issuer
 * 3. Validate the issuer against the list of providers
 * 4. Validate the audience against the allowed audiences list of the provider
 * 5. Check token expiration
 * 6. Create a Participant with user information from the token claims
 * 
 * Caching and fetching:
 * - Keys are only fetched for issuers that match an enabled provider's authority
 * - JWKS key sets are refreshed every {@link #jwksRefreshInterval}, and early when a token names an unknown
 *   key id, at most once per {@link #jwksRefreshCooldown}. Through an outage the last fetched keys keep being
 *   used, for at most {@link #jwksMaxStaleness} when that is set
 * - Well-known configurations are cached for 24 hours
 * - Failed fetches are not cached; a failed refresh keeps the cached copy, and a failed first fetch is
 *   retried after {@link #jwksRetryBackoff}. Concurrent lookups share one fetch
 * - Every fetch is bounded by {@link #jwksConnectTimeout} and {@link #jwksRequestTimeout}
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "oidc-security-service")
public class OidcSecurityServiceProperties {

    /**
     * Master switch for enabling/disabling the OIDC security service.
     */
    private boolean enabled = false;

    /**
     * The field name for the tenant ID in the JWT token.
     */
    private String tenantIdFieldName = "tenantId";

    /**
     * List of OIDC providers to be used for authentication.
     */
    private List<OidcProvider> oidcProviders;

    /**
     * enable debugging for the OIDC security service in the UI. 
     */
    private boolean debug;

    /**
     * The path that the frontend configuration overrides will be served from.
     * Will override any default configurations in the app-config.json file.
     */
    private String frontendConfigurationPath = "/app-config.override.json";

    /**
     * How long to wait for a TCP connection to an OIDC provider when fetching its discovery document or JWKS.
     */
    private Duration jwksConnectTimeout = Duration.ofSeconds(5);

    /**
     * The longest a single discovery or JWKS fetch may take, connecting included. Authentication that needs
     * a fetch fails once this passes, rather than waiting on the provider.
     */
    private Duration jwksRequestTimeout = Duration.ofSeconds(10);

    /**
     * How often each cached key set is refreshed. The refresh starts on the first lookup after this has passed,
     * which is served the cached set meanwhile. A failed refresh keeps the cached set, and is retried after this
     * passes again. Keys an IdP publishes ahead of using them are picked up within this interval.
     */
    private Duration jwksRefreshInterval = Duration.ofHours(1);

    /**
     * How long a key set may keep being used while every refresh fails, counted from its last successful fetch.
     * After that, lookups need a successful fetch and fail while the IdP is unreachable. Unset (the default), the
     * last fetched keys are used for as long as an outage lasts, as Microsoft.IdentityModel and go-oidc do.
     * Setting it bounds how long a key the IdP has revoked can still be trusted while it cannot be reached.
     */
    private Duration jwksMaxStaleness;

    /**
     * The minimum time between key set refreshes caused by tokens with an unknown key id. Lets rotated keys be
     * picked up, without letting each token with a made up key id cause a fetch. Counted from the last such
     * refresh only, not from routine refreshes.
     */
    private Duration jwksRefreshCooldown = Duration.ofSeconds(30);

    /**
     * After a discovery or JWKS fetch fails with nothing cached, how long lookups fail with that error before
     * one fetches again. Limits the requests and log lines an unreachable provider causes.
     */
    private Duration jwksRetryBackoff = Duration.ofSeconds(5);

    /**
     * The enabled providers whose authority is exactly the given issuer. Both the key lookup and the
     * provider match use this, so a token is only ever fetched for when it could match a provider.
     */
    public List<OidcProvider> findEnabledProviders(String issuer) {
        if (issuer == null || oidcProviders == null) {
            return List.of();
        }
        return oidcProviders.stream()
                            .filter(OidcProvider::isEnabled)
                            .filter(p -> issuer.equals(p.getAuthority()))
                            .toList();
    }
    
}
