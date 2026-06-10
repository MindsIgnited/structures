# Structures Auth Library

A comprehensive JWT authentication and authorization library for the Structures framework.

## Overview

The Structures Auth library provides JWT token management, OIDC integration, and authorization services for Spring Boot applications. It follows the standard Structures library conventions and integrates seamlessly with the broader Structures ecosystem.

## Features

- **JWT Token Management**: Create, validate, and parse JWT tokens
- **OIDC Integration**: OpenID Connect authentication support
- **Authorization Services**: Role-based access control and permission management
- **Spring Boot Auto-Configuration**: Automatic configuration for Spring Boot applications
- **Security Best Practices**: Secure token handling and validation

## Dependencies

- **JJWT**: JWT token creation and validation
- **Jackson**: JSON processing for token claims
- **Continuum Core**: Framework integration
- **Spring Boot**: Auto-configuration and dependency injection

## Quick Start

### 1. Add the dependency

```gradle
implementation project(':structures-auth')
```

### 2. Enable auto-configuration

The library automatically configures OIDC security when the `oidc-security-service.enabled` property is set to `true`. No additional annotations are required.

```java
@SpringBootApplication
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

### 3. Configure OIDC providers

```yaml
oidc-security-service:
  enabled: true
  debug: false
  tenant-id-field-name: "tenantId"
  frontend-configuration-path: "/app-config.override.json"
  oidc-providers:
    - provider: "keycloak"
      display-name: "Keycloak"
      enabled: true
      client-id: "structures-client"
      authority: "http://localhost:8888/auth/realms/test"
      redirect-uri: "http://localhost:5173/login"
      post-logout-redirect-uri: "http://localhost:5173"
      silent-redirect-uri: "http://localhost:5173/login/silent-renew"
      domains:
        - "example.com"
      audience: "structures-client"
      roles-claim-path: "realm_access.roles"
      additional-scopes: "groups"
```

### 4. Use the services

```java
@Service
public class MyService {
    
    @Autowired
    private SecurityService securityService;
    
    public void processRequest(HttpServletRequest request) {
        // OIDC token validation is automatic
        String userId = securityService.getCurrentUserId();
        Set<String> roles = securityService.getCurrentUserRoles();
        
        if (roles.contains("admin")) {
            // Perform admin operations
        }
    }
}
```

## Configuration

The library provides sensible defaults but can be customized through Spring Boot configuration properties:

```yaml
oidc-security-service:
  enabled: true
  debug: false
  tenant-id-field-name: "tenantId"
  frontend-configuration-path: "/app-config.override.json"
  oidc-providers:
    - provider: "keycloak"
      display-name: "Keycloak"
      enabled: true
      client-id: "structures-client"
      authority: "http://localhost:8888/auth/realms/test"
      redirect-uri: "http://localhost:5173/login"
      post-logout-redirect-uri: "http://localhost:5173"
      silent-redirect-uri: "http://localhost:5173/login/silent-renew"
      domains:
        - "example.com"
      audience: "structures-client"
      roles-claim-path: "realm_access.roles"
      additional-scopes: "groups"
    
    - provider: "okta"
      display-name: "Okta"
      enabled: true
      client-id: "your-okta-client-id"
      authority: "https://your-okta-domain.okta.com/oauth2/default"
      redirect-uri: "http://localhost:5173/login"
      post-logout-redirect-uri: "http://localhost:5173"
      silent-redirect-uri: "http://localhost:5173/login/silent-renew"
      domains:
        - "yourcompany.com"
      audience: "your-okta-client-id"
      roles-claim-path: "roles"
      additional-scopes: "groups"
