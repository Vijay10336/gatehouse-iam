# Configure and deploy Gatehouse

## Local configuration

Run `python3 scripts/setup.py` to generate `.env`. The file is ignored by Git. Keep its credentials outside source archives and container images.

| Setting | Purpose |
| --- | --- |
| `DATABASE_PASSWORD` | PostgreSQL application password |
| `KEY_ENCRYPTION_SECRET` | Base64-encoded 32-byte AES key for the persistent signing key |
| `BOOTSTRAP_ADMIN_EMAIL` | Initial administrator email |
| `BOOTSTRAP_ADMIN_PASSWORD` | Initial administrator password, consumed when that account is first created |
| `DEMO_CLIENT_SECRET` | Initial secret for the example service client |
| `PUBLIC_ISSUER` | Stable public origin advertised by OIDC discovery |
| `PUBLIC_PORT` | External port forwarded to the application |
| `PUBLIC_SCHEME` | External HTTP/HTTPS scheme forwarded to the application |
| `COOKIE_SECURE` | Secure flag for the browser session cookie |
| `AUTH_REQUESTS_PER_MINUTE` | Shared per-IP/per-authentication-path request limit, default 300 |
| `SMTP_HOST`, `SMTP_PORT` | SMTP destination |
| `SMTP_USER`, `SMTP_PASSWORD` | SMTP credentials when required |
| `SMTP_AUTH`, `SMTP_TLS` | SMTP authentication and STARTTLS flags |
| `MAIL_FROM` | Verified sender address for reset messages |

Each API instance can also use `DB_POOL_SIZE`, default 20, from its environment. `DATABASE_URL` and `DATABASE_USER` are backend settings for an external database. The provided Compose file configures its own PostgreSQL service.

Administrator bootstrap values apply once per database. Changing them after initialization cannot add another administrator. Existing example client registrations and their secrets are preserved on subsequent runs. Use the console's password/reset and secret-rotation controls for existing records.

## HTTPS deployment

Choose a stable issuer before initializing a deployment database. For example, use the following settings with your own domain and credentials.

```dotenv
PUBLIC_ISSUER=https://identity.example.com
PUBLIC_PORT=443
PUBLIC_SCHEME=https
COOKIE_SECURE=true
```

Run an HTTPS reverse proxy on the host/platform and forward to `127.0.0.1:18100`. Preserve the public Host header. The included internal gateway forwards the configured public scheme and port to Spring Boot. The API containers and database have no published host ports in Compose.

For a host NGINX TLS virtual host, the proxy location can use the following configuration alongside your certificate configuration.

```nginx
location / {
    proxy_pass http://127.0.0.1:18100;
    proxy_set_header Host $http_host;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
}
```

Recreate services after changing deployment settings.

```sh
docker compose up --build --scale auth=2 -d
```

Register application callbacks using their exact public HTTPS URLs. The bundled demo registration uses the issuer selected when its client record is first created. Use a fresh deployment database with the intended public issuer when moving the local sample to a new environment, or migrate existing client registrations deliberately with matching integration configuration.

## Real reset emails

The local stack uses Mailpit and delivers reset emails into `http://localhost:18125`. To deliver to actual inboxes, configure your SMTP provider.

```dotenv
SMTP_HOST=smtp.your-provider.example
SMTP_PORT=587
SMTP_USER=your-smtp-user
SMTP_PASSWORD=your-smtp-password
SMTP_AUTH=true
SMTP_TLS=true
MAIL_FROM=identity@your-verified-domain.example
```

Use the sender/domain approved by your provider. Recreate the auth containers and verify delivery to your own test account. The local automated protocol script assumes Mailpit for its email assertions.

## Database backup and restore

Create a custom-format database backup from the project root.

```sh
mkdir -p work/backups
docker compose exec -T db pg_dump -U gatehouse -d gatehouse -Fc > work/backups/gatehouse.dump
```

Preserve the matching original `KEY_ENCRYPTION_SECRET` in your secret manager or protected backup location. The database backup contains the encrypted signing key, and the original master secret is needed to load it. Keep user/client passwords and SMTP credentials in your secret-management process as well.

To restore into a replacement database, stop the API instances, start PostgreSQL, and restore the dump into the intended empty replacement database.

```sh
docker compose stop auth
docker compose up -d db
docker compose exec -T db pg_restore -U gatehouse -d gatehouse --clean --if-exists < work/backups/gatehouse.dump
docker compose up --scale auth=2 -d
```

Use the matching encryption secret before starting the API nodes. Test the restore in an isolated environment before replacing an existing deployment.

## Multiple API instances

The gateway resolves running `auth` containers and distributes requests. Sessions, grants, keys, rate counters, and directory state are shared through PostgreSQL. No sticky browser-session routing is required for the included stack.

```sh
docker compose up --scale auth=3 -d
```

Allocate database connections for every node and leave capacity for migrations and administration. Monitor CPU, request latency, database waits, and error rates under your application's actual workload. The included load check records a bounded local test. It establishes the behavior of that test case.

Gateway access logs omit request query strings so OAuth codes and logout token hints are excluded from those log entries.

The development gateway's `X-Gatehouse-Node` header supports verification. Remove that diagnostic `add_header` line from `nginx.conf.template` if you do not want to expose internal upstream addresses in a deployed service.

## Versioned schema changes

Flyway migrations live in `backend/src/main/resources/db/migration`. Add a new migration for a subsequent schema change. Existing applied migration files retain their checksum. Keep application versions compatible with the schema during rollout.

## Current extension points

- Add new authentication methods through Spring Security.
- Add client configuration editing and version checks in `ClientStore`/`AdminService` when needed.
- Introduce a key-ring and explicit rollover lifecycle for signing-key rotation.
- Add application-scoped roles, tenants, or resource policies as separate domain data.
- Extend audit querying and retention based on the deployment's requirements.
