#!/usr/bin/env bash
# TEMPORARY preview deploy for dss-backend-modular's feature/devops-monitoring
# branch, on port 4004 (project dss-devops-monitoring). Fully isolated from
# prod (:3001), dss-test (:4000), and each other: its own Postgres, Redis, ML
# sidecars, image names, and volumes — see docker-compose.devops-monitoring.yml
# in the app repo itself (committed on this branch, unlike docker-compose.test.yml
# which this script's sibling copies in from the prod checkout — here it's
# already in the branch being deployed, so no copy step is needed for it).
#
# Meant to be torn down once this branch's /metrics work is verified — see
# rollback/teardown notes at the bottom of this file.
#
# Run over SSH stdin from the dss-backend-modular-devops-monitoring Jenkins job:
#   bash -s -- feature/devops-monitoring < deploy-dss-backend-devops-monitoring.sh
set -euo pipefail

BRANCH="${1:-${BRANCH:-feature/devops-monitoring}}"
PROD=/home/ec2-user/dss-backend-modular
SRC=/home/ec2-user/dss-backend-modular-devops-monitoring-src
STATE=/home/ec2-user/dss-backend-modular-devops-monitoring
PROJECT=dss-devops-monitoring
BASE=docker-compose.devops-monitoring.yml
OVERRIDE=docker-compose.devops-monitoring.ci.yml

echo ">> Isolated checkout ($SRC) -> origin/$BRANCH"
if [ ! -d "$SRC/.git" ]; then
  git clone "$PROD" "$SRC"
  git -C "$SRC" remote set-url origin "$(git -C "$PROD" remote get-url origin)"
fi
git -C "$SRC" fetch origin "$BRANCH" --quiet
git -C "$SRC" checkout -- . 2>/dev/null || true
git -C "$SRC" clean -fdx --quiet
git -C "$SRC" checkout -B "$BRANCH" "origin/$BRANCH" --quiet
git -C "$SRC" reset --hard "origin/$BRANCH" --quiet

IMAGE_TAG="$(git -C "$SRC" rev-parse --short HEAD)"
export IMAGE_TAG
echo ">> Building isolated app image dss-backend-modular-app-devops-monitoring:${IMAGE_TAG}"

echo ">> Syncing untracked env config from prod checkout"
cp "$PROD/.env.test" "$SRC/.env.test"

# Fix 1 pattern (borrowed from the test env's deploy script) — CI override
# pins app/worker to the SHA-tagged, devops-monitoring-only image. Never
# writes the prod image name, the test image name, or any :latest tag.
cat > "$SRC/$OVERRIDE" <<YML
services:
  app:
    image: dss-backend-modular-app-devops-monitoring:${IMAGE_TAG}
    build: .
  worker:
    image: dss-backend-modular-app-devops-monitoring:${IMAGE_TAG}
YML

cd "$SRC"
mkdir -p "$STATE/.deploy"
docker compose -p "$PROJECT" -f "$BASE" -f "$OVERRIDE" build app
docker compose -p "$PROJECT" -f "$BASE" -f "$OVERRIDE" up -d

printf '%s\n' "${IMAGE_TAG}" > "$STATE/.deploy/last-image-sha"

docker image prune -f >/dev/null 2>&1 || true
echo ">> Done: dss-devops-monitoring app/worker @ ${IMAGE_TAG} on :4004"

# ---------------------------------------------------------------------------
# TEARDOWN (run manually once testing is confirmed pass/fail — this script
# does not do this automatically):
#   cd /home/ec2-user/dss-backend-modular-devops-monitoring-src
#   docker compose -p dss-devops-monitoring -f docker-compose.devops-monitoring.yml \
#     -f docker-compose.devops-monitoring.ci.yml down -v
#   rm -rf /home/ec2-user/dss-backend-modular-devops-monitoring-src \
#          /home/ec2-user/dss-backend-modular-devops-monitoring
#   docker image rm dss-backend-modular-app-devops-monitoring:${IMAGE_TAG} 2>/dev/null || true
# Also revert the AirOS commit that added this job (Jenkinsfile, this script,
# repos.json entry) and remove targets/probes/... :4004 entries and the
# preview target in targets/applications/airquality.yml from the monitoring
# repo, once the branch is merged or dropped.
# ---------------------------------------------------------------------------