```

## API Reference

### SecurityService

- `getCurrentUserId()`: Gets the current authenticated user ID
- `getCurrentUserRoles()`: Gets all roles for the current user
- `hasRole(String role)`: Checks if the current user has a specific role
- `hasAnyRole(Set<String> roles)`: Checks if the current user has any of the specified roles
- `isAuthenticated()`: Checks if the current user is authenticated
- `getCurrentUserClaims()`: Gets all claims for the current user

### OIDC Configuration

- **Automatic JWT Validation**: JWT tokens are automatically validated on each request
- **Multi-Provider Support**: Support for multiple OIDC providers simultaneously
- **JWKS Caching**: Efficient JSON Web Key Set caching for performance
- **Role Extraction**: Automatic role extraction from JWT claims

### Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `false` | Master switch for OIDC security service |
| `debug` | boolean | `false` | Enable debug logging and UI debugging |
| `tenant-id-field-name` | string | `"tenantId"` | JWT claim field name for tenant ID |
| `frontend-configuration-path` | string | `"/app-config.override.json"` | Path for frontend configuration overrides |
| `oidc-providers` | array | `[]` | List of OIDC provider configurations |

#### OIDC Provider Properties

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `provider` | string | Yes | Unique identifier for the provider |
| `display-name` | string | Yes | Human-readable provider name |
| `enabled` | boolean | Yes | Enable/disable this specific provider |
| `client-id` | string | Yes | OAuth client ID from the provider |
| `authority` | string | Yes | OIDC issuer authority URL |
| `redirect-uri` | string | Yes | OAuth redirect URI after authentication |
| `post-logout-redirect-uri` | string | Yes | Redirect URI after logout |
| `silent-redirect-uri` | string | Yes | URI for silent token renewal |
| `domains` | array | No* | Email domains this provider handles. *Required unless `allow-any-domain: true`. |
| `allow-any-domain` | boolean | No | If `true`, this provider matches on issuer alone and skips the email-domain check. Required for M2M tokens (no email claim, `sub` is a client id) and useful when the IDP issues tokens for users from arbitrary email domains. A provider whose `domains` matches a token's email domain is preferred over an `allow-any-domain` provider with the same issuer. Default: `false`. |
| `audience` | string | Yes | Expected audience claim in JWT tokens |
| `roles-claim-path` | string | No | JSON path to roles claim in JWT |
| `additional-scopes` | string | No | Additional OAuth scopes to request |
| `roles` | array | No | Roles required by the backend authorization flow. If set, the token must contain at least one of these in the claim at `roles-claim-path`. |
| `front-end-roles` | array | No | Roles required to access the **frontend** application via this provider. **Not used by backend authorization** — the backend accepts any token that satisfies `roles` (or has none required). The frontend enforces this list after login by intersecting it with the participant's roles; users with no matching role are rejected at the UI. Leave empty/unset to impose no additional frontend gate. |
| `metadata` | object | No | Additional provider metadata |

## Security Considerations

- **JWT Validation**: All JWT tokens are automatically validated for signature, expiration, and claims
- **Issuer Verification**: Only tokens from configured, trusted issuers are accepted
- **Audience Validation**: Tokens must have valid audience claims for your application
- **Role-Based Access**: Implement role-based access control using extracted JWT claims
- **HTTPS Required**: Always use HTTPS in production for secure token transmission
- **Token Storage**: Store tokens securely in memory, never in localStorage or cookies
- **Regular Updates**: Keep OIDC provider configurations and JWKS endpoints up to date

## Testing

The library includes comprehensive tests:

```bash
./gradlew :structures-auth:test
```

## Contributing

1. Follow the existing code conventions
2. Add tests for new functionality
3. Update documentation as needed
4. Ensure security best practices are followed
5. Test with multiple OIDC providers
6. Validate JWT token handling thoroughly

## How It Works

### 1. **JWT Token Extraction**
- Automatically extracts JWT tokens from `Authorization: Bearer <token>` headers
- Supports both access tokens and ID tokens

### 2. **Token Validation**
- Validates JWT signature using JWKS from the issuer
- Resolves a matching provider for the token (see **Provider Matching** below)
- Checks audience claims against provider configuration
- Validates token expiration
- If `roles-claim-path` is set, extracts roles and (if `roles` is set on the provider) requires at least one match

### 3. **Provider Matching**
For each token, an enabled provider is selected as follows:
1. Filter providers whose `authority` equals the token's `iss` claim.
2. The matcher looks for an email-formatted value across the `email`, `preferred_username`, `sub`, `upn`, and `unique_name` claims.
3. If an email was found, a provider whose `domains` contains the email's domain wins.
4. Otherwise, fall back to any matching provider with `allow-any-domain: true`.
5. If the token has no email and no candidate has `allow-any-domain: true`, the token is rejected.

This lets a single Okta tenant (one `authority`) split across multiple providers — e.g. a per-customer config with explicit `domains` plus a catchall with `allow-any-domain: true` for M2M tokens and arbitrary-domain users.

### 4. **User Creation**
- Creates `Participant` objects from JWT claims
- Extracts user information (email, name, roles)
- Email is omitted from metadata when not present in the token (M2M case)
- Applies role-based access control

### 5. **Frontend Integration**
- Serves configuration overrides at `/app-config.override.json`
- Enables dynamic frontend configuration without rebuilds
- Supports runtime provider enable/disable
- `front-end-roles` is shipped to the frontend in the config payload. After login, the frontend intersects this list with the participant's roles and rejects users with no overlap — the backend already accepted the token, but the UI app stays gated behind a role check.

## Examples

### Keycloak Configuration
```yaml
oidc-security-service:
  enabled: true
  oidc-providers:
    - provider: "keycloak"
      display-name: "Keycloak"
      enabled: true
      client-id: "structures-client"
      authority: "http://localhost:8888/auth/realms/test"
      redirect-uri: "http://localhost:5173/login"
      post-logout-redirect-uri: "http://localhost:5173"
      silent-redirect-uri: "http://localhost:5173/login/silent-renew"
      domains:
        - "example.com"
      audience: "structures-client"
      roles-claim-path: "realm_access.roles"
