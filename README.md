# Gatehouse

A web application for basic identity and access management, built with React 19.3.1, TypeScript 7.0.2, Vite 8.3.8, Java 25, Spring Boot 4.1.2, Spring Security 7.1.2, and PostgreSQL 18.

Gatehouse provides a central user directory, an admin console, and OAuth 2.0 / OpenID Connect endpoints that other applications can use. Spring Security implements the protocol endpoints. Application code provides directory management, roles, persistent configuration, and the web console.

## Get the source

```sh
git clone https://github.com/Vijay10336/gatehouse-iam.git
cd gatehouse-iam
```

## Start the app

Install Docker Desktop and Python 3. From this directory, run the following commands.

```sh
python3 scripts/setup.py
docker compose up --build --scale auth=2 -d
curl --fail --retry 20 --retry-delay 1 --retry-connrefused http://localhost:18100/api/health
```

Open [the console](http://localhost:18100), [the integration demo](http://localhost:18100/demo), and [the development email inbox](http://localhost:18125).

The bootstrap administrator email is `admin@gatehouse.local`. Read `BOOTSTRAP_ADMIN_PASSWORD` in your generated `.env` file for its password. Setup creates unique secrets and preserves existing credentials on subsequent runs. The bootstrap account is created on the first startup. Later changes to its password happen through the account/reset flows.

The Sign in page includes **Create an account**. Public signups verify their email before signing in and receive standard account access. Administrators can also create users from the console. See [administrator access and onboarding](docs/ADMINISTRATION.md).

## Features

| Area | Available behavior |
| --- | --- |
| Users | Public signup with email verification, administrator-created accounts, search and paginate, update names, enable/disable access, assign roles, manage directory administrators, clear login locks, and revoke all access |
| Applications | Register public browser, confidential web, and service clients, configure exact redirect URLs and scopes at creation, enable/disable clients, and rotate confidential client secrets |
| Authorization | Directory roles in token claims and scope enforcement, with an example API that checks both scopes and roles |
| Sign-in | Cookie-based browser SSO, authorization code with PKCE, application consent, OIDC discovery, ID tokens, and user info |
| Tokens | Client credentials, confidential-client refresh tokens with rotation, introspection, revocation, and a public JWKS endpoint |
| Passwords | Password hashing, authenticated password changes, email reset links, single-use reset consumption, and session/grant revocation after reset |
| Sessions | Shared PostgreSQL-backed sessions, active session listing, and browser session revocation |
| Operations | Flyway migrations, persistent encrypted signing keys, audit events, shared request throttling, and a two-node Docker setup |

The included demo uses a standard OIDC browser SDK and makes actual requests to protected APIs. It exercises the same integration endpoints available to your other applications.

## Run through the main flow

1. Sign in to the console with the bootstrap account.
2. Open **Users** and create an account with a password of at least 12 characters.
3. Assign `viewer` and, if appropriate, `editor`. The administrator switch controls access to the Gatehouse console separately from application roles.
4. Open **Applications** and register your application. Choose public browser, confidential web, or service account. Copy a confidential client secret when it is shown.
5. Open **Integrations** for issuer URLs and a browser SDK example.
6. Open the demo and select **Sign in with Gatehouse**. Grant the requested scopes if asked.
7. Call the profile API and check editor access. Editor access requires both `api.write` and `editor`.
8. Revoke a user's access from the console and confirm its sessions and OAuth grants are invalidated.

## Documentation

- [docs/ADMINISTRATION.md](docs/ADMINISTRATION.md) explains administrator login, sole-admin review, sign-up, and application onboarding.
- [COMMANDS.md](COMMANDS.md) covers setup, builds, development, logs, scaling, and tests.
- [docs/INTEGRATION.md](docs/INTEGRATION.md) covers browser, backend, service, and token integration.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains shared state, concurrency, roles, signing keys, and revocation.
- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) covers HTTPS, configuration, SMTP, persistence, and backup commands.
- [docs/VERIFICATION.md](docs/VERIFICATION.md) records the checks and their limits.

## Concurrent users

The local verification used 50 separate user accounts and 25 parallel workers through two API nodes. Every user signed in and completed three authenticated reads. Both nodes were observed, all reads matched the correct account, and 50 service tokens were issued and validated.

Concurrent redemption of one authorization code produced one success and one rejection. The same result was verified for one rotating refresh token. A concurrent administrator test preserved the final enabled administrator. The recorded browser workflow p95 was approximately 754 ms in this local run. These numbers describe the tested workload on this development machine. See [the recorded results](docs/concurrency-results.json).

## Scope

This first version has one directory and global application roles. Application IDs, types, redirect URLs, and allowed scopes are configured at registration. Register a new client configuration when those settings need to change. Client enablement and secret rotation remain available on an existing client.

Passwords and email addresses are administered locally. MFA, passkeys, social login, LDAP federation, multi-realm tenancy, automated signing-key rotation, and fine-grained resource policies are outside this version's feature set.

Access tokens live for five minutes. External applications that validate signatures locally can accept a previously issued token until it expires. Use introspection when an integration needs immediate revocation checks. Gatehouse's sample API checks the stored grant on each request.
