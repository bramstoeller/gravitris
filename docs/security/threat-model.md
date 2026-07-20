# Threat model — Squish

Status: draft for Gate 2
Date: 2026-07-20
Author: Security Engineer

## Summary

**The application-level attack surface is genuinely empty, and that is the
finding.** Squish is an offline, single-player Android game with no network
permission, no accounts, no user data beyond a high score and two settings, no
server, no third-party SDKs and no content pipeline. There is no authentication,
no authorization, no untrusted input parser, no output encoding, no transport
and no PII. The categories a security review normally spends its time on do not
exist here, and inventing threats for them would be padding.

Three real risks remain, and they are all about the *supply chain and the build*
rather than the running app:

| # | Risk | Severity | Control |
| - | ---- | -------- | ------- |
| 1 | A compromised or malicious Gradle plugin / dependency executes code at build time or ships code in the APK | **High** | `docs/security/dependency-policy.md` — pinning, lockfile, checksum verification, wrapper pinning |
| 2 | `INTERNET` permission silently reintroduced by a dependency's merged manifest, breaking the "no telemetry" guarantee | **Medium** | Automated merged-manifest assertion — CHK-1 below |
| 3 | Local game data leaves the device via **Android Auto Backup**, which does not require `INTERNET` and is **on by default** | **Medium** | `android:allowBackup="false"` — CHK-3 below |

Risk 3 is the non-obvious one and is expanded in §4. It is the reason the
"no network permission" guarantee, as currently worded in the brief, is not
quite true as stated.

**The app is no longer permission-free.** Impact haptics require
`android.permission.VIBRATE`, so CHK-1 has moved from "zero permissions" to a
governed allowlist of exactly that one, with `INTERNET` banned unconditionally.
The allowlist, what that change cost, and how it may be widened are in §5.
Hardening CHK-1 uncovered **finding S-2 (High, fixed)** — a complete bypass of
the check via `<uses-permission-sdk-23>` — recorded in §5.

Signing key custody is handled separately in
`docs/security/signing-and-key-custody.md` — it is client-facing and must be
handed over **before** the first APK, not at release.

Since the client has chosen direct APK distribution over the Play Store, key
custody is no longer merely a handover formality: without Play App Signing there
is no key reset path, so **losing the keystore is the project's single
unrecoverable failure mode.** It is not in the table above because it is not a
defect in the product — it is a risk that lives entirely on the client's
machine, after handover, where no build check can reach it. §11 covers what the
distribution change alters in both directions.

---

## 1. Assets

Ranked by what an attacker would actually want.

| Asset | Where it lives | Worth attacking? |
| ----- | -------------- | ---------------- |
| **The signing key** | Client's custody, never in this container | **Yes.** The only high-value asset in the project. Compromise lets an attacker ship malware as the client, to the client's installed base, with no user-visible warning. Under direct distribution there is **no reset path** — see §11. |
| **The build pipeline** | This container, CI | **Yes.** Code injected here is signed by the legitimate key and inherits all its trust. |
| **Player's device integrity** | The user's phone | Indirectly — via 1 or 2. |
| High score, settings | App-private storage on device | **No.** See §4. |
| Game source, shader parameters | This repo | No. Nothing secret; cloning risk is commercial, not security. |

There are no credentials, no personal data, no money and no records whose
integrity matters to anyone but the individual player.

## 2. Entry points

The complete list of places untrusted input can enter the running app:

| Entry point | Trust | Notes |
| ----------- | ----- | ----- |
| Touch events | Untrusted in principle, meaningless in practice | Coordinates from the OS. Cannot be malformed in a way the solver cares about. Normal input validation, not a security control. |
| Locally stored score/settings file | **Semi-trusted** | Writable by the user on a rooted or debug device. See §4 — must be parsed defensively, but the impact is nil. |
| Launcher intent | Untrusted | The activity must not accept extras that change behaviour. |
| System callbacks (lifecycle, config change) | Trusted | OS-originated. |

