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

Two independent, and separately important, findings — read both before
trusting anything this emulator shows you:

1. **Hardware GL is not actually available in this container**, despite
   `/dev/dri` being present and readable. Confirmed two ways:
   - The native host GL stack (Mesa, via `eglinfo -B`) falls back to
     `llvmpipe` (software) — the AMD GPU's own kernel driver refuses the
     acceleration query (`amdgpu_query_info(ACCEL_WORKING)` fails with
     `EACCES`), independent of Android entirely.
   - The emulator's own hardware-GL capability check reaches the same
     conclusion and says so directly: `ERROR | Your GPU cannot be used for
     hardware rendering. Consider using software rendering.`
   - `scripts/emulator-screenshot.sh` tries `-gpu host` first, detects this
     failure from the emulator's own log rather than waiting for a boot that
     has already failed, and falls back to `-gpu swiftshader_indirect`
     (software) — printing an unmissable `GPU status: SOFTWARE (SwiftShader)`
     line rather than silently proceeding as if it got hardware rendering.
     If a future container genuinely has working hardware GL, the script
     will report `hardware GL` instead and nothing else needs to change.

2. **The emulator itself crashes on a majority of boot attempts in this
   container** — a `SIGSEGV` inside `qemu-system-x86_64-headless`
   (gfxstream), roughly 15-20 seconds into "performing a full startup",
   reproduced with `strace` attached to confirm it is a real crash and not a
   script bug. This happens under **both** `-gpu host` and
   `-gpu swiftshader_indirect`, and **with or without** `-no-accel` (i.e. it
   is not specific to KVM or to the GPU question above) — it is some other
   incompatibility between this emulator build (36.6.11) and this
   container's sandboxing that was not fully root-caused. One fix was
   found and kept (`QT_QPA_PLATFORM=offscreen` — the emulator's Qt UI layer
   initializes even in `-no-window` mode and segfaults immediately without a
   display or an offscreen platform plugin), but it reduces the crash rate
   rather than eliminating it. `scripts/emulator-screenshot.sh` retries up
   to 3 times per GPU mode before giving up and reporting failure plainly —
   this is a concession to an unresolved container-level issue, not evidence
   the script is flaky.
   
   **Practical effect: `make screenshot` may well fail outright in this
   container right now**, separate from and in addition to the
   software-rendering finding above. Both are recorded here so the next
   person doesn't have to re-derive either from scratch. See handoff 0007
   for the full diagnostic trail (the `strace` excerpt, the `eglinfo`
   output, the exact log lines) if it needs to be picked up again — likely
   next steps: try an older emulator revision, or ask whoever administers
   this container about the seccomp/capability profile QEMU is running
   under.

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
