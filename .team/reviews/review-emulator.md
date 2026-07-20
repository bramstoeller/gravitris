# Review: chore/emulator (PR #4)

Verdict: approve-with-comments
Range: origin/chore/build-foundation..origin/chore/emulator (tip 93e1595)
Reviewer: Code Reviewer
Date: 2026-07-20

Merge target: `main`, on top of PR #1. Correctness-only emulator tooling
(screenshots via software rendering) — explicitly not a performance instrument
and explicitly not on the required build/test path.

## Merge verification (verify, don't trust)

- PR #4 branched from an earlier point of `build-foundation` (`dead62a`) than
  its current tip, but touches only Makefile / docs / scripts / `.env.example`,
  which do not collide with the later foundation commits. Merged onto
  `main + PR #1` **cleanly, no conflicts**.
- Merge result pushed to `verify/main-plus-foundation-plus-emulator` and run
  through CI (cold Android SDK, full `make test` + `make build` +
  reproducibility). **Result: GREEN** (run 29766000884, completed/success).
- Contract check on the merged Makefile: `test` and `build` targets are
  unchanged and still wrap only `./gradlew`; `emulator-setup`, `emulator`, and
  `screenshot` are separate opt-in targets with **no dependency edge** from
  `test`/`build`. The correctness-only boundary holds on the merge result.

## Blocking

None. Tooling is opt-in, off the required path, and does not affect whether
`main` builds or runs.

## Should fix (handed to DevOps Engineer — tool robustness, not trunk blockers)

The emulator screenshot script has real robustness gaps. None can break `main`'s
build/run (the tool is opt-in), so none block this merge — but each can hand a
developer a **false-positive screenshot** or leak a process, which for a
correctness instrument is worth fixing.

- `scripts/emulator-screenshot.sh` — `set -uo pipefail` but **no `set -e`**.
  Combined with the unchecked commands below, a failed build/install continues
  to screenshot the wrong surface. Failure: `./gradlew :app:assembleDebug` fails
  → `$APK` absent → `adb install` errors → `am start` launches nothing →
  `screencap` captures the **Android home screen** → the `[ ! -s ]` non-empty
  guard passes → success banner. Reviewer gets a green run and a launcher
  screenshot instead of the app.
- Same script — `adb install` (`:196`) and `am start` (`:199`) exit codes are
  swallowed; with no `set -e`, an `INSTALL_FAILED_*` or wrong-ABI install
  proceeds to screenshot the home screen and reports success.
- Same script — only `trap cleanup EXIT` is set; **`INT`/`TERM` are not
  trapped**. A `timeout(1)` wrapper, outer `kill`, or container stop terminates
  the script without cleanup, orphaning the backgrounded `qemu-system` child,
  which holds the AVD lock until the next run. Contradicts the script's own
  stated goal ("an orphaned emulator is worse than a slow script"). Add
  `trap cleanup EXIT INT TERM`.
- Same script — hardcoded `SERIAL="emulator-5554"`. If the developer already
  runs a different AVD on port 5554 (which `.env.example` explicitly invites),
  `kill_stale_emulator` (scoped to `-avd $AVD_NAME`) won't touch it, this
  emulator lands on 5556, but every `adb -s emulator-5554` command — including
  the final `adb emu kill` — targets the **user's unrelated emulator**. It
  installs onto, screenshots, and kills the wrong instance while orphaning its
  own. Discover the serial from `adb devices` / the launched PID, don't assume
  5554.

## Notes (non-blocking)

- `-gpu host` fast-fail detection greps for specific log strings that the actual
  host-GL failure (amdgpu `ACCEL_WORKING` `EACCES`, X11 "Failed to open
  display") may not emit; if they don't match, each `host` attempt burns the
  full 180s boot timeout ×2 (~6 min) before falling back to `swangle`. Fragile,
  not incorrect.
- `setup-emulator.sh` license-acceptance runs `yes | sdkmanager --licenses`
  under a `set +o pipefail` window without capturing `PIPESTATUS`, unlike the
  sibling `setup-android-sdk.sh` which deliberately checks `PIPESTATUS[1]`. A
  `--licenses` failure is invisible (caught only indirectly by the next install
  failing). Asymmetry looks like an oversight.

## What is good

- Correctness-only contract is honored end to end: `make test`/`make build`
  untouched, emulator targets opt-in with no dependency edge, no timing/FPS
  number is ever computed or printed, banner + docs enforce "never a performance
  instrument," and there is no CI emulator job.
- No unverified download/execution: `setup-android-sdk.sh` pins the
  cmdline-tools URL and `sha1sum -c`s it; `setup-emulator.sh` installs only
  pinned package IDs via `sdkmanager`. No `eval`, no `curl | bash`, no secrets.
- Screenshot uses `adb exec-out screencap -p` (not `adb shell`), avoiding
  CRLF-mangled PNGs, and guards against an empty file.
- Boot wait is bounded (`BOOT_TIMEOUT`, 3s poll on `sys.boot_completed`),
  detects a dead emulator PID via `kill -0`, and the swangle root-cause
  (gdb-traced SIGSEGV under `-gpu host` in the sandbox) is documented, not
  guessed.
- Retry bookkeeping is correct: `EMULATOR_PID` is reset after a kill so the EXIT
  trap won't double-signal a reaped PID.

— **Code Reviewer**
