# DevOps Engineer → Product Lead: correctness-only Android emulator

Placeholder number — Product Lead owns the sequence, per `.team/handoffs/README.md`.

## What I did

Branch `chore/emulator` (PR #4, based on the now-green `chore/build-foundation`).
`/dev/kvm` and `/dev/dri` are now passed into this container; handoff 0005's
"no emulator" recommendation was correct *at the time* (no device nodes at
all) but no longer applies to whether one can exist here — only, as it turns
out, to what it's worth. Re-evaluated from scratch rather than assumed.

- `scripts/setup-emulator.sh` / `make emulator-setup`: installs the pinned
  emulator + one system image and creates one AVD, idempotent.
- `scripts/emulator-screenshot.sh` / `make screenshot`: builds the debug APK,
  boots, installs, launches, waits, screenshots, shuts down — the actual
  point of the exercise, since a screenshot is worth more than an interactive
  emulator for this team's purposes.
- `make emulator`: interactive boot with a window, for a machine with a real
  display (not useful in this headless container, kept for completeness /
  a developer's own laptop).
- x86_64, not the client's arm64-v8a: **verified, not assumed**, that neither
  `:core-sim` nor `:app` has any NDK/JNI code (no `ndk {}` block, no
  `jniLibs/`), so nothing here is ABI-sensitive, and x86_64 gets real KVM
  acceleration on this x86_64 host.
- API 36 (Android 16), matching the client's actual OS, not minSdk 29 (the
  floor, not the version worth testing rendering against).

## What I found, and it is not good news

Two separate, independently-confirmed problems. Both documented in
`docs/operations.md` "Emulator — correctness testing only" in full, this is
the short version:

1. **Hardware GL is not actually available in this container**, despite
   `/dev/dri` being present and readable. Confirmed two independent ways:
   the host's own Mesa/EGL stack (`eglinfo -B`) falls back to `llvmpipe`
   because the AMD GPU's kernel driver refuses
   `amdgpu_query_info(ACCEL_WORKING)` with `EACCES`; and the emulator's own
   capability check says the same thing directly (`Your GPU cannot be used
   for hardware rendering`). The script detects this from the emulator's own
   log and falls back to `-gpu swiftshader_indirect`, printing an unmissable
   `GPU status: SOFTWARE (SwiftShader)` line rather than silently proceeding.

2. **The emulator itself crashes on a majority of boot attempts** — a
   `SIGSEGV` inside `qemu-system-x86_64-headless` (gfxstream), ~15-20s into
   boot, under *both* `-gpu host` and `-gpu swiftshader_indirect`, *with or
   without* `-no-accel` (so it is not specifically a KVM or a GPU-mode
   problem). Confirmed with `strace -f` attached that it's a genuine SIGSEGV
   in the qemu process, not a script bug. `QT_QPA_PLATFORM=offscreen` (the
   emulator's Qt UI layer initializes even in `-no-window` mode and
   segfaults immediately without a display or offscreen platform plugin) is
   a real fix for one crash mode but reduces rather than eliminates the
   overall crash rate — repeated back-to-back attempts in a tight loop
   still crashed 6/6 times in one run of testing. **Not fully root-caused.**
   The script retries up to 3 times per GPU mode as a pragmatic concession,
   then fails loudly.

**Practical effect: `make screenshot` may well fail outright in this
container today**, separately from and in addition to the software-rendering
finding above. I'm not shipping this as "it works" — it's shipped as
"correctly designed, currently blocked by an environment issue neither
finding was hiding from the other."

PR #4 CI is green (the scripts aren't exercised by CI — deliberately, see
below — but nothing here broke the existing build/test/lint pipeline).

## What I deliberately did not do

- Did not root-cause the qemu SIGSEGV to a specific line/commit in
  QEMU/gfxstream. Would need a real stack trace (blocked: no `dmesg`, no
  accessible core dump — `systemd-coredump` on the host intercepts it and
  I have no path to it from in here) or GDB attached, neither of which I
  have a path to from inside this sandbox. Recorded the `strace` evidence
  (SIGSEGV in the main thread + one other, right after an ~18s idle gap,
  followed by an orderly-looking multi-thread shutdown that reads like a
  crash handler) in case someone with more access wants to pick it up.
- Did not wire `make screenshot` into CI. GitHub-hosted runners have neither
  `/dev/kvm` nor `/dev/dri` — an emulator there would be slower than the JVM
  tests already are and would inherit today's crash with no way to diagnose
  it interactively (no SSH into a CI runner here). This is a local,
  this-container-only tool, same spirit as `make dev`.
- Did not try an older emulator revision, or ask whoever administers this
  container about the seccomp/capability profile QEMU runs under — both
  listed as the likely next steps in `docs/operations.md`, both need access
  or time I didn't have in this dispatch.

## What the next person needs to know

- **The standing rule is untouched**: no frame-time or FPS number from this
  emulator is ever a performance claim, regardless of whether the crash gets
  fixed. The client's phone remains the only performance instrument. The
  script never prints one, on purpose.
- `pkill` should not be used in this container's sandbox for anything — see
  the build-foundation handoff for what it actually does (aborts the whole
  tool invocation, not just the matched process). `scripts/emulator-screenshot.sh`
  uses `ps` + `awk` + targeted `kill` instead.
- Backgrounding a long-running process (the emulator) across *separate* tool
  calls does not work reliably in this sandbox — the process group appears
  to be torn down between calls even with `disown`. Both scripts run their
  entire lifecycle (boot → install → screenshot → shutdown) inside one
  invocation for this reason, not just for tidiness.
- The AVD is named `gravitris_correctness`, installed under `$ANDROID_HOME`
  (`/state/android-sdk`), so it survives a container restart the same way
  the rest of the toolchain does.

## Considered and rejected

- Considered shipping without the GPU/crash findings prominently documented,
  since neither is flattering and the task was nominally "set up an
  emulator." Rejected: the brief's own instruction was to verify hardware GL
  is genuinely in use and say so plainly if it falls back — extending that
  same honesty to "it often doesn't boot at all" is the same principle, not
  a separate judgment call.
- Considered dropping the `-gpu host` attempt entirely, since it always
  fails in this container. Kept it as the first attempt anyway (with fast
  failure detection, not a full timeout wait) — the script should behave
  correctly on a future container where hardware GL genuinely works, without
  needing to be rewritten.
- Considered giving up on `make screenshot` producing a screenshot at all in
  this dispatch, given the crash rate. Did not — the design (retry bounded,
  fail loudly, report the real GPU status) is the right shape regardless of
  today's crash rate, and is what the next person should build on rather
  than starting over.

## Open questions

- Is it worth asking whoever administers this container about the seccomp/
  capability profile QEMU is running under, given the SIGSEGV reproduces
  independent of GPU mode and KVM? That's the most promising lead I didn't
  get to chase.
- Should `/dev/shm` size (63MB in this container) be increased regardless of
  the above? I checked it wasn't the proximate cause of the crash (never
  filled during a crash window) but it's small for a graphics-heavy VM and
  could still matter once the SIGSEGV itself is fixed.
