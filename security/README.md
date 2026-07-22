# Secrets management for Jenkins (SSH keys + GitHub token)

## The problem this solves

Jenkins needed 3 SSH private keys (to deploy over SSH to other servers) and a
GitHub token (to clone/watch private repos). Previously these lived as
**permanent plaintext files** on this server:

- `~/.ssh/air-quality.pem`, `~/.ssh/product-dev.pem`, `~/.ssh/test.airawat.org.pem`
- `GITHUB_TOKEN=...` as a plaintext line inside `deploy/jenkins/.env`

Anyone (or anything — malware, a compromised process, a stray backup) with
read access to this server's filesystem could copy those files silently,
with no logging and no way to tell it happened.

## The fix, in plain terms

1. All 4 secrets now live in **one AWS Secrets Manager secret** called
   `AirOS-jenkins`, encrypted at rest, access-controlled by AWS IAM.
2. This EC2 instance has an **IAM role** (`AirOS-Jenkins-SecretsReader`)
   attached to it that is allowed to *read* that one secret — nothing else.
   No AWS access keys are stored anywhere on disk; the permission comes from
   the instance itself (via its IAM role), which is how AWS recommends doing
   this.
3. `fetch-secrets.sh` pulls the secret and writes it to disk in the shapes
   Jenkins needs (see below) — but every file it writes is **disposable**:
   git-ignored, regenerated fresh every time Jenkins starts, never a
   permanent fixture.
4. `start.sh` always runs `fetch-secrets.sh` *before* starting Jenkins, so
   Jenkins never boots with stale or missing secrets.
5. The SSH keys are additionally registered as real **Jenkins credentials**
   (Manage Jenkins → Credentials, ids `ssh-air-quality`, `ssh-product-dev`,
   `ssh-test-airawat`) via `casc/jenkins.yaml`, so pipelines can reference
   them by ID with `sshagent([...])` instead of a raw file path — this masks
   the key in build logs and lets you control which jobs can use which key.

## Files in this folder

| File | Purpose |
|---|---|
| `secrets-manifest.json` | Maps each field inside the AWS secret to where it gets written on disk. Edit this (not the script) if you rename/add a secret field. |
| `fetch-secrets.sh` | Fetches the secret from AWS and writes the files described above. Safe to re-run any time — always overwrites with the latest values. |

## Where the fetched secrets end up (all git-ignored, all disposable)

- `deploy/jenkins/.env.secrets` — `GITHUB_TOKEN=...` (loaded into the Jenkins
  container via Docker Compose's `env_file`, and used by `casc/jenkins.yaml`
  for the `github-creds` credential).
- `deploy/jenkins/secrets-runtime/*.pem` — the 3 SSH keys, `chmod 600`.
  Mounted read-only into the Jenkins container at `/var/jenkins_home/.ssh/`
  (the same path the old files used to occupy, so any pipeline that still
  does `ssh -i /var/jenkins_home/.ssh/xxx.pem` keeps working unchanged).
  `casc/jenkins.yaml` also reads these files (via JCasC's `${readFile:...}`
  helper) to register them as proper Jenkins credentials.

## How to start/restart Jenkins now

Always use the wrapper script — it refreshes secrets first, then starts
Jenkins:

```bash
cd deploy/jenkins
./start.sh
```

Don't run `docker compose -f jenkins-compose.yaml up` directly, since that
skips the secrets refresh and Jenkins would start with an empty
`.env.secrets` / `secrets-runtime` if they haven't been generated yet.

## How to rotate a secret (e.g. a new GitHub token, or a replaced SSH key)

1. Go to **AWS Secrets Manager → AirOS-jenkins-Uov0b7 → Retrieve secret
   value → Edit**.
2. Use the **"Plaintext"** tab, not "Key/value pairs" — the key/value UI
   uses single-line boxes that silently turn multi-line SSH keys into one
   broken line. Plaintext mode preserves line breaks correctly (as JSON
   `\n` escapes).
3. Update the field you need (see `secrets-manifest.json` for the exact
   field names) and save.
4. Re-run `./deploy/jenkins/start.sh` (or just `./security/fetch-secrets.sh`
   if you don't need to restart Jenkins itself yet — e.g. before a
   scheduled restart).

## What's intentionally NOT changed

- The GitHub **username** values (`GITHUB_USER`, `GITHUB_AIRAWAT_USER`) stay
  in `deploy/jenkins/.env` in plaintext — they're not secret, just labels.
- Other repos' own Jenkinsfiles (e.g. `dss-backend-modular`'s Jenkinsfile,
  which lives in that repo, not this one) that reference
  `/var/jenkins_home/.ssh/xxx.pem` directly were left alone — they keep
  working because that path still exists, just backed by a securely-sourced
  file instead of a permanent one.
