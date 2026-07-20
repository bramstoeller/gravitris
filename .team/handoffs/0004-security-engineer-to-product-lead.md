# 0003 — Security Engineer → Product Lead

Date: 2026-07-20 (**revised same day** — see Revision 2)
Branch: `chore/threat-model` (worktree at `/work/.worktrees/threat-model`)
Status: complete, **not merged** — awaiting Code Reviewer per the constitution

> **Numbering note.** `0003` stands; Product Lead confirmed no renumbering.

---

## Revision 2 — direct distribution

The client has decided to **skip the Play Store this phase**: build to
store-ready standard, hand over a signed APK, share it directly, recipients
sideload. Publishing deferred, not cancelled.

This invalidated the core of my signing recommendation, which assumed Play App
Signing. `signing-and-key-custody.md` is **rewritten**; the threat model and
dependency policy have targeted revisions. **The `INTERNET` enforcement,
CHK-1…7 and the dependency policy rules R1–R7 are unchanged** — nothing in them
depended on the distribution channel.

Thank you for verifying the tester figure. Corrected to **12 testers / 14
consecutive days, personal accounts created after 13 Nov 2023, organization
accounts exempt**, with the support URL cited, in the deferred-publishing
section.

### The consequence, stated plainly

**Losing the keystore or its password is now unrecoverable.** Play App Signing
would have held the real key at Google and reset a lost upload key; without a
Play Console account the client holds the only copy of the only key. If it goes,
the app can never be updated under the same identity — recipients must uninstall
and reinstall.

**And uninstalling deletes their scores permanently**, because we disabled Auto
Backup for privacy (S-1). Those two decisions interact and I have said so
explicitly in the client document: no backup means no leak, and it also means no
recovery. I still think disabling backup is right for a local arcade score, but
the client should absorb that key loss now costs their players something, not
just them.

I wrote the custody guidance around a judgement worth flagging to you:
**the realistic risk is loss, not theft.** A solo non-developer with no store
presence is not a target; they are far more likely to lose the key to a dead
laptop or a forgotten password than to an attacker. So the guidance
deliberately **biases toward redundancy over secrecy** — three copies, and the
password stored *with* the file in the offline copies. That inverts standard
secret-handling advice, and I have said in the document why. If you disagree
with that call, it is the one place I would expect pushback.

The backup section is concrete rather than principled, as you asked: exactly
what to back up (file **plus** password **plus** alias — all three, losing any
one loses the app), three named locations, and a **verification procedure that
tests the backup copy rather than the original**, with what success and failure
each look like, run at creation and annually. That last part is the step people
skip and it is the one that decides whether this ever happens.

Also corrected a thing the client may otherwise google and misread: **v3 key
rotation is not a recovery path.** It requires the old key to prove continuity.
It helps with compromise, never with loss.

### Sideloading

Covered in `signing-and-key-custody.md` §9. The framing I landed on: the "unknown
sources" warning is **about the source, not the file** — Android correctly saying
it cannot vouch for something that did not come from a store. Accurate without
being alarming, and it does not train people to click past a real protection.
Includes suggested wording for the client to send, which explicitly gives
recipients permission to decline.

What a recipient can check, honestly ranked: **the permission list on the
installer screen** (strongest — "requests no permissions" is visible to anyone,
on-device, with no tooling), then the certificate fingerprint, **with its limit
stated** — comparing a fingerprint only helps if it arrived through a different
channel than the APK, otherwise an attacker who replaced both matches perfectly.
Its real value is confirming a *later* APK came from the same person as the
first. Ultimately the control is trust in the sender, and I said so rather than
implying more.

### One upside, genuinely

Leaving Play **improves** one thing. Google is no longer re-signing the app, so
the bytes the user installs are the bytes we built. Reproducibility now extends
all the way to the device instead of breaking at upload, which materially
increases what the supply-chain controls buy. It is the one aspect of this
decision that cut in our favour and it is worth using if anyone asks why they
should trust a sideloaded APK.

### Data Safety

Marked **DEFERRED**, not deleted, in threat model §10 — with the three
preconditions that must hold for it to be truthfully filled in later (CHK-1
green, CHK-3 green, no §9 SDK added).

### One thing that got worse and is not in the documents as a task

With no store there is **no crash dashboard and no analytics — the team is blind
to field problems by construction.** The only signal is a user telling the client
something broke. That is the accepted cost of the privacy posture, but it
*raises* the pressure behind the crash-reporter tripwire rather than lowering
it. I have noted it in both documents. **Worth expecting that conversation
post-release rather than being surprised by it.**

### Also flagged, not security — for the Architect/DevOps

