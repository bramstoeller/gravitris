# 0003. Collision, contacts, and the substep floor for stack stability

Status: proposed
Date: 2026-07-20

## Context

Stack stability is the classic failure mode for position-based solvers. A tall
pile compressing under its own weight, with pieces getting heavier as the game
progresses, is the single hardest case this solver will face — and an unstable
solver produces jitter that players read as bugs, not as physics. The brief also
requires that impact "propagates visibly down the stack", so we need the stack to
be *responsive* without being *twitchy*, which are adjacent failure modes.

Contact handling between deformable bodies also has to be cheap: it runs every
substep, and ADR 0001 commits to 8 substeps per frame.

## Decision

**Particle-level contacts on a uniform-grid broadphase, solved as rigid
zero-compliance position corrections with Coulomb friction, at a hard floor of 8
substeps per 60Hz tick, plus a small velocity damping term.**

Four parts, each measured.

**1. Broadphase: uniform grid, counting sort, rebuilt every substep.** Cell size
equals one particle diameter. The grid is rebuilt at the top of *every substep*
(`XpbdSolver.step`, `grid.build` inside the substep loop), and the narrowphase
centres a 3×3 stencil on the cell a particle was bucketed into at that rebuild.
The rebuild allocates nothing (cell arrays are retained and refilled) — a
per-frame `IntArray` in the first draft showed up immediately in the allocation
check and was hoisted. The extra rebuilds cost +2.1% per step on the ADR 0001
reference scene (468 → 478 µs), a relative figure that survives device derating.

> **Superseded — original decision, kept for the record.** This ADR first
> specified the grid "rebuilt per *frame*, not per substep; narrowphase runs per
> substep against the frame's grid. Particles move a fraction of a cell per
> substep, so this is safe, and it moves the rebuild cost off the substep
> multiplier." That per-frame bound is false for the closing speeds this game
> actually produces, and it is not quality-tier safe: at the ADR 0009 lattice-6
> tier the cell shrinks until a piece at terminal speed outruns the stencil
> within one frame and its contact is missed. **See Amendment 3** for the
> coupling, the before/after measurements, and the fix (commit `0571697`,
> guarded by `BroadphaseMarginTest`).

**2. Contacts are rigid, not compliant.** Bodies are soft because their *interior*
constraints are compliant; the non-penetration constraint itself is solved with
zero compliance. Making contacts soft as well lets bodies visibly sink into each
other, which reads as a rendering bug rather than as softness.

**3. Friction is not optional polish.** Coulomb friction with a static-slide clamp
proportional to penetration depth is what makes a pile stand still instead of
slowly creeping outward. It is solved in the same pass as non-penetration.

**4. The substep floor is 8, and it is measured.** Residual kinetic energy of a
settled pile of 60 bodies, after 1200 frames:

| substeps | firm (1e-8) | medium (1e-6) | spongy (1e-5) |
| -------- | ----------- | ------------- | ------------- |
| 2  | 19.58 jittering | 58.64 jittering | 9256 jittering |
| 4  | 7.08 jittering  | 0.005 settled   | 7.75 creeping  |
| 6  | 0.71 creeping   | 0.74 creeping   | 0.007 settled  |
| 8  | 0.19 settled    | 0.002 settled   | 0.001 settled  |
| 12 | 0.001 settled   | 0.006 settled   | 0.071 settled  |
| 16 | 0.003 settled   | 0.002 settled   | 0.021 settled  |

**8 substeps is the lowest count that settles across the whole stiffness range.**
2 substeps never settles; 4 and 6 settle for some materials and not others, which
is worse than uniformly bad because it makes stability depend on a tuning dial
the designer expects to be free. Above 8 the returns are marginal.

Stability holds as pieces get heavier, which is the late-game case that matters:
at 8 substeps, per-particle mass 1, 2, 4 and 8 all settle (residual KE 0.002 to
0.023). The brief's difficulty ramp does not destabilise the solver.

