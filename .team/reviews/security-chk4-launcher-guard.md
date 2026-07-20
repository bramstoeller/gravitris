# Security review: CHK-4 launcher-exemption guard gap

Author: Security Engineer
Date: 2026-07-20
Branch: `fix/chk4-launcher-guard` (off `main`, tip 4c7e3bc)
Origin: Code Reviewer escalation, PR #1 review
(`.team/reviews/review-build-foundation.md`, "Should fix" §, lines 55-63).

## The finding (was: unfixed everywhere)

`buildSrc/.../CheckMergedManifest.kt` — CHK-4.

Vulnerable path: `check()` CHK-4 block delegating to `isLauncherActivity`.
The old code exempted any exported `<activity>` for which
`isLauncherActivity` returned true, and `isLauncherActivity` returned true
for **every** activity carrying a `category LAUNCHER` — regardless of how
many there were. The check's own failure message said "only the launcher
activity" (singular); the code enforced "every launcher activity."

Exploitation scenario: a dependency at any depth contributes a second
exported `<activity>` with a MAIN/LAUNCHER intent-filter — ad and analytics
SDKs routinely do this on manifest merge. The merged manifest then ships a
foreign, externally-startable entry point (any app on the device can
`startActivity` it, with attacker-controlled extras). CHK-4 waved it through
and the build stayed green.

Impact: silent foreign exported entry point in a shipping build; the guard
manufactured confidence about a property it did not enforce — the same class
as the CHK-1 `uses-permission-sdk-23` bypass.

Severity: **High** (defense-in-depth false negative; no dependency currently
contributes manifest components on `main`, so nothing is exploitable in the
landed build today — which is why this is High, not Critical — but it must be
closed before any component-contributing dependency lands).

## The fix

Structural, not a maintained list — so it does not rot:

1. **Exempt at most one launcher.** Collect the exported launcher activities.
   `singleOrNull()` is the exemption; if there are two or more, none is
   exempt and all are reported. Fail closed on ambiguity — the check cannot
   tell which of two launchers is the app's, so it trusts neither.
2. **Tightened the launcher definition** to require action MAIN **and**
   category LAUNCHER in the same intent-filter (was: LAUNCHER category
   alone). A lone LAUNCHER category with no MAIN action is not a real
   launcher entry point and no longer earns the exemption.
3. Exemption stays scoped to a plain `<activity>`; an exported
   `<activity-alias>` launcher is never exempt.

Rejected: matching the launcher by component name / namespace prefix (the
Code Reviewer's alternative suggestion). Checked the real manifest first —
`app/src/main/AndroidManifest.xml` declares the launcher as
`gravitris.app.MainActivity` while the applicationId is
`nl.brainbuilders.gravitris` (deliberately different packages, documented in
that file). A namespace-prefix match would have **false-flagged the
legitimate build**. That is the trap the task warned about; a regression test
(`...package differs from applicationId`) pins it down.

## Does this change what a legitimate build produces?

No. The shipping app declares exactly one launcher activity with MAIN +
LAUNCHER and no other exported component, so it still passes unchanged. No
escalation needed on that axis.

## Demonstration (break → fail → fix → pass)

Ran `./gradlew :buildSrc:test` in the worktree:

- Fix in place: **29 tests pass**.
- Reverted `CheckMergedManifest.kt` to `origin/main` (old code) keeping the
  new tests: **2 fail** — `CHK-4 fails when a second exported launcher
  activity is present` and `CHK-4 does not exempt an exported activity with
  LAUNCHER category but no MAIN action`. The old code let both through, so
  `assertThrows` fired on nothing. This proves the tests catch the bug rather
  than merely asserting current behaviour.
- Restored the fix: **back to green.**

Cases landed as tests in
`buildSrc/src/test/kotlin/gravitris/buildlogic/CheckMergedManifestTest.kt`,
not as a manual transcript.

## Verdict

CHK-4 gap closed, fail-closed, with tests. This is a fix branch, not a review
of someone else's PR — it needs Code Reviewer approval before merge to `main`.
The two prior CHK-4 false-negative escalations (this one) and CHK-1 (already
landed) are now both closed.

— **Security Engineer**
