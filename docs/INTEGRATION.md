# Integrate an application

## Common settings

For the local stack, use these values.

```text
Issuer                 http://localhost:18100
Discovery document     http://localhost:18100/.well-known/openid-configuration
Authorization endpoint http://localhost:18100/oauth2/authorize
Token endpoint         http://localhost:18100/oauth2/token
Public JWKS            http://localhost:18100/oauth2/jwks
User info              http://localhost:18100/userinfo
Introspection          http://localhost:18100/oauth2/introspect
Revocation             http://localhost:18100/oauth2/revoke
End session            http://localhost:18100/connect/logout
```

Use your HTTPS public issuer in deployment. Obtain endpoint addresses from discovery when using an OIDC library.

## Browser application with React

Register a **Public browser app** in the console. Use an exact callback such as `http://localhost:5174/callback` for development and the matching HTTPS URL for deployment. Register your post-logout URL as well. Browser clients have no shared client secret.

Install the OIDC browser SDK.

```sh
npm install oidc-client-ts
```

Create one shared client instance in your application.

```ts
import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

export const auth = new UserManager({
  authority: 'http://localhost:18100',
  client_id: 'your-registered-client-id',
  redirect_uri: window.location.origin + '/callback',
  post_logout_redirect_uri: window.location.origin + '/',
  response_type: 'code',
  scope: 'openid profile email roles api.read',
  automaticSilentRenew: false,
  userStore: new WebStorageStateStore({ store: sessionStorage })
});
```

Start sign-in from a button handler.

```ts
await auth.signinRedirect({ nonce: crypto.randomUUID() });
```

Complete the callback on your `/callback` route.

```ts
const user = await auth.signinRedirectCallback();
window.history.replaceState({}, '', '/');
```

The SDK manages PKCE and state and checks the nonce supplied for this sign-in. The callback URL must match the registered URL. Request only the scopes your app needs. The Gatehouse consent screen records a user's scope approval for that application.

Use the access token when calling a protected API.

```ts
const user = await auth.getUser();
if (!user || user.expired) {
  await auth.signinRedirect({ nonce: crypto.randomUUID() });
} else {
  const response = await fetch('http://localhost:18100/api/resource/profile', {
    headers: { Authorization: `Bearer ${user.access_token}` }
  });
  const result = await response.json();
}
```

Public clients receive short-lived access tokens. Start a new sign-in redirect when needed. An existing Gatehouse SSO session can complete the sign-in without another password prompt. The included demo stores tokens in sessionStorage for its example workflow. Applications with server-side sessions can choose a confidential backend-for-frontend integration.

To sign out through OIDC, use the registered post-logout destination.

```ts
await auth.signoutRedirect();
```

Process the returning logout state with `signoutRedirectCallback()` if your SDK configuration uses it. The example implementation is in `frontend/src/Demo.tsx`.

## Confidential web application

Register a **Confidential web app** and configure its exact redirect/post-logout URLs. Store the generated secret on your application's server. Configure your framework's OIDC client with discovery, the client ID, the secret, and authorization code + PKCE.

Confidential clients can receive refresh tokens. Access tokens live for five minutes. Refresh tokens live for eight hours and rotate at redemption. Replace the stored refresh token after every successful exchange. Serialize refresh operations per application session so simultaneous browser requests do not redeem the same refresh token twice.

## Protect a Spring Boot API

Add `spring-boot-starter-oauth2-resource-server` and your web starter. Set these properties.

```properties
spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:18100
spring.security.oauth2.resourceserver.jwt.audiences=gatehouse-api
```

Map application roles while preserving scope authorities.

```java
@Bean
JwtAuthenticationConverter jwtAuthenticationConverter() {
    var scopes = new JwtGrantedAuthoritiesConverter();
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
        var authorities = new ArrayList<GrantedAuthority>(scopes.convert(jwt));
        var roles = jwt.getClaimAsStringList("roles");
        if (roles != null) {
            for (String role : roles) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }
        return authorities;
    });
    return converter;
}

@Bean
SecurityFilterChain apiSecurity(HttpSecurity http,
        JwtAuthenticationConverter converter) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .oauth2ResourceServer(oauth ->
            oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
        .build();
}
```

