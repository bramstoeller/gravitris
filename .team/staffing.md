# Staffing

Which roles are staffed for this product, how many of each, and why. Zero is a
valid number — record the roles deliberately *not* staffed and the reason.

Decided by the Product Lead with the Architect after Gate 1, revisited at every
gate.

## Architect's recommendation — 2026-07-20 (for Product Lead to decide)

**Two builders, a designer, a tester, a build engineer.** Five working heads plus
standing review. The product is a single-mode game with one hard technical seam;
padding beyond this buys coordination cost, not throughput.

| Role | Count | Rationale |
| ---- | ----- | --------- |
| backend-engineer | 1 | Owns `:core-sim` — solver, contacts, coverage bands, clear rule, losing condition, scoring. Pure Kotlin, no Android, fully JVM-testable. The meatiest and most subtle job in the product, and cleanly bounded by the contract in `/work/docs/contracts.md`. The spike has already de-risked the hard part. |
| frontend-engineer | 1 | Owns `:app` — GL ES 3.0 renderer, procedural shaders, gesture input, haptics, insets, settings, dev tuning panel. **This is the largest single job** and the one carrying the now-largest unmeasured risk (fragment shader cost, ADR 0009). One head, because splitting it would put a seam inside the renderer where none belongs. If anything balloons, the second head goes here first. |
| ux-designer | 1 | Already engaged and working in parallel. Needs the varying contract (ADR 0007) and the edge-to-edge inset constraint (ADR 0010) now, before frontend work starts. |
| qa-engineer | 1 | Unusually high value on this product. ADR 0006's determinism makes physics testable by seeded replay on the JVM in milliseconds — "does the stack jitter" becomes an assertion on kinetic energy, not an eyeball judgement. Also owns the hands-on feel testing that Milestone 1 exists for. |
| devops-engineer | 1 | Scope deliberately small: reproducible pinned Gradle build, two modules, the `:core-sim` no-Android build check, one-command run and test, signed APK. **No Play Console, no store machinery, no CI beyond what makes the build reproducible** — the store is deferred and the audience is the client. |
| code-reviewer | 1 | Standing. Two conventions here cannot be enforced by a compiler and will erode without review: the read-only `SimState` arrays (ADR 0008) and the no-transcendentals / no-concurrency determinism rules (ADR 0006). |
| architect | consulted | Gate 2 work is done. Re-engage on structural change or if a boundary needs crossing. |

### Deliberately **not** staffed

| Role | Count | Why not |
| ---- | ----- | ------- |
| data-engineer | **0** | There is no data. Personal best, settings and a cached quality tier in `SharedPreferences` — three values. Staffing a data engineer for three key-value pairs would be padding, and I would rather say so plainly. |
| security-engineer | **0 further** | Already completed a design-time pass; their no-`INTERNET` merged-manifest check and `allowBackup=false` are folded into ADR 0010. Re-engage once at the release gate. The product has no network, no accounts, no PII and no third-party SDKs, so the attack surface is close to nil by construction. |
| tech-writer | **0 for now** | With the store deferred, the first release audience is the client and people they share the APK with directly. That needs a README and sideload instructions — small enough for DevOps or the Product Lead to carry. Re-staff if the client decides to publish and store copy is needed. |
| second backend / second frontend | **0** | The two modules meet at one narrow contract. A second head on either side would spend more on coordination across that seam than it recovers. Revisit only if Milestone 1 shows the shader work is bigger than one person. |

*(Original template table below, for the Product Lead to fill as decided.)*

| Role | Count | Rationale |
| ---- | ----- | --------- |
