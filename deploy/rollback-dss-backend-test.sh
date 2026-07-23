#!/usr/bin/env bash
# Host-side rollback for dss-backend-modular TEST environment (Fix 3).
#
# Restarts the dss-test app/worker containers (project "dss-test", port 4000)
# at the image SHA recorded in .deploy/previous-image-sha by the last test
# deploy. The SHA-tagged image is permanent, so this just re-points compose at
# the previous tag — no rebuild, no git reset.
#
# Run on the AirQuality server (13.205.88.131) over SSH from the
# dss-backend-modular-test-rollback Jenkins job. Piped over SSH stdin.
#
# Usage: rollback-dss-backend-test.sh

set -euo pipefail

STATE_DIR="${STATE_DIR:-/home/ec2-user/dss-backend-modular-test}"
SRC="${SRC:-/home/ec2-user/dss-backend-modular-test-src}"
PROJECT="dss-test"
BASE="docker-compose.test.yml"
OVERRIDE="docker-compose.test.ci.yml"
ROLLBACK_FILE="$STATE_DIR/.deploy/previous-image-sha"

if [ ! -s "$ROLLBACK_FILE" ]; then
    echo "ERROR: no previous image SHA recorded at $ROLLBACK_FILE." >&2
    echo "Run a SHA-tagged test deploy first so a rollback target exists." >&2
    exit 1
fi

TARGET_SHA="$(cat "$ROLLBACK_FILE")"
echo "Rolling back dss-test app/worker to previous image SHA: $TARGET_SHA"

# Rewrite the CI override to point at the previous SHA's image (no build).
cat > "$SRC/$OVERRIDE" <<YML
services:
  app:
    image: dss-backend-modular-app:${TARGET_SHA}
  worker:
    image: dss-backend-modular-app:${TARGET_SHA}
YML

cd "$SRC"
docker compose -p "$PROJECT" -f "$BASE" -f "$OVERRIDE" up -d --no-deps app worker

# Bookkeeping: the rolled-back-to SHA is now live.
mkdir -p "$STATE_DIR/.deploy"
echo "$TARGET_SHA" > "$STATE_DIR/.deploy/last-image-sha"

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

echo "✅ dss-test rolled back to image SHA $TARGET_SHA"
