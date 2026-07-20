# Operations

Status: Stage 0 (foundation) — docs/build-order.md. This document covers the
build, not the game: there is no solver, renderer or game logic yet (Stage 1).

## What this product is, operationally

Gravitris is an offline Android game distributed as a signed, sideloaded APK
(ADR 0010). There is no server, no backend, no database, and no network
permission. "Operating" it means two things: building it reproducibly, and
getting the APK onto a phone. There is no service to deploy, restart, or
page someone about.

## One command to run, one command to test

```sh
make setup   # once per machine: installs the pinned Android SDK
make test    # runs everything: JVM tests, the ADR 0008 no-Android check,
             # the CHK-1/3/4 merged-manifest checks, and Android Lint
make dev     # builds the debug APK; installs+launches it if a device is
             # attached over adb, otherwise prints where the APK is and
             # points at the sideload instructions below
```

`make help` lists every target (`setup`, `dev`, `test`, `lint`, `build`,
`apk`, `clean`, `doctor`, `emulator-setup`, `emulator`, `screenshot`). All of
them wrap `./gradlew` or a script under `scripts/` — nothing here is
Makefile-specific magic; anyone who prefers Gradle directly can run
`./gradlew check` or `./gradlew :app:assembleDebug`.

## Toolchain — how a fresh container/machine gets the same build

Everything is pinned so a clone produces the same bytes, not "whatever was
already installed" (docs/security/dependency-policy.md R4):

| Tool | Pinned in | Notes |
| ---- | --------- | ----- |
| Gradle | `gradle/wrapper/gradle-wrapper.properties` | `distributionSha256Sum` set; `./gradlew` refuses to run on a tampered/wrong distribution |
| JDK | `jvmToolchain(21)` in each module + `foojay-resolver-convention` in `settings.gradle.kts` | Gradle downloads JDK 21 itself if the machine doesn't have it — this is what makes the toolchain declarative rather than dependent on the architect's ad-hoc install |
| Kotlin, AGP | `gradle/libs.versions.toml` (version catalog) | Exact versions, no ranges (R1) |
| Android SDK (platform, build-tools, platform-tools) | `scripts/setup-android-sdk.sh` | Gradle cannot provision this itself; `make setup` runs the script, which pins exact package versions and verifies the command-line tools download by SHA-1 before install |
| Dependency graph | `core-sim/gradle.lockfile`, `app/gradle.lockfile` (R2) | Full transitive lock, not just direct versions. Update via `./gradlew <module>:dependencies --write-locks` after a deliberate bump |
| Artifact integrity | `gradle/verification-metadata.xml` (R3) | SHA-256 per artifact; Gradle fails the build on a mismatch |

`make doctor` prints the JDK and Gradle actually in use and fails fast if
`ANDROID_HOME` doesn't exist.

## Configuration

Gravitris has no runtime configuration — see `.env.example`. The only
environment variable that matters is `ANDROID_HOME`, and it configures the
*build*, not the app. There are no API keys, no feature flags, no per-
environment settings, because there is no environment to vary across: one
build, one APK, one device family (Android, API 29+).

## Producing and installing the debug APK

```sh
make build
# → app/build/outputs/apk/debug/app-debug.apk
```

### Installing on a phone (sideload)

This is written for the client, who is not an Android developer.

