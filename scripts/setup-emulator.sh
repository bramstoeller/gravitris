#!/usr/bin/env bash
# Installs the pinned emulator + system image and creates a single AVD for
# correctness testing (docs/operations.md "Emulator — correctness testing
# only"). Idempotent: safe to re-run, skips what's already there.
#
# This is deliberately separate from scripts/setup-android-sdk.sh (which
# `make setup` always runs): the emulator + system image are a large
# download (~1.5GB) that most invocations of `make setup`/`make test`/
# `make build` do not need. Only `make screenshot` / `make emulator` pull
# this in, and both do so idempotently before use.
#
# x86_64, not arm64: the client's phone is arm64-v8a, but :core-sim is pure
# Kotlin/JVM with no NDK (verified — see docs/operations.md) and :app has no
# native code either, so nothing here is ABI-sensitive. x86_64 runs under
# this container's /dev/kvm at native speed; an arm64 system image on an
# x86_64 host gets no KVM acceleration at all (it would run under much
# slower dynamic binary translation) for zero correctness benefit. If the
# app ever gains NDK/JNI code this choice must be revisited.
#
# API 36 (Android 16), not minSdk 29: the client's own phone runs Android
# 16. minSdk exists to define the floor we still support, not the version
# we test rendering against — an API-29 image would not catch a bug that
# only exists on the client's actual OS version.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
AVD_NAME="${AVD_NAME:-gravitris_correctness}"
SYSTEM_IMAGE_PACKAGE="system-images;android-36;google_apis;x86_64"
DEVICE_PROFILE="pixel_6"

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

if [ ! -x "$SDKMANAGER" ]; then
  echo "==> Base SDK not found — running scripts/setup-android-sdk.sh first" >&2
  bash "$(dirname "${BASH_SOURCE[0]}")/setup-android-sdk.sh"
fi

echo "==> Installing pinned packages: emulator $SYSTEM_IMAGE_PACKAGE"
set +o pipefail
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses > /dev/null
set -o pipefail
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" "emulator" "$SYSTEM_IMAGE_PACKAGE" > /dev/null

if [ -d "$HOME/.android/avd/${AVD_NAME}.avd" ]; then
  echo "==> AVD '$AVD_NAME' already exists — leaving it alone"
else
  echo "==> Creating AVD '$AVD_NAME' ($DEVICE_PROFILE, $SYSTEM_IMAGE_PACKAGE)"
  echo "no" | "$AVDMANAGER" create avd \
    --name "$AVD_NAME" \
    --package "$SYSTEM_IMAGE_PACKAGE" \
    --device "$DEVICE_PROFILE" \
    --sdcard 512M
fi

echo "==> Done. Emulator: $ANDROID_HOME/emulator/emulator -avd $AVD_NAME"
