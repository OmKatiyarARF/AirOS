#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# start.sh — the ONLY supported way to (re)start the Jenkins stack now.
#
# Always refreshes secrets from AWS Secrets Manager first, then brings the
# containers up. If the secrets fetch fails (AWS unreachable, IAM role
# missing, etc.) this script stops immediately — `set -e` — instead of
# starting Jenkins with stale or missing credentials.
#
# Usage:
#   ./start.sh            # fetch secrets, then `docker compose up -d --build`
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "== 1/2: refreshing secrets from AWS Secrets Manager =="
../../security/fetch-secrets.sh

echo "== 2/2: starting Jenkins =="
docker compose -f jenkins-compose.yaml up -d --build

echo "Done. Jenkins is starting — check with: docker compose -f jenkins-compose.yaml ps"