Sideloading needs a **single universal APK** containing every ABI, where an App
Bundle would have shipped per-device splits. With a native NDK solver that means
bundling `arm64-v8a`, `armeabi-v7a` and `x86_64` in one file. **The brief's ~5MB
APK target was implicitly an App Bundle figure and will not survive contact with
this.** Not my call and not a security issue — but somebody should re-baseline
that number deliberately rather than discover it at release, and decide whether
to drop `x86_64` and 32-bit ARM.

### Files changed in this revision

| File | Change |
| ---- | ------ |
| `docs/security/signing-and-key-custody.md` | **Rewritten** for self-managed signing, backup/verification procedure, sideloading, deferred-Play checklist |
| `docs/security/threat-model.md` | §10 Data Safety → deferred with preconditions; **new §11** on direct distribution; §12 renumbered; S-1 and assets table updated |
| `docs/security/dependency-policy.md` | Reproducible-builds caveat inverted into the upside; R6 sharpened for the blind-in-the-field problem |

Everything below this line is the original handoff, still accurate except where
Revision 2 supersedes it.

---

## Headline

**I can confirm the narrowness you asked me to verify. The application-level
attack surface is genuinely empty, and that is the finding**, not a failure to
look hard enough. No authentication, no authorization, no untrusted parser, no
transport, no PII, no server. I did not pad the documents to justify the
staffing.

Three things are real, and all three are about the build rather than the running
app: the supply chain, keeping `INTERNET` out of the *merged* manifest, and
signing key custody.

**One finding you did not ask about and should read.** Your framing — that the
absence of `INTERNET` is a strong checkable guarantee that no telemetry can
leak — is *almost* right, and I want to be precise because the brief leans on
it publicly.

### Finding S-1 — Android Auto Backup, Medium

`android:allowBackup` **defaults to true**. With it on, the Android platform
copies app-private storage, including `SharedPreferences`, to the user's Google
Drive. This happens **outside the app process and does not require the app to
hold `INTERNET`**.

So: absence of `INTERNET` is kernel-enforced and does guarantee *the app cannot
open a socket*. It does **not**, on its own, guarantee *no data leaves the
device*. Right now, with default settings, some would.

The data at stake is a high score and two booleans going to the player's own
Google account, so the real-world harm is negligible. The reason it matters is
that success criterion 5 and the Data Safety declaration both rest on the
stronger claim. Fix is two manifest attributes (`allowBackup="false"` plus
`dataExtractionRules`), enforced by CHK-3. **Not release-blocking for the
binary; I would treat it as blocking for the store listing copy.** Cost: players
lose their score when they change phones — worth stating in release notes rather
than absorbing silently.

Suggested wording that is true today: *"no network permission; the app cannot
make network requests."* Avoid *"no data ever leaves your device"* until S-1 is
fixed.

**No critical findings. No high findings.** Nothing is exploitable in anything
running, because nothing is running. No escalation needed.

## What I did

Three documents under `/work/docs/security/`, plus one repo fix.

| File | What it is |
| ---- | ---------- |
| `/work/docs/security/threat-model.md` | Assets, entry points, trust boundaries, the local-data verdict, manifest posture, **7 automated checks (CHK-1…7) for DevOps**, out-of-scope, tripwires |
| `/work/docs/security/dependency-policy.md` | Binding rules R1–R7: pinning, lockfiles, checksum/PGP verification, toolchain pinning, the bar for new dependencies. Plus what reproducible builds do and do not buy |
| `/work/docs/security/signing-and-key-custody.md` | **Client-facing**, for handover at release. Written for someone who has never shipped an Android app |
| `/work/.gitignore` | Fixed a real gap — see below |

### On your five asks

1. **Supply chain** — done, `dependency-policy.md`. This is the only High-
   severity risk in the project. The point I want to land: a Gradle plugin
   executes arbitrary code **as root in this container at configuration time**,
   before any test runs. It does not need to ship anything into the APK to be a
   problem. Build-time dependencies are held to a higher bar than runtime ones.
2. **Permissions** — done, and this is the strongest part. See "what I want you
   to actually act on" below.
3. **Signing** — ~~Recommendation: let Google generate the app signing key at
   Play App Signing enrolment~~ **SUPERSEDED by Revision 2.** That advice
   required a Play Console account we will not have. Now self-managed signing
   with a single irreplaceable key, and the recovery risk handled by backup
   procedure instead of by Google. No credentials entered this container; the
   hard rule is unchanged and restated in two documents.
