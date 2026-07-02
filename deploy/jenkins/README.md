# AirOS Jenkins Setup

Jenkins CI/CD server for the Airawat platform. Runs in Docker and watches multiple GitHub repos — deploys them on the same server or remote servers via SSH.

## Folder Structure

```
jenkins/
├── jenkins-compose.yaml     # Docker Compose for Jenkins + nginx
├── Dockerfile               # Custom Jenkins image with plugins + init script
├── plugins.txt              # List of Jenkins plugins to install
├── nginx.conf               # Reverse proxy config (port 90 → Jenkins 8080)
├── .env                     # Secrets (Jenkins password, GitHub token) — NEVER COMMIT
├── repos.json               # YOUR list of repos to watch — edit this often
├── jenkins.sample           # Sample Jenkinsfile template for new repos
├── casc/
│   └── jenkins.yaml         # JCasC config (admin user, credentials)
└── init.groovy.d/
    └── create-jobs-from-json.groovy  # Script that reads repos.json and creates pipeline jobs
```

## How It Works

1. **On startup**, Jenkins runs `init.groovy.d/create-jobs-from-json.groovy`
2. That script reads `repos.json` and creates one Multibranch Pipeline job per repo
3. Each job watches its GitHub repo for pushes
4. When code is pushed, Jenkins runs the repo's `Jenkinsfile` (build → test → deploy)
5. Deploy happens locally (same server) or remotely (via SSH to another server)

## Setup

### 1. Create `.env`

```bash
cp .env.example .env
nano .env
```

Fill in:
| Variable | What to put |
|---|---|
| `JENKINS_ADMIN_USER` | Jenkins login username |
| `JENKINS_ADMIN_PASSWORD` | Jenkins login password |
| `GITHUB_USER` | GitHub account or org that owns the repos |
| `GITHUB_TOKEN` | GitHub Personal Access Token (scopes: `repo`, `admin:repo_hook`) |

### 2. Edit `repos.json` — List repos to watch

Each entry supports these fields:

| Field | Required | What it does |
|---|---|---|
| `name` | yes | Jenkins job display name |
| `git_url` | yes | Repo URL. The GitHub **owner/org** and **repo name** are parsed from this, so repos in any account work |
| `branch` | no (default `main`) | The **only** branch Jenkins scans, builds, and deploys for this repo |
| `credentials_id` | no (default `github-creds`) | Which GitHub account's credential to authenticate with — must match an `id` in `casc/jenkins.yaml` |
| `deploy` | no | Informational only; actual deploy logic lives in the repo's own `Jenkinsfile` |
| `disabled` | no | Set `true` to skip creating a job for this repo |

```json
[
  {
    "name": "AirOS",
    "git_url": "https://github.com/OmKatiyarARF/AirOS.git",
    "branch": "main",
    "credentials_id": "github-creds"
  },
  {
    "name": "dss-backend",
    "git_url": "https://github.com/AirawatOrg/dss-backend.git",
    "branch": "develop",
    "credentials_id": "github-airawat"
  }
]
```

**Multiple GitHub accounts:** each distinct account/org needs its own PAT credential.
Define it once in `casc/jenkins.yaml` (with an `id`), supply its token via `.env`,
then reference that `id` from each repo's `credentials_id`. The repo above named
`dss-backend` is owned by a different account and uses `github-airawat`.

**Branch selection:** `branch` is enforced by a filter on the multibranch scan —
only that branch is discovered, so no feature branches or PRs get built, and the
deploy runs on exactly that branch.

### 3. Build and start Jenkins

```bash
cd AirOS/deploy/jenkins
docker compose -f jenkins-compose.yaml up -d --build
```

Wait 2 minutes, then open `http://13.205.13.220:90` (port 90).

### 4. Add GitHub Webhooks

For each repo, go to GitHub → Settings → Webhooks → Add webhook:
- **Payload URL**: `http://13.205.13.220:90/github-webhook/`
- **Content type**: `application/json`
- **Trigger**: `Just the push event`

### 5. Add a Jenkinsfile to each repo

Copy `jenkins.sample` into each repo as `Jenkinsfile` and customize the deploy stage.

## Adding a New Repo

1. Add the repo entry to `repos.json` with `git_url`, `branch`, and `credentials_id`
2. If the repo is owned by a **new GitHub account**: add that account's PAT to `.env`
   (e.g. `GITHUB_AIRAWAT_USER` / `GITHUB_AIRAWAT_TOKEN`) and add a matching
   `usernamePassword` block in `casc/jenkins.yaml` whose `id` equals the
   `credentials_id` you used
3. Clone the repo on the target server (if local: `cd /home/ec2-user && git clone <URL>`)
4. Add a `Jenkinsfile` to the repo root (copy `jenkins.sample`)
5. Add GitHub webhook for the repo
6. Restart Jenkins: `docker compose -f jenkins-compose.yaml restart jenkins`

## ⚠️ Deploy resets the working tree

The deploy stage runs `git reset --hard origin/<branch>` inside the repo's
checkout on the server. **Any uncommitted local changes in that checkout are
destroyed** when the pipeline runs. Never edit code directly in a server deploy
checkout (e.g. `/home/ec2-user/AirOS`) and leave it uncommitted — commit and push
first, or edit elsewhere. Consider deploying from a separate checkout than the one
you develop in.

## Removing a Repo

Delete its entry from `repos.json` and restart Jenkins. The pipeline job will be removed.

## Important Notes

- **Never commit `.env`** — it contains secrets
- **Never commit `repos.json`** if it contains internal paths
- **Add a Jenkinsfile to each repo** — Jenkins needs it to know how to build
- **Each team owns their repo's Jenkinsfile** — they decide what build/test/deploy commands to run
- **To pause CI for a repo**, add `"disabled": true` to its entry in `repos.json`