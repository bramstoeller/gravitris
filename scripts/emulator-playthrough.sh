#!/usr/bin/env bash
# Scripted play-through against the correctness AVD.
#
# `emulator-screenshot.sh` proves the app *launches and renders one frame*.
# This proves — as far as a software-rendered emulator can — that the game
# *runs its mechanic over time*: it boots, installs, launches, then drives
# real touch input with `adb shell input` (horizontal swipes to move, taps to
# rotate, fast down-swipes to hard-drop) over a long enough session for several
# pieces to fall and material to accumulate, capturing a numbered screenshot at
# each meaningful moment.
#
# It is a CORRECTNESS check, exactly like the screenshot script, and the same
# rule applies without exception: this is software rendering (ANGLE/SwiftShader
# via -gpu swangle when hardware GL is unavailable, which it is in this
# container). It proves the mechanic RUNS and RENDERS. It says NOTHING about how
# the game looks or performs on a real device. No frame-time or FPS number from
# this emulator is ever a performance claim — the client's phone is the only
# performance instrument this project has (docs/operations.md, the brief).
#
# --- Boot lifecycle is duplicated from emulator-screenshot.sh, deliberately ---
# The boot/GPU-fallback/serial-discovery half below is copied from
# emulator-screenshot.sh rather than sourced. DevOps owns that script and was
# hardening its first-frame settle logic when this was written, so refactoring a
# shared `scripts/emulator-lib.sh` out from under them would have collided. The
# agreed follow-up (handoff 0022) is to extract the shared lifecycle once their
# settle-hardening lands, and have both scripts source it. Until then, a change
# to the boot logic in one script must be mirrored in the other.
set -uo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

AVD_NAME="${AVD_NAME:-gravitris_correctness}"
OUT_DIR="${OUT_DIR:-build/emulator/playthrough}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-180}"   # seconds
PACKAGE="nl.brainbuilders.gravitris"
ACTIVITY="$PACKAGE/gravitris.app.MainActivity"
APK="app/build/outputs/apk/debug/app-debug.apk"

# How long, in real seconds, to let the game run between input bursts. Software
# rendering is slow and the app clamps its accumulator (no time dilation, so
# frames are dropped rather than slowed — ADR 0013), which means the game
# advances slower than wall-clock here. Generous waits are how a piece is given
# time to actually fall and settle before the next screenshot. Overridable.
SETTLE_SECONDS="${SETTLE_SECONDS:-6}"

# Clear threshold for this run, passed to the app as a debug-only launch extra
# (MainActivity honours it only on a debuggable build). The shipped default is
# 0.90 — a band packed almost solidly — which crude adb input on a slow software
# emulator will not reliably reach inside one scripted session. A lower,
# reachable value lets the clear (spawn -> lock -> ignite -> remove -> re-settle)
# actually be filmed; it changes WHEN a band clears, never HOW. Set empty to run
# at the shipped default and prove only that the mechanic runs, not that it
# clears.
CLEAR_THRESHOLD="${CLEAR_THRESHOLD:-0.35}"

EMULATOR_BIN="$ANDROID_HOME/emulator/emulator"
EMULATOR_STDOUT_LOG="$OUT_DIR/emulator.stdout.log"
EMULATOR_PID=""
SERIAL=""

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

kill_stale_emulator "$AVD_NAME"
rm -rf "$HOME/.android/avd/${AVD_NAME}.avd/running" "$HOME/.android/avd/running" 2>/dev/null || true
sleep 1

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
  sleep 3
  return 1
}

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

if [ "$GPU_REQUESTED" = "host" ]; then
  GPU_STATUS="hardware GL"
else
  GPU_STATUS="SOFTWARE ($GPU_REQUESTED) — not hardware GL"
fi

echo "==> Installing debug APK"
adb -s "$SERIAL" wait-for-device
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

