# Review: chore/build-foundation

Verdict: **request-changes**
Range: `493897f..6e4c53a` (5 commits; `main` merge-base `493897f`)
Reviewer: code-reviewer
Date: 2026-07-20

One blocking defect: a genuinely fresh clone cannot build. Everything else is
in good shape, and the two guard checks are real — I re-proved both by hand
rather than taking the handoff's word for it.

---

## Blocking

### B-1. A fresh clone fails `make test` — dependency verification metadata is incomplete

`gradle/verification-metadata.xml` is missing five artifacts that Gradle
resolves on a **cold** cache. With a warm `~/.gradle` (this machine, and the
engineer's own verification) the build is green; from a fresh clone with an
empty `GRADLE_USER_HOME` it fails during `:buildSrc` configuration, before a
single line of code compiles.

**Failure scenario:** anyone who clones this repo into a fresh container — the
CI runner on its first run, or the next engineer — runs `make test` and gets a
hard failure with no game code involved. This violates "trunk always builds".

Reproduced twice. Fresh `git clone` + `GRADLE_USER_HOME=/tmp/gravitris-gradlehome`:

```
=== wrapper jar present in clone? ===
-rw-r--r--. 1 root root 43583 Jul 20 09:49 gradle/wrapper/gradle-wrapper.jar
=== make test (cold gradle home) ===
Downloading https://services.gradle.org/distributions/gradle-8.11.1-bin.zip
.............10%.............20%.............30%.............40%.............50%
.............60%.............70%.............80%.............90%.............100%
Starting a Gradle Daemon (subsequent builds will be faster)

FAILURE: Build failed with an exception.

* What went wrong:
A problem occurred configuring project ':buildSrc'.
> Dependency verification failed for configuration 'classpath'
  One artifact failed verification: kotlinx-coroutines-bom-1.6.4.pom
  (org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.6.4) from repository
  Gradle Central Plugin Repository
  If the artifacts are trustworthy, you will need to update the
  gradle/verification-metadata.xml file.

BUILD FAILED in 52s
make: *** [Makefile:52: test] Error 1

real	0m53.206s
```

Gradle stops at the first failure, so I re-ran a second cold clean-room with
`--dependency-verification=lenient` to enumerate **all** of them:

```
Dependency verification failed for configuration 'classpath'
One artifact failed verification: kotlinx-coroutines-bom-1.6.4.pom ... from repository Gradle Central Plugin Repository
Dependency verification failed for configuration ':buildSrc:kotlinBuildToolsApiClasspath'
2 artifacts failed verification:
  - kotlinx-coroutines-bom-1.6.4.pom (org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.6.4) from repository Gradle Central Plugin Repository
  - kotlinx-coroutines-bom-1.6.4.pom (org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.6.4) from repository MavenRepo
Dependency verification failed for configuration 'classpath'
3 artifacts failed verification:
  - junit-bom-5.10.2.module (org.junit:junit-bom:5.10.2) from repository MavenRepo
  - junit-bom-5.9.2.module (org.junit:junit-bom:5.9.2) from repository MavenRepo
  - kotlinx-coroutines-bom-1.8.0.pom (org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.0) from repository MavenRepo
Dependency verification failed for configuration ':app:debugCompileClasspath'
4 artifacts failed verification:
  - junit-bom-5.10.2.module (org.junit:junit-bom:5.10.2) from repository MavenRepo
  - junit-bom-5.9.2.module (org.junit:junit-bom:5.9.2) from repository MavenRepo
  - kotlinx-coroutines-bom-1.8.0.pom (org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.0) from repository MavenRepo
  - junit-bom-5.11.4.pom (org.junit:junit-bom:5.11.4) from repository MavenRepo
```

**Root cause**, which is worth stating precisely because it explains why it was
missed. For every BOM involved, exactly one of the two metadata forms was
recorded and the other — the one a cold resolve actually needs — was not:

| Component | Recorded in verification-metadata.xml | Needed on cold cache | Status |
| --- | --- | --- | --- |
| `org.junit:junit-bom:5.10.2` | `.pom` | `.module` | missing |
| `org.junit:junit-bom:5.9.2` | `.pom` | `.module` | missing |
| `org.junit:junit-bom:5.11.4` | `.module` | `.pom` | missing |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.6.4` | *(nothing)* | `.pom` | missing |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.0` | *(nothing)* | `.pom` | missing |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.7.3` | `.pom` | `.pom` | OK |

The metadata was generated against a partially-warm cache, so Gradle only
recorded what it had to fetch during that run. `<verify-metadata>true</verify-metadata>`
means `.pom` and `.module` files are verified too, and on a cold cache Gradle
fetches both forms for BOMs published with Gradle Module Metadata.

This is also why handoff claim 23 ("verified from an actual fresh `git clone`
of the branch, reusing only the machine-level caches `/state/android-sdk`,
`/state/.gradle`") did not catch it: reusing `/state/.gradle` is exactly the
condition that hides this. The clone was fresh; the cache was not.

**Required fix.** Regenerate the metadata from a genuinely cold Gradle home:

```sh
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --write-verification-metadata sha256 \
  clean check assembleDebug assembleRelease
```

Then re-verify the same way I did — fresh clone, empty `GRADLE_USER_HOME`,
`make test` — and paste that transcript into the follow-up handoff. Please do
not verify against a warm cache; that is the specific thing that failed here.

Note this would also have turned CI red on its very first run: `actions/cache`
starts empty, and the engineer states (claim 33) the workflow was never
executed. So CI would not have caught it either.

---

## Should fix

### S-1. `CheckNoAndroidDependency` silently ignores dependencies that fail to resolve

`buildSrc/src/main/kotlin/gravitris/buildlogic/CheckNoAndroidDependency.kt`, via
the wiring in `core-sim/build.gradle.kts:51-56`:

```kotlin
configurations.getByName(name).incoming.resolutionResult.allDependencies
    .mapNotNull { result ->
        (result as? org.gradle.api.artifacts.result.ResolvedDependencyResult)
            ?.selected?.moduleVersion
            ?.let { "${it.group}:${it.name}" }
    }
```

An unresolved dependency comes back as `UnresolvedDependencyResult`, the safe
cast yields `null`, and `mapNotNull` drops it. The guard then reports success.

**Failure scenario, reproduced.** I added an Android AAR to `:core-sim`:

```kotlin
implementation("com.google.android.material:material:1.12.0")
```

`com.google.android.material` is not in the banned group list, but it pulls
`androidx.appcompat`/`androidx.core` transitively — so this should be the
guard's headline catch. Instead:

```
> Task :core-sim:checkNoAndroidDependency

BUILD SUCCESSFUL in 2s
4 actionable tasks: 1 executed, 3 up-to-date
EXIT=0
```

The dependency had failed to resolve (an AAR has no JVM-consumable variant),
so the guard saw nothing:

```
compileClasspath - Compile classpath for 'main'.
+--- org.jetbrains.kotlin:kotlin-stdlib:2.1.21
|    \--- org.jetbrains:annotations:13.0
+--- com.google.android.material:material:1.12.0 FAILED
```

**Why this is "should fix" and not blocking:** the overall build still fails
loudly — `:core-sim:build` dies with a variant-matching error naming the
artifact. The Android dependency does not get in. But the guard reports green
on a broken state, which is the "manufactures confidence" failure mode this
check exists to prevent: someone running `checkNoAndroidDependency` alone gets
a clean bill of health.

Suggested fix: handle `UnresolvedDependencyResult` explicitly — fail if the
attempted coordinate matches a banned group, and at minimum never treat an
unresolved graph as a passing one.

### S-2. `verification-metadata.xml` is SHA-256 only; no PGP

`grep -c pgp gradle/verification-metadata.xml` → `0`; `trusted-key` blocks → `0`.

Dependency policy R3 says: *"Prefer PGP signatures where the publisher provides
them, with SHA-256 as the fallback for artifacts that are unsigned."* Kotlin,
JUnit and AndroidX artifacts on Maven Central are PGP-signed, so the preferred
mechanism is available and unused. The handoff describes this as "Checksum
verification", which is accurate but is a quieter divergence from R3 than it
looks. Regenerate with `sha256,pgp` when fixing B-1 — it is the same command.

### S-3. `buildSrc` dependencies are not locked

`dependencyLocking { lockAllConfigurations() }` sits in `allprojects { }` in the
root `build.gradle.kts`, which does not reach `buildSrc` — it is a separate
build. There is no `buildSrc/gradle.lockfile`. R2 requires the full transitive
graph be locked; `buildSrc` pulls the entire AGP 8.9.3 tree and is currently
pinned only by the exact AGP coordinate plus checksums. Notably, B-1's first
and hardest failure is in `:buildSrc` — the least-locked part of the build.

---

## Notes (non-blocking)

- **N-1. `CheckMergedManifest`'s hand-written missing-file message is
  unreachable.** Behaviour is correct — it does fail closed — but via Gradle's
  `@InputFile` validation, which fires before the task action. Pointing the
  property at a nonexistent path gives Gradle's generic error, not the careful
  "CHK-1/3/4 FAIL CLOSED …" text. The empty-file branch *is* reachable and does
  produce it. Worth knowing so nobody debugs the wrong message. Both proven —
  see Verification below.

- **N-2. CHK-4 only catches explicit `android:exported="true"`.** A component
  that is implicitly exported (has an `<intent-filter>`, no explicit attribute)
  is not flagged. Mitigated: `targetSdk 36` makes the explicit attribute
  mandatory, so this cannot compile. Fine as-is; do not let it rot if targetSdk
  ever drops.

- **N-3. ADR 0008 says `:core-sim` depends on "the Kotlin stdlib and nothing
  else"; the guard only bans Android groups.** An arbitrary non-Android
  third-party dependency would pass both layers. That is a wider rule than the
  check enforces. Probably fine deliberately — but it is a gap between the ADR's
  words and the mechanism, and the ADR claims the boundary "cannot erode by
  accident".

- **N-4. CI installs the Android SDK before the cache-restore step.**
  `.github/workflows/ci.yml` — "Install the pinned Android SDK" (line 57) runs
  ahead of "Cache Gradle and Android SDK" (line 62), so the SDK is downloaded
  fresh on every run and the cache entry for it never helps. Reorder.

- **N-5. CHK-2 is not implemented** (release artifact not debuggable / no
  `testOnly`). Reasonably release-gate scope, but it is in the threat model's
  §6 table and is not mentioned in the handoff. I verified it manually against
  the built artifact and it currently holds — see Verification.

- **N-6. Weekly OSV/OSS-Index advisory scan is absent** (dependency-policy §2
  requires it "scheduled weekly as well as on push"); the handoff explicitly
  rules out scheduled jobs. Flagging the contract mismatch, not asking for it
  in Stage 0.

- **N-7. `./gradlew check` alone fails without `ANDROID_HOME`** — only `make`
  exports it. `docs/operations.md` invites people to "run `./gradlew check`
  directly". Minor doc/UX mismatch; `make doctor` covers the diagnosis.

- **N-8. Scaffold files.** `CoreSimBuildScaffold.kt` and its test are honest
  scaffolding — both kdocs say what they are and instruct deletion when Stage 1
  lands. They are pulling real weight right now (they are what makes
  `:core-sim:test` non-empty and prove the `:app` → `:core-sim` link). Not dead
  weight today; worth a backlog item to delete when `physics/` or `game/`
  arrives. **Not a merge blocker** — per the brief, flagging rather than blocking.

- **N-9.** `.team/journal.md` shows as modified in the shared checkout because
  `team log` hardcodes `WORK=/work` regardless of worktree (handoff item 46).
  Not this branch's defect; noting so it is not mistaken for stray edits.

---

## What is good

Genuinely strong work. Specifics worth recording:

- **Byte-for-byte reproducible builds — better than was claimed.** The handoff
  only claims pinning; dependency-policy §3 asks for "two clean builds from
  scratch producing identical unsigned artifacts". I ran it. Two full
  `make clean && make build` cycles:

  ```
  build1 exit=0
  f80b97eff5cdf07c830274bab684f08c3e12dff9fdbce970904f502d22893953  app-debug.apk
  ed74553784a7019646e4e2753924cf01257f91fc4fb9c3add1d1c46389612a15  app-release-unsigned.apk
  build2 exit=0
  f80b97eff5cdf07c830274bab684f08c3e12dff9fdbce970904f502d22893953  app-debug.apk
  ed74553784a7019646e4e2753924cf01257f91fc4fb9c3add1d1c46389612a15  app-release-unsigned.apk
  ```

  Identical. That requirement is met and should be locked in as a CI job.

- **Both guard checks are real.** I broke each one myself; transcripts below.
  The fail-closed path the security engineer specifically demanded — the one
  the handoff asserts but never demonstrated — genuinely errors. CHK-4, also
  never demonstrated in the handoff, genuinely fires.

- **The merged-manifest check is wired the right way**, via
  `variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)` rather than a
  hardcoded intermediates path, and registered per-variant so debug *and*
  release are both covered. That is the AGP-upgrade-proof approach and it is
  the correct call.

- **The `.gitignore` landmine was real and is genuinely fixed.** Confirmed
  independently: the jar is tracked, is not ignored, and survives a fresh clone.

- **The manifest carries both controls the threat model asks for** — the active
  `tools:node="remove"` block *and* the assertion. The handoff never mentions
  the former; it is there and correct.

- **Documentation is honest.** `docs/operations.md` and the handoff state
  limitations plainly rather than rounding them up — including that CI was never
  executed and that no device install was attempted. That candour is what let me
  target verification where it mattered.

---

## Verification performed

Everything below I ran myself on `6e4c53a`. The worktree was returned to a
pristine state after each destructive test (`git status --porcelain` empty,
`git diff 6e4c53a` empty — confirmed).

### Documented commands

| Command | Result |
| --- | --- |
| `make setup` | exit 0 — installed pinned SDK to `/state/android-sdk` |
| `make test` (warm cache) | exit 0, `BUILD SUCCESSFUL in 1s` |
| `make build` (warm cache) | exit 0, `BUILD SUCCESSFUL in 932ms`, both APKs produced |
| `make test` (**cold**, fresh clone) | **exit 1 — see B-1** |

Both guard tasks execute rather than no-op'ing under `make test`:
`:app:checkDebugMergedManifest`, `:app:checkReleaseMergedManifest`,
`:core-sim:checkNoAndroidDependency` all appear as executed.

**Caveat on the warm-cache timings:** 1.2s and 0.98s are not evidence of a
working build from scratch — 34 of 59 tasks came from cache. That is precisely
what motivated the cold clean-room run that found B-1.

### CHK-1 / CHK-3 / CHK-4 — broke all three at once

Added `<uses-permission android:name="android.permission.CAMERA"/>`, set
`allowBackup="true"`, added an exported `<service>`:

```
> Task :app:checkDebugMergedManifest FAILED
* What went wrong:
Execution failed for task ':app:checkDebugMergedManifest'.
> Merged-manifest checks failed for variant 'debug' (docs/security/threat-model.md §6):
    - CHK-1: expected zero <uses-permission> elements in the merged manifest, found 1: android.permission.CAMERA
    - CHK-3: android:allowBackup must be "false", found "true".
    - CHK-4: expected only the launcher activity exported, also found: service gravitris.app.EvilService
