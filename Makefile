# lil guy machine: run `make help` for the list.

SHELL := /bin/bash
.DEFAULT_GOAL := help

ANDROID_HOME ?= $(HOME)/Library/Android/sdk
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 17 2>/dev/null || echo $(HOME)/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home)
export ANDROID_HOME JAVA_HOME
ADB := $(ANDROID_HOME)/platform-tools/adb

# API keys live outside the repo, in one file every worktree sees: `export NAME=value` lines
# (TYPESAFE_API_KEY and the like). Make exports them to the CLI, the desktop app and
# tv-push-config. Never commit keys or echo them here.
SECRETS_ENV ?= $(HOME)/.config/lil-guy-machine/secrets.env
-include $(SECRETS_ENV)
GRADLE := ./gradlew --console=plain
APP_ID := dev.lilguy.machine

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

local.properties:
	@echo "sdk.dir=$(ANDROID_HOME)" > $@

# --- desktop / headless --------------------------------------------------------

.PHONY: desktop
desktop: ## Run the app in a desktop window (ENDPOINT=<name>, MEMORY_ENDPOINT=<name|none>, WRITER_ENDPOINT=<name|none>, RECALL_ENDPOINT=<name|none>, SCENARIO=key-door, MINUTE_TICKS=<n>, DAYS=<n, 0 no end>, BEAM=<n, 0 flat>, RESUME=<run id|latest>)
	LILGUY_RESUME=$(RESUME) LILGUY_ENDPOINT=$(ENDPOINT) LILGUY_MEMORY_ENDPOINT=$(MEMORY_ENDPOINT) LILGUY_WRITER_ENDPOINT=$(or $(WRITER_ENDPOINT),$(JOURNAL_ENDPOINT)) LILGUY_RECALL_ENDPOINT=$(RECALL_ENDPOINT) LILGUY_SCENARIO=$(SCENARIO) LILGUY_MINUTE_TICKS=$(MINUTE_TICKS) LILGUY_DAYS=$(DAYS) LILGUY_BEAM=$(BEAM) $(GRADLE) :desktop:run

.PHONY: cli
cli: ## Run the headless CLI (ARGS="--endpoint hosted --runs 10"; ARGS=--help for all)
	$(GRADLE) -q :cli:run $(if $(ARGS),--args="$(ARGS)")

.PHONY: content-docs
content-docs: ## Regenerate the bedroom content's JSON Schemas and build/grammar-reference.html
	$(GRADLE) -q :cli:run --args="--content-docs"

.PHONY: test
test: ## Run all JVM unit tests
	$(GRADLE) test

# --- sim host ------------------------------------------------------------------
# The headless host runs a life in real time and streams its state over WebSocket
# (http://127.0.0.1:8790/ is its debug page). Commands need LILGUY_ADMIN_PASSWORD, from the
# secrets file above; without one every command is refused. Never commit or print it.

NODE_IMAGE := node:22-alpine

.PHONY: host
host: ## Run the sim host (ENDPOINT=<name>, else the scripted policy; PAINTER=<s> paints with random picks; START=0 waits for a start command; PORT=8790; DAYS=<n>; RESUME=<run id|latest>)
	LILGUY_ENDPOINT=$(ENDPOINT) LILGUY_MEMORY_ENDPOINT=$(MEMORY_ENDPOINT) LILGUY_WRITER_ENDPOINT=$(WRITER_ENDPOINT) LILGUY_RECALL_ENDPOINT=$(RECALL_ENDPOINT) LILGUY_PAINTER=$(PAINTER) LILGUY_AUTOSTART=$(or $(START),1) LILGUY_HOST_PORT=$(PORT) LILGUY_DAYS=$(DAYS) LILGUY_RESUME=$(RESUME) $(GRADLE) -q :host:run

.PHONY: protocol-types
protocol-types: ## Regenerate the protocol's JSON Schema from its Kotlin types, and the TypeScript types from it
	$(GRADLE) -q :protocol:writeProtocolSchema
	docker run --rm -v $(CURDIR)/protocol:/p -w /p $(NODE_IMAGE) sh gen_types.sh

.PHONY: protocol-check
protocol-check: ## Fail when protocol/ts/protocol.ts isn't what the generator makes from the current schema (make test checks the hash only)
	$(GRADLE) -q :protocol:checkProtocolTypes
	docker run --rm -v $(CURDIR)/protocol:/p -w /p $(NODE_IMAGE) sh -c 'sh gen_types.sh /tmp/protocol.ts && cmp /tmp/protocol.ts ts/protocol.ts || { echo "protocol/ts/protocol.ts is stale: run make protocol-types"; exit 1; }'

# --- run database --------------------------------------------------------------
# Every run records into runs/lilguy.db and syncs to the server when LILGUY_SYNC_URL and
# LILGUY_SYNC_TOKEN are set (put them in the secrets file above). The server is server/:
# Postgres plus the FastAPI ingest service. See server/README.md.

DEV_COMPOSE := POSTGRES_PASSWORD=dev LILGUY_INGEST_TOKENS=dev:dev DOMAIN=localhost HOST_DOMAIN=localhost ACME_EMAIL=dev@localhost docker compose -f server/docker-compose.yml -f server/docker-compose.dev.yml

