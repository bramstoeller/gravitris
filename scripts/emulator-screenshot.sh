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
# Discovered per-boot from the emulator's own running-instance file, not
# assumed — see discover_serial() below. A hardcoded emulator-5554 would
# cross-talk with a developer's own, unrelated AVD already on that port
# (installing onto it, screenshotting it, and `emu kill`ing it instead of
# ours) exactly the kind of machine .env.example invites by saying nothing
# stops you running other AVDs alongside this one.
SERIAL=""

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
    if [ -n "$SERIAL" ]; then
      adb -s "$SERIAL" emu kill >/dev/null 2>&1 || kill "$EMULATOR_PID" 2>/dev/null
    else
      kill "$EMULATOR_PID" 2>/dev/null
    fi
    for _ in $(seq 1 10); do
      kill -0 "$EMULATOR_PID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$EMULATOR_PID" 2>/dev/null || true
  fi
  rm -rf "$HOME/.android/avd/${AVD_NAME}.avd/running" "$HOME/.android/avd/running" 2>/dev/null || true
}
# EXIT alone misses a Ctrl-C, an outer `timeout`, or a container stop — any
# of which would otherwise orphan the backgrounded qemu child, which then
# holds the AVD lock until the next run. That is exactly the failure mode
# this script exists to avoid (see the file header).
#
# INT/TERM need their own explicit `exit` after cleanup, not just `trap
# cleanup INT TERM` — bash does not terminate a script on a trapped signal
# once a handler is installed for it, it just runs the handler and *resumes*
# execution afterwards. Verified this the hard way: an earlier version of
# this trap ran cleanup on SIGTERM, then carried on into another boot
# attempt instead of stopping. 128+signal is the conventional exit code.
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

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
  SERIAL=""
  "$EMULATOR_BIN" -avd "$AVD_NAME" \
    -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu "$gpu_mode" \
    > "$EMULATOR_STDOUT_LOG" 2>&1 &
  EMULATOR_PID=$!
  # The emulator writes its own actual assigned port here, keyed by its own
  # PID (which we already have) — this is what makes SERIAL correct instead
  # of assumed. See the file header comment on why hardcoding emulator-5554
  # is wrong.
  local running_ini="$HOME/.android/avd/running/pid_${EMULATOR_PID}.ini"

  local waited=0
  local died=0
  local port boot
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
    if [ -z "$SERIAL" ] && [ -f "$running_ini" ]; then
      port="$(grep -m1 '^port\.serial=' "$running_ini" 2>/dev/null | cut -d= -f2 | tr -d '[:space:]')"
      [ -n "$port" ] && SERIAL="emulator-$port"
    fi
    if [ -n "$SERIAL" ]; then
      boot="$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null || true)"
      if [ "$boot" = "1" ]; then
        return 0
      fi
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
  SERIAL=""
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

if [ -z "$SERIAL" ]; then
  echo "FAIL: boot reported complete but no adb serial was ever discovered — this is this script's own bug, not the emulator's" >&2
  exit 1
fi

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
# Checked explicitly, not left to `set -e`: a failed install must not be
# allowed to fall through to "launch" a component that was never installed
# and screenshot whatever the home screen happens to show instead — that
# would report success on a build that never actually ran. `adb install`
# does return nonzero on failure, but the output is also checked for
# "Failure" since that is the more specific signal adb itself uses.
install_output="$(adb -s "$SERIAL" install -r "$APK" 2>&1)" || {
  echo "FAIL: adb install failed:" >&2
  echo "$install_output" >&2
  exit 1
}
if echo "$install_output" | grep -q "^Failure"; then
  echo "FAIL: adb install reported a failure despite a zero exit code:" >&2
  echo "$install_output" >&2
  exit 1
fi

echo "==> Launching $ACTIVITY"
# Same reasoning as the install check above: `am start` can exit 0 while
# still failing to resolve or launch the component (e.g. a typo'd activity
# name, or ADR/manifest drift) — the failure shows up only in its own
# output, as an "Error:" line instead of "Status: ok".
start_output="$(adb -s "$SERIAL" shell am start -W -n "$ACTIVITY" 2>&1)" || {
  echo "FAIL: am start failed:" >&2
  echo "$start_output" >&2
  exit 1
}
if ! echo "$start_output" | grep -q "^Status: ok"; then
  echo "FAIL: am start did not report Status: ok:" >&2
  echo "$start_output" >&2
  exit 1
fi

# Wait for the *specific window* to actually be composited, not a fixed
# sleep. `am start -W`'s Status/TotalTime measures the activity reaching
# top-resumed, which is not the same moment as pixels landing on screen —
# there is a real gap between them (a launch splash-screen window is shown
# and torn down in between, and the activity's own window goes through
# DRAW_PENDING before HAS_DRAWN). A fixed sleep guessed short enough to be
# fast enough to be worth having can and did land inside that gap: the
# first `make screenshot` run against the integrated build captured the
# launch-transition wallpaper instead of the app, on a perfectly healthy
# build - a false negative for the one thing this tool exists to check.
#
# What actually tracks the real, on-screen state: WindowManager's own
# per-window draw state, from `dumpsys window windows`. The activity's
# window entry reaching `shown=true` and `mDrawState=HAS_DRAWN` is
# WindowManagerService's own record of "this window's surface has been
# drawn and is being shown", independent of screenshot pixel content
# (screencap's PNG bytes are not stable frame-to-frame even for a static
# screen - checked directly: two captures one second apart of the same
# unchanged launcher screen produced different checksums - so diffing
# screenshots against a pre-launch baseline was tried and rejected as the
# detection mechanism, though it's mentioned as an option in the backlog
# item this responds to) and independent of the app's own render-thread
# frame count (`dumpsys gfxinfo`, which can be nonzero before the
# compositor has actually swapped the buffer visible on screen).
#
# A build that never reaches HAS_DRAWN (a crash before first frame, a
# shader that fails to compile and never produces a frame) times out here
# and fails loudly instead - verified by deliberately breaking MainActivity
# and confirming this reports FAIL rather than screenshotting whatever was
# on screen before the crash (see handoff).
# Matches the `WindowStateAnimator{<hash> <package>/<activity>}:` header
# line for this specific activity (not any other window — a "Splash
# Screen" window for the same package exists transiently too, and must not
# satisfy this check) and looks at the two lines after it, where the
# `Surface: shown=<bool> mDrawState=<state>` line for that window lives.
wait_for_first_frame() {
  local timeout="${1:-15}"
  local waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if adb -s "$SERIAL" shell dumpsys window windows 2>/dev/null \
        | grep -A2 -F "${ACTIVITY}}:" \
        | grep -q "shown=true.*mDrawState=HAS_DRAWN"; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

if ! wait_for_first_frame 15; then
  echo "FAIL: $ACTIVITY never reached shown=true/HAS_DRAWN within 15s — the app did not render a first frame (crash before draw, or a shader/compile failure). Not capturing a screenshot of whatever was on screen before this." >&2
  exit 1
fi
# One more beat past the first HAS_DRAWN sighting: the window state can
# legitimately flip DRAW_PENDING -> HAS_DRAWN -> (a resize/inset pass) ->
# DRAW_PENDING -> HAS_DRAWN again in the first moment after launch: this
# settles on the frame actually worth screenshotting rather than the
# earliest possible one.
sleep 1

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
