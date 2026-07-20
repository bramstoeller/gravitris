# 0005 — devops-engineer → product-lead

Date: 2026-07-20
Branch: `chore/build-foundation`, based on `chore/architecture` (not `main` —
per your instruction, `chore/architecture` is current truth; `main` has not
merged it yet). **Not merged. Not pushed.** You said you'd handle both.
Commit range: `4272f58..2d0384d` (4 commits on top of `chore/architecture`
@ `17dfe9b`).

## What I did

Stage 0 (docs/build-order.md): "the only true bottleneck in the plan." Two
engineers are waiting on this; it is done and demonstrably works.

**Pinned, reproducible Gradle build.**
- Gradle 8.11.1, wrapper pinned by `distributionSha256Sum`
  (`validateDistributionUrl=true` too — `./gradlew` itself refuses a
  tampered or wrong distribution).
- AGP 8.9.3, Kotlin 2.1.21 (same Kotlin version the architect installed
  ad hoc for the spike — kept identical rather than drifting to whatever
  is newest, since the spike already proved it works) via
  `gradle/libs.versions.toml`, no version ranges (R1).
- JDK 21 via `jvmToolchain(21)` in every module, plus
  `org.gradle.toolchains.foojay-resolver-convention` in
  `settings.gradle.kts` — a fresh container with no JDK at all gets one
  provisioned automatically. This is what makes the toolchain declarative
  rather than dependent on what the architect happened to install.
- Dependency locking (`dependencyLocking { lockAllConfigurations() }`,
  R2) — `core-sim/gradle.lockfile`, `app/gradle.lockfile`,
  `settings-gradle.lockfile` committed.
- Checksum verification (R3) — `gradle/verification-metadata.xml`
  committed, generated against a full `clean check assembleDebug
  assembleRelease`.
- Android SDK is the one piece Gradle cannot provision itself.
  `scripts/setup-android-sdk.sh` installs cmdline-tools 22.0,
  `platforms;android-36`, `build-tools;36.0.0`, `platform-tools` — every
  version pinned, cmdline-tools verified by SHA-1 before install, into
  `$ANDROID_HOME` (default `/state/android-sdk` — a toolchain cache, so it
  belongs under `/state` per the mount contract, not `/work`).

**Two modules per ADR 0008.** `:core-sim` (pure Kotlin/JVM, `kotlin("jvm")`
only, no Android plugin ever) and `:app` (`com.android.application`,
`minSdk 29`, `compileSdk`/`targetSdk 36` per ADR 0010). `:app` depends on
`:core-sim`; nothing depends the other way.

**The `:core-sim` no-Android check — built and proven.**
`buildSrc/.../CheckNoAndroidDependency`, wired into `:core-sim`'s `check`.
Two independent layers: (1) resolves `compileClasspath` /
`runtimeClasspath` / test equivalents and fails if any artifact's group is
`androidx.*`, `com.android.*`, or the legacy `android` group; (2) greps
`.kt` sources for `import android.`/`import androidx.`, reporting exact
file:line. Also a structural guard in `core-sim/build.gradle.kts` that
throws immediately if an Android plugin is ever applied to the module.

**Proven, not asserted** (both reverted immediately after):
```
$ ./gradlew :core-sim:checkNoAndroidDependency   # after adding androidx.annotation
> ADR 0008 violation: :core-sim must have no Android dependency, none...
    - androidx.annotation:annotation
    - androidx.annotation:annotation-jvm

$ ./gradlew :core-sim:checkNoAndroidDependency   # after adding `import android.util.Log`
> Android imports found in :core-sim sources:
    - .../TempAndroidImportProbe.kt:3: import android.util.Log
```

