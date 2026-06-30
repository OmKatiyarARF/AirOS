# AirOS Platform — Makefile
# Start, stop, backup, and manage the AirOS infrastructure platform
# =============================================================================

.PHONY: help up down stop restart logs logs-keycloak logs-jenkins backup build clean status

# ──── Colors ────────────────────────────────────────────────────────────────
RED    := $(shell tput -Txterm setaf 1)
GREEN  := $(shell tput -Txterm setaf 2)
YELLOW := $(shell tput -Txterm setaf 3)
RESET  := $(shell tput -Txterm sgr0)

# ──── Paths ─────────────────────────────────────────────────────────────────
DEPLOY           := deploy
KEYCLOAK         := deploy/keycloak.yaml
BACKUP_DIR       := ./backups
BACKUP_DATE      := $(shell date +%Y%m%d_%H%M%S)

# ──── Jenkins Configuration (override for local dev vs production) ─────────
# By default, these point to the production EC2 paths.
# For local development where /home/ec2-user doesn't exist, override:
#   make jenkins-up JENKINS_REPOS_VOLUME=/home/om-katiyar/arf JENKINS_SSH_VOLUME=/home/om-katiyar/.ssh
JENKINS_REPOS_VOLUME ?= /home/ec2-user
JENKINS_SSH_VOLUME   ?= /home/ec2-user/.ssh

# ──── Help (default) ────────────────────────────────────────────────────────
help:
	@echo "$(GREEN)AirOS Platform — Quick Commands$(RESET)"
	@echo ""
	@echo "$(YELLOW)make up$(RESET)              - Start Keycloak platform services"
	@echo "$(YELLOW)make down$(RESET)            - Stop Keycloak platform services"
	@echo "$(YELLOW)make restart$(RESET)         - Restart Keycloak platform services"
	@echo "$(YELLOW)make logs$(RESET)            - Tail all Keycloak + Postgres logs"
	@echo "$(YELLOW)make logs-keycloak$(RESET)   - Tail Keycloak container logs only"
	@echo "$(YELLOW)make logs-postgres$(RESET)   - Tail PostgreSQL container logs only"
	@echo "$(YELLOW)make backup$(RESET)          - Backup Keycloak PostgreSQL database"
	@echo "$(YELLOW)make status$(RESET)          - Show running platform containers and health"
	@echo "$(YELLOW)make clean$(RESET)           - Stop and remove Keycloak volumes (DESTROYS DATA)"
	@echo ""
	@echo "$(GREEN)Jenkins Commands$(RESET)"
	@echo "$(YELLOW)make jenkins-up$(RESET)      - Start Jenkins CI/CD"
	@echo "$(YELLOW)make jenkins-down$(RESET)    - Stop Jenkins CI/CD"
	@echo "$(YELLOW)make jenkins-logs$(RESET)    - Tail Jenkins logs"
	@echo "$(YELLOW)make jenkins-restart$(RESET)   - Restart Jenkins"

# ──── Core Platform ─────────────────────────────────────────────────────────

up:
	@echo "$(GREEN)Starting AirOS Keycloak Platform...$(RESET)"
	docker compose -f $(KEYCLOAK) up -d
	@echo ""
	@echo "$(GREEN)Platform started successfully!$(RESET)"
	@echo "  🗝️   Keycloak:     http://localhost:9081"
	@echo ""
	@echo "$(YELLOW)First-time setup:$(RESET)"
	@echo "  - Visit http://localhost:9081 to access Keycloak admin console"

down:
	@echo "$(RED)Stopping AirOS Keycloak Platform...$(RESET)"
	docker compose -f $(KEYCLOAK) down

stop: down

restart: down up

# ──── Logs ──────────────────────────────────────────────────────────────────

logs:
	docker compose -f $(KEYCLOAK) logs -f

logs-keycloak:
	@echo "$(YELLOW)Keycloak logs:$(RESET)"
	docker logs -f airos-keycloak

logs-postgres:
	@echo "$(YELLOW)PostgreSQL logs:$(RESET)"
	docker logs -f airos-keycloak-postgres

# ──── Jenkins ──────────────────────────────────────────────────────────────

jenkins-up:
	@echo "$(GREEN)Starting Jenkins CI/CD...$(RESET)"
	@export REPOS_VOLUME="$(JENKINS_REPOS_VOLUME)" SSH_VOLUME="$(JENKINS_SSH_VOLUME)"; \
	 cd deploy/jenkins && docker compose -f jenkins-compose.yaml up -d --build
	@echo "$(GREEN)Jenkins started at http://localhost:90$(RESET)"
	@echo "$(YELLOW)Note: Jenkins UI is also exposed directly on port 9080$(RESET)"

jenkins-down:
	@echo "$(RED)Stopping Jenkins...$(RESET)"
	@export REPOS_VOLUME="$(JENKINS_REPOS_VOLUME)" SSH_VOLUME="$(JENKINS_SSH_VOLUME)"; \
	 cd deploy/jenkins && docker compose -f jenkins-compose.yaml down

jenkins-logs:
	@export REPOS_VOLUME="$(JENKINS_REPOS_VOLUME)" SSH_VOLUME="$(JENKINS_SSH_VOLUME)"; \
	 cd deploy/jenkins && docker compose -f jenkins-compose.yaml logs -f

jenkins-restart: jenkins-down jenkins-up

# ──── Backup ─────────────────────────────────────────────────────────────────

backup:
	@mkdir -p $(BACKUP_DIR)
	@echo "$(YELLOW)Backing up Keycloak database...$(RESET)"
	@docker exec airos-keycloak-postgres pg_dump -U keycloak keycloak 2>/dev/null | gzip > $(BACKUP_DIR)/keycloak_$(BACKUP_DATE).sql.gz
	@ls -lh $(BACKUP_DIR)/keycloak_$(BACKUP_DATE).sql.gz && echo "$(GREEN)Backup completed.$(RESET)"

restore-list:
	@echo "$(YELLOW)Available backups:$(RESET)"
	@ls -1t $(BACKUP_DIR)/*.sql.gz 2>/dev/null || echo "No backups found."

# ──── Status & Utilities ─────────────────────────────────────────────────────

status:
	@echo "$(GREEN)AirOS Platform Status$(RESET)"
	@echo "========================"
	@docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" --filter "name=airos-"

keycloak-health:
	@echo "$(YELLOW)Checking Keycloak health...$(RESET)"
	@curl -s http://localhost:9081/health/ready 2>/dev/null || echo "$(RED)Keycloak is not ready at http://localhost:9081$(RESET)"

clean:
	@echo "$(RED)WARNING: This will remove ALL Keycloak data and volumes!$(RESET)"
	@echo "$(RED)All user accounts, realms, and settings will be lost.$(RESET)"
	@read -p "Are you sure? Type 'yes' to confirm: " confirm && [ "$$confirm" = "yes" ] || exit 1
	docker compose -f $(KEYCLOAK) down -v
	@echo "$(GREEN)Keycloak data cleaned.$(RESET)"
