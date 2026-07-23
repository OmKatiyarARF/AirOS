#!/usr/bin/env bash
# Host-side rollback for dss-backend-modular prod deploys (Fix 3).
#
# Restarts the app/worker/gp-heatmap/dhsa-api-model containers using the image
# tag recorded in .deploy/previous-image-sha by the last deploy, giving an
# instant way back from a bad deploy. The SHA-tagged images are permanent (not
# overwritten by later builds), so this just re-points compose at the previous
# tag — no rebuild, no git reset needed.
#
# Run on the deploy target (13.205.88.131) over SSH from the
# dss-backend-modular-rollback Jenkins job. Piped over SSH stdin (not run from
# a host file) so it cannot be self-modified mid-execution.
#
# Usage: rollback-dss-backend.sh

set -euo pipefail

DEPLOY_PATH="${DEPLOY_PATH:-/home/ec2-user/dss-backend-modular}"
ROLLBACK_FILE="$DEPLOY_PATH/.deploy/previous-image-sha"

if [ ! -s "$ROLLBACK_FILE" ]; then
    echo "ERROR: no previous image SHA recorded at $ROLLBACK_FILE." >&2
    echo "Run a SHA-tagged deploy first (the Jenkinsfile records it) so a rollback target exists." >&2
    exit 1
fi

TARGET_SHA="$(cat "$ROLLBACK_FILE")"
echo "Rolling back dss-backend-modular to previous image SHA: $TARGET_SHA"

cd "$DEPLOY_PATH"
export IMAGE_TAG="$TARGET_SHA"

# The SHA-tagged image already exists locally (permanent tag), so just recreate
# the containers pointing at it — no rebuild.
docker compose up -d --no-build app worker gp-heatmap dhsa-api-model

# Bookkeeping: the rolled-back-to SHA is now live.
echo "$TARGET_SHA" > .deploy/last-image-sha

sleep 10
docker compose ps

echo "Checking app health on :3000 ..."
MAX_RETRIES=12
HEALTHY=0
for i in $(seq 1 "$MAX_RETRIES"); do
    if wget -qO- http://localhost:3000/health >/dev/null 2>&1; then
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

echo "✅ dss-backend-modular rolled back to image SHA $TARGET_SHA"
