#!/usr/bin/env bash
# Host-side rollback for dss-backend-modular TEST environment (:4000).
#
# The rollback TARGET is decided by the Jenkins rollback pipeline — the PREVIOUS
# successful build of the dss-backend-modular-test deploy job (dev) — and passed
# in as TARGET_SHA. READ-ONLY with respect to deploy state: never writes
# .deploy/*-image-sha, so a rollback run can never change what a future rollback
# targets. Re-points the dss-test app/worker at the permanent TEST-ONLY image
# dss-backend-modular-app-test:<sha>. Prod (:3001) is never touched.
#
# Run over SSH stdin from the dss-backend-modular-test-rollback Jenkins job:
#   TARGET_SHA=<shortsha> bash -s < rollback-dss-backend-test.sh

set -euo pipefail

SRC="${SRC:-/home/ec2-user/dss-backend-modular-test-src}"
PROJECT="dss-test"
BASE="docker-compose.test.yml"
OVERRIDE="docker-compose.test.ci.yml"
: "${TARGET_SHA:?TARGET_SHA is required (set by the rollback pipeline from the deploy job previous successful build)}"

if ! docker image inspect "dss-backend-modular-app-test:${TARGET_SHA}" >/dev/null 2>&1; then
    echo "ERROR: image dss-backend-modular-app-test:${TARGET_SHA} not present on host (pruned?)." >&2
    exit 1
fi
if [ ! -f "$SRC/$BASE" ]; then
    echo "ERROR: $SRC/$BASE not found — run a test deploy first." >&2
    exit 1
fi

echo "Rolling back dss-test app/worker to image SHA: $TARGET_SHA"

# Re-point the CI override at the previous SHA TEST-ONLY image (no build).
cat > "$SRC/$OVERRIDE" <<YML
services:
  app:
    image: dss-backend-modular-app-test:${TARGET_SHA}
  worker:
    image: dss-backend-modular-app-test:${TARGET_SHA}
YML

cd "$SRC"
docker compose -p "$PROJECT" -f "$BASE" -f "$OVERRIDE" up -d --no-deps app worker

sleep 10
docker compose -p "$PROJECT" -f "$BASE" -f "$OVERRIDE" ps app worker

echo "Checking test app health on :4000 ..."
MAX_RETRIES=12
HEALTHY=0
for i in $(seq 1 "$MAX_RETRIES"); do
    if wget -qO- http://localhost:4000/health >/dev/null 2>&1; then
        echo "Test app is healthy after rollback."
        HEALTHY=1
        break
    fi
    echo "Attempt $i/$MAX_RETRIES — test app not ready yet, retrying in 5s ..."
    sleep 5
done

if [ "$HEALTHY" -ne 1 ]; then
    echo "ERROR: test app health check failed after rollback." >&2
    exit 1
fi

echo "✅ dss-test rolled back to image SHA $TARGET_SHA (deploy records untouched)"
