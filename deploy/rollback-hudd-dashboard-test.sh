#!/usr/bin/env bash
# Host-side rollback for hudd-dashboard TEST environment (:8766).
#
# The rollback TARGET is decided by the Jenkins rollback pipeline — the PREVIOUS
# successful build of the hudd-dashboard deploy job (multibranch, branch dev) —
# and passed in as TARGET_SHA (full git SHA).
#
# CODE-ONLY rollback: checks out the previous commit, reinstalls dependencies,
# rebuilds, and restarts the PM2 test process. It does NOT touch the database
# and does NOT reverse any Prisma migration — if the build being rolled back
# shipped a migration, the schema stays exactly as it is; only the application
# code goes back. This script never writes any state file of its own, so
# repeated rollbacks always target whatever the deploy job's history says is
# the previous successful build, decided fresh each time.
#
# Run over SSH stdin from the hudd-dashboard-test-rollback Jenkins job:
#   TARGET_SHA=<full-sha> bash -s < rollback-hudd-dashboard-test.sh

set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-/home/ec2-user/dev/hudd-dashboard}"
: "${TARGET_SHA:?TARGET_SHA is required (set by the rollback pipeline from the deploy job previous successful build)}"

cd "$PROJECT_DIR"

git fetch origin dev --quiet || true

if ! git cat-file -e "${TARGET_SHA}^{commit}" 2>/dev/null; then
    echo "ERROR: commit ${TARGET_SHA} not found in the local hudd-dashboard checkout (history pruned?)." >&2
    exit 1
fi

echo "Rolling back hudd-dashboard-test to commit: ${TARGET_SHA}"

# Stay "on" the dev branch (rather than a detached HEAD) so any later manual
# use of npm run deploy:test / redeploy:test on this host still works — those
# scripts check that the current branch is named dev before pulling.
git checkout dev --quiet
git reset --hard "${TARGET_SHA}"

echo ">> Installing dependencies..."
npm ci

echo ">> Building..."
npm run build

echo ">> Restarting PM2 test instance..."
npm run pm2:restart:test || npm run pm2:start:test

sleep 5
pm2 show hudd-dashboard-test | grep -E "status|uptime|restarts" || true

echo "Checking hudd-dashboard-test health on :8766 ..."
MAX_RETRIES=12
HEALTHY=0
for i in $(seq 1 "$MAX_RETRIES"); do
    # curl (no -f) so a 503 from the unrelated FastAPI dependency check inside
    # the health route does not itself count as "app is down" — we only care
    # that the Next.js process is up and responding, which the nextjs:"ok"
    # field reports regardless of that dependency's status.
    BODY="$(curl -s http://localhost:8766/hudd-dashboard/api/health || true)"
    if printf '%s' "$BODY" | grep -q '"nextjs":"ok"'; then
        echo "hudd-dashboard-test is healthy after rollback."
        HEALTHY=1
        break
    fi
    echo "Attempt $i/$MAX_RETRIES — test app not ready yet, retrying in 5s ..."
    sleep 5
done

if [ "$HEALTHY" -ne 1 ]; then
    echo "ERROR: hudd-dashboard-test health check failed after rollback." >&2
    exit 1
fi

echo "hudd-dashboard-test rolled back to commit ${TARGET_SHA} (code only, database untouched)"