4. **Local data** — verified, **nothing needs protecting**. Score plus two
   settings, in the per-app UID sandbox. The only party who can tamper is the
   device owner, editing their own score, with no leaderboard and no second
   party to deceive. Explicit instruction: **no encryption, no checksums, no
   anti-tamper** — that would be theatre and would contradict the standing
   "keep iteration one simple" constraint. One genuine requirement (REQ-D1):
   parse persisted state defensively so a corrupt file cannot crash launch.
   That is robustness, not security, but it lives on the same code path.
5. **Privacy** — done. Declaration is trivially clean *provided* CHK-1 and
   CHK-3 stay green. Tripwires listed in §9 of the threat model.

### The repo fix

`.gitignore` covered `*.pem`, `*.key`, `id_rsa*` — but **not** `*.jks`,
`*.keystore`, `keystore.properties` or `local.properties`, which are exactly
what an Android signing setup produces. Added.

I audited the full history rather than assuming: **4 commits, no key material
has ever been committed.** Clean. `.gitignore` is a safety net, not the control
— CHK-7 (history secret scan in CI) is the control.

## What I want you to actually act on

**CHK-1 is the deliverable you asked for**, and it needs one thing understood or
it will not work.

The manifest that ships is the **merged** manifest. Any library at any depth can
contribute `<uses-permission android:name="android.permission.INTERNET"/>` and
the merger adds it **silently**. Nobody has to edit our file for your guarantee
to break. **Checking `app/src/main/AndroidManifest.xml` therefore proves
nothing** — this is the trap, and it is where I would expect a well-intentioned
team to think it was covered when it was not.

Two controls, both wanted:

- **Active block** — `<uses-permission android:name="android.permission.INTERNET"
  tools:node="remove"/>` in our manifest. This inverts the failure mode: instead
  of a dependency silently *gaining* network access, it silently *loses* it, and
  a library that genuinely needs the network fails loudly. Which is exactly when
  we want to hear about it.
- **Assert on the merged manifest** — zero `<uses-permission>` elements, wired
  into `check` so it runs on every build. Plus `aapt2 dump permissions` on the
  release artifact as the backstop.

**CHK-1 must fail closed.** If the merged manifest is missing — task ordering, a
renamed AGP intermediates path — it must error, never pass by finding nothing to
inspect. That is the most likely way this gate quietly stops working, and it
would fail silently at the exact moment it mattered.

## What I deliberately did not do

- **Did not review code.** There is none. (I noticed the Architect's
  `spike/solver-budget/` in history; not in my scope and not reviewed.)
- **Did not threat-model multiplayer, leaderboards, cloud save or accounts.**
  One-line tripwires only, per your instruction.
- **Did not write the Gradle/CI implementation.** CHK-1…7 are specified for
  DevOps to implement. Writing them now would conflict with whatever build the
  Architect lands.
- **Did not recommend R8/ProGuard as a security control.** It is a size and
  performance decision. No secret is embedded in the app, so obfuscation
  protects nothing. Flagged explicitly so nobody argues for it on security
  grounds later.
- **Did not propose any at-rest encryption, key derivation or integrity
  checking for local data.** Deliberate — see item 4 above.
- **Did not touch `staffing.md`** — yours to update.

## Considered and rejected

| Considered | Rejected because |
| ---------- | ---------------- |
| Encrypting local score/settings, or HMAC-ing them | Only adversary is the device owner cheating themselves. No leaderboard, no second party. Pure theatre and added complexity against the standing simplicity constraint. |
| Certificate pinning, network security config | No network. Adding a `networkSecurityConfig` would imply networking exists. |
| Allowing patch-level version ranges for "automatic security updates" | Trades a reviewable deliberate bump for an unreviewable automatic one — precisely the mechanism a compromised patch release would use. R1 bans all dynamic versions. |
| Checking only our own `AndroidManifest.xml` for `INTERNET` | Does not survive manifest merging. This was the single most important call in the engagement. |
| ~~Recommending the client self-manage the app signing key~~ | Rejected in Revision 1 as taking custody of an irreplaceable secret for no benefit. **Revision 2: forced on us by the no-Play decision.** The reasoning was sound and the conclusion is now unavailable — which is exactly why the backup and verification procedure had to become concrete rather than principled. |
| A longer document covering OWASP MASVS end to end | Most of it is inapplicable. You asked for one page of true statements; MASVS would have produced ten of padding. |
| Requiring SLSA provenance / signed attestations | Disproportionate for an offline single-player game with a near-empty dependency list. Revisit only if the surface grows. |

## Open questions

