# Review: chore/build-foundation (PR #1)

Verdict: approve-with-comments
Range: main..origin/chore/build-foundation (tip c5a4c4a; last code commit 3880a94)
Reviewer: Code Reviewer
Date: 2026-07-20

Merge target: `main`. This is the Stage 0 build scaffold everything else needs
on trunk. Reviewed as a merge-to-`main` candidate.

## Merge verification (verify, don't trust)

- The merge of `chore/build-foundation` into today's `main` conflicts on **docs
  only** — a single `.gitignore` comment/rule divergence (main forked long ago;
  build-foundation carries an older copy). Resolved as a union that keeps the
  detailed build-foundation comments plus main's `.claude/settings.local.json`
  ignore and the wrapper-jar exception. After resolution the merged tree's
  **code, build files, CI, and buildSrc are byte-identical to the reviewed
  branch** (`git diff origin/chore/build-foundation <merge>` over all code paths
  is empty). No code conflict exists.
- The merge result was pushed to `verify/main-plus-foundation` and the CI
  workflow (`make test` + `make build` + two-clean-build reproducibility check,
  cold Android SDK) was dispatched against it. **Result: GREEN**
  (run 29765966695, completed/success) — a green branch and a green merge are
  different things, so the merge itself was run, not just the branch.
- The branch tip's own last code commit (`3880a94`) is green in CI
  (workflow_dispatch 2026-07-20 17:20). The two commits after it (`c5a4c4a`,
  the coroutines handoff) are docs-only.

## Blocking

None. The merge builds, the tests pass, the manifest the product actually ships
is clean, and the scaffold does what it claims.

## Should fix (tracked, non-blocking for this merge)

These are guard **false negatives** — defense-in-depth weaknesses in the merged-
manifest checks. They do not block PR #1 because Stage 0 ships only an empty
`MainActivity` with **no dependencies contributing manifest entries**, so no real
permission or exported component can slip in on `main`-after-#1. They must be
closed before any dependency that contributes components/permissions lands.
Escalated to the Security Engineer.

- `buildSrc/.../CheckMergedManifest.kt:93` (CHK-1) — the scan matches the literal
  tag `uses-permission` only, via `getElementsByTagName("uses-permission")`.
  Failure: a dependency contributing
  `<uses-permission-sdk-23 android:name="android.permission.INTERNET"/>` (a real,
  distinct permission-granting element; minSdk 29 so it is granted on every
  supported device) ships INTERNET while CHK-1 reports zero permissions → green.
  **Already fixed downstream**: `fix/chk1-sdk23-bypass` rewrites this to a
  `startsWith("uses-permission")` prefix match, and that fix is an ancestor of
  both `feat/app-shell` and `feat/mechanic` — it lands when the game lands.
  Verified. No action needed beyond landing the game via the sequence below.

- `buildSrc/.../CheckMergedManifest.kt:131` (CHK-4) — `isLauncherActivity`
  exempts *every* activity carrying `category LAUNCHER`, but the check's own
  message claims "only the launcher activity" (singular). Failure: a dependency
  contributing a second exported activity with a LAUNCHER intent-filter (ad/
  analytics SDKs do this) ships a foreign exported entry point and passes green.
  **Not fixed anywhere in the stack** — verified still present in `feat/mechanic`
  (`:191`). Fix: assert exactly one launcher and/or that it matches the expected
  component name; add a test with a second LAUNCHER activity. Backlog item +
  Security Engineer escalation.

## Notes (non-blocking)

- `scripts/setup-android-sdk.sh` — only the cmdline-tools zip is integrity-pinned
  (published SHA-1). `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`
  are fetched by `sdkmanager` with no repo-side checksum, resting on HTTPS +
  Google's signed repo manifest. Consistent with common practice, but weaker than
  the repo's otherwise-strict verification-metadata/lockfile regime. Note, not a
  blocker.
- `CheckNoAndroidDependency` source-import scan only covers `.kt` under
  `src/main/kotlin` / `src/test/kotlin`. A Kotlin file under `src/main/java`, or a
  `.java` file, would evade the import regex. Low severity — the
  dependency-resolution check remains the real defense.
- CHK-4 does not detect implicit export (no explicit `android:exported`), but
  with targetSdk 36 AGP hard-fails a component that has an intent-filter and no
  explicit `exported`, so the platform closes this. Latent assumption worth a
  comment.

## What is good

- Dependency verification is real and strict: `verify-metadata=true`,
  `verify-signatures=true`, 324 components / 580 sha256 checksums, no
  trust-all escape hatch.
- Locking is complete: per-module `gradle.lockfile`s, `settings-gradle.lockfile`,
  and buildSrc has its **own** `dependencyLocking { lockAllConfigurations() }`
  because the root `allprojects {}` block does not reach the separate buildSrc
  build — a subtle correct call.
- Wrapper defended two ways: `distributionSha256Sum` +
  `validateDistributionUrl=true`, plus `gradle/actions/wrapper-validation` in CI
  against Gradle's known-good list.
- The checks run against the AGP `SingleArtifact.MERGED_MANIFEST` (not a
  hardcoded intermediates path), use namespace-aware `getAttributeNS`, fail
  closed on missing/empty/unparseable manifest, and aggregate all failures.
- `CheckNoAndroidDependency` treats `UnresolvedDependencyResult` as a hard
  failure and matches banned groups by exact/prefix, not substring — the
  `com.pandroid` / `android:android:99` tests pin this down.
- `make test` runs `:buildSrc:check` explicitly, with a comment explaining that
  plain `check` builds buildSrc classes but never runs its tests — without this
  the guard tests would silently stop running while CI stayed green.
- Tests assert real behaviour (offending names appear in messages, failures
  aggregate, variant echoed, true-negative cases) — not implementation trivia.
- Release build is unsigned, no keystore in the container, gitleaks runs over
  full history (CHK-7). No secrets.

— **Code Reviewer**