**5. Velocity damping.** A small per-substep damping term (0.5%) is applied when
deriving velocities. Position-based dynamics conserves energy well enough that an
undamped pile rings indefinitely; the spike's first stable run still showed a
persistent low-level hum until damping was added. This is a feel parameter as much
as a stability one and belongs in the tuning set.

**6. Self-collision within a body is off.** Bodies are held in shape by their own
distance and area constraints, so a body folding through itself requires extreme
deformation we do not expect at these compliance values. Skipping same-body pairs
cuts narrowphase work substantially. If playtesting shows bodies self-intersecting
under heavy load, this flips to on for a measured cost increase.

## Alternatives considered

**Sequential impulses / velocity-level contacts (Box2D-style)** — rejected.
It is the best-understood approach to stable stacking and would be the right call
for rigid bodies. It lost because it lives at the velocity level while XPBD lives
at the position level; mixing them means reconciling two notions of contact per
substep, and the position-level correction has to happen anyway to stop
interpenetration. One contact model is simpler and the measurement says it is
sufficient.

**Signed distance fields per body** — rejected. Accurate, cheap to query, and the
usual answer for deformable-vs-deformable contact. It lost because the field must
be *rebuilt every frame* for a body that deforms every frame, which is precisely
our case, and that rebuild costs more than the particle contacts it replaces.

**Convex hull / polygon contact against the deformed body outline** — rejected.
Far fewer contact pairs, and tempting for that reason. It lost because a squashed
body pressed into a gap is exactly when its outline stops being convex, and the
mechanic depends on material flowing into gaps. Decomposing a deforming concave
outline every frame is more work and more code than particle contacts.

**More substeps (12 or 16) for safety margin** — rejected. It buys measurably
little (residual KE is already ~0.002 at 8) and costs 50–100% more solver time,
which is the budget that ADR 0009 wants to spend on particle count instead.
Particles per piece is what the player can see; substeps past the stability floor
is not.

**Fewer substeps (4) with quality scaling down to 2 under load** — rejected, and
this is an explicit push back on treating substeps as a runtime dial. The table
above shows that dropping substeps converts a framerate problem into a *jitter*
problem, and jitter reads as a bug. Substeps are a correctness floor, not a
quality slider. ADR 0009 scales other things.

## Consequences

**Easy.** One contact model, one broadphase, no per-body acceleration structures.
Contact cost is a small fraction of total solver cost — the constraint solve
dominates — so contact count is not the thing to optimise. Stability is now a
property we can assert in tests rather than eyeball: "settled kinetic energy
below a threshold after N frames" is a QA assertion, and it is the same predicate
the losing condition uses (ADR 0005).

**Hard.** The 8-substep floor multiplies everything: every constraint is solved 8
times per frame, and that multiplier is the main reason particle budgets are in
the low thousands rather than the tens of thousands. Gauss-Seidel contact
resolution is order-dependent, so contact ordering is part of the determinism
contract (ADR 0006) and must not be made concurrent casually.

**Live with.** Friction with a static-slide clamp proportional to penetration
depth is a good approximation, not a correct one; at very light contact the clamp
approaches zero and friction becomes nearly perfect static friction, so a settled
pile is somewhat *stickier* than reality. This helps stability and may hurt the
feel of material flowing into gaps. It is a tuning risk to watch at Milestone 1,
not a known defect.

**Unease worth recording:** the stability numbers come from a pile roughly 4 units
wide and 46 tall, not a filled 10x20 well, because of how the spike seeds bodies.
That is a *harder* compression case, so the substep floor is conservative — but a
wide well produces more simultaneous contacts per body than a narrow tower, and
that specific configuration has not been measured. It should be checked at
Milestone 1.

## Amendments

### Amendment 3 — the broadphase is rebuilt every substep (2026-07-20)

Supersedes §1's "rebuilt once per frame". This change has shipped (commit
`0571697`, on `main`) and the client has approved the resulting feel; §1 above is
updated to match, and the original per-frame reasoning is preserved there as a
superseded note so the decision stays auditable.

