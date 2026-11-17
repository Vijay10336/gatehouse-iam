# Setup and build commands

## Requirements

- Docker Desktop, or Docker Engine with Compose v2, for the complete stack.
- Python 3 for setup and integration/load verification scripts.
- Node 24 or newer for local UI development.
- Java 25 and Maven 3.9 or newer for a local backend build. The Docker build supplies both.

## Clone the repository

```sh
git clone https://github.com/Vijay10336/gatehouse-iam.git
cd gatehouse-iam
```

## Start with two API nodes

From the repository/source directory, run the following commands.

```sh
python3 scripts/setup.py
docker compose up --build --scale auth=2 -d
curl --fail --retry 20 --retry-delay 1 --retry-connrefused http://localhost:18100/api/health
```

Open `http://localhost:18100`. Sign in as `admin@gatehouse.local` using `BOOTSTRAP_ADMIN_PASSWORD` from `.env`. The SMTP inbox is `http://localhost:18125`.

## Logs and status

```sh
docker compose ps
docker compose logs -f auth gateway
docker compose logs --tail 100 db
```

The gateway emits an `X-Gatehouse-Node` response header in this development setup. The verification script uses it to confirm requests reach more than one node.

## Stop, start, and rebuild

```sh
docker compose stop
docker compose start
```

Rebuild after source changes.

```sh
docker compose up --build --scale auth=2 -d
```

Scale a running stack.

```sh
docker compose up --scale auth=3 -d
```

Each API node uses a connection pool of 20 by default. Size database connections and request limits for the number of nodes and your expected workload. Keep all nodes on the same application version, issuer, database, and encryption secret.

## Develop the UI

Start the Docker stack first, then run the following commands in another terminal.

```sh
cd frontend
npm ci
npm run dev
```

Open `http://localhost:5173` for console development. API/login calls are proxied to port 18100. Use the bundled demo at port 18100 for its registered OIDC callback and issuer.

Build the UI with type checking.

```sh
cd frontend
npm ci
npm run build
```

## Build and test the backend

With Java 25 and Maven installed, run the following commands.

```sh
cd backend
mvn test
mvn package
```

These commands build the backend API. The Dockerfile also bundles the React production build into the JAR. Use `docker compose up --build --scale auth=2 -d` to build the complete application.

The unit tests validate redirect allowlists, password byte limits, directory identifiers, normalized email addresses, and signing-key encryption integrity.

## Protocol and permission checks

Start the Docker stack with its default Mailpit email configuration, then run the following command from the project root.

```sh
python3 scripts/verify.py
```

This checks discovery, JWKS, browser login across nodes, PKCE, redirect validation, claims, scope/role enforcement, client credentials, introspection, CSRF, revocation, SMTP password reset, one-use reset links, administrator protection, and optimistic user versions. It creates a test user in the development directory and writes a summary to `docs/protocol-verification.json`.

## Signup and administrator-isolation checks

```sh
python3 scripts/registration.py
```

This checks public signup, verification email delivery, password confirmation, one-use verification links, duplicate registration, blocked self-promotion, and the administrator-only directory filter.

## Concurrent-user checks

Run against the local development stack with two API nodes.

```sh
python3 scripts/concurrency.py --users 50 --workers 25
```

The script creates temporary accounts, signs them in concurrently, reads their profiles, and issues/validates service tokens. It also races duplicate authorization-code and refresh-token redemption. It briefly exercises the bootstrap administrator's access to verify last-administrator protection, restores that account, removes temporary users, and disables the test client afterward. Run this workflow with development data.

Results are written to `docs/concurrency-results.json`. Re-running the scripts records additional audit events. Keep the result file if you need to compare the workload after implementation changes.

## Dependency checks

```sh
cd frontend
npm audit --omit=dev
npm audit
```

The production dependency audit was clean at verification time. Build-tool dependency findings are documented in `docs/VERIFICATION.md`.

## Remove containers

```sh
docker compose down
```

The database volume is retained. `docker compose down -v` also deletes the database volume, including accounts, sessions, application registrations, and signing keys. Back up the database and the original key-encryption secret before replacing stored data.
