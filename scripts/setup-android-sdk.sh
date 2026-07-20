#!/usr/bin/env bash
# Installs the exact, pinned Android SDK components this build needs, into
# $ANDROID_HOME (default: $HOME/android-sdk — under /state, alongside the
# Gradle cache; never under /work, per the team's mount contract).
#
# This is what makes "the toolchain is declarative" (docs/security/
# dependency-policy.md R4) true for the *Android* half of the build, not just
# Gradle/Kotlin/AGP: a fresh container has no SDK at all, and `make setup`
# must produce the same one every time rather than whatever a developer
# happened to click through in Android Studio.
#
# Every version below is exact and matches gradle/libs.versions.toml
# (compileSdk/targetSdk 36, ADR 0010). Bumping any of them is a reviewable
# diff to this file, same as a version-catalog change.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="22.0"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip"
CMDLINE_TOOLS_SHA1="040d3996a65543d22ec4bf73e4c37aa37a8d4af4"

PLATFORM_PACKAGE="platforms;android-36"
BUILD_TOOLS_PACKAGE="build-tools;36.0.0"
PLATFORM_TOOLS_PACKAGE="platform-tools"

echo "==> Installing Android SDK into $ANDROID_HOME"
mkdir -p "$ANDROID_HOME"

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> Fetching command-line tools $CMDLINE_TOOLS_VERSION"
  tmpzip="$(mktemp -d)/cmdline-tools.zip"
  curl -fL --retry 3 -o "$tmpzip" "$CMDLINE_TOOLS_URL"
  echo "$CMDLINE_TOOLS_SHA1  $tmpzip" | sha1sum -c -

  tmpdir="$(mktemp -d)"
  unzip -q "$tmpzip" -d "$tmpdir"
  # The zip extracts to a top-level "cmdline-tools" dir; sdkmanager expects
  # to live under <sdk>/cmdline-tools/<some-name>/bin, never directly under
  # cmdline-tools/ itself, or it refuses to run.
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmpdir/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmpdir" "$(dirname "$tmpzip")"
fi

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

echo "==> Accepting SDK licenses (non-interactive, pinned license text bundled with cmdline-tools $CMDLINE_TOOLS_VERSION)"
# `yes` is killed by SIGPIPE once sdkmanager stops reading (normal, expected)
# and that would otherwise trip `set -o pipefail`. Check sdkmanager's own
# exit status via PIPESTATUS rather than the pipeline's.
set +o pipefail
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses > /dev/null
sdkmanager_status="${PIPESTATUS[1]}"
set -o pipefail
if [ "$sdkmanager_status" -ne 0 ]; then
  echo "sdkmanager --licenses failed with exit code $sdkmanager_status" >&2
  exit "$sdkmanager_status"
fi

echo "==> Installing pinned packages: $PLATFORM_TOOLS_PACKAGE $PLATFORM_PACKAGE $BUILD_TOOLS_PACKAGE"
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
  "$PLATFORM_TOOLS_PACKAGE" "$PLATFORM_PACKAGE" "$BUILD_TOOLS_PACKAGE" > /dev/null

echo "==> Done. Export ANDROID_HOME=$ANDROID_HOME (see .env.example) for gradlew to find it."
