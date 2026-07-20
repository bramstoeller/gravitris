# 0003 — Security Engineer → Product Lead

Date: 2026-07-20
Branch: `chore/threat-model` (worktree at `/work/.worktrees/threat-model`)
Status: complete, **not merged** — awaiting Code Reviewer per the constitution

> **Numbering note.** No handoff files existed on `main`, `chore/architecture`
> or `chore/ux-spec` when I wrote this. I took `0003` because `gates.md` records
> the dispatch order as Architect, UX Designer, Security Engineer, so 0001 and
> 0002 should fall to them. If they land differently, renumber this file — it
> has no inbound references.

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
3. **Signing** — done. Recommendation: **let Google generate the app signing
   key** at Play App Signing enrolment. The client then only ever holds the
   *upload* key, which Google can reset if lost. That converts the project's
   one unrecoverable failure mode into a support ticket. No credentials entered
   this container; the hard rule is restated in two documents.
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
| Recommending the client self-manage the app signing key | Takes custody of an irreplaceable secret for zero benefit when they have no existing published app. |
| A longer document covering OWASP MASVS end to end | Most of it is inapplicable. You asked for one page of true statements; MASVS would have produced ten of padding. |
| Requiring SLSA provenance / signed attestations | Disproportionate for an offline single-player game with a near-empty dependency list. Revisit only if the surface grows. |

## Open questions

1. **Play Console timeline — this is the one I would chase first.** Google
   requires new *personal* developer accounts to run a closed test with ~20
   testers for 14 continuous days before applying for production access. If
   that still applies it adds **weeks** between "build ready" and "on the
   store", and needs 20 real humans opted into a test track. It may differ for
   organization accounts, which also affects which account type the client
   registers. I have flagged it in the client document as "verify current
   policy" rather than asserting it — Play policy shifts and I did not want a
   stale claim in a handover document. **Worth the client confirming as soon as
   they register, not at the release gate.** Organization accounts also need a
   D-U-N-S number, which can take days.
2. **Android vitals and Data Safety.** Google collects crash/ANR data at
   platform level and surfaces it in Play Console. My understanding is that this
   is Google's own collection and not declarable by us, but I would not state
   that as settled — please confirm against current Play policy wording at the
   release gate.
3. **Does the client accept losing the high score on device migration?** That is
   the cost of fixing S-1. I think it is obviously right for an offline arcade
   score, but it is a product call, not mine.
4. **Who owns CHK-1…7?** I assumed DevOps. They need the Android SDK in the
   container — it is not installed yet, and `aapt2` is required for CHK-1's
   artifact check, CHK-2 and CHK-4.

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

## Suggested backlog items

| Item | Owner | Priority |
| ---- | ----- | -------- |
| Manifest posture: zero permissions, `tools:node="remove"` on `INTERNET`, `allowBackup="false"` + `dataExtractionRules` | Backend/Android | High — before first installable build |
| CHK-1 merged-manifest assertion wired into `check`, failing closed | DevOps | High |
| Dependency locking + `verification-metadata.xml` + wrapper SHA pinning | DevOps | High — cheapest at project start, painful to retrofit |
| CHK-2/4 artifact assertions at release | DevOps | Medium |
| CHK-7 full-history secret scan in CI | DevOps | Medium |
| REQ-D1 defensive parsing of persisted state, with tests | whoever builds persistence | Medium |
| Hand `signing-and-key-custody.md` to client **early**, not at release, because of the Play Console lead time | Product Lead | High |
