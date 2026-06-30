# AirOS Platform Repository

This is the platform infrastructure repository for the **Airawat** ecosystem. AirOS provides shared services that other Airawat applications rely on:

- **Keycloak** — Centralized identity and access management (OIDC/OAuth2)
- **Jenkins CI/CD** — Automated build, test, and deployment pipelines for all repos

## What this repository is NOT

This is **not an application**. It does not contain users, dashboards, or business logic. Those live in separate repositories (e.g., `central_auth_framework`, `dss-backend`, `aq-gis-frontend`).

Think of AirOS as the **"plumbing"** that other apps connect to for authentication and CI/CD.

## Services and Ports

| Service | Description | Port | Access |
|---------|-------------|------|--------|
| Keycloak | Identity provider (SSO, OAuth2, OIDC) | `9081` | Browser + API calls from other apps |
| Keycloak DB | PostgreSQL database for Keycloak | internal only (`5432`) | Keycloak only |
| Jenkins | Continuous Integration / Deployment | `90` (via nginx), `9080` (direct) | Admins / DevOps |

## Prerequisites

- Docker & Docker Compose
- Make (optional, for convenience commands)
- PostgreSQL client (`pg_dump`) if you run manual backups

## Quick Start

```bash
# 1. Clone the repository
git clone <repo-url>
cd AirOS

# 2. Configure environment
cp deploy/.env.example deploy/.env
# Edit deploy/.env with your secrets

# 3. Start Keycloak
make up
# OR manually:
# docker compose -f deploy/keycloak.yaml up -d

# 4. (Optional) Start Jenkins CI/CD
make jenkins-up

# 5. Verify
make status
make keycloak-health
```

Keycloak admin console: [http://localhost:9081](http://localhost:9081)
Jenkins dashboard: [http://localhost:90](http://localhost:90) or [http://localhost:9080](http://localhost:9080)

## Available Commands

```bash
make up               # Start Keycloak platform
make down             # Stop Keycloak platform
make restart          # Restart Keycloak platform
make logs             # Tail all platform logs
make logs-keycloak    # Tail Keycloak logs only
make logs-postgres    # Tail PostgreSQL logs only
make backup           # Backup Keycloak database
make restore-list     # List available database backups
make status           # Show running containers
make keycloak-health  # Check Keycloak health endpoint
make clean            # Stop and DESTROY all Keycloak data (DANGEROUS)

make jenkins-up       # Start Jenkins CI/CD
make jenkins-down     # Stop Jenkins CI/CD
make jenkins-logs     # Tail Jenkins logs
make jenkins-restart  # Restart Jenkins
```

## Architecture

### Keycloak Setup

Keycloak runs in development mode (`start-dev`) for ease of setup. The admin credentials are defined in `deploy/.env`:
- **Admin username**: `KC_ADMIN_USER` (default: `admin`)
- **Admin password**: `KC_ADMIN_PASSWORD`

**Important**: Change these defaults immediately for production.

### How Other Apps Connect to Keycloak

Other applications authenticate users via Keycloak using the host-accessible port:

```
http://localhost:9081  (or the server\'s public IP/domain)
```

In Docker networks, Keycloak is reachable at:
```
http://airos-keycloak:8080  (from within the `airos-auth` network)
```

**Do not hardcode Keycloak URLs** in app configs. Use environment variables like `KEYCLOAK_URL` so the same app works in dev, staging, and production.

### Jenkins CI/CD Setup

Jenkins is configured to watch multiple GitHub repositories and auto-deploy when code is pushed to `main`.

Configuration:
- `deploy/jenkins/repos.json` — list of repositories to watch
- `deploy/jenkins/casc/jenkins.yaml` — admin credentials, GitHub tokens
- `deploy/jenkins/init.groovy.d/create-jobs-from-json.groovy` — auto-creates pipeline jobs on startup

**Important**: Jenkins requires a `.env` file with `JENKINS_ADMIN_USER`, `JENKINS_ADMIN_PASSWORD`, `GITHUB_USER`, and `GITHUB_TOKEN`.

## Jenkinsfile (CI Pipeline)

The `Jenkinsfile` in this repo defines the CI pipeline:
- On every push to any branch: checks out code
- On push to `main`: deploys Keycloak automatically

There is **no build/test stage** for AirOS because it is infrastructure, not application code.

## Backup & Restore

### Automated Backup
```bash
make backup
```
Backups are saved to `./backups/` with timestamps. Old backups are NOT auto-deleted.

### Manual Restore
```bash
# List backups
make restore-list

# Restore a specific backup
zcat backups/keycloak_YYYYMMDD_HHMMSS.sql.gz | docker exec -i airos-keycloak-postgres psql -U keycloak keycloak
```

## Security

- **Never commit `.env` or `.env.*` files** — they contain passwords and tokens
- Rotate secrets regularly: `KC_DB_PASSWORD`, `KC_ADMIN_PASSWORD`, `JENKINS_ADMIN_PASSWORD`, `GITHUB_TOKEN`
- Keycloak runs with `KC_HTTP_ENABLED=true` in dev mode — for production, set up HTTPS
- Jenkins exposes port `90` publicly on the server — restrict access in production

## Troubleshooting

### Keycloak container keeps restarting
- Check PostgreSQL health: `make logs-postgres`
- Verify `.env` values are set: `cat deploy/.env`
- Ensure port `9081` is not in use: `lsof -i :9081`

### Jenkins jobs not being created
- Verify `repos.json` is valid JSON
- Check Jenkins init logs: `cd deploy/jenkins && docker compose logs -f jenkins`
- Restart Jenkins: `make jenkins-restart`

### Keycloak not accessible from other apps
- Make sure Keycloak container is running: `make status`
- Check Keycloak health endpoint: `make keycloak-health`
- Verify the app is looking at the correct URL/port

## Contributing

This repository is maintained by the Airawat DevOps team. To add a new repository to CI/CD monitoring, edit `deploy/jenkins/repos.json`.

## License

Internal use only — Airawat Foundation
