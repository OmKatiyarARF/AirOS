#!/usr/bin/env bash
# Host-side Keycloak deploy for AirOS. Run on the deploy target over SSH
# (J1: Jenkins no longer drives Docker locally — it SSHes here and runs this).
#
# P1: before overwriting the live checkout, the currently-deployed commit SHA
# is saved to .deploy/previous-sha so the airos-rollback job can restore it
# instantly if this deploy turns out bad. There is no longer a destructive
# `git reset --hard` with no way back.
#
# Usage: deploy-keycloak.sh <branch>

set -euo pipefail

BRANCH="${1:?usage: deploy-keycloak.sh <branch>}"
DEPLOY_PATH="${DEPLOY_PATH:-/home/ec2-user/AirOS}"

cd "$DEPLOY_PATH"

# P1: record the commit currently running so we can roll back to it.
mkdir -p "$DEPLOY_PATH/.deploy"
PREV_SHA="$(git rev-parse HEAD 2>/dev/null || true)"
if [ -n "$PREV_SHA" ]; then
    printf '%s\n' "$PREV_SHA" > "$DEPLOY_PATH/.deploy/previous-sha"
    echo "Recorded previous deploy SHA for rollback: $PREV_SHA"
fi

echo "Deploying AirOS ($BRANCH) at $DEPLOY_PATH ..."
git fetch origin "$BRANCH"
git reset --hard "origin/$BRANCH"

echo "Starting Keycloak platform ..."
cd deploy
docker compose -f keycloak.yaml pull
docker compose -f keycloak.yaml up -d

echo "Deployed. Waiting 5 seconds for services to stabilize ..."
sleep 5
docker compose -f keycloak.yaml ps

# Keycloak readiness probe. /health/ready is served on the management port
# 9000 (KC_HEALTH_ENABLED), which is not published to the host. Probe it by
# running curl in a throwaway container that shares the Keycloak container's
# network namespace, so localhost:9000 resolves to Keycloak itself.
echo "Checking Keycloak health ..."
MAX_RETRIES=24
RETRY_DELAY=5
HEALTHY=0
for i in $(seq 1 "$MAX_RETRIES"); do
    if docker run --rm --network "container:airos-keycloak" \
           curlimages/curl:8.11.0 \
           -sf http://localhost:9000/health/ready >/dev/null 2>&1; then
        echo "Keycloak is healthy."
        HEALTHY=1
        break
    fi
    echo "Attempt $i/$MAX_RETRIES — Keycloak not ready yet, retrying in ${RETRY_DELAY}s ..."
    sleep "$RETRY_DELAY"
done

if [ "$HEALTHY" -ne 1 ]; then
    echo "ERROR: Keycloak health check failed after $((MAX_RETRIES * RETRY_DELAY)) seconds." >&2
    echo "Roll back with the airos-rollback Jenkins job (restores $PREV_SHA)." >&2
    exit 1
fi

# Reclaim space safely (only images labelled airos-platform — currently none
# set the label, so this is a no-op kept for when images are labelled).
docker image prune -f --filter "label=airos-platform" >/dev/null 2>&1 || true

echo "✅ AirOS ($BRANCH) deployed. SHA: $(git rev-parse --short HEAD)"
