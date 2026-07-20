#!/usr/bin/env bash
# Boots the correctness AVD, installs the debug APK, waits for the first
# frame, and saves a screenshot. This is the whole point of having an
# emulator at all (docs/operations.md "Emulator — correctness testing
# only"): does it launch, does it render, does it look right — never a
# performance measurement (see the banner this script prints, and
# docs/operations.md for why that rule is not negotiable).
#
# Runs the full lifecycle — boot, install, screenshot, shutdown — in one
# script invocation rather than backgrounding the emulator across separate
# commands, deliberately: an orphaned emulator process left behind by a
# half-finished script is worse than a script that takes three minutes.
set -uo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

AVD_NAME="${AVD_NAME:-gravitris_correctness}"
OUT_DIR="${OUT_DIR:-build/emulator}"
SCREENSHOT="$OUT_DIR/screenshot.png"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-180}"   # seconds
PACKAGE="nl.brainbuilders.gravitris"
ACTIVITY="$PACKAGE/gravitris.app.MainActivity"
APK="app/build/outputs/apk/debug/app-debug.apk"

EMULATOR_BIN="$ANDROID_HOME/emulator/emulator"
EMULATOR_STDOUT_LOG="$OUT_DIR/emulator.stdout.log"
EMULATOR_PID=""
SERIAL="emulator-5554"

# Deliberately not `pkill` — matching and signalling processes by name
# scan (rather than a PID this script itself started) is a broader
# operation than this script needs and is best avoided in a sandboxed
# container.
kill_stale_emulator() {
  local avd="$1"
  local pid
  for pid in $(ps -eo pid=,args= | grep -F "qemu-system" | grep -F -- "-avd $avd" | awk '{print $1}'); do
    kill -9 "$pid" 2>/dev/null || true
  done
}

