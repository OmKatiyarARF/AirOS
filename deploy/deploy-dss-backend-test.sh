#!/usr/bin/env bash
# Test-environment deploy for dss-backend-modular on port 4000 (project dss-test).
# Fully isolated from prod: separate checkout + a DEDICATED image repo name
# (dss-backend-modular-app-test, SHA-tagged). Prod and test share one Docker
# daemon, so test MUST NOT build/tag anything under the prod image name
# (dss-backend-modular-app) or its :latest — doing so previously let a broken
# test build hijack prod's :latest. Only app/worker are rebuilt+recreated; test
# DB, redis and ML sidecars keep running, and prod (:3001) is never touched.
#
# Run over SSH stdin from the dss-backend-modular-test Jenkins job:
#   bash -s -- <branch> < deploy-dss-backend-test.sh
#
# This script used to live only on the AirQuality host as
# /home/ec2-user/dss-backend-modular-test/deploy.sh, in a git repo with no
# remote — untracked by any shared repo, edited in place, with .bak copies
# beside it. It now lives here and is piped over SSH like deploy-keycloak.sh
# and the rollback scripts, so what runs on the host is always what is in this
# repo. STATE below still points at that same host directory: the .deploy
# records it holds are live rollback state and must not be relocated.
set -euo pipefail

BRANCH="${1:-${BRANCH:-dev}}"
PROD=/home/ec2-user/dss-backend-modular
SRC=/home/ec2-user/dss-backend-modular-test-src
STATE=/home/ec2-user/dss-backend-modular-test
PROJECT=dss-test
BASE=docker-compose.test.yml
OVERRIDE=docker-compose.test.ci.yml

echo ">> Isolated checkout ($SRC) -> origin/$BRANCH"
if [ ! -d "$SRC/.git" ]; then
  git clone "$PROD" "$SRC"
  git -C "$SRC" remote set-url origin "$(git -C "$PROD" remote get-url origin)"
fi
git -C "$SRC" fetch origin "$BRANCH" --quiet
# Robust to a dirty working tree (e.g. a previous deploy left local changes to
# tracked files): discard local mods + untracked files, then force-reset to the
# target branch. Without this, `git checkout -B` aborts on modified files.
git -C "$SRC" checkout -- . 2>/dev/null || true
git -C "$SRC" clean -fdx --quiet
git -C "$SRC" checkout -B "$BRANCH" "origin/$BRANCH" --quiet
git -C "$SRC" reset --hard "origin/$BRANCH" --quiet

IMAGE_TAG="$(git -C "$SRC" rev-parse --short HEAD)"
export IMAGE_TAG
echo ">> Building isolated app image dss-backend-modular-app-test:${IMAGE_TAG}"

echo ">> Syncing untracked test config from prod checkout"
cp "$PROD/$BASE" "$SRC/$BASE"
cp "$PROD/.env.test" "$SRC/.env.test"
mkdir -p "$SRC/docker"
if [ -f "$PROD/docker/init-db.sql" ]; then
  cp "$PROD/docker/init-db.sql" "$SRC/docker/init-db.sql"
fi

# Fix 1 — CI override pins app/worker to the SHA-tagged, TEST-ONLY image
# (permanent tag, so rollback can re-point at it). The prod image name and
# :latest are never written from the test env.
cat > "$SRC/$OVERRIDE" <<YML
services:
  app:
    image: dss-backend-modular-app-test:${IMAGE_TAG}
    build: .
  worker:
    image: dss-backend-modular-app-test:${IMAGE_TAG}
YML

cd "$SRC"
docker compose -p "$PROJECT" -f "$BASE" -f "$OVERRIDE" build app

# Fix 3 prep — record the rollback target BEFORE recreating.
#
# previous-image-sha only advances when this deploy actually CHANGES the
# running commit. Re-running the job on the same commit (a manual rerun, or a
# poll that found nothing new) must not copy the live SHA over the previous
# one: that leaves both records equal and the next rollback becomes a silent
# no-op. This mirrors the "skip consecutive duplicate SHAs" rule the rollback
# pipelines used to implement by walking Jenkins' build history.
mkdir -p "$STATE/.deploy"
LAST_SHA=""
[ -s "$STATE/.deploy/last-image-sha" ] && LAST_SHA="$(cat "$STATE/.deploy/last-image-sha")"
if [ -n "$LAST_SHA" ] && [ "$LAST_SHA" != "$IMAGE_TAG" ]; then
  printf '%s\n' "$LAST_SHA" > "$STATE/.deploy/previous-image-sha"
  echo ">> Rollback target recorded: ${LAST_SHA}"
else
  echo ">> Same commit as the live deploy (${IMAGE_TAG}) — rollback target left unchanged"
fi

echo ">> Recreating ONLY app + worker on the test image (deps left running)"
docker compose -p "$PROJECT" -f "$BASE" -f "$OVERRIDE" up -d --no-deps app worker

printf '%s\n' "${IMAGE_TAG}" > "$STATE/.deploy/last-image-sha"

docker image prune -f >/dev/null 2>&1 || true
echo ">> Done: dss-test app/worker @ ${IMAGE_TAG} on :4000"