That is the whole list. There are **no** HTTP handlers, forms, uploads,
webhooks, queues, deep links, content providers, exported services, broadcast
receivers, IPC surfaces, CLI arguments or environment inputs. If any of those
appear later, this document is out of date — see §6.

## 3. Trust boundaries

Only two exist, and neither is at runtime:

1. **Upstream package repositories → our build.** Maven Central, Google's Maven,
   and the Gradle distribution itself cross into a context where they execute
   arbitrary code as root in this container and ship bytes to users' phones.
   This is the boundary that matters. Controls: `dependency-policy.md`.
2. **Our build → the user's device.** Crossed by the signing key. Controls:
   `signing-and-key-custody.md`.

The conventional boundary — *user input → privileged code* — is absent, because
there is no privileged code and no privilege to escalate to.

## 4. Local data — verified, and one real finding

**Stored:** personal best score (integer), reduced-motion flag (boolean),
colourblind palette selection (enum). That is all the brief calls for.

**Verdict: this data needs no protection, and adding any would be wasted
complexity.** Reasoning, stated explicitly so it can be challenged:

- Android's per-app UID sandbox already prevents other apps reading it on a
  non-rooted device. That is the platform's job and it does it.
- The only party who can tamper with it is the device owner, editing *their own*
  score.
- There is no leaderboard, no server and no other player, so a forged score
  harms nobody and deceives nobody. The player has cheated themselves.
- Therefore: **no encryption, no obfuscation, no integrity checksum, no
  anti-tamper.** Any of those would be security theatre against an adversary
  whose only victim is themselves, and would contradict the standing
  "keep iteration one simple" constraint.

The one requirement: **parse it defensively.** A corrupt, truncated or
hand-edited file must not crash the app on launch. This is a robustness
requirement, not a security one, but it lives on the same code path:

> **REQ-D1.** Reading persisted state must handle absent file, empty file,
> malformed content, and out-of-range values by falling back to defaults and
> continuing. Covered by unit tests. Owner: whoever implements persistence.

### Finding S-1 — Auto Backup exfiltrates local data without `INTERNET` (Medium)

`android:allowBackup` defaults to **true**. With it enabled, the Android
platform — not the app — copies app-private storage, including
`SharedPreferences`, to the user's Google Drive, and restores it onto new
devices. This happens entirely outside the app process and **does not require
the app to hold `INTERNET`**.