# Clear the log buffer right before launch so the mechanic events captured at
# the end belong to this session only. GravitrisPlay is GameRenderer's tag for
# clear / spawn / game-over events — the unambiguous "a band ignited" signal.
LOG_TAG="GravitrisPlay"
adb -s "$SERIAL" logcat -c 2>/dev/null || true

# Pass the debug clear-threshold as a float extra when one is set. `--ef` is an
# am float extra; MainActivity ignores it on a non-debuggable build.
THRESHOLD_ARGS=()
if [ -n "$CLEAR_THRESHOLD" ]; then
  THRESHOLD_ARGS=(--ef gravitris.debug.clearThreshold "$CLEAR_THRESHOLD")
  echo "==> Launching $ACTIVITY (debug clearThreshold=$CLEAR_THRESHOLD)"
else
  echo "==> Launching $ACTIVITY (shipped clear threshold)"
fi
start_output="$(adb -s "$SERIAL" shell am start -W -n "$ACTIVITY" "${THRESHOLD_ARGS[@]}" 2>&1)" || {
  echo "FAIL: am start failed:" >&2
  echo "$start_output" >&2
  exit 1
}
if ! echo "$start_output" | grep -q "^Status: ok"; then
  echo "FAIL: am start did not report Status: ok:" >&2
  echo "$start_output" >&2
  exit 1
fi

# --- The play-through -------------------------------------------------------
# Everything below is what makes this more than the screenshot script.

# Discover the surface size at runtime rather than hardcoding it, so the input
# coordinates track whatever the AVD's resolution is. `wm size` prints e.g.
# "Physical size: 1080x2340"; the override line, if present, wins.
size_line="$(adb -s "$SERIAL" shell wm size 2>/dev/null | tr -d '\r')"
res="$(echo "$size_line" | grep -m1 'Override size' | sed 's/.*: *//')"
[ -z "$res" ] && res="$(echo "$size_line" | grep -m1 'Physical size' | sed 's/.*: *//')"
W="${res%x*}"
H="${res#*x}"
if ! [[ "$W" =~ ^[0-9]+$ && "$H" =~ ^[0-9]+$ ]]; then
  echo "FAIL: could not read the screen size from '$size_line'" >&2
  exit 1
fi
echo "==> Screen is ${W}x${H}"

# Column x-coordinates as fractions of the width, so a burst can be aimed at
# the left, centre and right of the well to spread material across a band's
# full span rather than piling it all in the centre column. Drag is relative
# (delta since last sample), so a horizontal swipe nudges the active piece by
# roughly its own screen-space length.
xL=$(( W * 25 / 100 ))
xC=$(( W * 50 / 100 ))
xR=$(( W * 75 / 100 ))
# A vertical band well inside the safe area for swipes, away from the status
# bar and the gesture bar.
yTop=$(( H * 30 / 100 ))
yBot=$(( H * 70 / 100 ))

shot() {   # shot <nn> <label>
  local name="$OUT_DIR/$1-$2.png"
  adb -s "$SERIAL" exec-out screencap -p > "$name"
  if [ ! -s "$name" ]; then
    echo "FAIL: screenshot $name was empty" >&2
    exit 1
  fi
  echo "    captured $name"
}

# A tap in the lower half rotates the active piece (a stationary press/release
# stays under touch slop -> rotate, per docs/ux/gestures.md).
rotate() { adb -s "$SERIAL" shell input tap "$xC" "$yBot"; }

# A slow-ish horizontal swipe reads as a drag (moves the piece), NOT a hard
# drop: it is horizontal and its velocity is well under the hard-drop cone.
move() {   # move <from_x> <to_x>
  adb -s "$SERIAL" shell input swipe "$1" "$yBot" "$2" "$yBot" 300
}

# A short, fast, straight-down swipe clears the hard-drop gates: >16dp travel,
# within the ±25° cone, and >1000dp/s (a long vertical throw over 80ms).
harddrop() {  # harddrop <x>
  adb -s "$SERIAL" shell input swipe "$1" "$yTop" "$1" "$yBot" 80
}

