# AirOS Jenkins Setup

Jenkins CI/CD server for the Airawat platform. Runs in Docker and watches multiple GitHub repos — deploys them on the same server or remote servers via SSH.

## Folder Structure

```
jenkins/
├── jenkins-compose.yaml     # Docker Compose for Jenkins + nginx
├── Dockerfile               # Custom Jenkins image with plugins + init script
├── plugins.txt              # List of Jenkins plugins to install
├── nginx.conf               # Reverse proxy config (port 80 → Jenkins 8080)
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

```json
[
  {
    "name": "AirOS",
    "git_url": "https://github.com/airawatiitk/AirOS.git",
    "deploy": {
      "type": "local",
      "path": "/home/ec2-user/AirOS"
    }
  },
  {
    "name": "central-auth-framework",
    "git_url": "https://github.com/airawatiitk/central-auth-framework.git",
    "deploy": {
      "type": "local",
      "path": "/home/ec2-user/central-auth-framework"
    }
  },
  {
    "name": "remote-api",
    "git_url": "https://github.com/airawatiitk/remote-api.git",
    "deploy": {
      "type": "remote",
      "host": "13.205.13.220",
      "user": "ubuntu",
      "path": "/home/ubuntu/remote-api",
      "ssh_key": {"type": "file", "path": "/var/jenkins_home/.ssh/key.pem"}
    }
  }
]
```

### 3. Build and start Jenkins

```bash
cd AirOS/deploy/jenkins
docker compose -f jenkins-compose.yaml up -d --build
```

Wait 2 minutes, then open `http://13.205.13.220` (port 80).

### 4. Add GitHub Webhooks

For each repo, go to GitHub → Settings → Webhooks → Add webhook:
- **Payload URL**: `http://13.205.13.220/github-webhook/`
- **Content type**: `application/json`
- **Trigger**: `Just the push event`

### 5. Add a Jenkinsfile to each repo

Copy `jenkins.sample` into each repo as `Jenkinsfile` and customize the deploy stage.

## Adding a New Repo

1. Add the repo entry to `repos.json`
2. Clone the repo on the target server (if local: `cd /home/ec2-user && git clone <URL>`)
3. Add a `Jenkinsfile` to the repo root
4. Add GitHub webhook for the repo
5. Restart Jenkins: `docker compose -f jenkins-compose.yaml restart jenkins`

## Removing a Repo

Delete its entry from `repos.json` and restart Jenkins. The pipeline job will be removed.

## Important Notes

- **Never commit `.env`** — it contains secrets
- **Never commit `repos.json`** if it contains internal paths
- **Add a Jenkinsfile to each repo** — Jenkins needs it to know how to build
- **Each team owns their repo's Jenkinsfile** — they decide what build/test/deploy commands to run
- **To pause CI for a repo**, add `"disabled": true` to its entry in `repos.json`