1. Copy `app-debug.apk` to the phone (USB cable, or any file-transfer app).
2. Open it from the phone's Files app. Android will warn that installing
   apps from outside the Play Store is normally blocked — this is Android
   protecting you by default, not a sign that anything is wrong. Tap
   through to allow this one install ("Install unknown apps" / "Install
   anyway").
3. The app installs and appears as **Gravitris** in the app drawer.

There is currently no release signing (Stage 5) — this is a debug build,
which Android will install and run identically to a signed one, it just
cannot be distributed as the final release artifact. Nothing about the
sideload warning is specific to this project; it is what Android shows for
every app installed outside a store.

## What Stage 0 actually ships

`:app`'s `MainActivity` is a placeholder screen proving the module structure,
not the game. There is no solver, no renderer, no touch input, and nothing to
play yet — that is Stage 1 (docs/build-order.md), owned by the backend and
frontend engineers. If you install the APK you will see a black screen with
white placeholder text; that is expected and correct for this stage.

## Build-time checks — what they catch and where to look when one fails

| Check | What it catches | Where it runs | If it fails |
| ----- | ---------------- | -------------- | ----------- |
| `checkNoAndroidDependency` (`:core-sim`) | Any Android import or dependency landing in the pure-JVM module (ADR 0008) | Every `./gradlew check` / `make test` | The failure message names the exact dependency or `file:line` of the offending import. Remove it, or move the code into `:app`. This is the single most important check in the project — see docs/adr/0008-module-boundaries.md. |
| `check<Variant>MergedManifest` (`:app`) | `INTERNET` permission reintroduced by any dependency at any depth (CHK-1); `allowBackup`/`dataExtractionRules` regressing (CHK-3, S-1); an unexpected exported component (CHK-4) | Every `./gradlew check`, for both `debug` and `release` variants | Reads `app/build/.../AndroidManifest.xml` (the **merged** manifest, via the AGP variant API — not the source manifest, which proves nothing on its own). Fails closed: if the merged manifest is missing or empty, this is treated as a hard error, not a pass. The failure message lists exactly which assertion failed. |
| Android Lint | Misconfiguration AGP itself knows how to detect (e.g. a manifest referencing a class that doesn't exist) | Every `./gradlew check` / `make lint` | See `app/build/reports/lint-results-debug.html` |
| Dependency locking | A dependency's resolved version changing without a reviewed lockfile update (R2) | Every build | `./gradlew <module>:dependencies --write-locks`, then review the lockfile diff like any other change |
| Verification metadata | A dependency artifact's bytes not matching its recorded checksum (R3) | Every build | Investigate before regenerating — do not blindly re-run `--write-verification-metadata` to make a real mismatch go away |
| Gradle wrapper checksum | A tampered `gradle-wrapper.jar` (R4) | Locally (`distributionSha256Sum` in the properties file) and in CI (`gradle/actions/wrapper-validation`, CHK-6) | Locally: `./gradlew` itself refuses to run. In CI: the `Validate Gradle wrapper` step fails the workflow. |
| Secret scan | A keystore, key or credential ever committed (R7, CHK-7) | CI only, full history, every push | `gitleaks` step fails with the offending commit/file. Stop and escalate per the team constitution — do not try to quietly remove it from a new commit; history needs to be dealt with. |

**To see a check fail on purpose** (useful when reviewing this build, or
onboarding): temporarily add `implementation("androidx.annotation:annotation:1.9.1")`
to `core-sim/build.gradle.kts` and run `./gradlew :core-sim:checkNoAndroidDependency`,
or temporarily delete `android:allowBackup="false"` from
`app/src/main/AndroidManifest.xml` and run `./gradlew :app:checkDebugMergedManifest`.
Both were exercised this way (and reverted) while building Stage 0; see
handoff 0005.

## CI

`.github/workflows/ci.yml` runs `make test` then `make build` on every push to
`main` and every pull request — the same two commands documented above, nothing
more. It also validates the Gradle wrapper (CHK-6) and scans full history for
secrets (CHK-7) on every push. It does not build a release artifact, does not
sign anything, and does not touch an emulator or a device.

This is deliberately the ceiling, not a floor to build on. docs/build-order.md
is explicit: *"CI beyond a reproducible build"* is something this project
should resist while the store is deferred and the only audience is the
client. If a milestone later needs a release pipeline, add it then, against a
real requirement — not now, on spec.

**Branch protection is not configured by this workflow file** — GitHub
branch-protection rules are a repository setting, not something a workflow
can set for itself. Whoever administers `github.com/bramstoeller/gravitris`
needs to require the `build-and-test` check to pass before merging to `main`,
in the repository's Settings → Branches, for "blocks merge on failure" to
actually be true rather than just advisory.

## Emulator — correctness testing only

**This never measures performance. No frame time, FPS, or timing number
that ever comes out of this emulator may be cited as evidence of anything
about how the product performs.** The client's own phone is the only
performance instrument this project recognizes — this is a standing
constraint in the brief, not a suggestion. What the emulator is for: does it
launch, does it render, does it look right. `scripts/emulator-screenshot.sh`
never prints a frame-time or FPS number for exactly this reason.

`/dev/kvm` and `/dev/dri` are passed into this container (they were not,
earlier in the project — see handoff 0005, which recommended against an
emulator on that basis; that recommendation no longer applies to whether an
emulator can exist, only to what it's worth here, below).

```sh
make emulator-setup   # once: installs the pinned emulator + system image,
                       # creates the AVD (idempotent, ~1.5GB download)
make screenshot        # build the debug APK, boot, install, launch, wait
                       # for the first frame, save a PNG
make emulator          # boot the AVD with a window, for poking at it by hand
```

**x86_64, not arm64.** The client's phone is `arm64-v8a`, but neither
`:core-sim` (pure Kotlin/JVM, ADR 0008 forbids an Android dependency, let
alone native code) nor `:app` has any NDK/JNI code — confirmed, not assumed:
neither module declares a `ndk {}` block or ships a `jniLibs/` directory.
Nothing in this build is ABI-sensitive. x86_64 runs under this container's
`/dev/kvm` at native speed; an arm64 system image on an x86_64 host gets no
KVM acceleration (much slower binary translation) for zero correctness
benefit. Revisit if the app ever gains native code.

**API 36 (Android 16), not minSdk 29.** minSdk is the floor this build still
supports, not the version to test rendering against. The client's phone
runs Android 16 — an API-29 image would not catch anything specific to the
OS version the client actually has.

### What was actually found setting this up

Updated after a second, deeper pass (gdb, not just strace; a real Vulkan
compute workload, not just enumeration). The short version: **`make
screenshot` is now reliable in this container** — it was not on the first
pass, and the fix is `-gpu swangle` instead of the emulator's own default.
Two things worth knowing, in order of how much they should worry you:

1. **The emulator's default software GPU mode crashes; a different software
   mode doesn't, and the fix is now applied.** Root-caused with `gdb`
   attached to a live boot (a naive `strace`/backtrace-after-the-fact wasn't
   enough — see "how this was actually diagnosed" below if you need to
   redo this kind of investigation): `-gpu swiftshader_indirect` (the
   default this AVD was created with) SIGSEGVs inside
   `libGLESv2.so` — specifically, a JIT-compiled routine (SwiftShader's
   Subzero/Reactor JIT, which compiles shaders to native code at runtime)
   faults with `SEGV_ACCERR` (the memory *is* mapped, just not with the
   permissions the faulting instruction needed — the signature of a
   write-then-execute JIT transition going wrong) on a majority of boots.
   `-gpu swangle` runs the identical GLES calls through ANGLE on top of
   SwiftShader's *Vulkan* backend instead of straight through SwiftShader's
   GLESv2 JIT path, and has not reproduced the crash once: 9/9 clean boots
   and 3/3 full `make screenshot` runs (build, install, launch, screenshot)
   while confirming this. `scripts/emulator-screenshot.sh` now requests
   `swangle` as its software fallback, not `swiftshader_indirect`.
   **Both are still software** — this fixes a stability bug, not the
   hardware-acceleration question below.

2. **Real hardware GL rendering is possible in this container — the AMD GPU
   genuinely works — but the Android emulator has no way to reach it
   today.** These are two different claims and it matters that they don't
   get merged into one:
   - The GPU itself: confirmed with actual executed work, not just
     enumeration. A hand-written Vulkan compute program (device creation,
     memory allocation, a real compiled SPIR-V shader, command buffer
     submission, `vkQueueWaitIdle`, and a correct readback) ran successfully
     on `AMD Radeon Graphics (RADV GFX1152)`. Separately,
     `MESA_LOADER_DRIVER_OVERRIDE=zink eglinfo -B` (Mesa's OpenGL-over-Vulkan
     driver, headless, via `/dev/dri` directly — no X server) reports a real
     hardware-backed **`OpenGL ES 3.2` renderer naming the RADV device**, not
     `llvmpipe`. The GPU is not the problem.
   - What *is* blocked: the classic Mesa/AMDGPU path — the one `eglinfo -B`
     picks by default, and the one the emulator's own `-gpu host` capability
     check relies on — goes through `libdrm_amdgpu`'s
     `amdgpu_query_info(ACCEL_WORKING)` at device-init time, and that call
     fails with `EACCES`. This is not a file-permission problem
     (`/dev/dri/renderD128` is `0666`, world-writable) and not fixable with
     Mesa driver-selection environment variables (tried
     `MESA_LOADER_DRIVER_OVERRIDE=radeonsi` explicitly — same failure). An
     `EACCES` on a specific privileged ioctl, with the device node itself
     wide open, is the signature of a container-level restriction (a seccomp
     filter or an AppArmor/SELinux device policy) on that specific
     operation, imposed by whoever configured this container's GPU
     passthrough — not something liftable from inside the container, even as
     root. Precisely what to ask for, if this is worth pursuing: **whatever
     policy is blocking the `AMDGPU_INFO` "accel working" query ioctl (or
     more broadly, privileged `amdgpu`/DRM ioctls) on `/dev/dri/card1` needs
     relaxing** — the same shape of ask as the original `/dev/kvm`/`/dev/dri`
     grant, one level more specific.
   - Separately, `-gpu host` *also* wants a real X server for its GLX-based
     interop path on Linux (it tries `libX11`, fails "Failed to open
     display" without one) — and a virtual one doesn't help: `Xvfb` is a
     software-only X server, and GLX through it resolves to `llvmpipe`
     regardless of `MESA_LOADER_DRIVER_OVERRIDE` (tested). So even if the
     `ACCEL_WORKING` gate above were lifted, `-gpu host` would need a real,
     GPU-backed X server too, or the emulator would need to route its
     rendering through EGL/zink the way the bare-Mesa test above does — which
     is not something exposed by any `emulator` flag found (`-help-gpu`
     lists `auto`, `host`, `software`, `lavapipe`, `swiftshader`, `swangle` —
     none of them "use the system's EGL/zink").
   - `scripts/emulator-screenshot.sh` still tries `-gpu host` first (fast:
     it detects the `ACCEL_WORKING`-driven failure from the emulator's own
     log rather than waiting out a timeout) and reports `GPU status:
     hardware GL` if it ever succeeds — so a future container with this
     restriction actually lifted needs no script change to benefit from it.

#### How this was actually diagnosed (for next time)

The first pass at this (see the git history of this section) used `strace`
and concluded "crashes on a majority of attempts, not root-caused." That
undersold it — `strace`'s own overhead was enough to sometimes avoid the
race entirely, and a plain post-mortem backtrace attempt caught the crash
handler's cleanup, not the fault. What actually worked:

- Attach `gdb -p <pid>` to the emulator process *after* it execs into
  `qemu-system-x86_64-headless` (same PID, `ps -p $PID -o comm` confirms the
  exec happened), not to the `emulator` launcher script.
- `handle SIGUSR1 SIGUSR2 SIGPIPE noprint nostop pass` before continuing —
  QEMU uses these internally, and gdb's default is to stop on them, which
  looks exactly like an unrelated hang if you don't know to expect it.
- Exactly **one** `continue`, then gather everything you need (`bt full`,
  `info registers`, `print $_siginfo`) before doing anything else — a second
  queued `continue` resumes past the fault and hands you a half-torn-down
  process instead.
- `$_siginfo.si_code` is the detail that actually explains the bug: `2`
  (`SEGV_ACCERR`, wrong permissions on mapped memory) rather than `1`
  (`SEGV_MAPERR`, not mapped at all) is what points at a JIT write→execute
  transition rather than a plain bad pointer.

The part of this product that most needs deterministic, automated testing
(`:core-sim` — physics and game rules) is already fully covered by plain JVM
tests with no device or emulator involved at all (the entire point of ADR
0008) and is unaffected by any of the above.

This is not wired into CI: CI runners have no `/dev/kvm` and no `/dev/dri`,
so an emulator there would be slower and no more informative than
JVM tests already are, and would inherit the crash above with no way to
diagnose it interactively. `make screenshot` is a local, this-container-only
tool for the person doing rendering work, same spirit as `make dev`.

## When something breaks

| Symptom | Likely cause | First thing to check |
| ------- | ------------ | --------------------- |
| `./gradlew` fails immediately with a checksum error | Wrapper distribution mismatch (tampering, or a manual edit to `gradle-wrapper.properties`) | `git diff gradle/wrapper/` |
| Build fails resolving a dependency | Network access to `google()`/`mavenCentral()`, or a verification-metadata checksum mismatch | Check container network access first; then `gradle/verification-metadata.xml` against what actually resolved |
| `make setup` fails on the licenses step | `sdkmanager --licenses` combined with `set -o pipefail` — see the comment in `scripts/setup-android-sdk.sh`; this was an actual bug hit and fixed while building Stage 0 | Re-run `make setup`; if it still fails, run `bash -x scripts/setup-android-sdk.sh` and read where it stopped |
| `checkNoAndroidDependency` fails unexpectedly | Something in `:core-sim` now depends on Android, even transitively | The failure message names the exact dependency or import — start there |
| `check<Variant>MergedManifest` fails unexpectedly | A dependency was added that contributes a permission or an exported component, or someone touched `AndroidManifest.xml` | The failure message names which of CHK-1/3/4 failed and why |
| CI is green but a local build fails (or vice versa) | This should not happen — CI runs the identical `make test`/`make build` — treat it as a bug in this setup, not in the code, and check for machine-specific state (uncommitted `local.properties`, a stale `~/.gradle` cache) | `make clean`, then retry |

## Health, readiness, version

Not applicable in the usual sense — there is no running service. The closest
equivalents for a shipped APK:

- **"Is it healthy"** = does it install and launch without crashing. There is
  no automated device check for this yet (see the emulator section above);
  it is verified by hand on the reference device before each milestone demo.
- **"Version"** = `versionName`/`versionCode` in `app/build.gradle.kts`
  (currently `0.0.1-stage0`), visible via the system Settings → Apps screen
  on the installed device. No in-app version display exists yet; add one
  when there is a UI to add it to (Stage 1B).