echo "==> Play-through begins (clearThreshold=${CLEAR_THRESHOLD:-shipped})"

# Give the first frame time to land.
sleep 4
shot 001 launch

# Feed pieces fast and wide, screenshotting densely, so the whole
# spawn -> fall -> lock -> band-fill -> clear -> re-settle cycle is sampled.
#
# Two things make this reliable where the first cut was not:
#
#  - Hard-drop every spawned piece. A natural fall is ~135 ticks; a hard drop
#    lands in a fraction of that. Software rendering runs the sim well below
#    real time (FrameDriver drops ticks it cannot afford — this device is below
#    the hardware floor, which is fine for a correctness check), so without the
#    speed-up too few pieces accumulate to fill a band in a sane session.
#  - Spread the drops across the floor. A central tower only ever fills the
#    floor band to about one piece width no matter how tall it gets, because a
#    band's fill is horizontal coverage; dragging each piece to a cycling column
#    before dropping builds a wide layer that actually reaches the threshold.
#
# The `bodies` figure in the readout is the ground truth: it climbs as pieces
# lock and DROPS when a band clears and its material is removed. That sawtooth,
# across the dense frames, is the proof the mechanic runs end to end — read the
# frames in order and watch it rise and fall.
xs=("$xC" "$xL" "$xR" "$(( W * 38 / 100 ))" "$(( W * 62 / 100 ))" "$xL" "$xC" "$xR")
frame=1
for iter in $(seq 1 30); do
  x="${xs[$(( (iter - 1) % ${#xs[@]} ))]}"
  # Nudge the active piece toward its target column (skip a zero-length swipe,
  # which would read as a tap and rotate), then commit it downward.
  [ "$x" != "$xC" ] && move "$xC" "$x"
  harddrop "$x"
  [ $(( iter % 6 )) -eq 0 ] && rotate
  sleep 2
  frame=$((frame + 1))
  printf -v nn "%03d" "$frame"
  shot "$nn" "t$(printf '%02d' "$iter")"
done

# Let the last clear-and-re-settle finish, then capture the final state.
sleep 4
shot 999 after-settle

# Dump the mechanic event log — the definitive record of what actually happened:
# every clear (with the body count and the fill that triggered it), every spawn,
# and any game over. This is what turns the screenshots from "gel blobs on a
# software renderer" into a yes/no.
MECHANIC_LOG="$OUT_DIR/mechanic.log"
adb -s "$SERIAL" logcat -d -s "$LOG_TAG:I" > "$MECHANIC_LOG" 2>/dev/null || true
clears_logged=$(grep -c "^.*clear #" "$MECHANIC_LOG" 2>/dev/null || echo 0)
spawns_logged=$(grep -c "^.*spawn #" "$MECHANIC_LOG" 2>/dev/null || echo 0)
echo "==> Mechanic log: $MECHANIC_LOG ($clears_logged clears, $spawns_logged spawns)"

cat <<EOF

================================================================================
Play-through screenshots in: $OUT_DIR
Renderer:   $(grep -m1 "Graphics Adapter " "$EMULATOR_STDOUT_LOG" 2>/dev/null || echo unknown)
GPU status: $GPU_STATUS (requested: -gpu $GPU_REQUESTED)

CORRECTNESS ONLY. This proves the mechanic RUNS and RENDERS over time under
software rendering. It is NOT a performance measurement and NOT a claim about
appearance on a real device. The client's phone is the only performance
instrument for this project (docs/operations.md, the brief).

Read the screenshots in order: material should accumulate and a coverage band
should brighten as it fills. Whether a band CLEARS (material in the glowing
zone released, the stack above dropping and re-settling) is exactly what this
session exists to show — or to show the absence of.
================================================================================
EOF