```

### Okta Configuration
```yaml
oidc-security-service:
  enabled: true
  oidc-providers:
    - provider: "okta"
      display-name: "Okta"
      enabled: true
      client-id: "0oaowrlsm5Ua1vWD85d7"
      authority: "https://dev-39125344.okta.com/oauth2/default"
      redirect-uri: "http://localhost:5173/login"
      post-logout-redirect-uri: "http://localhost:5173"
      silent-redirect-uri: "http://localhost:5173/login/silent-renew"
      domains:
        - "yourcompany.com"
      audience: "0oaowrlsm5Ua1vWD85d7"
      roles-claim-path: "roles"
```

### Okta with Arbitrary Email Domains + Frontend Role Gate

Use this shape when the IDP issues tokens for customers from any email domain
and you still want to restrict who can access the frontend. The `domains` entry
is what the frontend uses for SSO discovery; the backend accepts any domain
because `allow-any-domain` is `true`.

```yaml
oidc-security-service:
  enabled: true
  oidc-providers:
    - provider: "okta-customers"
      display-name: "Okta"
      enabled: true
      client-id: "0oaowrlsm5Ua1vWD85d7"
      authority: "https://acme.okta.com/oauth2/default"
      redirect-uri: "http://localhost:5173/login"
      post-logout-redirect-uri: "http://localhost:5173"
      silent-redirect-uri: "http://localhost:5173/login/silent-renew"
      audience: "0oaowrlsm5Ua1vWD85d7"
      domains:
        - "acme.com"
      allow-any-domain: true
      roles-claim-path: "roles"
      front-end-roles:
        - "structures-user"
        - "structures-admin"
```

### Okta M2M (Machine-to-Machine) Tokens

M2M tokens from Okta typically have no `email` claim and a `sub` that is the
client id. Set `allow-any-domain: true` so the matcher doesn't try to validate
an email domain, and use `roles` (combined with `roles-claim-path`) to gate on
the scope/role assigned to the client. Often configured as a *second* provider
under the same `authority` as your interactive provider, with a different
audience.

```yaml
oidc-security-service:
  enabled: true
  oidc-providers:
    - provider: "okta-m2m"
      display-name: "Okta M2M"
      enabled: true
      client-id: "0oaowrlsm5Ua1vWD85d7"
      authority: "https://acme.okta.com/oauth2/default"
      redirect-uri: ""
      post-logout-redirect-uri: ""
      silent-redirect-uri: ""
      audience: "api://internal-m2m"
      allow-any-domain: true
      roles-claim-path: "scp"
      roles:
        - "m2m-service"
```

## Related Documentation

For detailed OIDC configuration and troubleshooting, see:
- [OIDC Implementation Guide](oidc-docs/OIDC_IMPLEMENTATION.md)
- [Provider-Specific Guides](oidc-docs/) - Okta, Microsoft, Keycloak, and more
- [Frontend Configuration](../structures-frontend/CONFIGURATION.md)

## License

This library is part of the Structures framework and follows the same licensing terms. 