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
     (1.6.4 or 1.8.0) across otherwise-identical cold builds**, with no
     version range of ours involved anywhere (AGP 8.9.3, Kotlin 2.1.21 are
     both exact-pinned). Reproduced repeatedly against fresh
     `GRADLE_USER_HOME`s, including the *same command twice in the same
     session* resolving differently. **Not fully root-caused** — I suspect
     Gradle's own embedded Kotlin DSL tooling and AGP/KGP each wanting a
     different exact version of this transitive dependency, with which one
     "wins" depending on how much of the classpath graph a given invocation
     realizes, but I did not chase it further than that. Both versions'
     checksums are now recorded (see the comment in
     `gradle/verification-metadata.xml` right above them) so verification
     passes either way, but a third version showing up on some future
     machine/day is not ruled out. Flagging this plainly rather than
     claiming it's solved.
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

- Did not fully root-cause the kotlinx-coroutines nondeterminism. Chasing it
  further (a real fix would likely mean forcing a resolution strategy on the
  plugin classpath, which I didn't find a clean way to do from user code
  given it's the `plugins {}` block's own synthesized classpath, not a normal
  `dependencies {}` block) felt like it was heading toward a much larger time
  sink than the immediate goal (unblock the pipeline) justified. Recorded as
  a comment in the metadata file and here so it isn't silently re-discovered.
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

- Should the kotlinx-coroutines nondeterminism be escalated as its own
  backlog item (a build reproducibility gap, docs/security/dependency-policy
  R2/R3 territory) rather than living only as a code comment? I lean yes,
  but didn't want to create backlog items unilaterally.