1. ~~**Play Console timeline.**~~ **CLOSED** — you verified it (12 testers /
   14 consecutive days, personal accounts post-13 Nov 2023, org accounts
   exempt) and the client responded by deferring publishing entirely. Corrected
   figure and source URL are recorded in `signing-and-key-custody.md` §13 so
   it is there if they revisit.
2. ~~**Android vitals and Data Safety.**~~ **MOOT this phase** — no Play
   Console, nothing to declare. Retained as a precondition in threat model §10
   for whenever publishing resumes.
3. **Does the client understand that key loss now costs their players their
   scores?** Revision 2 raises this from "device migration" to "any key loss".
   I have stated the interaction plainly in the client document, but I would
   like confirmation it was actually read and accepted rather than skimmed —
   it is the one irreversible thing in the project.
4. **Will the client actually do the annual backup verification?** The
   procedure is written to be two minutes and unambiguous. It is still the step
   most likely to be skipped, and it is the difference between an inconvenience
   and an unrecoverable loss. **Consider whether the Product Lead should
   diarise a reminder rather than trusting the document to do it** — this is
   the only control in the project with no automated backstop and no team
   member accountable for it after handover.
5. **Who owns CHK-1…7?** I assumed DevOps. They need the Android SDK in the
   container — it is not installed yet, and `aapt2` is required for CHK-1's
   artifact check, CHK-2 and CHK-4.
6. **Who verifies the handover APK is the one we built?** Under direct
   distribution the client signs an artifact we hand them. Somebody should
   confirm the unsigned artifact they sign is the one CI produced, otherwise
   the reproducibility chain has a manual gap at the last step.

## What I am uneasy about

- **R5/R6 have no automated backstop.** No check can judge whether a dependency
  is *justified*. The mitigation is that any addition shows up as a lockfile
  diff, so the *fact* is always visible in review even though the *judgement*
  cannot be machine-checked. This is the weakest link in the policy and I would
  rather name it than let it read as fully enforced.
- **The realistic tripwire is post-release, not pre-release.** The pressure to
  add a crash reporter arrives *after* launch, when something is wrong and
  nobody can see why. That is exactly when the privacy posture gets discarded
  without a decision being recorded. CHK-1 will catch it mechanically — I flag
  it because the social pressure at that moment will be to disable the check
  rather than respect it. If that conversation happens, it should come back to
  the client, not be settled in a pull request.
- **Verification metadata trusts whatever was downloaded when it was
  generated.** It gives tamper-evidence going forward, not proof the original
  artifact was clean. Documented in R3 so nobody over-trusts it.
- **(Revision 2) The project's biggest remaining risk is now a human habit, not
  a control.** Every other risk here has an automated backstop — CHK-1…7 catch
  the technical ones. Key custody has none, and cannot: it lives on the client's
  machine, after handover, outside anything the team can check. I have made the
  procedure as concrete and as short as I can, but I am uneasy that the single
  unrecoverable failure mode in this project is guarded only by a document
  someone has to actually follow, twice, years apart. That is why open question
  4 asks whether you should own the reminder rather than the document.
- **(Revision 2) I did not verify the Play tester figure myself** — I flagged it
  and you checked it, which worked. Worth noting the general point: the deferred
  Play section in the client document contains policy claims that will drift.
  Anyone reopening that path should re-verify against the cited URL rather than
  trusting the text, and I have said so in the document.

## Suggested backlog items

| Item | Owner | Priority |
| ---- | ----- | -------- |
| Manifest posture: zero permissions, `tools:node="remove"` on `INTERNET`, `allowBackup="false"` + `dataExtractionRules` | Backend/Android | High — before first installable build |
| CHK-1 merged-manifest assertion wired into `check`, failing closed | DevOps | High |
| Dependency locking + `verification-metadata.xml` + wrapper SHA pinning | DevOps | High — cheapest at project start, painful to retrofit |
| CHK-2/4 artifact assertions at release | DevOps | Medium |
| CHK-7 full-history secret scan in CI | DevOps | Medium |
| REQ-D1 defensive parsing of persisted state, with tests | whoever builds persistence | Medium |
| Hand `signing-and-key-custody.md` to client **before** first APK handover — they must generate and *verify backups of* the key before there is anything to sign, not while a release is waiting | Product Lead | High |
| Confirm client has completed §6.3 backup verification, and diarise the annual repeat | Product Lead | High — this is the project's only unrecoverable failure mode |
| Re-baseline the ~5MB APK target for a universal APK carrying all ABIs; decide whether to drop `x86_64` / 32-bit ARM | Architect + DevOps | Medium — not security, flagged in Revision 2 |
| Confirm the artifact the client signs is the one CI produced | DevOps | Medium |