BUILD FAILED in 608ms
EXIT=1
```

All three fire, with the launcher activity correctly *not* flagged. Reverted;
re-ran clean: `BUILD SUCCESSFUL in 584ms`.

### Fail-closed — the check the security engineer insisted on

**Merged manifest absent** (property repointed at a nonexistent path):

```
* What went wrong:
A problem was found with the configuration of task ':app:checkDebugMergedManifest' (type 'CheckMergedManifest').
  - Type 'gravitris.buildlogic.CheckMergedManifest' property 'mergedManifest' specifies file
    '.../app/build/does-not-exist/AndroidManifest.xml' which doesn't exist.
    Reason: An input file was expected to be present but it doesn't exist.
BUILD FAILED in 1s
EXIT=1
```

**Merged manifest present but empty:**

```
* What went wrong:
Execution failed for task ':app:checkDebugMergedManifest'.
> CHK-1/3/4 FAIL CLOSED: merged manifest for variant 'debug' is missing or empty at
  .../app/build/does-not-exist/AndroidManifest.xml. This must never be treated as
  'nothing to inspect, so pass' — see docs/security/threat-model.md §6. Likely cause:
  an AGP upgrade moved the merged-manifest task, or task ordering skipped it.
BUILD FAILED in 613ms
EXIT=1
```

**It errors in both cases. It does not pass by finding nothing to inspect.**
This is the requirement threat-model §6 calls out by name, and it holds. See
N-1 on which message you actually get. Reverted; `checkDebug` and
`checkRelease` both green afterward.

### ADR 0008 — transitive detection

The handoff's own example (`androidx.annotation`) does not by itself prove
transitivity, so I checked that the failure names an artifact that is only
reachable transitively. Adding `androidx.annotation:annotation:1.9.1`:

```
> ADR 0008 violation: :core-sim must have no Android dependency, none — see docs/adr/0008-module-boundaries.md.
  Android dependencies found on :core-sim's classpath:
    - androidx.annotation:annotation
    - androidx.annotation:annotation-jvm
  Remove the dependency/import, or move the code that needs it into :app.
