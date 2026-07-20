# Security review — CHK-1 permission allowlist (`feat/app-shell`)

Reviewer: Security Engineer
Date: 2026-07-20
Scope: `fcded24..fd87783` on `feat/app-shell` — the change from "CHK-1 asserts
zero permissions" to "CHK-1 asserts an allowlist of exactly `VIBRATE`, with
`INTERNET` banned unconditionally"
Requested by: Product Lead, because the change modifies a security control the
Security Engineer owns and blocks merge until signed off.

## Verdict

**Approved, conditional on `fix/chk1-sdk23-bypass` merging into
`feat/app-shell` first.**

The allowlist change itself is sound and I would have made the same call. But
hardening it uncovered a **High** finding — a complete bypass of CHK-1 that
predates this change and is my own specification error. It is fixed on
`fix/chk1-sdk23-bypass` (commit `4cc81a0`).

| Question asked | Answer |
| -------------- | ------ |
| Did fail-closed survive the refactor? | **Yes** for the paths it covered. But the covered element set was wrong — see S-2. |
| Is `INTERNET` genuinely unconditional? | **Yes, verified.** Independent code path, does not consult the allowlist. |
| Would `ACCESS_NETWORK_STATE` fail loudly? | **Yes, verified.** |
| Can the allowlist be widened casually? | **It was a one-line edit. Now it is not** — see "Widening". Still not fully gated; stated honestly below. |
| Does the client-facing doc need updating? | **Yes, and it did.** Done on `docs/permission-posture`. |

## S-2 — `<uses-permission-sdk-23>` bypassed CHK-1 entirely (High, fixed)

**This is my error, not the frontend engineer's.** My CHK-1 specification said
"assert the merged manifest contains zero `<uses-permission>` elements". That is
exactly what was implemented, correctly. The element set is what was wrong.

### The vulnerable path

`CheckMergedManifest.check()` scanned:

```kotlin
val permissions = manifestElement.getElementsByTagName("uses-permission")
```

`getElementsByTagName` matches the tag name exactly. Android has a sibling
element that requests a permission just as effectively:

```xml
<uses-permission-sdk-23 android:name="android.permission.INTERNET" />
```

It requests the permission on API 23+. **`minSdk` is 29** (`gradle/libs.versions.toml`),
so it applies to every device the app supports. This is a total bypass, not a
partial one.

### Exploitation scenario

Identical to the one CHK-1 was written to stop, with one word changed. Any
transitive dependency — at any depth, in a manifest nobody on the team reads —
contributes the `sdk-23` spelling. The manifest merger adds it silently. CHK-1
scans for `uses-permission`, finds only `VIBRATE`, and reports success. The APK
ships with `INTERNET`.

The `tools:node="remove"` active block in `app/src/main/AndroidManifest.xml`
does not help: it targets `uses-permission`, so the `sdk-23` element survives
the merge untouched.

### Impact

The app gains network capability while every control we built says it has not.
The client continues telling each person they hand the APK to that it "has no
internet access at all" — a claim they make personally, face to face, under
direct distribution. CHK-1 exists specifically because the client asked for that
guarantee to be *enforced rather than intended*; this made it intended again
while looking enforced, which is worse than having no check, because it
manufactures confidence.

### Verification — demonstrated, not argued

Injected `<uses-permission-sdk-23 android:name="android.permission.INTERNET"/>`
into `app/src/main/AndroidManifest.xml` and ran the real Gradle task against the
real merged manifest.

**Pre-fix code, injected manifest:**

```
> Task :app:checkDebugMergedManifest
BUILD SUCCESSFUL in 724ms
```

**Post-fix code, same injected manifest:**

```
FAILURE: Build failed with an exception.
  - CHK-1: merged manifest declares 1 permission(s) outside the allowlist:
    <uses-permission-sdk-23> android.permission.INTERNET. ...
  - CHK-1: merged manifest declares android.permission.INTERNET. This is never
    permitted — the brief's no-telemetry guarantee is kernel-enforced by the
    absence of this permission (docs/security/threat-model.md §5).
```

Manifest restored afterwards; `:app:checkDebugMergedManifest` and
`:app:checkReleaseMergedManifest` both green on the clean tree.

### Fix

Match every element whose tag name begins with `uses-permission`, rather than
the literal tag, and report the tag name in the failure so the reader knows
which form appeared. Prefix-matching rather than enumerating known variants is
deliberately fail-closed: a future platform version adding another
`<uses-permission-*>` form is caught by default rather than missed until someone
updates a list.

Covered cases, all verified against a faithful standalone port of the scan
logic, plus the two real Gradle runs above:

| Case | Result |
| ---- | ------ |
| `VIBRATE` only (the legitimate manifest) | passes |
| `<uses-permission>` `INTERNET` | fails |
| `<uses-permission-sdk-23>` `INTERNET` | fails |
| `<uses-permission>` `ACCESS_NETWORK_STATE` | fails |
| `<uses-permission>` `READ_EXTERNAL_STORAGE` | fails |
| `<uses-permission-sdk-23>` `CAMERA` | fails |
| `<uses-permission>` with no `android:name` | fails |
| hypothetical `<uses-permission-sdk-99>` `INTERNET` | fails |

## Fail-closed — verified for the remaining paths

