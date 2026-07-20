# Gravitris — task runner. `make help` lists every target.
#
# One command runs the product (`make dev`), one command runs the tests
# (`make test`). Everything else here exists to keep those two honest.
#
# Toolchain: JDK is provisioned by Gradle itself (see settings.gradle.kts's
# foojay-resolver-convention plugin — it downloads JDK 21 if one isn't
# already on the machine). The Android SDK is not something Gradle can
# provision on its own, so `make setup` installs it, pinned, via
# scripts/setup-android-sdk.sh.

SHELL := /bin/bash
ANDROID_HOME ?= $(HOME)/android-sdk
export ANDROID_HOME
# Some machines (and the GitHub-hosted CI runner image) already export
# ANDROID_SDK_ROOT for a different, pre-installed SDK. AGP refuses to guess
# which one you meant when the two disagree, so pin it to the one we just
# pinned rather than leaving it to whatever the environment happens to have.
export ANDROID_SDK_ROOT := $(ANDROID_HOME)

.DEFAULT_GOAL := help

.PHONY: help setup dev test lint build clean apk doctor emulator-setup emulator screenshot playthrough

help: ## Show this list
	@echo "Gravitris — Stage 0 build scaffold. Targets:"
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*##/ { printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@echo
	@echo "ANDROID_HOME is currently: $(ANDROID_HOME)"

setup: ## Install the pinned Android SDK components (idempotent). Run this once per machine.
	@bash scripts/setup-android-sdk.sh

doctor: ## Print toolchain versions actually in use, and fail if ANDROID_HOME is missing
	@echo "java:"; java -version
	@if [ ! -d "$(ANDROID_HOME)" ]; then \
		echo "ANDROID_HOME ($(ANDROID_HOME)) does not exist — run 'make setup' first." >&2; \
		exit 1; \
	fi
	@echo "ANDROID_HOME: $(ANDROID_HOME)"
	@./gradlew --version

dev: ## Build the debug APK; install and launch it if a device is attached over adb, else print sideload instructions
	@./gradlew :app:assembleDebug
	@apk="app/build/outputs/apk/debug/app-debug.apk"; \
	if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then \
		echo "==> Device found over adb — installing and launching"; \
		adb install -r "$$apk"; \
		adb shell am start -n nl.brainbuilders.gravitris/gravitris.app.MainActivity; \
	else \
		echo "==> No adb device attached. The APK is built at: $$apk"; \
		echo "    See docs/operations.md 'Installing on a phone' for plain-language sideload steps,"; \
		echo "    or 'make screenshot' for a correctness-only emulator run (read that section first —"; \
		echo "    it is currently unreliable in this container; see docs/operations.md)."; \
	fi

test: ## Run the full verification suite: JVM tests, the buildSrc guard-check tests, the ADR 0008 no-Android check, the CHK-1/3/4 merged-manifest checks, and lint
	# :buildSrc:check is listed explicitly, not folded into plain `check`:
	# buildSrc is its own separate build, and the root project's `check`
	# builds buildSrc's *classes* (needed for the plugin classpath) but never
	# runs buildSrc's own tests — dropping this would silently stop running
	# the CheckMergedManifest/CheckNoAndroidDependency tests with `make test`
	# still reporting green.
	@./gradlew check :buildSrc:check

lint: ## Run Android Lint on :app
	@./gradlew :app:lint

build: ## Produce the debug APK (also exercises the release variant's build+checks, unsigned)
	@./gradlew :app:assembleDebug :app:assembleRelease

apk: build ## Alias for build; prints where the APK landed
	@echo "Debug APK: app/build/outputs/apk/debug/app-debug.apk"

clean: ## Remove all build output
	@./gradlew clean

emulator-setup: ## Install the pinned emulator + system image and create the correctness AVD (idempotent, ~1.5GB download)
	@bash scripts/setup-emulator.sh

emulator: emulator-setup ## Launch the correctness AVD interactively (window, for poking at it by hand — not for scripts)
	@ANDROID_SDK_ROOT="$(ANDROID_HOME)" "$(ANDROID_HOME)/emulator/emulator" -avd gravitris_correctness

screenshot: ## Build the debug APK, boot the emulator, install, launch, and save a screenshot. Correctness only — see docs/operations.md before reading anything into how it performed.
	@bash scripts/emulator-screenshot.sh

playthrough: ## Build+boot+install, then drive real adb input over a scripted session and screenshot each moment. Proves the mechanic RUNS and RENDERS — correctness only, never a performance or appearance claim.
	@bash scripts/emulator-playthrough.sh