Enable method security with `@EnableMethodSecurity`. Protect API operations with the required scope and role.

```java
@PreAuthorize("hasAuthority('SCOPE_api.write') and hasRole('editor')")
@GetMapping("/reports/editor-preview")
Map<String, String> editorPreview() {
    return Map.of("message", "Editor access granted.");
}
```

These classes come from Spring Security's OAuth resource-server, web-security, core-authority, and method-security packages. Use `java.util.ArrayList` and `java.util.Map` for the collection types. Gatehouse's `Security.java` and `ApiController.java` contain the running example.

For other frameworks, configure an OIDC/JWT library with the issuer, JWKS, expected audience, and accepted signing algorithms. Validate expiry and enforce scopes/roles in the API that owns the protected operation. ID tokens are intended for the client sign-in result. API calls use access tokens.

## Service-to-service access

Register a **Service account**, allow `api.read`, and save its generated secret in your service's secret configuration. This initial version's machine clients use the read scope.

```sh
curl --fail --user 'your-client-id:your-client-secret' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'scope=api.read' \
  http://localhost:18100/oauth2/token
```

The token response contains an access token and its lifetime. Send that token as a Bearer header to the API.

```sh
curl --fail \
  -H 'Authorization: Bearer YOUR_ACCESS_TOKEN' \
  http://localhost:18100/api/resource/profile
```

## Introspection and revocation

A confidential client authenticates with its client ID and secret when using these endpoints.

```sh
curl --fail --user 'your-client-id:your-client-secret' \
  --data-urlencode 'token=YOUR_ACCESS_TOKEN' \
  http://localhost:18100/oauth2/introspect

curl --fail --user 'your-client-id:your-client-secret' \
  --data-urlencode 'token=YOUR_TOKEN' \
  http://localhost:18100/oauth2/revoke
```

For introspection, require `active: true` and check the returned audience, scope, and roles before allowing access. An inactive response fails authorization. Signed-token validation in a separate API can continue accepting an issued JWT until expiry. Use introspection for immediate centralized revocation checks.

## Admin API access

The console uses an authenticated, HTTP-only browser session and CSRF tokens. Management endpoints under `/api/admin/` require a directory administrator. Application bearer tokens authenticate the protected resource APIs. The console keeps these two permission systems separate.

Core management routes include the following.

| Route | Operation |
| --- | --- |
| `POST /api/registration/signup` | Create a standard pending-verification account |
| `POST /api/registration/verify` | Verify email using its link token and the chosen password |
| `POST /api/registration/resend` | Request a new verification email |
| `GET /api/me` | Current directory account |
| `GET /api/csrf` | CSRF header name/token for session mutations |
| `GET, POST /api/admin/users` | List/create users |
| `PUT, DELETE /api/admin/users/{id}` | Update/remove a user |
| `POST /api/admin/users/{id}/revoke` | Revoke sessions and OAuth grants |
| `POST /api/admin/users/{id}/unlock` | Clear a login lock |
| `POST /api/admin/users/{id}/reset-link` | Send a password reset email |
| `GET, POST /api/admin/roles` | List/create roles |
| `DELETE /api/admin/roles/{id}` | Remove a role and its assignments |
| `GET, POST /api/admin/clients` | List/register applications |
| `PUT /api/admin/clients/{id}/enabled` | Enable/disable an application |
| `POST /api/admin/clients/{id}/rotate-secret` | Rotate a confidential client secret |
| `GET /api/admin/sessions` | List active browser sessions |
| `DELETE /api/admin/sessions/{id}` | Revoke one browser session |
| `GET /api/admin/events` | Read recent audit events |

User updates include the version returned by the user listing. HTTP 409 indicates a stale edit. The console and `scripts/verify.py` demonstrate the request formats.

## References

- [Spring Security Authorization Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/getting-started.html)
- [Spring Security OAuth resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [oidc-client-ts](https://github.com/authts/oidc-client-ts)