The properties I was emphatic about in the original spec, re-checked against the
implementation rather than the comment claiming them:

| Path | Behaviour | Verdict |
| ---- | --------- | ------- |
| Merged manifest missing or zero-length | explicit `GradleException` | **holds** |
| Manifest does not parse as XML | explicit `GradleException` | **holds** |
| No root element | explicit `GradleException` | **holds** |
| AGP artifact provider unresolvable | `RegularFileProperty.get()` throws | **holds** |
| Task not wired to `check` | `onVariants { }` registers per variant and `check` `dependsOn` it — verified on both `debug` and `release` | **holds** |
| Hardcoded intermediates path drifting across AGP versions | avoided: bound to `SingleArtifact.MERGED_MANIFEST` via the variant API | **improved on my spec** |

That last row is worth calling out: binding to the AGP variant API rather than
the intermediates path I named in the spec is **better than what I asked for**,
and removes the most likely cause of the check silently ceasing to run.

## `INTERNET` is genuinely unconditional

Confirmed by reading the code, not the comment. `declaresInternet` is computed
during the scan and checked on its own path:

```kotlin
if (declaresInternet) { failures += "... never permitted ..." }
```

It does not consult `ALLOWED_PERMISSIONS`. Adding `INTERNET` to the allowlist
does not permit it — the build still fails. This is exactly right, and it is the
single most important property in the check. The frontend engineer got this
right without being asked to.

## Widening the allowlist

**Before:** `ALLOWED_PERMISSIONS` was a `Set<String>` in a private companion
object. Adding a permission was a one-line edit that reads as noise in a diff.

**Now:** it is a `Map<String, String>` of permission to justification, and the
task **fails closed if any justification is blank**. Widening requires stating
why, in the same change, which makes it legible to a reviewer skimming the diff.
The threat model §5 carries a matching table that must be updated in the same
change; code and table out of sync is a review failure.

**What this does not do, stated plainly:** it does not stop a determined author.
Nothing in the repository can. It removes the *silent* path, not the deliberate
one. The control that would genuinely gate this is a CODEOWNERS entry over
`buildSrc` plus branch protection requiring code-owner review — which only takes
effect once merges go through pull requests on `origin` rather than locally.
**I deliberately did not add a CODEOWNERS file**: with local merges and no
GitHub accounts for the AI roles it would enforce nothing, and a control that
looks like enforcement but is not is precisely the theatre I object to
elsewhere. Recorded as a follow-up for when the workflow moves to PRs.

## The `VIBRATE` permission itself

Approved. Not relitigating the haptics — the reasoning was accepted by the
Product Lead and it is sound.

`android.permission.VIBRATE` is **normal**, not dangerous: granted at install,
no runtime prompt, no data access, no network reachability. The worst it can do
is buzz the device. It does not weaken the no-network guarantee, which rests
entirely on the absence of `INTERNET`. It is not declarable data collection and
would not affect a future Play Data Safety form.

## Consequence for the client-facing documentation

This is the part that mattered most and it is not in the code.

`signing-and-key-custody.md` §9 told recipients that checking "requests no
permissions" on the installer screen was the strongest thing they could verify
on-device without tools. **That sentence became false.** It has been rewritten,
along with the suggested message the client sends to people they share the APK
with, which contained the same claim.

Two accuracy points that were wrong in a way worth recording, because the
obvious correction is also wrong:

1. **A normal permission does not appear on the install screen at all.** Android
   surfaces only dangerous permissions. So a recipient checking the installer UI
   will see nothing — and would wrongly conclude the app requests nothing.
   Simply changing "no permissions" to "one permission" would have left the
   reader unable to reconcile the document with what their phone shows them.
2. **Reading the complete permission list requires a tool** (`aapt2 dump
   permissions`), not the on-device installer screen.

The document now leads with **"no internet access"** rather than "no
permissions": it is the claim that carries the privacy weight, it is
kernel-enforced, it is still exactly true, and it is verifiable with the same
tool. The old claim's strength came from being checkable by a layperson; the new
framing is honest about needing a tool, which is worse for the client and
correct.

## Follow-ups

| # | Item | Owner | Priority |
| - | ---- | ----- | -------- |
| 1 | Merge `fix/chk1-sdk23-bypass` into `feat/app-shell` **before** it merges to `main` | Product Lead | **Blocking** |
| 2 | Unit tests for `CheckMergedManifest` — `buildSrc` has no test source set, so the fail-closed property is asserted by comment and by my manual runs, not by CI. The eight cases in the table above are the suite. | DevOps | High |
| 3 | CODEOWNERS over `buildSrc` + branch protection, once merges move to PRs | DevOps | Medium |
| 4 | `aapt2 dump permissions` assertion on the release artifact (CHK-1's release backstop) — the merged-manifest gate is in place, the artifact gate is not | DevOps | Medium |
| 5 | Product name: these documents say **Squish**; the code, `applicationId` and ADRs say **Gravitris**. A client-facing document naming a different product than the app is a handover defect. Not mine to resolve. | Product Lead | Medium |

Follow-up 2 is the one I am least comfortable leaving. The check that guards the
product's headline guarantee has no automated test of its own, and this review
is the evidence that exactly that kind of check can be wrong for months without
anyone noticing.
