#!/usr/bin/env bash
# Host-side rollback for dss-backend-modular PRODUCTION (:3001).
#
# The rollback TARGET is decided by the Jenkins rollback pipeline — the PREVIOUS
# successful build of the dss-backend-modular deploy job (release/v1) — and
# passed in as TARGET_SHA. This script is READ-ONLY with respect to deploy
# state: it never writes .deploy/*-image-sha, so a rollback run can never change
# what a future rollback targets (that is derived purely from the deployment
# pipeline build history).
#
# Re-points app/worker/gp-heatmap/dhsa-api-model at the permanent SHA-tagged
# images built by that deploy. No rebuild, no git reset.
#
# Run over SSH stdin from the dss-backend-modular-rollback Jenkins job:
#   DEPLOY_PATH=... TARGET_SHA=<shortsha> bash -s < rollback-dss-backend.sh

set -euo pipefail

DEPLOY_PATH="${DEPLOY_PATH:-/home/ec2-user/dss-backend-modular}"
: "${TARGET_SHA:?TARGET_SHA is required (set by the rollback pipeline from the deploy job previous successful build)}"

cd "$DEPLOY_PATH"

echo "Rolling back dss-backend-modular to deployment image SHA: $TARGET_SHA"

# Every service must have an image at this SHA (permanent tag from that deploy).
MISSING=0
for svc in app worker gp-heatmap dhsa-api-model; do
    if ! docker image inspect "dss-backend-modular-${svc}:${TARGET_SHA}" >/dev/null 2>&1; then
        echo "ERROR: image dss-backend-modular-${svc}:${TARGET_SHA} not present on host." >&2
        MISSING=1
    fi
done
if [ "$MISSING" -ne 0 ]; then
    echo "Refusing to roll back: one or more target images are missing (pruned?)." >&2
    exit 1
fi

export IMAGE_TAG="$TARGET_SHA"
docker compose up -d --no-build app worker gp-heatmap dhsa-api-model

sleep 10
docker compose ps

# Health-check on whatever host port the app service publishes (prod uses 3001).
HOSTPORT="$(docker compose port app 3000 2>/dev/null | sed 's/.*://')"
HOSTPORT="${HOSTPORT:-3001}"
echo "Checking app health on :${HOSTPORT} ..."
MAX_RETRIES=12
HEALTHY=0
for i in $(seq 1 "$MAX_RETRIES"); do
    if wget -qO- "http://localhost:${HOSTPORT}/health" >/dev/null 2>&1; then
        echo "App is healthy after rollback."
        HEALTHY=1
        break
    fi
    echo "Attempt $i/$MAX_RETRIES — app not ready yet, retrying in 5s ..."
    sleep 5
done

if [ "$HEALTHY" -ne 1 ]; then
    echo "ERROR: app health check failed after rollback." >&2
    exit 1
fi

echo "✅ dss-backend-modular rolled back to image SHA $TARGET_SHA (deploy records untouched)"
