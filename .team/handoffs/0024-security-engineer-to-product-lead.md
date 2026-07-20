# Handoff 0024 — Security Engineer → Product Lead

Branch: `fix/chk4-launcher-guard` off `main`. Not merged.
Commits: cd4bd65 (fix), fa0b4bf (docs). Range `main..fix/chk4-launcher-guard`.

## What I did

Closed the CHK-4 guard gap the Code Reviewer escalated (PR #1 review,
`.team/reviews/review-build-foundation.md` lines 55-63): `isLauncherActivity`
exempted **every** exported activity with a LAUNCHER category, so a merged-in
second launcher activity (ad/analytics SDKs contribute these) shipped a
foreign exported entry point green. Same class as the CHK-1 sdk-23 bypass.

- `buildSrc/.../CheckMergedManifest.kt`: exempt **at most one** launcher
  (`singleOrNull()`); two or more → none exempt, all reported (fail closed on
  ambiguity). Tightened the launcher definition to require action MAIN **and**
  category LAUNCHER together. Structural rule, no maintained list.
- `buildSrc/.../CheckMergedManifestTest.kt`: 4 new CHK-4 cases (+1 regression
  guard). 29 tests total, green. Demonstrated break→fail→fix→pass — the two
  bug-catching tests fail against `origin/main`'s old code.
- `docs/security/threat-model.md`: CHK-4 row sharpened to the single-launcher,
  fail-closed rule.
- Full record: `.team/reviews/security-chk4-launcher-guard.md`.

## What I deliberately did not do

- Did **not** match the launcher by component name / namespace prefix (the
  Code Reviewer's alternative). Checked the manifest first: launcher is
  `gravitris.app.MainActivity`, applicationId is `nl.brainbuilders.gravitris`
  — a namespace match would false-flag the legitimate build. Rejected with a
  regression test. This is why the fix is "exactly one launcher", not "the
  named launcher".
- Did not change any app manifest or build output. The shipping app has one
  launcher and no other exported component, so it passes unchanged — no
  legitimate build is broken, nothing to escalate on that axis.
- Did not merge. Needs Code Reviewer approval.

## For the next agent

- CHK-1 (landed) and CHK-4 (this branch) are the two escalated merged-manifest
  false negatives; both are now closed. No known CHK gap remains open.
- The residual, accepted limitation: if a build ever legitimately needs two
  launchers, that is now a deliberate edit to the threat-model CHK-4 row +
  code, by a security engineer — not something a dependency can introduce
  silently. That is the intended posture.

## Uneasy about

Nothing exploitable in the current landed build (no component-contributing
dependency on `main`), so this is High, not Critical, and does not block a
release that ships today's `main`. It must land before any such dependency
does.

— **Security Engineer**
