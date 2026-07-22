#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# fetch-secrets.sh
#
# Pulls the AirOS-jenkins secret out of AWS Secrets Manager and writes its
# fields to disk, in the exact shapes Jenkins needs:
#
#   - Simple values (GitHub token)  -> deploy/jenkins/.env.secrets  (KEY=VALUE)
#   - SSH private keys (multi-line) -> deploy/jenkins/secrets-runtime/*.pem
#
# Both outputs are git-ignored and are treated as DISPOSABLE: this script is
# meant to be re-run every time Jenkins starts, so the files are always a
# fresh copy of whatever is currently in AWS Secrets Manager, never a
# permanent copy that sits on disk forever like the old ~/.ssh/*.pem files
# did.
#
# Requires: the AWS CLI, and an environment where AWS credentials are
# available (on this EC2 instance, that's automatic via the attached IAM
# role "AirOS-Jenkins-SecretsReader" — no access keys needed, no
# `aws configure` needed).
#
# Usage:
#   ./fetch-secrets.sh
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="${SCRIPT_DIR}/secrets-manifest.json"
JENKINS_DIR="${SCRIPT_DIR}/../deploy/jenkins"
ENV_OUT="${JENKINS_DIR}/.env.secrets"
KEYS_DIR="${JENKINS_DIR}/secrets-runtime"

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required but not installed (sudo dnf install -y jq / apt-get install -y jq)." >&2
  exit 1
fi
if ! command -v aws >/dev/null 2>&1; then
  echo "ERROR: aws CLI is required but not installed." >&2
  exit 1
fi

SECRET_ID="$(jq -r '.secret_id' "$MANIFEST")"
REGION="$(jq -r '.region' "$MANIFEST")"

echo "Fetching secret '${SECRET_ID}' from AWS Secrets Manager (region ${REGION})..."

# Pull the whole secret ONCE into a variable that never touches disk.
SECRET_JSON="$(aws secretsmanager get-secret-value \
  --secret-id "$SECRET_ID" \
  --region "$REGION" \
  --query SecretString \
  --output text)"

# --- 1. Simple single-line values -> .env.secrets --------------------------
mkdir -p "$JENKINS_DIR"
: > "$ENV_OUT"
chmod 600 "$ENV_OUT"

jq -r '.env_fields | to_entries[] | select(.key != "_comment") | "\(.key)\t\(.value)"' "$MANIFEST" | \
while IFS=$'\t' read -r secret_field env_var_name; do
  value="$(echo "$SECRET_JSON" | jq -r --arg k "$secret_field" '.[$k] // empty')"
  if [ -z "$value" ]; then
    echo "  WARNING: field '${secret_field}' not found in secret, skipping ${env_var_name}." >&2
    continue
  fi
  echo "${env_var_name}=${value}" >> "$ENV_OUT"
  echo "  wrote env var ${env_var_name} -> ${ENV_OUT}"
done

# --- 2. Multi-line SSH keys -> secrets-runtime/*.pem ------------------------
mkdir -p "$KEYS_DIR"
chmod 700 "$KEYS_DIR"

jq -r '.file_fields | to_entries[] | select(.key != "_comment") | "\(.key)\t\(.value)"' "$MANIFEST" | \
while IFS=$'\t' read -r secret_field file_name; do
  value="$(echo "$SECRET_JSON" | jq -r --arg k "$secret_field" '.[$k] // empty')"
  if [ -z "$value" ]; then
    echo "  WARNING: field '${secret_field}' not found in secret, skipping ${file_name}." >&2
    continue
  fi
  out_path="${KEYS_DIR}/${file_name}"
  printf '%s\n' "$value" > "$out_path"
  chmod 600 "$out_path"
  echo "  wrote SSH key ${file_name} -> ${out_path}"
done

# Clear the in-memory copy as soon as we're done with it.
unset SECRET_JSON

echo "Done. Secrets are refreshed in ${JENKINS_DIR} (git-ignored, disposable)."
