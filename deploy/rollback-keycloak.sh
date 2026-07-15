#!/usr/bin/env bash
# Host-side rollback for AirOS Keycloak deploys (P1).
#
# Restores the commit recorded in .deploy/previous-sha by deploy-keycloak.sh
# and redeploys it, giving an instant way back from a bad deploy. Run on the
# deploy target over SSH from the airos-rollback Jenkins job.
#
# Usage: rollback-keycloak.sh

set -euo pipefail

DEPLOY_PATH="${DEPLOY_PATH:-/home/ec2-user/AirOS}"
ROLLBACK_FILE="$DEPLOY_PATH/.deploy/previous-sha"

if [ ! -s "$ROLLBACK_FILE" ]; then
    echo "ERROR: no previous deploy SHA recorded at $ROLLBACK_FILE." >&2
    echo "Run a normal deploy first (deploy-keycloak.sh) so a rollback target exists." >&2
    exit 1
fi

TARGET_SHA="$(cat "$ROLLBACK_FILE")"
cd "$DEPLOY_PATH"

echo "Rolling back AirOS Keycloak to previous deploy SHA: $TARGET_SHA"
git fetch origin "$TARGET_SHA" 2>/dev/null || true
git reset --hard "$TARGET_SHA"

echo "Redeploying Keycloak from $TARGET_SHA ..."
cd deploy
docker compose -f keycloak.yaml pull
docker compose -f keycloak.yaml up -d

sleep 5
docker compose -f keycloak.yaml ps

echo "Checking Keycloak health ..."
MAX_RETRIES=24
RETRY_DELAY=5
HEALTHY=0
for i in $(seq 1 "$MAX_RETRIES"); do
    if docker run --rm --network "container:airos-keycloak" \
           curlimages/curl:8.11.0 \
           -sf http://localhost:9000/health/ready >/dev/null 2>&1; then
        echo "Keycloak is healthy after rollback."
        HEALTHY=1
        break
    fi
    echo "Attempt $i/$MAX_RETRIES — Keycloak not ready yet, retrying in ${RETRY_DELAY}s ..."
    sleep "$RETRY_DELAY"
done

if [ "$HEALTHY" -ne 1 ]; then
    echo "ERROR: Keycloak health check failed after rollback." >&2
    exit 1
fi

echo "✅ AirOS rolled back to $(git rev-parse --short HEAD)"
