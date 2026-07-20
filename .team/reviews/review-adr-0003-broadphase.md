# Review: docs/adr-0003-broadphase (PR #14)

Verdict: approve-with-comments
Range: `origin/main` `4a3c996` .. `origin/docs/adr-0003-broadphase`

**Update after QA cross-check:** one provenance detail in Amendment 3 is wrong
and should be corrected before this lands as the record — see "Should fix". The
decision and the physics are sound; the fix is a one-line label change.

Documentation fix: ADR 0003 §1 claimed the broadphase is "rebuilt once per
frame," false since commit `0571697` (per-substep), and three documents cited an
"Amendment 3" that existed in no ADR. The Architect corrected §1 (superseded
block preserved) and reconstructed Amendment 3 from the surviving references. I
verified it is docs-only and that every enshrined number matches what landed —
including the one I measured myself.

## Blocking

None.

## Verified

- **Docs-only.** Three files, all markdown, none under `src`:
  `docs/adr/0003-contacts-and-stack-stability.md`, `.team/decisions.md` (index
  line), `.team/handoffs/0025-architect-to-product-lead.md`. No code touched.
- **§1 now matches the code.** The rewrite says the grid is rebuilt "at the top
  of every substep (`XpbdSolver.step`, `grid.build` inside the substep loop)" —
  which is exactly where `grid.build(world.posX, world.posY, n)` sits on `main`
  (inside `for (s in 0 until config.substeps)`). The original per-frame reasoning
  is kept verbatim in a "Superseded" block, so the decision stays auditable.
- **The Amendment 3 numbers are correct.** The table reads: per-frame lattice 6
  = 39.2%, per-substep = 0.90%; lattice 4 = 0.40% and lattice 5 = 0.86% in both
  rows; 15% rigid budget.
  - The per-frame **39.2%** is the figure I reproduced independently when I
    verified PR #10 — I reverted `XpbdSolver` to a per-tick rebuild and the guard
    failed at `lattice 6: 39.2%`, lattice 4/5 clean. Exact match.
  - Per-substep 0.90% / 0.40% / 0.86% and the 15% budget match PR #10's measured
    header and `RIGID_PENETRATION_FRACTION = 0.15f`, which I confirmed green.
  - QA's **43.7%** is a real measurement — but see "Should fix" on its
    provenance. The number is right; the ADR's "8-body pile" label for it is not.
  - The +2.1% cost (468 → 478 µs) and the 2.9× lattice-6 margin
    (`MAX_SPEED * h` = 0.0625, closing 0.125 against the 0.36-unit cell) are
    arithmetically consistent.
- Cross-checked the table against the Backend Engineer (handoff 0023) and QA
  (handoff 0020 / `reviews/qa-broadphase-margin.md`) directly rather than
  trusting the ADR's citations alone.
- The residual coupling the Architect recorded is real and worth having in the
  ADR: `BroadphaseMarginTest` hard-codes its drop speed at 30 = today's
  `MAX_SPEED`, so raising `MAX_SPEED` must raise the guard's drop speed too or it
  silently tests a slower-than-terminal drop.

## Should fix

- **Correct the provenance of the 43.7% figure.** Amendment 3 says "QA
  independently measured the per-frame lattice-6 case at 43.7% **on an 8-body
  pile**." QA re-checked against the actual scene (`BroadphaseMarginTest`, which
  I confirmed uses `TestScenes.pile(config, bodies = 6)`): 43.7% is that **6-body**
  scene, measured on `feat/core-sim`'s per-frame solver. So both figures in the
  table are the *same 6-body scene* — 39.2% is main's reworked solver reverted to
  per-frame (which I reproduced myself), 43.7% is feat/core-sim's original
  per-frame solver. They differ by **solver baseline, not pile size**. The ADR's
  "8-body pile" label is a reconstruction error (QA's notes hold a separate
  ~44% 8-body exploratory run whose label got fused onto the 43.7% value). Fix:
  attribute 43.7% to the 6-body guard scene, and frame the 39.2%/43.7% gap as two
  solver generations rather than two pile sizes — that ties the ADR figure to the
  standing guard's scene and keeps record and test in agreement. Number stays
  43.7%; only the label is wrong. Not a blocker on the decision, but an ADR is the
  authoritative record, so it should be right before it lands.

## Notes (non-blocking)

- Handoff numbering collides again: `main` already has
  `.team/handoffs/0025-qa-engineer-to-product-lead.md` (QA renumbered into it),
  and this PR adds `0025-architect-to-product-lead.md`. Different filenames, so no
  git conflict, but two handoffs share 0025. Next free number is 0026 — worth
  renumbering on merge to keep the log monotonic. Flagged to the Architect.

## What is good

- The reconstruction is scrupulous about provenance: it states plainly that no
  Amendment 3 text was ever committed, that it is rebuilt from three surviving
  citations, and that the wording is therefore new even where the reasoning is
  not — so a reader is never misled about what is original record versus
  reconstruction. Correcting the live §1 while preserving the superseded decision
  is exactly the right shape for an ADR amendment.

---
*— **Code Reviewer***
