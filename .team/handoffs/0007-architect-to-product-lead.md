# 0007 — Architect → Product Lead

Date: 2026-07-20
Branch: `chore/architecture` @ `9f0a72f`. **Pushed to `origin`. Not merged.**
Follows handoff 0003. Responds to the backend engineer's escalation in 0006.

## The backend engineer was right, and I was wrong

They measured ADR 0003's substep floor at **between 2 and 4, not 8**, kept 8
anyway, and escalated rather than silently diverging. That was exactly the right
call, and the escalation was worth making.

I re-ran the spike to find out *why* we disagreed rather than split the
difference. New experiment: `spike/solver-budget/src/Reconcile.kt`, results in
`results-reconcile.txt`.

## The finding: it was never a solver disagreement

**The two solvers agree.** Reproducing their two scenes in the spike solver
reproduces their numbers:

| scene | 2 | 4 | 6 | 8 | 12 | 16 |
| ----- | - | - | - | - | -- | -- |
| wide well, 24 bodies (mine) | 0.0000 | 0.0003 | 0.0001 | 0.0000 | 0.0030 | 0.0008 |
| tall tower, 40 bodies (mine) | **160 624** | 0.0002 | 0.0161 | 0.0011 | 0.0826 | 0.0036 |

Their tall tower exploded to 139 405 at 2 substeps; mine to 160 624. Same
phenomenon, same order of magnitude, flat noise from 4 upward in both shapes.

**The disagreement was between scenes, not solvers.** My original table came from
a chaotic deep pile seeded ~57 units in the air, measured in a fixed 1200-frame
window, with the worst verdict taken across three compliance values. Three faults
compounded:

1. **I read a monotonic floor off noise.** Residual energy in a deep pile varies
   non-monotonically by orders of magnitude between adjacent substep counts, for
   reasons about which metastable configuration the pile fell into. The new run
   shows it directly: at one drop height, 6 substeps leaves KE 19.5 while 4 and 8
   leave 0.002 — and the 19.5 is *stable over 2 700 further frames*, so it is a
   trapped configuration, not a convergence failure.
2. **Worst-case across compliance** in that noisy scene meant one unlucky
   configuration at 4 or 6 pushed the apparent floor to 8.
3. **The 1200-frame window measured settling *time*, not stability.** Run to 3600
   frames, my own original scene settles at 4 substeps.

This is the same class of error as the three spike bugs I already recorded — and,
as you noted, the same class as the dependency-verification failure the code
reviewer caught: **a check that passed because the thing it was checking was not
in the state it claimed to be.** Mine passed because the pile had not finished
settling.

## A hypothesis I tested and had to discard

I expected the 2-substep failure to be tunnelling — travel per substep approaching
a particle diameter. **It is not.** Driving a body into a settled pile at up to
2.0 particle diameters of travel per substep produced **no penetration at any
substep count**. The velocity explanation is refuted, and I have recorded it as
refuted rather than quietly dropping it.

What the evidence supports is **contact-depth accumulation down a long chain**:
with few substeps gravity integrates further between corrections, particles sink
deeper before the rigid contact resolves them, and resolving a deep overlap
injects energy — compounding down a deep pile. That is why pile *depth*
discriminates and impact *velocity* does not, and why the tall tower fails where
the wide well never does.

## What changed, and what did not

**Substeps stay at 8. Nothing in the code or the contract moves.** The backend
engineer's decision to keep 8 stands.

**The justification changes**, and that is the point. 8 is now recorded as
**~2x engineering margin over a measured floor of ~3–4**, not as the floor:

- The failure below the floor is **catastrophic, not graceful** — eight orders of
  magnitude in both implementations. One step above a cliff that steep is not
  where a shipped game belongs.
- The margin costs ~0.25 ms/frame (0.49 vs 0.25 at 4 substeps). Affordable.
- **It hands ADR 0009 a lever it thought it did not have:** re-pinning 8 → 6 would
  still leave margin and return ~0.12 ms, if on-device measurement proves tight.
  Still a *build-time* re-pin with replay fixtures regenerated, never a runtime dial.

**For QA: build replay fixtures against 8. The number is not going to move.**
That was the reason this needed settling before Stage 3, and it is settled.

## The other two items

**Wide well — open question closed.** Their measurement, independently reproduced:
the wide well is the *gentler* case, settling at every substep count including 2.
My conservatism held in the direction I hoped — conservative enough, in fact, to
have produced a wrong floor. ADR 0003 Amendment 2; the "unease worth recording"
paragraph is marked resolved rather than deleted.

**Broadphase justification — they were right, the wording was sloppy.** ADR 0003
argued from per-*substep* motion while the code rebuilds per *frame*, 8x that.
Nothing is broken and the decision stands, but the stated reasoning did not
describe the code. Corrected in Amendment 3: the real margin is the 3x3
neighbourhood tolerating ~a cell of drift per frame, and floor/wall contacts
bypass the grid entirely, so **containment does not depend on grid freshness** at
all. Recorded with the coupling spelled out — raising the velocity clamp, lowering
substeps or shrinking particle radius all eat that margin, and each should
re-trigger the non-tunnelling test.

## Changes made

All on `chore/architecture`, superseding rather than deleting so the wrong
reasoning stays auditable:

- `docs/adr/0003` — status flagged; original table retained with a supersede
  marker; Amendments 1, 2 and 3 added; broadphase paragraph corrected inline.
- `docs/adr/0009` — the "never scaled" claim amended: still not a runtime dial,
  but the headroom now exists and is described.
- `docs/contracts.md` — the `substeps` comment said "below 8 stacks jitter",
  which was the wrong reason. Now states the margin and the cliff.
- `.team/decisions.md` — ADR 0003 row updated.
- `spike/solver-budget/README.md` — a fourth defect recorded alongside the
  original three, including the refuted hypothesis.
- `spike/solver-budget/src/Reconcile.kt`, `results-reconcile.txt` — new.

## What I am uneasy about

1. **My spike has now produced four wrong conclusions, three caught by me and one
   caught by someone else.** The pattern is consistent: I trusted a scene I had
   not validated was in the state I assumed. The production code guards all three
   original defects explicitly; the fourth has no guard because it is a
   methodology error, not a code error. **The mitigation is that measurements of
   chaotic settling need multiple seeds and a convergence check, not a fixed
   frame budget** — worth saying to whoever next measures physics behaviour.
2. **I still have no on-device numbers.** Blockers 1 and 2 from handoff 0003 are
   unchanged and remain the largest unknowns.
3. **The 8→6 lever is real but I have not measured 6 as carefully as I should**
   before anyone spends it. If ADR 0009 ever reaches for it, re-measure with
   multiple seeds first rather than trusting the flat-noise reading above.

## Housekeeping

The branch-contamination note in handoff 0003 still stands: **merge
`chore/architecture` before the branch that carries duplicate copies of its first
three commits.** Also note the cherry-pick that moved those commits silently
dropped three spike source files (they were added by another agent's `git add -A`
in a commit not on my branch); I restored them from `5dae9f0` in this change.
Worth a glance at merge time.
