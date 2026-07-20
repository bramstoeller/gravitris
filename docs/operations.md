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
`apk`, `clean`, `doctor`). All of them wrap `./gradlew` — nothing here is
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

**The client-facing instructions are `docs/install-milestone-1.md`** — send them
that, not this file. It covers the "install from unknown sources" warning in
plain language, states the permission posture accurately, and says what to
report back.

The short version for the team:

1. Copy `app-debug.apk` to the phone (USB cable, or any file-transfer app).
2. Open it from the phone's Files app and allow the unknown-sources install.
3. It appears as **Gravitris** in the app drawer.

There is currently no release signing (Stage 5) — this is a debug build,
which Android will install and run identically to a signed one, it just
cannot be distributed as the final release artifact.

**On the permission claim.** The honest statement is **"no internet access"**,
not "no permissions": the app ships `android.permission.VIBRATE` for impact
haptics, and Android does not show normal permissions on the install screen
anyway. `INTERNET` is banned unconditionally by `CheckMergedManifest`, on a
code path that does not consult the allowlist, so the no-network guarantee is
build-enforced rather than asserted. See `docs/security/threat-model.md` §5.
The older "no permissions" wording is wrong and must not be reintroduced.

## What the current build ships

Milestone 1, the "squish toy": one soft-body piece falls into an empty well,
the player drags it, drops it and watches it squash and settle, with an impact
haptic scaled to the landing. Flat colours plus the approved compression
darkening term — no procedural shading, no landing silhouette.

Deliberately **not** in it: piece sequence, lock detection, coverage bands,
clearing, losing, scoring, menus and settings. Those are Stage 3 and later
(`docs/build-order.md`). The milestone exists to answer "does the squish feel
heavy?", and anything else dilutes the answer.

Two hidden measurement affordances in the debug build, both hardware keys so
they carve no dead zone out of a drag-anywhere play area:

| Key | Effect |
| --- | ------ |
| Volume **up** | Toggles the compression-darkening shader term. The readout's `shade:` field says which state it is in. Frame times are not comparable across the two, which is the entire point. |
| Volume **down** | Runs the ADR 0009 solver benchmark and prints the result under the live figures. Freezes the screen for a few seconds. Debug builds only, gated on `FLAG_DEBUGGABLE`. |

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

## Why there is no emulator, and no instrumented Android tests

This container has no `/dev/kvm` and no `/dev/dri`. Any Android emulator here
would run under full software CPU emulation with a software GL rasterizer.
Two consequences, both permanent for this container:

- **No performance number from an emulator here is ever valid**, for
  anything — not solver timing, not frame rate, not shader cost. This is a
  hardware fact about the container, not a temporary limitation, and no
  future change to this project should cite an emulator frame time as
  evidence of anything.
- **Correctness-only use (launch, tap, screenshot, instrumented test)** is
  technically available but was evaluated and **not set up**. See handoff
  0005 for the reasoning: the part of this product that most needs
  deterministic, automated testing (`:core-sim` — physics and game rules) is
  already fully covered by plain JVM tests with no device or emulator
  involved at all (that is the entire point of ADR 0008). The remaining
  surface an emulator could check — does `:app` launch, does a tap register —
  is thin at Stage 0 (one placeholder screen) and the client already has the
  one device that matters for it. Revisit only if `:app` grows enough
  instrumented-test surface that waiting for the client's phone becomes the
  actual bottleneck; it is not today.

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
