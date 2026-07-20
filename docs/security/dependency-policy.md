# Dependency and supply-chain policy — Squish

Status: draft for Gate 2
Date: 2026-07-20
Author: Security Engineer
Audience: the team. Binding on anyone who adds or updates a dependency.

## Why this is the only security document that constrains day-to-day work

Squish has no network, no accounts and no user data. The one way an attacker
reaches a player's phone is by getting code into our build — through a Gradle
plugin, a Kotlin/AndroidX library, or the Gradle distribution itself — and
having us sign it for them. A Gradle plugin executes arbitrary code as root in
this container at *configuration* time, before any test runs. It does not need
to ship anything into the APK to be a problem; it can read the build
environment, alter the artifact, or modify the source tree.

Everything below exists to make that hard and, failing that, detectable.

## 1. Rules

### R1 — Every version is pinned. No dynamic versions, ever.

Banned everywhere, including plugins and transitive constraints:

```
1.9.+          // banned
latest.release // banned
[1.0, 2.0)     // banned
```

A build whose inputs can change without a commit is not reproducible and cannot
be reviewed. Declare versions in `gradle/libs.versions.toml` (Gradle version
catalog) so there is exactly one place a version is stated and diffs are
reviewable.

Rejected alternative: allowing patch-level ranges for "security updates". It
trades a reviewable, deliberate bump for an unreviewable automatic one — which
is precisely the mechanism a compromised patch release would use.

### R2 — Dependency locking is on, and the lockfile is committed.

```kotlin
dependencyLocking { lockAllConfigurations() }
```

Run `./gradlew dependencies --write-locks` to update. The lockfile pins the
**full transitive graph**, which R1 alone does not — a pinned direct dependency
can still pull a floating transitive one.

**A lockfile change in a diff is a security-relevant change** and gets reviewed
as one, not waved through as noise. If a lockfile moves and no one intended a
dependency change, stop and find out why.

### R3 — Checksum and signature verification is on.

`gradle/verification-metadata.xml`, committed, generated with:

```
./gradlew --write-verification-metadata sha256,pgp <task>
```

Prefer PGP signatures where the publisher provides them, with SHA-256 as the
fallback for artifacts that are unsigned. This is what actually detects a
tampered artifact — a lockfile pins *which version*, a checksum pins *which
bytes*.

Caveat, stated so nobody over-trusts it: generating the metadata trusts whatever
was downloaded at generation time. It gives us **tamper-evidence from that point
forward**, not proof the original artifact was clean. Verification is only as
good as the review that accompanied the first fetch — which is R5.

### R4 — The toolchain is pinned, including the wrapper.

- `distributionSha256Sum` set in `gradle/wrapper/gradle-wrapper.properties`.
  The wrapper downloads and executes a JAR; unpinned, it is the softest target
  in the whole build.
- `gradle-wrapper.jar` validated in CI (`gradle/actions/wrapper-validation` or
  equivalent checksum check against the known-good set) — a modified wrapper JAR
  is a well-documented attack and is invisible in a normal diff, since it is a
  binary blob almost nobody opens.
- JDK pinned via Gradle toolchains; AGP, Kotlin and NDK versions pinned exactly.
- Android SDK build-tools and platform pinned to exact versions in the
  container image, not resolved at build time.

### R5 — Adding a dependency requires a written justification.

This project's dependency list should be close to empty: Kotlin stdlib,
AndroidX, and the test libraries. A custom XPBD solver and procedural shaders
mean there is very little left to import.

Before anything new is added, record in the handoff or an ADR:

1. **What it does that we are not willing to write ourselves.** For a ~5MB APK
   with a custom solver, "saves a few hours" is not sufficient.
2. **Who publishes it**, and is it actively maintained.
3. **Its own transitive footprint** — check the resulting lockfile delta, not
   the README's claims.
4. **Does it add any permission?** Read its manifest. If it contributes
   `INTERNET`, it does not go in, and CHK-1 will stop it regardless.
5. **What happens at build time** — does it register a Gradle plugin, an
   annotation processor, or a bytecode transform? Those run code on our machine
   and are held to a higher bar than a plain runtime library.

Default answer is **no**. The bar rises for anything pulled in at build time,
because a compromised runtime library at least has to ship its payload in the
APK where it can be found; a compromised build plugin does not.

### R6 — Never add a dependency to debug a field problem.

Named separately because it is the realistic failure mode. The pressure to add a
crash reporter arrives *after* release, when something is wrong and the team
wants visibility. That is exactly when the privacy posture is discarded without
a decision being recorded.

**Direct distribution makes this sharper.** With no store, there is no crash
dashboard and no analytics — the team is blind to field problems by
construction, and the only signal is a user telling the client something broke.
That blindness is the accepted cost of the privacy posture, not a gap to be
closed with a dependency. Any such SDK requires a client decision — see the
tripwires in `threat-model.md` §9.

### R7 — No credentials, keystores or signing keys in this container. Ever.