EXIT=1
```

`androidx.annotation:annotation-jvm` was never declared — it is reachable only
through the graph. **Transitive detection works** for artifacts that resolve.
For artifacts that fail to resolve, see S-1.

Source-import layer, with `import android.util.Log` in a `:core-sim` file:

```
> ADR 0008 violation: :core-sim must have no Android dependency, none ...
  Android imports found in :core-sim sources:
    - .../core-sim/src/main/kotlin/gravitris/coresim/Probe.kt:3: import android.util.Log
EXIT=1
```

Exact `file:line`, as claimed.

### Wrapper jar (the `.gitignore` fix)

```
$ git ls-files --error-unmatch gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.jar
$ git check-ignore -v gradle/wrapper/gradle-wrapper.jar
not ignored -> OK
$ git cat-file -p HEAD:gradle/wrapper/gradle-wrapper.jar | sha256sum
2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046
$ git cat-file -s HEAD:gradle/wrapper/gradle-wrapper.jar
43583
```

And it is present in a fresh clone (`43583` bytes) — the clean-room run got far
enough to download Gradle 8.11.1, which is only possible with a working
wrapper jar. **The fix is confirmed good.** I could not independently confirm
the jar against Gradle's published checksum list (`services.gradle.org` returned
a 301 to a redirect I did not chase); CI's `gradle/actions/wrapper-validation`
covers this, but that job has never been run — see B-1's closing note.

### Platform baseline and shipped artifact

Verified against the built release APK with `aapt2` from the pinned build-tools,
i.e. against the artifact rather than the source manifest:

```
$ aapt2 dump permissions app-release-unsigned.apk
package: nl.brainbuilders.gravitris
```

**Zero permissions in the shipped APK** — CHK-1 holds on the real artifact, not
just at build time.

```
$ aapt2 dump badging app-release-unsigned.apk | grep -Ei "debuggable|testOnly|package:|sdkVersion"
package: name='nl.brainbuilders.gravitris' versionCode='1' versionName='0.0.1-stage0'
  platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36'