cleanup() {
  if [ -n "$EMULATOR_PID" ] && kill -0 "$EMULATOR_PID" 2>/dev/null; then
    echo "==> Shutting down emulator (pid $EMULATOR_PID)"
    adb -s "$SERIAL" emu kill >/dev/null 2>&1 || kill "$EMULATOR_PID" 2>/dev/null
    for _ in $(seq 1 10); do
      kill -0 "$EMULATOR_PID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$EMULATOR_PID" 2>/dev/null || true
  fi
  rm -rf "$HOME/.android/avd/${AVD_NAME}.avd/running" "$HOME/.android/avd/running" 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$OUT_DIR"

if [ ! -d "$HOME/.android/avd/${AVD_NAME}.avd" ]; then
  echo "==> AVD '$AVD_NAME' not found — running scripts/setup-emulator.sh"
  bash "$(dirname "${BASH_SOURCE[0]}")/setup-emulator.sh"
fi

if [ ! -f "$APK" ]; then
  echo "==> Debug APK not found — building it"
  ./gradlew :app:assembleDebug
fi

# Kill anything left running from a previous, interrupted invocation — a
# stale qemu process holds the AVD's lock and every boot after it fails
# with "another emulator instance is running".
kill_stale_emulator "$AVD_NAME"
rm -rf "$HOME/.android/avd/${AVD_NAME}.avd/running" "$HOME/.android/avd/running" 2>/dev/null || true
sleep 1

# QT_QPA_PLATFORM=offscreen: without a real display, the emulator's Qt UI
# layer (still initialized even in -no-window mode) segfaults a few seconds
# into boot in this container, GPU mode notwithstanding — reproduced with
# strace, see handoff. This is the fix, not a workaround for something else.
export QT_QPA_PLATFORM=offscreen

boot_emulator() {
  local gpu_mode="$1"
  echo "==> Booting emulator ($AVD_NAME, -gpu $gpu_mode)"
  rm -f "$EMULATOR_STDOUT_LOG"
  "$EMULATOR_BIN" -avd "$AVD_NAME" \
    -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu "$gpu_mode" \
    > "$EMULATOR_STDOUT_LOG" 2>&1 &
  EMULATOR_PID=$!

  local waited=0
  local died=0
  while [ "$waited" -lt "$BOOT_TIMEOUT" ]; do
    if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
      died=1
      break
    fi
    if grep -q "cannot be used for hardware rendering\|Could not start renderer" "$EMULATOR_STDOUT_LOG" 2>/dev/null; then
      echo "==> Host declined hardware GL for -gpu $gpu_mode — not waiting for a boot that already failed"
      died=1
      break
    fi
    boot="$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null || true)"
    if [ "$boot" = "1" ]; then
      return 0
    fi
    sleep 3
    waited=$((waited + 3))
  done

  if [ "$died" = "1" ]; then
    echo "==> Emulator process ended before boot completed (-gpu $gpu_mode)" >&2
  else
    echo "==> Timed out after ${BOOT_TIMEOUT}s waiting for boot (-gpu $gpu_mode)" >&2
  fi
  kill -9 "$EMULATOR_PID" 2>/dev/null || true
  wait "$EMULATOR_PID" 2>/dev/null || true
  EMULATOR_PID=""
  kill_stale_emulator "$AVD_NAME"
  rm -rf "$HOME/.android/avd/${AVD_NAME}.avd/running" "$HOME/.android/avd/running" 2>/dev/null || true
  # Give the crash handler and any lingering child processes (crashpad,
  # netsim, the gRPC server) a moment to fully release the AVD lock and
  # ports before the next attempt starts — starting immediately after a
  # kill -9 was observed to make the *next* boot crash too, not just fail
  # cleanly.
  sleep 3
  return 1
}

# Bounded retries, kept as defense-in-depth rather than the primary fix.
# The primary fix is the GPU mode choice below — see the long comment
# there. root-caused with gdb: `-gpu swiftshader_indirect` (the emulator's
# default software mode, and this AVD's original setting) SIGSEGVs inside
# libGLESv2.so's SwiftShader/Subzero JIT on a majority of boots in this
# container; `-gpu swangle` (ANGLE-mediated) does not, and hasn't in 9/9
# clean boots while chasing this down. If a third mode starts flaking too,
# retries buy a little slack, not silent tolerance of routine failure.
MAX_ATTEMPTS_PER_MODE=2

boot_with_retries() {
  local gpu_mode="$1"
  local n
  for n in $(seq 1 "$MAX_ATTEMPTS_PER_MODE"); do
    if boot_emulator "$gpu_mode"; then
      return 0
    fi
    echo "==> -gpu $gpu_mode attempt $n/$MAX_ATTEMPTS_PER_MODE failed" >&2
  done
  return 1
}

# GPU mode: try -gpu host first (real hardware GL, if this container ever
# genuinely has it — it does not today, see docs/operations.md), then fall
# back to -gpu swangle, NOT the emulator's own default -gpu
# swiftshader_indirect / auto. This is not an arbitrary alternative:
# swiftshader_indirect calls into SwiftShader's GLESv2 implementation
# directly, whose Subzero JIT SIGSEGVs (confirmed with gdb — a JIT-compiled
# shader routine's own generated code faults with SEGV_ACCERR) on a
# majority of boots in this container. swangle routes the same GLES calls
# through ANGLE on top of SwiftShader's Vulkan backend instead of directly
# through SwiftShader's GLESv2 JIT path, and has not reproduced the crash
# once in 9 clean boots while diagnosing this (handoff has the full
# evidence trail). Both are still software (SEE THE GPU_STATUS BANNER
# BELOW) — this is a stability fix, not a hardware-acceleration one.
GPU_REQUESTED="host"
if ! boot_with_retries "host"; then
  echo "==> Falling back to software rendering (ANGLE/SwiftShader via -gpu swangle) — see docs/operations.md" >&2
  GPU_REQUESTED="swangle"
  if ! boot_with_retries "swangle"; then
    echo "FAIL: emulator did not boot under either -gpu host or -gpu swangle after $MAX_ATTEMPTS_PER_MODE attempts each" >&2
    exit 1
  fi
fi

echo "==> Boot completed"

RENDERER_LINE="$(grep -m1 "Graphics Adapter " "$EMULATOR_STDOUT_LOG" 2>/dev/null || true)"
API_LINE="$(grep -m1 "Graphics API Version " "$EMULATOR_STDOUT_LOG" 2>/dev/null || true)"
# Keyed off which -gpu mode actually booted, not off sniffing the renderer
# string for "swiftshader" — swangle's own renderer line contains the word
# "SwiftShader" (true, it's ANGLE-over-SwiftShader), but so would any other
# software fallback added later that might not happen to mention it. This
# is the one place a future silent-fallback regression would show up, so it
# does not get to depend on string-matching being kept in sync by hand.
if [ "$GPU_REQUESTED" = "host" ]; then
  GPU_STATUS="hardware GL"
else
  GPU_STATUS="SOFTWARE ($GPU_REQUESTED) — not hardware GL"
fi

echo "==> Installing debug APK"
adb -s "$SERIAL" wait-for-device
adb -s "$SERIAL" install -r "$APK" >/dev/null

echo "==> Launching $ACTIVITY"
adb -s "$SERIAL" shell am start -W -n "$ACTIVITY" >/dev/null

# "Waits for first frame": am start -W blocks until the activity reports
# drawn, but the very first GL frame can still land a beat after that for a
# GLSurfaceView. A short, fixed settle is simpler and more honest than
# guessing from logcat for a marker this project doesn't emit.
sleep 2

echo "==> Capturing screenshot"
adb -s "$SERIAL" exec-out screencap -p > "$SCREENSHOT"

if [ ! -s "$SCREENSHOT" ]; then
  echo "FAIL: screenshot capture produced an empty file" >&2
  exit 1
fi

cat <<EOF

================================================================================
Screenshot: $SCREENSHOT
Renderer:   ${RENDERER_LINE:-unknown}
API:        ${API_LINE:-unknown}
GPU status: $GPU_STATUS (requested: -gpu $GPU_REQUESTED)

This is a correctness check only — does it launch, does it render, does it
look right. NO FRAME-TIME OR FPS NUMBER FROM THIS EMULATOR IS EVER A
PERFORMANCE CLAIM. The client's phone is the only performance instrument for
this project (see docs/operations.md and the brief).
================================================================================
EOF