**The merged-manifest checks (CHK-1, CHK-3, CHK-4 from
`docs/security/threat-model.md` on `chore/threat-model`, not yet merged —
implementing these was explicitly mine per your brief).**
`buildSrc/.../CheckMergedManifest`, wired via the AGP **variant API**
(`variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)`) in
`app/build.gradle.kts` for every variant, not a hardcoded intermediates
path — the threat model calls out AGP-version drift by name as the likely
failure mode, and the variant API is AGP's own answer to that. Fails
closed on a missing/empty file (explicit check, on top of the `Provider`
itself throwing if the artifact can't resolve). Asserts: zero
`<uses-permission>` anywhere in the merged manifest (CHK-1);
`allowBackup="false"` + `dataExtractionRules` present (CHK-3, finding
S-1); no exported component besides the launcher activity (CHK-4).

**Proven, not asserted:**
```
$ ./gradlew :app:checkDebugMergedManifest   # allowBackup/dataExtractionRules stripped
> CHK-3: android:allowBackup must be "false", found (absent, defaults to true).
> CHK-3: android:dataExtractionRules must be set on <application>...

$ ./gradlew :app:checkDebugMergedManifest   # INTERNET permission reintroduced
> CHK-1: expected zero <uses-permission> elements in the merged manifest, found 1: android.permission.INTERNET
```
Manifest restored, `./gradlew check` passes clean afterward in both cases.

**One command to run, one to test.**
```
make setup   # once per machine
make test    # == ./gradlew check: JVM tests + both checks above + lint,
             #    on both debug and release variants
make dev     # builds debug APK; installs+launches over adb if a device
             #    is attached, else prints the APK path + points at sideload docs
```
`make help` self-documents. Verified from an actual fresh `git clone` of the
branch (`/tmp/gravitris-verify`), reusing only the machine-level caches
(`/state/android-sdk`, `/state/.gradle`) that `make setup` is what
populates — that's the intended "fresh container" story, since re-running
the full SDK+dependency download from zero network state isn't something I
can additionally prove beyond having done it once for real when I first
ran `scripts/setup-android-sdk.sh` against nothing.

**Debug (and unsigned release) APK produced.**
`app/build/outputs/apk/debug/app-debug.apk` and
`.../release/app-release-unsigned.apk` both build. No signing config
exists anywhere in the project — confirmed by the artifact name itself
(`-unsigned`), not just by omission. `:app`'s `MainActivity` is a plain
`Activity` + `TextView` placeholder proving the module wiring (including
the `:core-sim` dependency, which it reads from and displays); it is
explicitly not the game shell and says so in its own kdoc.

**`.gitignore`.** Added Android build-artifact patterns (`*.apk`, `*.aab`,
`.cxx/`, `captures/`) and signing-material patterns
(`*.jks`/`*.keystore`/`*.p12`/`*.pfx`/`keystore.properties`,
`local.properties`) matching what's already staged on `chore/threat-model`
(I did not merge that branch, just replicated the small, directly-relevant
lines so this doesn't regress before that branch lands — they're identical
text and will merge without conflict). **Caught a real landmine while
doing this**: the pre-existing `*.jar` rule (added for spike cleanup)
silently excludes `gradle/wrapper/gradle-wrapper.jar` — without the
wrapper jar committed, `./gradlew` doesn't exist for the next clone at
all. Added an explicit `!gradle/wrapper/gradle-wrapper.jar` exception and
confirmed via `git add -A -n` that the jar is actually staged.

**CI** — see recommendation below. Implemented: `.github/workflows/ci.yml`,
one job, wrapper validation (CHK-6) + full-history secret scan (CHK-7) +
`make test` + `make build`, on push to `main` and every PR.

**Docs.** `README.md` (quick start, layout, doc map), `docs/operations.md`
(toolchain pinning table, what each check catches and where to look when
it fails, sideload instructions written for a non-developer, why no
emulator, CI scope, a symptom→cause table), `.env.example` (the one env
var this build has, and an explicit statement that the *app* has none).

## What I deliberately did not do

- **No release signing, no keystore.** Stage 5. Confirmed no signing
  config exists anywhere and the release APK is named `-unsigned`.
- **No game logic, solver, or renderer.** `MainActivity` is a placeholder;
  its own kdoc says to replace it wholesale, not extend it.
- **No emulator setup.** See recommendation below.
- **No branch-protection configuration on GitHub.** That's a repository
  setting (Settings → Branches on `github.com/bramstoeller/gravitris`),
  not something a workflow file can set for itself. `origin` exists and
  `gh auth status` shows an authenticated `bramstoeller` account in this
  container, so it's technically reachable from here, but changing repo
  settings felt like a call for you or the client, not something to do
  silently as a side effect of a build task. Flagged in
  `docs/operations.md`'s CI section too.
- **Did not push the branch.** `origin` exists; I left it alone per "Do
  not merge to main; I will handle that."
- **Did not merge `chore/threat-model` or `chore/ux-spec`.** I read both
  (see brief conflict note below) but only pulled in the specific
  `.gitignore` lines that were directly my job to have regardless.
- **Did not add `androidx.core:core-ktx`** even though I put its version
  in the catalog — no code needs it yet (nothing in `:app` beyond the
  placeholder does). Left the catalog entry with a comment pointing at
  Stage 1B as the expected first user, per R5's "the bar is high, default
  is no."
- **No ktlint/detekt or other formatter/linter beyond Android Lint.** Not
  asked for, no dependency-policy R5 justification for it, and Kotlin
  compiler warnings + Lint already run in `check`. If the team wants
  enforced formatting later, that's a real but separate decision.

## Two recommendations, as asked

### The emulator: **not worth it. Zero.**

This container has no `/dev/kvm`, no `/dev/dri` — any emulator here is full
software CPU emulation with a software rasterizer, and you've already
(rightly) ruled its numbers out permanently for performance. The remaining
case was correctness: does it launch, does input register, do
instrumented tests pass, can it screenshot.

I looked at what that would actually buy right now and it's thin:
- The part of this product that most needs automated, repeatable testing —
  `:core-sim`, physics and game rules — already gets it for free, with no
  device or emulator at all. That's the entire point of ADR 0008, and it's
  real: `./gradlew :core-sim:test` runs in seconds on plain JVM JUnit.
- The part an emulator *could* check — `:app` launching, a tap
  registering — has almost no surface at Stage 0 (one placeholder
  screen), and by the time it has real surface (Stage 2's touch/drag/
  GLSurfaceView), you have exactly one real device and a client who
  already sideloads and reports back by hand. An emulator would be a
  second, weaker copy of a check you're already going to do for real.
- Setup cost is not trivial (a system image is a multi-hundred-MB
  download, boot time is real, and flaky software-GL rendering makes
  screenshot-diffing unreliable) for a signal that's redundant with "the
  client tried it on their Fairphone."
- The soft risk is the one you already named: having emulator numbers
  lying around invites someone to eventually cite one, even
  accidentally, in a place a real-device number belongs.

Revisit if `:app` grows enough instrumented-test surface that waiting for
the client's phone becomes the actual bottleneck on iteration speed. It
is not that today, and I'd rather say "not now" clearly than build it
half-heartedly.

### CI: **yes, but only exactly what mirrors the local commands.**

`docs/build-order.md` says "CI beyond a reproducible build" is something
to resist, and `staffing.md` echoes "no CI beyond what makes the build
reproducible." I read that as a ceiling on scope, not a ban — and a single
job that runs the identical `make test` / `make build` a developer runs
locally, plus wrapper/secret checks that are cheap and directly protect
the one attack surface the threat model calls "the only high-value asset"
(the build pipeline, since a compromised dependency or plugin is signed
by the legitimate key), stays inside that line.

What I did *not* add, on purpose, because it would cross that line: no
release build/signing job (no keystore exists, by design — R7), no
multi-API-level test matrix, no emulator/instrumented-test job (see
above), no scheduled/nightly job, no deployment step of any kind. If a
milestone later needs a release pipeline, that's a real, separate
decision to make against a real requirement.

One gap I can't close from here: I did not verify the workflow actually
runs green on `github.com/bramstoeller/gravitris`, since I didn't push.
The YAML parses and the action versions are current (checked against each
action's own latest release right now: `checkout` 7.0.0, `setup-java`
5.6.0, `cache` 6.1.0, `upload-artifact` 7.0.1, `gradle/actions` 6.2.0,
`gitleaks-action` 3.0.0 — pinned to those exact tags, not floating majors,
matching R1's spirit even though the dependency policy's letter is scoped
to Gradle coordinates). First push should be watched.

## What I considered and rejected

- **Kotlin/AGP/Gradle latest-and-greatest (Gradle 9.x, AGP 9.x, Kotlin
  2.4.x — all genuinely current as of today, I checked the live release
  feeds)** — rejected in favor of the versions above. Kotlin 2.1.21
  matches what the architect's spike already proved works; AGP 8.9.3 +
  Gradle 8.11.1 is a documented-compatible pairing that supports
  `compileSdk 36`. Chasing the newest of everything on day one of a
  reproducibility-first project buys risk (untested compatibility
  matrix) for no benefit anyone asked for.
- **A composite `includeBuild` for the check tasks instead of
  `buildSrc`** — rejected. `buildSrc` is the conventional, simpler
  mechanism for "custom Gradle task types this build needs and nothing
  else needs," and there is no second consumer that would justify a
  standalone included build.
- **AAB (Android App Bundle) output** — not built. ADR 0010 already
  decided this (no ABI splits exist per ADR 0002, so an AAB buys nothing
  while sideloading needs a plain APK). Nothing for me to add here.
- **`android.app.Activity` vs. AndroidX `ComponentActivity` for the
  placeholder** — went with plain `Activity`. Zero AndroidX dependency
  for a screen that will be deleted wholesale in Stage 1B; adding
  `androidx.activity` now to save a few lines on throwaway code isn't a
  good trade against R5's "the bar is high."
- **Fully qualifying the manifest activity name vs. changing the Kotlin
  package to match the Android namespace** — went with fully qualifying
  (`gravitris.app.MainActivity` in the manifest) rather than moving the
  Kotlin source into `nl.brainbuilders.gravitris`. Keeps the `gravitris.*`
  package convention shared with `:core-sim` (matches `contracts.md`'s
  `package gravitris.game`) while the reverse-domain `applicationId`
  stays a normal, separate concern. Cost one explicit `R` import and a
  code comment; a real Lint failure (`MissingClass`) caught the mismatch
  before I'd have found it any other way.
- **A Makefile target that intentionally breaks a check to prove it
  fails** — rejected as a permanent target; too easy for that to rot into
  "the thing that mutates your working tree unexpectedly." Did it by hand
  instead, captured the output above, and documented the recipe in
  `docs/operations.md` for anyone who wants to repeat it.

## Open questions / things I'm not fully settled on

- **Brief conflict check, as you asked.** I read `.team/brief.md` on both
  `chore/architecture` and `chore/ux-spec`. They don't conflict — the
  `chore/ux-spec` copy is a strict superset (product renamed Squish →
  Gravitris, Gate 2 approval note, the sideload/publishing-constraint
  section, the `allowBackup`/S-1 nuance, the deferred reaction-time
  accommodation). `chore/architecture`'s copy just predates that later
  editing pass. Nothing here changed anything I built; flagging only
  because you asked me to say so if anything looked material.
- **The `team log` CLI writes to the real `/work/.team/journal.md`
  regardless of which worktree/branch you run it from** — it hardcodes
  `WORK=/work`. My journal entry landed in the shared checkout, not on
  this branch. Worth knowing before the next merge: `.team/journal.md`
  will likely need reconciling the same way you mentioned the handoff
  numbering already does.
- **`androidx-core-ktx` version in the catalog is unused** (see above) —
  not a real open question, just flagging so it doesn't look like an
  oversight when Stage 1B's frontend engineer goes looking for it.
- **AGP 8.9.3 vs. a same-week newer patch** — I pinned to the latest
  stable *AGP 8.9.x* at the time I checked (8.9.3). If a security-relevant
  AGP release lands before Stage 1 starts, that's a normal, reviewable
  version-catalog bump, not a redo of this work.
- I did not attempt to install or start an emulator at all (per the
  recommendation above), so I have no first-hand data point on how bad
  the software-rasterizer experience actually is in this specific
  container — the recommendation rests on the structural argument (thin
  correctness surface right now, real device already available), not on
  having tried it and hit a wall.

## Commands for the next person

```sh
make setup && make test && make dev
```
That's the whole loop. `make help` lists everything else.