Standing client rule. The build produces **unsigned** artifacts; the client
signs. See `signing-and-key-custody.md`. CHK-7 (history secret scan) is the
enforcement.

## 2. Detecting a compromised dependency

Honestly: we will not catch a competent, targeted attack on a dependency by
reading code. Nobody does. What we can do is make the *common* cases loud, and
make the artifact reconstructible so that a later disclosure can be checked
against what we actually shipped.

| Control | Catches | Misses |
| ------- | ------- | ------ |
| Lockfile diff review (R2) | An unexpected dependency or version appearing at all — the most common signal | A malicious release of a version we deliberately chose |
| Checksum/PGP verification (R3) | Artifact substitution, registry compromise, MITM, retagged releases | A malicious artifact published legitimately by a compromised maintainer |
| No dynamic versions (R1) | Automatic pickup of a bad patch release | Anything we bump on purpose |
| Manifest assertion (CHK-1) | Any dependency that wants network access | A payload that exfiltrates via intents, or does local harm |
| Minimal dependency count (R5) | Most of it, by not being exposed | — |
| Reproducible build (§3) | Tampering *after* source, in the build environment | Anything already in the source or dependencies |
| Advisory scanning (below) | Publicly disclosed CVEs | Anything not yet disclosed |

**Advisory scanning.** Run OSV/OSS-Index against the resolved lockfile in CI,
scheduled weekly as well as on push — a dependency becomes vulnerable while
sitting still, so push-triggered scanning alone will miss it on a project that
may go quiet between milestones. Findings are triaged, not auto-merged: for an
offline game, most CVEs in an AndroidX transitive will be genuinely
unreachable, and a rushed bump is its own risk. Record the triage decision.

**The realistic detection story** is: minimal surface, pinned and verified
inputs, a reproducible artifact, and the ability to answer "did we ship the bad
version?" quickly when an advisory lands. That is proportionate for a game with
no network. It would not be proportionate for something handling money.

## 3. What reproducible builds buy us, and what they do not

Reproducibility is already a project requirement. It is worth being precise
about what it is doing here, because it is easy to over-claim.

**Buys us:**

- **Build-environment tamper-evidence.** Anyone with the tagged source can
  rebuild and compare. If this container, CI, or a developer machine was
  compromised and injected something at build time, the artifacts diverge and
  the injection is visible.
- **A trustworthy answer to "what is in this build?"** Given a released version,
  we can reconstruct the exact inputs — which is what makes advisory triage
  possible after the fact.
- **Debuggability.** A build that differs run to run hides real bugs too.

**Does not buy us:**

- **Any statement about whether the inputs are trustworthy.** A build that
  reproducibly includes a malicious library reproduces the malice perfectly.
  Reproducibility verifies the *transformation*, never the *ingredients*. R1–R5
  are what address the ingredients, and reproducibility is not a substitute for
  them.
- **Protection against a compromised signing key** — that is downstream of the
  build entirely.
- **A byte-identical comparison including the signature.** The signature block
  differs by construction and is applied by the client outside this build.
  Compare the **unsigned** artifact, or compare after stripping signatures.

> **The direct-distribution decision strengthens this.** Under the earlier Play
> plan, Google would have re-signed the app after upload, so what a user
> installed was by definition not the artifact we built — reproducibility gave
> us confidence up to the point of upload and no further. Distributing a
> directly-signed APK removes that break: **the bytes the user installs are the
> bytes we produced**, modulo the client's signature. An independent party can
> now rebuild from source and confirm nothing was inserted anywhere in the
> chain. This is the one place where leaving the Play Store made our security
> position better rather than worse, and it is worth saying out loud because
> everything else about that decision cut the other way.

Practical requirements to actually achieve it: no timestamps or build paths in
outputs, `SOURCE_DATE_EPOCH` respected, deterministic archive ordering, a pinned
container image, and no code generation that embeds a build host, user or date.
DevOps owns making this true and demonstrating it — two clean builds from
scratch producing identical unsigned artifacts, as a CI job rather than a claim.

## 4. Where this policy is enforced

Rules that are only prose get skipped under deadline. Mapping to the checks in
`threat-model.md` §6:

| Rule | Enforced by |
| ---- | ----------- |
| R1, R2 | CHK-5 — build fails if the lockfile is stale or a version is dynamic |
| R3 | CHK-5 — Gradle fails the build on a verification mismatch |
| R4 | CHK-6 — wrapper checksum validation in CI |
| R5, R6 | **Human review only.** No check can judge whether a dependency is justified. This is the weakest link in the policy and should be treated as such at review time. |
| R7 | CHK-7 — secret scan over full history |
| Manifest/permissions | CHK-1 |

R5 and R6 having no automated backstop is the honest gap. The mitigation is that
any new dependency shows up as a lockfile diff (R2), which is visible in code
review — so the *fact* of an addition is always caught even though its
*justification* cannot be machine-checked.