Impact: low in absolute terms (the data is a score and two flags, going to the
user's own Google account). But it directly falsifies the project's headline
claim. The brief's success criterion 5 rests on "no data collection"; a reviewer
or a privacy-minded user who inspects the manifest and finds backup enabled will
reasonably conclude the claim was not checked. It also means that if a future
feature ever stores something sensitive, it is silently off-device from day one.

Under direct distribution this matters more, not less: the client will be
vouching for the app personally to each person they share it with (§11), and an
inaccurate claim made face to face is harder to walk back than a store listing.

Fix — both attributes, on `<application>`:

```xml
<application
    android:allowBackup="false"
    android:dataExtractionRules="@xml/data_extraction_rules"
    ... >
```

with `data_extraction_rules.xml` excluding everything (belt and braces for
API 31+, where `dataExtractionRules` supersedes `allowBackup` for cloud backup
and device-to-device transfer). Cost: the player loses their high score when
they change phones. For an offline arcade score with no cloud save in scope,
that is the correct trade and should be stated in the release notes rather than
quietly accepted.

Enforced by CHK-3.

## 5. Manifest posture — required, and enforced

The app requests **exactly one permission**. The required end state of the
*merged* manifest:

- **Only permissions on the allowlist below.** Anything else — from our manifest
  or any dependency's, at any depth — fails the build.
- `android.permission.INTERNET` **absent**, actively blocked (below), and banned
  **unconditionally**: the ban does not consult the allowlist, so adding
  `INTERNET` to the allowlist does not permit it.
- `android:allowBackup="false"` and restrictive `dataExtractionRules` (S-1).
- `android:allowBackup="false"` and restrictive `dataExtractionRules` (S-1).
- `android:debuggable` absent from release builds (the Android Gradle Plugin
  handles this; CHK-2 verifies it rather than trusting it).
- The launcher activity is the only exported component, with `android:exported`
  set explicitly (mandatory from API 31).
- No `<provider>`, `<service>`, or `<receiver>` unless a later item justifies
  one in writing.

### The permission allowlist

| Permission | Protection level | Why | Approved |
| ---------- | ---------------- | --- | -------- |
| `android.permission.VIBRATE` | **normal** | Impact haptics scaled to mass and fall speed. `Vibrator.vibrate()` requires it; the permission-free `View.performHapticFeedback()` has no amplitude control and discards the energy ramp that makes blocks read as heavy. | Security Engineer, 2026-07-20, `.team/reviews/security-chk1-allowlist.md` |

`VIBRATE` is **normal**, not dangerous: granted at install, no runtime prompt,
no data access, no network. The worst it can do is buzz the device. It does not
weaken the no-network guarantee, which rests entirely on the absence of
`INTERNET`.

**CHK-1 originally asserted zero permissions.** That was a bright line, and I
preferred it. An allowlist is a slope, and this is the honest accounting of what
was lost and what was kept:

- **Kept:** a dependency contributing *any* permission still turns the build
  red, which was the property the client actually asked to have enforced. The
  guarantee was never "zero" for its own sake — it was "no permission arrives
  that nobody decided on".
- **Kept, and strengthened:** `INTERNET` is banned on a code path that does not
  consult the allowlist. Adding it to the allowlist does not permit it. This was
  verified by test, not assumed — see the review record.
- **Lost:** the check no longer proves its own correctness by being trivially
  empty. It now depends on a list staying short.

**Governance — how the allowlist may be widened.** Adding an entry is a Security
Engineer decision, recorded here and in `.team/reviews/`. Three things make a
silent widening harder:

1. **Each entry must carry a justification string.** The build fails if any is
   blank, so widening cannot be a one-word edit — the author must state why, in
   the same diff, which makes the change legible to a reviewer skimming it.
2. **This table must be updated** in the same change. A code-only edit leaves
   the two out of sync and should be treated as a review failure.
3. **`INTERNET` is unreachable by this route at all**, so the worst case for a
   careless widening is a non-network permission.

Be clear about the limit: **none of this stops a determined author**, and I am
not going to claim it does. It removes the *silent* path, not the deliberate
one. The control that would genuinely gate this is a CODEOWNERS entry over
`buildSrc` plus branch protection requiring code-owner review — which only bites
once merges go through pull requests on `origin` rather than locally. Recorded
as a follow-up; it is not in place today.

### Why "absent from our manifest" is not sufficient

The manifest that ships is the **merged** manifest. Any library, at any depth in
the dependency graph, can contribute `<uses-permission android:name="android.
permission.INTERNET"/>` from its own manifest, and the merger will add it
silently. Nobody has to edit our file for the guarantee to break. Checking
`app/src/main/AndroidManifest.xml` therefore proves nothing.

### Finding S-2 — `<uses-permission-sdk-23>` bypassed CHK-1 (High, fixed)

**This was my error, not the implementer's.** The original CHK-1 specification
said "zero `<uses-permission>` elements", and that is precisely what was built.
It is the wrong element set.

Android has a sibling element that requests a permission just as effectively:

```xml
<uses-permission-sdk-23 android:name="android.permission.INTERNET" />
```

It requests the permission on API 23 and above. **`minSdk` is 29**, so that is
every device this app supports — a total bypass, not a partial one. A dependency
contributing the `sdk-23` form would have been granted `INTERNET` on 100% of the
install base while the build stayed green and the client kept telling people the
app had no network access.

Exploitation is the same one-line manifest entry in any transitive dependency
that the original check was written to stop; the `sdk-23` spelling simply made
it invisible.

**Verified, not asserted.** Injecting the line above into the real manifest: the
pre-fix check reported `BUILD SUCCESSFUL`; the fixed check fails with both the
allowlist violation and the unconditional `INTERNET` ban. Evidence in
`.team/reviews/security-chk1-allowlist.md`.

**Fix:** match every element whose tag name begins with `uses-permission`,
rather than the literal tag. Prefix-matching is deliberately fail-closed — a
future platform version adding another `<uses-permission-*>` form is caught by
default instead of being missed until someone remembers to update a list.

The generalised lesson, which is the same one that motivated this whole section:
**enumerate the property, not the spelling.** "No permission is requested" is
the property; `<uses-permission>` was only one way to spell it.

Two controls, and we want both:

**Active block.** Declare the removal in our manifest so the merger strips any
contributed copy:

```xml
<uses-permission android:name="android.permission.INTERNET"
    tools:node="remove" />
```

This inverts the failure mode. Instead of a dependency silently *gaining*
network access, it silently *loses* it — and a library that genuinely needs the
network will fail loudly, which is exactly when we want to hear about it.

**Assertion.** Trust nothing; verify the artifact. See CHK-1.

### What the absent permission does and does not guarantee

State this precisely, because the client is leaning on it:

- **It does guarantee** the app process cannot open a network socket. Android
  grants the `inet` supplementary group only to apps holding the permission, so
  this is kernel-enforced, not policy-enforced. Any attempt fails with a
  `SecurityException`. This is genuinely strong, and it is checkable by anyone
  who inspects the APK — including the client, a reviewer, or a journalist.
- **It does not guarantee** that no data leaves the device. Three paths bypass
  it, all of which need code we did not intend to be there, except the first:
  - Android Auto Backup — **no malicious code required, on by default.**
    Finding S-1.
  - Handing a URL to another app via an implicit `ACTION_VIEW` intent. Requires
    no permission at all. Would have to be introduced by injected or careless
    code.
  - IPC to an installed app that does hold `INTERNET`. Same.

The honest formulation for the README and store listing is: *"the app has no
network permission and cannot make network requests."* That is defensible and
verifiable. *"No data ever leaves your device"* is only true once S-1 is fixed,
and should not be published before then.

## 6. Automated checks — for DevOps to implement

These are the deliverable. Each is a build-time or test-time gate, not advice.
All are cheap and fast; none needs a device or emulator.

> **Tooling dependency:** the Android SDK is not yet installed in this
> container. CHK-1/2/4 need `aapt2` (SDK build-tools). CHK-1 has a
> merged-manifest variant that needs only the Android Gradle Plugin, and that
> should be the primary gate; the artifact check is the release backstop.

| ID | Check | Where | Fails when |
| -- | ----- | ----- | ---------- |
| **CHK-1** | Assert every element in the **merged** manifest whose tag starts with `uses-permission` names a permission on the allowlist (§5), and that `INTERNET` is absent regardless of the allowlist | Gradle task `check<Variant>MergedManifest` wired into `check`, bound to the AGP variant API artifact `SingleArtifact.MERGED_MANIFEST` rather than a hardcoded intermediates path; plus `aapt2 dump permissions` on the release artifact at the release gate | Any off-allowlist permission is present, from our manifest or any dependency's, in any `uses-permission*` spelling — or `INTERNET` in any form |
| **CHK-2** | Assert release artifact is not debuggable and has no `android:testOnly` | `aapt2 dump badging` at release | Debug flags leak into a release build |
| **CHK-3** | Assert merged manifest has `allowBackup="false"` and a `dataExtractionRules` reference | Same task as CHK-1 | S-1 regresses |
| **CHK-4** | Assert no exported component other than the launcher activity | Same task as CHK-1 | A dependency contributes an exported service/receiver/provider |
| **CHK-5** | Dependency lockfile and `verification-metadata.xml` are current and verify | Gradle, inherent | Any dependency or plugin changes without a reviewed lockfile update |
| **CHK-6** | Gradle wrapper JAR matches pinned SHA-256 | `distributionSha256Sum` + CI wrapper validation | Wrapper tampering |
| **CHK-7** | Secret scan over the **full history**, not the working tree | CI, on every push | A keystore, key or credential is ever committed |

CHK-1 is the one the client specifically asked to be enforced rather than
intended. It runs on every `check`, so reintroducing `INTERNET` — deliberately
or via a dependency — turns the build red rather than shipping quietly.

**CHK-1 must fail closed.** If the merged manifest is missing (task ordering,
a renamed intermediates path across AGP versions), the check must error, never
pass by finding nothing to inspect. This is the most likely way for this gate to
silently stop working.

## 7. Repository hygiene — one gap found

`.gitignore` currently ignores `*.pem`, `*.key` and `id_rsa*`. It does **not**
ignore the file types an Android signing setup actually produces:

```
*.jks
*.keystore
keystore.properties
local.properties
```

No such file exists in the repo or its history today — this is preventative, and
it is fixed on this branch. It matters because the hard rule is that no keystore
enters this container: `.gitignore` is the last line of defence if someone ever
ignores that rule, and right now it would not catch the standard filename.

Note that `.gitignore` is a convenience, not a control — CHK-7 is the control.

## 8. Explicitly out of scope

| Not modelled | Why |
| ------------ | --- |
| Multiplayer, leaderboards, accounts, cloud save | Do not exist; explicit non-goals in the brief. Modelling them would be speculative fiction. |
| Ads, IAP, analytics, crash reporting SDKs | Explicit non-goals. Their arrival is a **tripwire**, not a threat — see §9. |
| Server-side anything | There is no server. |
| Reverse engineering / cloning of the APK | A commercial concern, not a security one. No secret is embedded in the app, so there is nothing for obfuscation to protect. R8 minification is a size and performance decision for DevOps, not a security control, and should not be argued for as one. |
| Cheating / score tampering | §4. No second party is deceived. |
| Physical device compromise, rooted devices, malicious OEM builds | Outside our control and outside the app's trust model. A rooted device can read app-private storage; the data does not warrant defending against that. |
| The player's Google account security | The client's and user's domain. |

## 9. Tripwires

One line each, as requested. Each of these **invalidates part of this document
and requires the Security Engineer to be re-staffed** before it ships.

- **Any crash reporter, analytics or ads SDK** → adds `INTERNET`, breaks CHK-1,
  and forecloses the clean Data Safety declaration described in §10 if the
  client ever publishes. This is the single most likely way the privacy posture
  dies, because it usually arrives as a two-line dependency someone adds to
  debug a field crash. **Direct distribution raises the pressure**, not lowers
  it: with no store analytics and no crash dashboard, the team is blind to field
  problems by construction, and the temptation to fix that with an SDK is
  correspondingly stronger. Being blind is the accepted cost of the privacy
  posture, not a defect to be engineered away.
- **Leaderboards or cloud save** → introduces a server, accounts, identity,
  authorization and PII in one step. Full threat model required, not an update.
- **Any content loaded from outside the APK** → creates the first real untrusted
  parser in the product.
- **A WebView, for any reason** (privacy policy, credits, help) → drags in a
  browser engine and usually `INTERNET`. Render such text natively.
- **Deep links or an exported component** → creates an IPC attack surface where
  none exists.
- **Play Games Services / Play Billing** → accounts and money.

## 10. Data Safety declaration — DEFERRED

**Not in scope this phase.** The client has decided to skip the Play Store and
distribute a signed APK directly, so there is no Data Safety form to file. This
section is retained rather than deleted, because the posture it describes is
what we are building to and it must remain true for the deferred option to stay
open.

**What must be true before this can be truthfully filled in**, if the client
later publishes:

- **CHK-1 green** — no permission in the merged manifest beyond the §5
  allowlist. `VIBRATE` is not declarable data collection and does not affect the
  form. If any dependency has contributed `INTERNET` in the meantime, "no data
  collected" is no longer automatically defensible and every network path needs
  auditing before the form is signed.
- **CHK-3 green** — `allowBackup="false"`. S-1 is the detail most likely to make
  the declaration inaccurate without anyone noticing, since it is a platform
  default rather than something we chose.
- **No SDK from §9 has been added** — a crash reporter or analytics library
  turns this from a nil return into a real disclosure obligation.

Given those, the declaration would be:

- **Data collected: none.** No data types, no purposes.
- **Data shared: none.**
- **Data encrypted in transit:** not applicable — no data is transmitted.
- **Data deletion:** not applicable — no data leaves the device; uninstalling
  removes it.

> Google's Android vitals collects crash and ANR data at the platform level and
> surfaces it in Play Console. My understanding is that this is Google's own
> platform collection and is not declarable by us, but this should be confirmed
> against current Play policy wording at the time of publishing rather than
> taken from this document. Moot while distribution stays direct — there is no
> Play Console to collect it.

Key custody and the deferred-publishing checklist are in
`signing-and-key-custody.md` §13.

## 11. Direct distribution — what changes

The client distributes a signed APK directly; users sideload it. Security
consequences, both directions:

**Weaker:**

- **No key reset path.** Play App Signing would have let Google hold the real
  signing key and reset a lost upload key. Without a Play Console account the
  client holds the only copy of the only key, and losing it means the app can
  never be updated under the same identity. This is now the project's single
  unrecoverable failure mode. Fully covered, with backup and verification
  procedure, in `signing-and-key-custody.md` §2 and §6. It is a custody problem,
  not a code problem, and nothing in the build can mitigate it.
- **No store review, no Play Protect vetting at scale, no automatic updates.**
  If a bad build ships there is no recall mechanism — only telling people.
- **Users must enable "install from unknown sources"**, a real protection they
  are being asked to relax. Guidance on explaining this honestly, and on what a
  recipient can reasonably check, is in `signing-and-key-custody.md` §9.

**Stronger:**

- **The artifact the user installs is the artifact we built.** Google is not
  re-signing anything, so reproducibility now extends all the way to the
  device — an independent party can rebuild from source and confirm nothing was
  inserted. Under Play App Signing that chain was broken at upload. This
  materially increases what our supply-chain controls actually buy.
- **The permission list is inspectable by any recipient.** The claim is now
  "one normal permission, and no network access" rather than "no permissions" —
  see `signing-and-key-custody.md` §9 for how to state that honestly. Two
  caveats worth knowing: `VIBRATE` is a *normal* permission, so most install
  screens will not display it at all, and reading the complete list needs a tool
  (`aapt2 dump permissions`) rather than the on-device installer screen. The
  strong, tool-verifiable, kernel-enforced claim remains **the absence of
  `INTERNET`**, and that is the one to lead with. CHK-1 is what keeps it true,
  and it is now a user-facing claim rather than an internal one.

CHK-1 through CHK-7 are unaffected by the distribution change and stand exactly
as specified in §6.

## 12. What blocks release

Per my remit: critical and high findings block release.

- **No critical findings.**
- **S-2 (High) — found and fixed, does not block.** `<uses-permission-sdk-23>`
  bypassed CHK-1 entirely. Fixed on `fix/chk1-sdk23-bypass`; **that branch must
  merge into `feat/app-shell` before `feat/app-shell` merges to `main`.** If it
  does not, the release ships with the product's headline guarantee unenforced,
  and this becomes blocking.
- **No other high findings in the app.** The High-severity supply-chain risk is
  a standing risk managed by the dependency policy, not an open defect — it does
  not block, provided the policy's controls are actually implemented.
- **S-1 (Medium)** does not formally block the binary, but it must be fixed
  before any public claim that no data leaves the device. That claim is in the
  brief's success criteria and, under direct distribution, is likely to be made
  verbally by the client to each person they share the APK with — which is
  harder to correct later than a store listing. Fix it before first handover.
  *(Implemented on `feat/app-shell`: `allowBackup="false"` plus
  `dataExtractionRules` are present and asserted by CHK-3.)*

S-2 was exploitable in code that was about to merge, not in anything running.
Escalated to the Product Lead immediately rather than held for a gate.