minSdkVersion:'29'
targetSdkVersion:'36'
```

`minSdk 29`, `targetSdk 36`, `compileSdk 36` — matches ADR 0010 and the brief.
No `debuggable`, no `testOnly` (CHK-2's condition currently holds, though it is
not enforced by a check — N-5).

### Not verified, and why

- **CI workflow has never been executed.** I did not run it either — no Actions
  runner here. Given B-1, I expect the first run to fail at `make test`. The
  YAML parses and the action versions are tag-pinned.
- **APK installed on a real device.** No device or emulator in this container.
  Stage 0's "debug APK installable on the Fairphone" is proven as far as
  "installable artifact is produced", not as "installed". Unchanged from the
  handoff's own honest statement.
- **Wrapper jar against Gradle's published known-good checksum** — see above.
- **PGP signature validity** — not applicable; no PGP entries exist (S-2).

---

## What must change before merge

Only B-1. Concretely:

1. Regenerate `gradle/verification-metadata.xml` from an empty
   `GRADLE_USER_HOME` (and take S-2's `sha256,pgp` in the same pass).
2. Prove it: fresh `git clone`, empty `GRADLE_USER_HOME`, `make test` green.
   Paste that transcript.

S-1, S-2 and S-3 are worth fixing in the same pass since they touch the same
files, but I will not hold the merge on them if the Product Lead prefers them
as backlog items. The notes are all backlog or informational.

Re-review needed on the B-1 fix — specifically the cold-cache transcript. I do
not need to re-review the guard checks; they work.
