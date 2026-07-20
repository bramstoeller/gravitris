# DevOps Engineer → Product Lead: chore/build-foundation (PR #1) green

Placeholder number — Product Lead owns the sequence, per `.team/handoffs/README.md`.

## What I did

PR #1 was red on CI ("13 artifacts failing dependency verification"). Iterated
against real CI runs, not a simulated cold cache, per instruction. Commits on
`chore/build-foundation` (pushed directly, PR #1's branch), in order:

1. `70fc784` — the last 3 of the original 13 verification failures:
   `org.slf4j:slf4j-api`/`slf4j-parent` 1.7.30. The group has a trusted-key
   entry whose signing key cannot be resolved from any key server in this
   sandboxed environment (identical failure mode to ~60 other keys already in
   `<ignored-keys>` in this file) — added it there, plus explicit sha256
   checksums for the three artifacts, matching the file's existing pattern.
2. `dead62a` — three more issues that only became visible once the above let
   CI reach `:app:lintReportDebug` for the first time:
   - `ANDROID_HOME` vs `ANDROID_SDK_ROOT`: the GitHub-hosted `ubuntu-latest`
     runner ships its own preinstalled SDK and exports `ANDROID_SDK_ROOT` for
     it, conflicting with our pinned one. Fixed in both the workflow (every
     step already setting `ANDROID_HOME`) and the Makefile (so a developer's
     own stray `ANDROID_SDK_ROOT` can't cause the same failure locally).
   - `org.jetbrains.kotlinx:kotlinx-coroutines-{bom,core-jvm}`: **the root
     project's plugin classpath resolves this to a different version
     (1.6.4 or 1.8.0) across otherwise-identical cold builds.** Root-caused
     in a follow-up commit (`3880a94`), after the coordinator asked me not
     to leave it merely documented: two different things depend on this
     artifact at two different exact versions (no ranges) — Gradle 8.11.1's
     own *bundled* Kotlin (`kotlin-compiler-embeddable`/`-runner:2.0.20`,
     used internally to compile this project's `.gradle.kts` scripts) wants
     1.6.4; this project's declared Kotlin Gradle Plugin
     (`kotlin-compiler-embeddable`/`-runner:2.1.21`, used to compile this
     project's own `.kt` sources) wants 1.8.0 — confirmed from both
     versions' published POMs. `./gradlew buildEnvironment` against a fresh
     cold cache only ever showed *one* of these two subtrees present per
     invocation, never both, which is why conflict resolution doesn't just
     deterministically pick 1.8.0 every time: it never sees both requesters
     at once to have to choose between them. Did not force a single
     resolution — the root project's plugin classpath (`plugins{}`'s own
     synthesized one) has no documented `resolutionStrategy` hook for this,
     and forcing it blind risked trading a well-understood, fully-
     checksummed two-outcome situation for a worse, less-understood one.
     Both versions' checksums are recorded (see the comment in
     `gradle/verification-metadata.xml`) so verification passes either way.
   - `buildSrc`'s `:jar` step prints "No valid plugin descriptors were found
     in META-INF/gradle-plugins" — confirmed harmless (buildSrc exposes
     plain Task classes, not registered plugin ids) and documented in place.
3. `3503240` — a test source set for `buildSrc` (the thing the Security
   Engineer said they were least comfortable leaving, after the
   `<uses-permission-sdk-23>` CHK-1 bypass on PR #3 survived a review of
   code that had zero tests). 25 tests across `CheckMergedManifestTest` and
   `CheckNoAndroidDependencyTest` — see the commit message for what each
   covers. **Wired into `make test` as `./gradlew check :buildSrc:check`,
   not folded into plain `check`**: buildSrc is its own separate build, and
   the root project's `check` only builds buildSrc's classes for the plugin
   classpath — it never runs buildSrc's own tests. I confirmed this the hard
   way (dry-run before the fix showed no `buildSrc:test` in the task graph
   at all) — worth knowing, because it means `make test` could stay green
   while silently never running these.

PR #1 CI is green as of run `29759026824`. Verified against several
genuinely cold `GRADLE_USER_HOME`s each time, not a simulated cold cache.

**PR #1's `mergeable` status still shows `CONFLICTING` on GitHub.** I checked
this is stale, not real: `git merge-tree $(git merge-base origin/main
origin/chore/build-foundation) origin/main origin/chore/build-foundation`
shows zero `CONFLICT` markers. Whoever merges should just try — GitHub
usually recomputes this fresh at merge time regardless of what the UI shows.

## What I deliberately did not do

- Did not force a single resolution for the kotlinx-coroutines version split
  once root-caused (see above) — no supported hook to do so on this specific
  classpath, and an untested hack risked a worse, harder-to-diagnose failure
  in exchange for tidiness on something already fully checksummed either way.
- Did not review PR #3 beyond the buildSrc/CHK-1 scan, as they specifically
  asked (comment posted on the PR).
- Did not chase the byte-for-byte reproducibility CI job — it already
  existed (added at `ce0f552`/`0018b19`, before my dispatch started), the
  dispatch note that it was "still owed" was stale.

## What the next person needs to know

- If a *fourth* dependency-verification gap shows up in the root plugin
  classpath on some future cold build, the pattern to follow is: reproduce
  with a genuinely empty `GRADLE_USER_HOME` (not a simulated one), read the
  dependency-verification-report.html for the real reason (usually "key
  couldn't be downloaded"), then add both an `<ignored-key>` entry (short
  16-hex key id, alphabetically sorted) *and* a `<component>` checksum entry
  — a checksum alone does not suffice if a `<trusted-key>` for that group
  already exists, since Gradle still requires the key to resolve unless it's
  explicitly ignored. This tripped me up on the slf4j fix before I found the
  right combination.
- `pkill` should not be used in scripts run in this container's sandbox — it
  appears to abort the *entire* tool invocation silently (not just return
  nonzero) rather than just signalling the matched process. Discovered while
  building the emulator scripts (separate PR #4); use `ps` + `awk` + `kill
  <pid>` against PIDs you tracked yourself instead.

## Considered and rejected

- Considered just re-running `--write-verification-metadata` blind to
  regenerate the whole file. Did this for the slf4j/coroutines gaps
  specifically (it's the same tool that generated everything else in this
  file, confirmed by the "Generated by Gradle" origin tags), but did not use
  it as a first resort for the whole file — a full regeneration risks
  changing unrelated entries. Ran it, then diffed to confirm it only added
  what I expected, before keeping the result.

## Open questions

- Now that the kotlinx-coroutines split is root-caused rather than mysterious
  (two legitimate, exact-pinned requesters at different versions, both
  checksummed), is it still worth a backlog item, or does the code comment
  in `gradle/verification-metadata.xml` suffice? I lean the comment is
  enough now — it's understood and bounded, not an open gap — but didn't
  want to unilaterally decide it needs no further tracking either.
