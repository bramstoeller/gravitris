# 0025 — Architect → Product Lead (cc Backend Engineer, QA Engineer)

**Task:** ADR 0003 described broadphase behaviour the code no longer has, and
three documents cited an "Amendment 3" that was never written. Fix the ADR.
Documentation only. Branch `docs/adr-0003-broadphase` off `main`, PR #14.

## Headline

Both defects were documentation, not code. I reconciled ADR 0003 with the
shipped solver and gave the three dangling "Amendment 3" citations a real
section to resolve to. **No production code changed**, and I confirmed the code
and the *intended* design agree, so there was nothing to escalate.

## What I did, and the commits

Branch off `origin/main` (fast-forwarded past `101aea9` → `a3fea85`; the two
intervening commits are the emulator first-frame-wait fix, PR #12, unrelated to
this and non-conflicting).

- `8d9a531` `docs(adr): match ADR 0003 to per-substep broadphase, add Amendment 3`
  - `docs/adr/0003-contacts-and-stack-stability.md` §1: was "rebuilt once per
    frame." Now states the per-substep rebuild (`grid.build` inside the substep
    loop, matching `XpbdSolver.step`), with the +2.1%/step cost. The original
    per-frame reasoning is preserved verbatim as a marked **superseded** block —
    auditable, not deleted, per our ADR convention.
  - New `## Amendments` → `### Amendment 3` section: the radius/drift/stencil
    coupling at high lattice, the before/after measurements, and the fix.
  - `.team/decisions.md`: the 0003 row now notes Amendment 3.

## The two problems, and how each was settled

**1. Stale §1.** False since `0571697` (`fix(core-sim): rebuild the broadphase
every substep`), which moved the rebuild inside the substep loop. I verified on
`main` that `XpbdSolver.step` (line 152) builds the grid inside the `for (s in 0
until config.substeps)` loop, and that the `step()` comment (line 126) already
reads "Rebuilt every substep, not every tick (revising ADR 0003 §1)." So the
code and the intended design already agreed; only the ADR (and one stale
comment, see below) lagged. Not a defect — a stale doc. The ADR now matches.

**2. Phantom "Amendment 3".** Cited by `reviews/qa-broadphase-margin.md`,
handoff 0020, and the KDoc of `BroadphaseMarginTest`, but present in no ADR on
any branch (checked `main` and `test/core-sim-hardening` via `git grep` across
`git rev-list --all`). I judged it *should* be a formal amendment — it is the
reasoning behind why §1 changed, and that reasoning otherwise lives only in a
test KDoc and two handoffs. I reconstructed it from the three references and said
so in the section itself: it is a reconstruction, numbered 3 to match the
citations, and there are no Amendments 1 or 2 on record. The citations now
resolve to a real section instead of dangling.

## Measurements enshrined (sourced, not invented)

| broadphase rebuild | lattice 4 | lattice 5 | lattice 6 | budget |
| ------------------ | --------- | --------- | --------- | ------ |
| once per frame     | 0.40%     | 0.86%     | 39.2%     | 15% |
| every substep      | 0.40%     | 0.86%     | 0.90%     | 15% |

Both-directions table from handoff 0023 (Backend Engineer's harness). QA
independently measured 43.7% for the per-frame lattice-6 case (handoff 0020,
`qa-broadphase-margin.md`); I recorded that alongside. Per-substep closing
margin at lattice 6: `MAX_SPEED * h` = 0.0625, closing 0.125 against a 0.36 cell
= 2.9×. Cost +2.1%/step (468 → 478 µs). I asked Backend and QA to re-check the
numbers I enshrined against their own harnesses before merge.

**Post-review correction (Code Reviewer, on PR #14).** The re-check paid off.
Two labels were fixed in a follow-up commit before merge:
- The 43.7% was mislabelled "an 8-body pile" in the ADR. It is not: QA confirmed
  `BroadphaseMarginTest` is a **6-body** scene (`bodies = 6`), and *both* table
  figures are that one 6-body guard scene. **39.2%** is `main`'s solver reverted
  to per-frame (Code Reviewer reproduced it by toggling the cadence); **43.7%**
  is `feat/core-sim`'s *original* per-frame solver. They differ by solver
  generation, not pile size. QA's separate ~44% 8-body exploratory run was a
  different measurement whose label had fused on. Tying 43.7% to the 6-body scene
  keeps the ADR and the standing `BroadphaseMarginTest` in agreement.
- The +2.1%/step cost is now explicitly marked **single-sourced** to handoff
  0023 and not independently reproduced — wall-clock microbenchmarking here is
  too noisy for a second figure to stand behind (Code Reviewer's call).

## What I deliberately did NOT do

- **No solver edit.** The code is correct and the client approved the feel
  (task scope). I did not touch `XpbdSolver`, the grid, or any test.
- **Did not change ADR Status.** It stays `proposed`, like all 11 ADRs. Flipping
  it to `accepted` is a gate/Product-Lead concern and a systemic one across the
  set, not mine to do inside a doc-reconciliation.
- **Did not fix the stale `deriveVelocities` comment** in `XpbdSolver` (still
  "per frame, not per substep — ADR 0003 §1" on `main`, lines 224–231). It is
  the Backend Engineer's, and is already corrected on `fix/lattice6-broadphase`
  / PR #10 — just not merged to `main` yet. Flagged to them.

## Considered and rejected

- **Just deleting the "Amendment 3" citations** from the three documents so
  nothing dangles. Rejected: that throws away a genuine design prediction (the
  coupling that justifies the per-substep rebuild) which is currently
  under-recorded. Writing it into the ADR preserves the reasoning *and* resolves
  the references — strictly more information, same number of dangling pointers
  (zero).
- **Back-dating Amendment 3 to look like it always existed.** Rejected as
  dishonest; the constitution says say what you actually did. The section states
  it is a reconstruction.
- **A new ADR (0012) superseding 0003.** Rejected: this is a correction/
  amendment of one decision within 0003, not a new decision that reverses it.
  Amending in place with the superseded reasoning kept is the lighter, truer
  record, and matches the task's instruction.

## Open questions / uneasy about

- **The reconstruction is only as faithful as the three citations.** They agree
  on the coupling and the "re-trigger the non-tunnelling test" ask, so I am
  confident on substance, but the original wording (and why it was numbered 3) is
  gone. If Backend or QA remembers it saying more, it should be amended. I asked
  both.
- **Temporary code/doc skew until PR #10 merges:** `main`'s `deriveVelocities`
  comment still says per-frame while the ADR now says per-substep. They converge
  when PR #10 lands. Worth ordering PR #10 and PR #14 so the record is never
  internally contradictory for long.
- The `MAX_SPEED` / hard-coded-30 coupling in `BroadphaseMarginTest` is now
  recorded in the Amendment's consequences (from handoff 0023) so it is not lost.

## Contract for the reader

Nothing to build against here — this is a record correction. The one thing
downstream that changes: anyone citing "ADR 0003 §1" for broadphase cadence, or
"ADR 0003 Amendment 3" for the lattice-6 coupling, now finds text that matches
the shipped code.

---
*— **Architect***
