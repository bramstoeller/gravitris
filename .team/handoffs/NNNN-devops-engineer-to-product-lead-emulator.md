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

## What I found — updated after a second, deeper pass

The first pass at this concluded "crashes most of the time, not fully
root-caused, hardware GL unavailable, both bad news." The coordinator asked
me to chase it further rather than leave it there. That was worth it —
`make screenshot` is now reliable. Full trail in `docs/operations.md`
"Emulator — correctness testing only", this is the short version:

1. **The crash is root-caused, and fixed.** A naive `strace` attach and a
   post-mortem backtrace both turned out to be misleading (gdb's own
   overhead sometimes avoided the underlying race entirely; a backtrace
   grabbed too late caught the crash handler's cleanup, not the fault
   itself). Attaching `gdb` to a *live* boot and catching the actual signal
   (not a subsequent one — QEMU uses `SIGUSR1` internally, which gdb stops
   on by default and which looks exactly like an unrelated hang if you
   don't tell it not to) got a real answer: `-gpu swiftshader_indirect`
   (this AVD's default) SIGSEGVs inside `libGLESv2.so` — specifically a
   JIT-compiled shader routine (SwiftShader's Subzero/Reactor JIT) faulting
   with `SEGV_ACCERR` (mapped memory, wrong permissions — a write-then-
   execute JIT transition going wrong, not a plain bad pointer). `-gpu
   swangle` runs the identical GLES calls through ANGLE on top of
   SwiftShader's *Vulkan* backend instead of straight through the crashing
   GLESv2 JIT path, and has not reproduced the crash once: **9/9 clean
   boots and 3/3 full `make screenshot` runs** (build, install, launch,
   screenshot) while confirming this. The script now uses `swangle` as its
   software fallback. Still software — this fixed a stability bug, not the
   hardware-acceleration question below.

2. **Real hardware GL rendering is possible in this container — separately
   confirmed, as asked — but the emulator has no way to reach it today.**
   Two different claims:
   - The AMD GPU itself works: a hand-written Vulkan compute program (real
     compiled shader, real command submission, `vkQueueWaitIdle`, correct
     readback) ran successfully on `AMD Radeon Graphics (RADV GFX1152)`.
     `MESA_LOADER_DRIVER_OVERRIDE=zink eglinfo -B` separately reports a real
     hardware-backed **OpenGL ES 3.2** renderer via EGL+GBM, headless, no X
     server — Mesa's OpenGL-over-Vulkan driver riding on the working Vulkan
     stack. The GPU is not the problem.
   - What's blocked: the *classic* Mesa/AMDGPU path (what `eglinfo`'s
     default driver selection picks, and what the emulator's `-gpu host`
     capability check depends on) calls `amdgpu_query_info(ACCEL_WORKING)`
     at device-init time, and that fails with `EACCES` — not a file-
     permission issue (`/dev/dri/renderD128` is `0666`), not fixable with
     `MESA_LOADER_DRIVER_OVERRIDE=radeonsi` (tried — same failure). An
     `EACCES` on a specific privileged ioctl, with the device node itself
     wide open, reads as a container-level seccomp/AppArmor restriction on
     that operation, imposed by whoever configured this container's GPU
     passthrough — not liftable from inside the container, even as root.
     `-gpu host` also separately needs a real GPU-backed X server for its
     GLX interop path; `Xvfb` doesn't substitute (tested — GLX through it
     still resolves to `llvmpipe` regardless of driver-override env vars,
     since Xvfb is a software-only X server with no real DRI backing at
     all). **Precise ask, if worth pursuing, same shape as the original
     `/dev/kvm`/`/dev/dri` grant**: whatever policy blocks the
     `AMDGPU_INFO` accel-working query (or privileged `amdgpu`/DRM ioctls
     more broadly) on `/dev/dri/card1` needs relaxing.

**Practical effect: `make screenshot` now works reliably** (software-
rendered, correctly and unmissably labeled as such). Not the hardware-
accelerated correctness testing the brief originally hoped for, but the
actual stated goal — "does it launch, does it render, does it look right" —
is met today, not blocked.

PR #4 CI is green (the scripts aren't exercised by CI — deliberately, see
below — but nothing here broke the existing build/test/lint pipeline).

## What I deliberately did not do

- Did not pursue getting the *emulator itself* onto real hardware GL beyond
  confirming the GPU can do the work. Would mean either the emulator
  exposing a "route GLES through the system's EGL/zink" mode (not offered
  by any `-gpu` value `-help-gpu` lists), or `LD_PRELOAD`-swapping its
  bundled `libEGL.so`/`libGLESv2.so` for the system's zink-enabled ones
  (risky — likely ABI/version mismatches against exactly what the emulator
  bundles), or getting the container-level ioctl restriction actually
  lifted (not something I can do from in here). Recorded precisely what to
  ask for, above and in `docs/operations.md`, rather than guessing further.
- Did not wire `make screenshot` into CI. GitHub-hosted runners have neither
  `/dev/kvm` nor `/dev/dri` — an emulator there would be slower than the JVM
  tests already are and gains nothing now that this container's own crash
  is fixed and the GPU question is answered. This is a local,
  this-container-only tool, same spirit as `make dev`.

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
- Considered giving up on `make screenshot` producing a screenshot at all
  after the first pass's crash rate. Did not — kept chasing it once asked
  to, and it paid off: `-gpu swangle` instead of the default fixed it.
- Considered keying `GPU_STATUS` off whether the renderer log line contains
  the word "swiftshader" (simplest to write). Rejected once `swangle`
  entered the picture — its own renderer string legitimately contains
  "SwiftShader" (it's ANGLE-over-SwiftShader), so that check would have
  kept working today but been one future software-fallback mode away from
  silently mis-reporting hardware GL. Keyed off which `-gpu` mode actually
  booted instead.

## Open questions

- Is it worth formally asking whoever administers this container to relax
  whatever's blocking the `AMDGPU_INFO` accel-working ioctl? I now have a
  precise, evidenced ask rather than a vague one, and real hardware GL
  rendering of the app is genuinely reachable if it's lifted (the GPU and
  the driver stack both already work — only that gate is in the way). Not
  mine to decide whether it's worth the ask.
- `/dev/shm` is 63MB in this container — confirmed it isn't the cause of
  the (now-fixed) crash, but it's small for graphics work generally and
  could matter for something else later.
