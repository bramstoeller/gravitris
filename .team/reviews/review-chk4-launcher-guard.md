# Review: fix/chk4-launcher-guard (PR #9)

Verdict: approve
Range: `origin/main` `101aea9` .. `origin/fix/chk4-launcher-guard` `d42d483`

Closes the CHK-4 gap I escalated: `isLauncherActivity` exempted *every* exported
activity carrying category LAUNCHER, so a dependency contributing a second
MAIN/LAUNCHER activity (ad/analytics SDKs do this on manifest merge) shipped a
foreign exported entry point while the build stayed green. Verified the fix is
fail-closed and does not change what a legitimate build produces.

## Blocking

None.

## Verified

- **Fail-closed on ambiguity.** The exemption is now `launcherActivities
  .singleOrNull()`: exactly one exported MAIN+LAUNCHER activity is exempt; zero
  or two-or-more exempts *none*. I traced each case and confirmed against the new
  tests: two exported launchers → the `size > 1` branch fails and names both,
  and the reporting loop skips them (`element in launcherActivities`) so they are
  reported once, not doubly. A dependency can no longer smuggle a second launcher
  in green.
- **The exemption is genuinely narrower.** `isLauncherActivity` now requires
  action MAIN **and** category LAUNCHER in the *same* intent-filter (via
  `filterHasChild`), not category LAUNCHER alone. An exported activity with a
  lone LAUNCHER category is reported like any other exported component.
- **`activity-alias` is never exempt.** `launcherActivities` is populated only
  from `getElementsByTagName("activity")`, which does not match `activity-alias`;
  an exported MAIN/LAUNCHER alias falls through to the reporting loop. Confirmed
  by the dedicated test asserting `activity-alias .AliasEntry` is reported.
- **A legitimate build is unchanged.** The real merged manifest exports exactly
  one activity — `gravitris.app.MainActivity` with MAIN+LAUNCHER in one filter
  (`app/src/main/AndroidManifest.xml:70-79`). `singleOrNull()` returns it, it is
  exempt, nothing else is exported → passes, as before. Critically the exemption
  keys off the intent-filter, **not** a name/namespace match, which is right: the
  launcher class (`gravitris.app.MainActivity`) deliberately differs from the
  applicationId (`nl.brainbuilders.gravitris`), so a namespace-prefix
  discriminator would have false-flagged the real build. That trap is explicitly
  guarded by a test.
- **Tests pass.** `:buildSrc:test` BUILD SUCCESSFUL, `CheckMergedManifestTest`
  `tests=19 skipped=0 failures=0` (15 prior + 4 new CHK-4 cases). Break→fail→fix
  →pass is demonstrated by the four adversarial cases each asserting the specific
  failure message.
- **Contract kept in sync.** `docs/security/threat-model.md` CHK-4 row is updated
  to state the single-launcher / fail-closed / MAIN+LAUNCHER rule, matching the
  code — not left describing the old behaviour.

## Notes (non-blocking)

- The KDoc's note that implicit export (intent-filter with no explicit
  `android:exported`) is a hard AGP error at targetSdk 36 is correct and worth
  keeping — it is why the check only needs to reason about `exported="true"`.

## What is good

- The fix resists the obvious-but-wrong repair (match the component name against
  the namespace) and documents *why* it would have broken the legitimate build.
  The failure messages name the offending components, and the "deliberate second
  launcher is a change to this threat-model row, not a build edit" framing puts
  the decision where it belongs.

---
*— **Code Reviewer***
