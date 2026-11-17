# Verification

Verified on 18 November 2026 using the local Docker stack.

| Check | Result |
| --- | --- |
| Java validation/encryption tests | 6 tests passed |
| TypeScript check and React production build | Passed |
| Docker build with unit tests | Passed |
| Two API nodes sharing PostgreSQL | Running and observed through the gateway |
| OIDC discovery and public JWKS | Passed |
| Authorization code + PKCE | Passed |
| Invalid verifier and redirect rejection | Passed |
| Role claims and scope/role enforcement | Passed |
| Client credentials and introspection | Passed |
| CSRF and administrator permissions | Passed |
| Password reset delivery through local SMTP | Passed |
| Single-use reset link and grant/session revocation | Passed |
| Last enabled administrator and stale user versions | Passed |
| Browser console and user-access controls | Checked |
| Browser demo login and protected API calls | Both read and editor API calls returned HTTP 200 |
| Production npm dependency audit | Zero vulnerabilities reported |

## Public signup and administrator isolation

Public signup, email verification, password confirmation for activation, verification resend, and one-use verification links passed. A signup that submitted administrator fields remained a standard account, and self-promotion through management APIs was rejected. The administrator-only filter returned one existing administrator. A regression test covers changed bootstrap settings after initialization. See [registration-verification.json](registration-verification.json).

## Concurrent-user workload

The run used 50 distinct accounts and 25 parallel workers. Each browser workflow signed in and completed three authenticated profile reads. Requests reached two API nodes, and every profile matched its authenticated account.

The browser workload completed in approximately 1.24 seconds. The median full-workflow latency was approximately 583 ms and p95 was approximately 754 ms. The script also issued and validated 50 unique service tokens.

A race on one authorization code produced one successful response and one rejection. A race on one refresh token produced the same result. A concurrent test of administrator access changes preserved an enabled administrator.

See [concurrency-results.json](concurrency-results.json) for the recorded measurements and [protocol-verification.json](protocol-verification.json) for the protocol check list. These are results from a local Docker development machine. Evaluate capacity with the intended deployment infrastructure and workload before setting service limits.

## Dependency audit

The full npm audit reported five high-severity development-toolchain findings rooted in the [picomatch repeated-extglob ReDoS advisory](https://github.com/advisories/GHSA-fc5q-39xc-vjpf), propagated through Vite and its plugin/globbing dependencies. The registry's latest picomatch version was 4.0.7 at verification time. The production dependency audit reported zero findings. Recheck upstream tooling releases when updating dependencies.

## Verification limits

Real-domain HTTPS, delivery through a real SMTP provider, and integration with a separately deployed third-party application have not been exercised in this local run. The included browser SDK demo and protected API exercise actual protocol requests against Gatehouse.

Automated checks cover this first version's implemented features. MFA, federation, multi-tenant isolation, key rotation, and large-scale production capacity are outside this version's implemented/tested scope.