**Provenance — this is a reconstruction.** Three surviving documents cite "ADR
0003 Amendment 3" — the QA review (`reviews/qa-broadphase-margin.md`), the
QA → Backend handoff (0020), and the KDoc of `BroadphaseMarginTest` — but no
Amendment text was ever committed to this file on any branch. It was written
informally, or only spoken, and lost. This section reconstructs it from those
three references so that nothing cites a document which does not exist; the
citations now resolve here. It is numbered 3 to match them — there are no
Amendments 1 or 2 on record. Because it is a reconstruction, the wording is new
even where the reasoning is not.

**The coupling Amendment 3 named.** The broadphase cell equals one particle
diameter, and the narrowphase centres a 3×3 stencil on the cell a particle was
bucketed into — so it tolerates about one cell of drift between rebuilds. The
particle radius, and therefore the cell, *shrinks as the ADR 0009 lattice count
grows* (lattice 4 → 0.600, lattice 5 → 0.450, lattice 6 → 0.360 well units). At a
fixed terminal speed a piece therefore crosses more cells per frame at higher
detail: 0.83, then 1.11, then **1.39 cells/frame** at lattice 6. Past one cell
the arriving piece falls outside the stencil and its contact is missed for that
frame. Amendment 3 predicted exactly this — shrinking the radius eats the drift
margin — and asked that any change to the particle radius **re-trigger the
non-tunnelling test**, which until then exercised only lattice 5.

**Why the per-frame ADR was wrong, measured both ways.** Nothing re-triggered
that test, so the high-detail tier shipped unguarded. Rebuilding once per frame
penetrated deeply at lattice 6 while lattice 4 and 5 stayed rigid — a clean
cliff, bit-identical across runs, which is the signature of a missed pair, not a
stiffness limit:

| broadphase rebuild | lattice 4 | lattice 5 | lattice 6 | rigid budget |
| ------------------ | --------- | --------- | --------- | ------------ |
| once per frame     | 0.40%     | 0.86%     | **39.2%** | 15% |
| every substep      | 0.40%     | 0.86%     | **0.90%** | 15% |

Deepest inter-body penetration as a fraction of a particle diameter, at the frame
boundary (Backend Engineer's harness, handoff 0023). QA independently measured
the per-frame lattice-6 case at 43.7% on an 8-body pile — same order — and pinned
it as a Major defect (handoff 0020, `reviews/qa-broadphase-margin.md`).

**Decision.** Rebuild the grid **every substep**, not once per frame. Per substep
the closing bound holds with margin: a piece moves at most `MAX_SPEED * h` =
0.0625 well units, closing 0.125 against the 0.36-unit lattice-6 cell — a 2.9×
margin — so the missed pair cannot occur. This shipped in commit `0571697`
(`fix(core-sim): rebuild the broadphase every substep`), which was made to fix a
*separate symptom of the same mechanism* — fast drags and hard drops closing
faster than ~35 units/s were manufacturing ejection at the terminal cap — and
closed the lattice-6 coupling as a consequence. Cost: +2.1% per step (§1).

**Consequences.** The rebuild cost now rides the substep multiplier, which §1's
original version deliberately avoided — accepted, because 2.1% buys a guarantee
the per-frame version could not make at any lattice. The guarantee is that
contact rigidity (§2) is independent of the ADR 0009 quality tier: a tier changes
render detail only, never what happens in the game. `BroadphaseMarginTest`
(`a hard drop stays rigid at every quality tier` — all three tiers, 15% budget)
is the standing guard Amendment 3 asked for, now live in `:core-sim`. One
residual coupling is on record: that guard drops at a hard-coded 30 units/s equal
to today's `MAX_SPEED`; if `MAX_SPEED` is ever raised, the guard's drop speed
must be raised with it, or it will test a slower-than-terminal drop (handoff
0023).