.PHONY: server-schema
server-schema: ## Regenerate the rows' JSON Schema from runlog's row classes, and the server's Pydantic models from it
	$(GRADLE) -q :cli:run --args="--server-schema server/schema/rows.schema.json"
	$(DEV_COMPOSE) run --rm --build --no-deps -v $(CURDIR)/server:/srv api sh gen_models.sh

.PHONY: server-up
server-up: ## Start Postgres and the ingest service locally (http://localhost:8787; token "dev")
	$(DEV_COMPOSE) up -d --build db api

.PHONY: server-down
server-down: ## Stop the local server (the data volume stays)
	$(DEV_COMPOSE) down

.PHONY: server-test
server-test: ## Run the server's tests (ingest is idempotent) against the local Postgres
	$(DEV_COMPOSE) up -d db
	$(DEV_COMPOSE) run --rm --build api pytest -q tests

.PHONY: server-psql
server-psql: ## Open psql on the local server's database
	$(DEV_COMPOSE) exec db psql -U lilguy lilguy

.PHONY: server-deploy
server-deploy: ## Build the sim host, sync server/ to the Lightsail box and restart it (DEPLOY_HOST=ubuntu@<host>)
	server/deploy/deploy.sh

.PHONY: server-tunnel
server-tunnel: ## Forward the box's Postgres to localhost:5434 for psql, pandas or DuckDB (DEPLOY_HOST=ubuntu@<host>)
	@: "$${DEPLOY_HOST:?set DEPLOY_HOST=ubuntu@<host>}"
	ssh -N -L 5434:127.0.0.1:5432 $(DEPLOY_HOST)

.PHONY: import-runs
import-runs: ## Read the old run logs under runs/ (and runs/tv/) into runs/lilguy.db, then sync
	$(GRADLE) -q :cli:run --args="--import-runs runs"

.PHONY: sync
sync: ## Send what runs/lilguy.db hasn't synced to the server
	$(GRADLE) -q :cli:run --args="--sync"

# --- tv ------------------------------------------------------------------------

.PHONY: tv-connect
tv-connect: ## Connect adb to the TV over Wi-Fi: make tv-connect IP=10.0.0.51
	@: "$${IP:?set IP=<tv ip>}"
	tools/adb/connect.sh $(IP)

.PHONY: tv-build
tv-build: local.properties ## Build the debug APK
	$(GRADLE) :android:assembleDebug

.PHONY: tv-install
tv-install: local.properties ## Build and install the debug APK on the connected device
	$(GRADLE) :android:installDebug

.PHONY: tv-run
tv-run: tv-install ## Install and launch on the connected device (ENDPOINT=<name>, MEMORY_ENDPOINT=<name|none>, WRITER_ENDPOINT=<name|none>, RECALL_ENDPOINT=<name|none>, SCENARIO=key-door, MINUTE_TICKS=<n>, DAYS=<n>, BEAM=<n, 0 flat>, RESUME=<run id|latest>)
	$(ADB) shell am start -S -n $(APP_ID)/dev.lilguy.android.AndroidLauncher $(if $(ENDPOINT),--es endpoint $(ENDPOINT)) $(if $(MEMORY_ENDPOINT),--es memory_endpoint $(MEMORY_ENDPOINT)) $(if $(or $(WRITER_ENDPOINT),$(JOURNAL_ENDPOINT)),--es writer_endpoint $(or $(WRITER_ENDPOINT),$(JOURNAL_ENDPOINT))) $(if $(RECALL_ENDPOINT),--es recall_endpoint $(RECALL_ENDPOINT)) $(if $(SCENARIO),--es scenario $(SCENARIO)) $(if $(MINUTE_TICKS),--ei minute_ticks $(MINUTE_TICKS)) $(if $(DAYS),--ei days $(DAYS)) $(if $(BEAM),--ei beam $(BEAM)) $(if $(RESUME),--es resume $(RESUME))

.PHONY: tv-push-config
tv-push-config: ## Push config/endpoints.yaml and its API keys to the device (dev only)
	tools/adb/push-config.sh

.PHONY: tv-clear-config
tv-clear-config: ## Delete the pushed config and API keys from the device
	tools/adb/push-config.sh --clear

.PHONY: tv-pull-logs
tv-pull-logs: ## Copy the app's run logs into runs/tv/ without overwriting earlier pulls (see tools/adb/pull-runs.sh)
	tools/adb/pull-runs.sh

.PHONY: tv-profile
tv-profile: local.properties ## Install and launch a release-like build (for measuring performance)
	$(GRADLE) :android:installProfile
	$(ADB) shell am start -n $(APP_ID)/dev.lilguy.android.AndroidLauncher

.PHONY: tv-stop
tv-stop: ## Force-stop the app on the device
	$(ADB) shell am force-stop $(APP_ID)

.PHONY: tv-logs
tv-logs: ## Tail the app's logs
	$(ADB) logcat -s LilGuy AndroidRuntime:E

