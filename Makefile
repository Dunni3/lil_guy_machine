# lil guy machine: run `make help` for the list.

SHELL := /bin/bash
.DEFAULT_GOAL := help

ANDROID_HOME ?= $(HOME)/Library/Android/sdk
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 17 2>/dev/null || echo $(HOME)/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home)
export ANDROID_HOME JAVA_HOME
ADB := $(ANDROID_HOME)/platform-tools/adb
GRADLE := ./gradlew --console=plain
APP_ID := dev.lilguy.machine

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

local.properties:
	@echo "sdk.dir=$(ANDROID_HOME)" > $@

# --- desktop / headless --------------------------------------------------------

.PHONY: desktop
desktop: ## Run the app in a desktop window
	$(GRADLE) :desktop:run

.PHONY: cli
cli: ## Run the headless CLI (ARGS="--ticks 5000")
	$(GRADLE) -q :cli:run $(if $(ARGS),--args="$(ARGS)")

.PHONY: test
test: ## Run all JVM unit tests
	$(GRADLE) test

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
tv-run: tv-install ## Install and launch on the connected device
	$(ADB) shell am start -n $(APP_ID)/dev.lilguy.android.AndroidLauncher

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

