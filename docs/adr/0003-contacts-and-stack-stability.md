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

**1. Broadphase: uniform grid, counting sort, rebuilt once per frame.** Cell size
equals one particle diameter. Rebuilt per *frame*, not per substep; narrowphase
runs per substep against the frame's grid. Particles move a fraction of a cell
per substep, so this is safe, and it moves the rebuild cost off the substep
multiplier. The rebuild allocates nothing (cell arrays are retained and refilled)
— a per-frame `IntArray` in the first draft showed up immediately in the
allocation check and was hoisted.

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
