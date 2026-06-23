# AirOS Jenkins Setup

Jenkins CI/CD server for the AirOS platform. Runs in Docker on a dedicated
Jenkins server and deploys to the production server via SSH.

## Folder Structure

```
jenkins/
├── jenkins-compose.yaml    # Docker Compose for Jenkins + nginx
├── Dockerfile              # Custom Jenkins image with plugins baked in
├── plugins.txt             # List of Jenkins plugins to install
├── nginx.conf              # Reverse proxy config (port 80 → Jenkins 8080)
├── .env                    # Secrets (gitignored)
├── .env.example            # Template for .env
├── Jenkinsfile.sample      # Sample pipeline to copy to repo root
└── casc/
    └── jenkins.yaml        # JCasC config (admin user, credentials, jobs)
```

## First-Time Setup

### On the Jenkins server

1. Clone the AirOS repo:
   ```bash
   git clone https://github.com/OmKatiyarARF/AirOS.git
   cd AirOS/deploy/jenkins
   ```

2. Copy `.env.example` to `.env` and fill in real values:
   ```bash
   cp .env.example .env
   nano .env
   ```

3. Generate SSH key for deploying to production:
   ```bash
   ssh-keygen -t ed25519 -f ~/.ssh/jenkins_prod -C "jenkins@airos"
   ssh-copy-id -i ~/.ssh/jenkins_prod.pub om-katiyar@<PROD_SERVER_IP>
   ```
   Then paste the private key contents into `PROD_SSH_PRIVATE_KEY` in `.env`.

4. Build and start:
   ```bash
   docker compose -f jenkins-compose.yaml up -d --build
   ```

5. Access Jenkins at `http://<jenkins-server-ip>` (port 80, via nginx).

### Add GitHub Webhook

In your GitHub repo → Settings → Webhooks → Add webhook:
- Payload URL: `http://<jenkins-server-ip>/github-webhook/`
- Content type: `application/json`
- Trigger: `Just the push event`

### Add Jenkinsfile to your repo

Copy `Jenkinsfile.sample` to the root of the AirOS repo as `Jenkinsfile`:
```bash
cp deploy/jenkins/Jenkinsfile.sample Jenkinsfile
```
Commit and push. Jenkins will pick it up automatically.

## How it works

1. Developer pushes code to GitHub `main` branch
2. GitHub webhook hits Jenkins
3. Jenkins runs the pipeline defined in `Jenkinsfile`
4. Jenkins SSHs into production server and pulls + restarts services
5. Team can view all builds in the Jenkins UI

## Updating Jenkins config

Edit `casc/jenkins.yaml` and restart:
```bash
docker compose -f jenkins-compose.yaml restart jenkins
```

JCasC will re-apply the config from scratch — no UI clicks needed.

## Configuring Which GitHub Repo Jenkins Watches

Jenkins watches whichever GitHub repo you specify in `.env`. You can change it anytime by editing just **2 variables** in `.env`:

```bash
# Jenkins will watch this repo for pushes
GITHUB_REPO_OWNER=OmKatiyarARF       # Your GitHub username or org
GITHUB_REPO_NAME=AirOS               # The repository name
```

No need to edit any code files — just change these 2 lines in `.env` and restart:

```bash
docker compose -f jenkins-compose.yaml restart jenkins
```

### All configurable variables in `.env`

| Variable | What it controls | Where it's used |
|---|---|---|
| `JENKINS_ADMIN_USER` | Jenkins admin login username | JCasC config |
| `JENKINS_ADMIN_PASSWORD` | Jenkins admin password | JCasC config |
| `GITHUB_USER` | Your GitHub username for API auth | JCasC credentials |
| `GITHUB_TOKEN` | GitHub Personal Access Token with `repo` + `admin:repo_hook` scopes | JCasC credentials |
| `GITHUB_REPO_OWNER` | **Which GitHub account/org owns the repo to watch** | JCasC job definition |
| `GITHUB_REPO_NAME` | **Which GitHub repo to watch** | JCasC job definition |
| `PROD_SERVER_HOST` | Production server IP (only if deploying to separate server) | Pipeline env var |
| `PROD_SERVER_USER` | SSH username on production server | Pipeline env var |
| `DEPLOY_PATH` | Absolute path where code lives on this server | Pipeline script |
| `PROD_SSH_PRIVATE_KEY` | SSH private key for deployment | JCasC credentials |

## Important Notes

- **Never commit `.env`** — it contains secrets
- **JCasC overrides manual UI changes** — always edit `casc/jenkins.yaml`,
  not the UI, otherwise changes will be lost on restart
- **Production server must allow SSH from Jenkins server's IP**
