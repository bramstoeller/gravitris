# 0003. Collision, contacts, and the substep floor for stack stability

Status: proposed — **substep-floor claim amended 2026-07-20, see "Amendment 1"**
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
runs per substep against the frame's grid, which moves the rebuild cost off the
substep multiplier. Safety comes from the 3x3 neighbourhood search tolerating
about a cell of drift per **frame**, and from floor/wall contacts bypassing the
grid entirely — **see Amendment 3, which corrects the looser argument originally
given here.** The rebuild allocates nothing (cell arrays are retained and refilled)
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

> ⚠ **The paragraph above is superseded by Amendment 1. The floor is ~3–4, not 8.
> The table is retained because it is what was measured; the inference drawn from
> it was wrong.**

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

## Amendment 1 — the substep floor is ~3–4, not 8 (2026-07-20)

The backend engineer measured the floor independently in production code
(handoff 0006) and put it **between 2 and 4, with no trend above 4**. They kept
8 and escalated rather than silently diverging, which was the right call.

**They are right and I was wrong.** I re-ran the spike to find out why we
disagreed; results in `spike/solver-budget/results-reconcile.txt`.

**The two solvers agree. It was never a solver disagreement — it was a scene
disagreement.** Reproducing their two scenes in the spike solver reproduces their
numbers:

| scene | 2 | 4 | 6 | 8 | 12 | 16 |
| ----- | - | - | - | - | -- | -- |
| wide well 10x20, 24 bodies (spike) | 0.0000 | 0.0003 | 0.0001 | 0.0000 | 0.0030 | 0.0008 |
| tall tower 5 wide, 40 bodies (spike) | **160624** | 0.0002 | 0.0161 | 0.0011 | 0.0826 | 0.0036 |

Their tall tower exploded to 139 405 at 2 substeps; mine to 160 624. Same
phenomenon, same order of magnitude. From 4 upward, both solvers show flat noise
with no trend, in both scene shapes.

**Why my original table said 8.** Three compounding measurement faults, and they
are worth naming because they are the same class of error as the three spike bugs
already recorded in the README:

1. **The scene was chaotic and I reported single runs.** Residual kinetic energy
   in a deep pile varies non-monotonically by orders of magnitude between adjacent
   substep counts, for reasons that are about which metastable configuration the
   pile fell into, not about solver fidelity. The new run shows this directly: at
   drop pitch 1.05, 6 substeps leaves KE 19.5 while 4 and 8 leave 0.002 — and the
   19.5 is *stable across 2 700 further frames*, so it is a trapped configuration,
   not a convergence failure. Reading a monotonic floor off that noise was
   unjustified.
2. **I took the worst verdict across three compliance values** in that noisy
   scene, so any single unlucky configuration at 4 or 6 pushed the apparent floor
   to 8.
3. **The 1200-frame window measured settling *time*, not stability**, in a scene
   whose pile was seeded ~57 units in the air and was still settling when I
   measured it. Running to 3600 frames, my own original scene settles at 4
   substeps.

**A hypothesis I tested and had to discard.** I expected the 2-substep failure to
be a tunnelling/velocity condition — travel per substep approaching a particle
diameter. It is not. Driving a body into a settled pile at up to 240 units/s, a
travel of **2.0 particle diameters per substep**, produced no penetration at any
substep count. The velocity explanation is refuted.

What the evidence supports instead is **contact-depth accumulation in a deep
pile**: with few substeps, gravity integrates further between corrections, so
particles sink deeper before the rigid contact resolves them, and resolving a deep
overlap rigidly injects energy. In a long contact chain that compounds down the
stack. This is why pile *depth* discriminates and impact *speed* does not, and why
the tall tower fails where the wide well never does.

**The decision does not change: substeps stay at 8. The justification does.**

8 is **engineering margin of roughly 2x over a measured floor of ~3–4**, not the
measured floor. That distinction matters:

- It is honest about what was measured, which is the point of this record.
- The failure below the floor is *catastrophic and not graceful* — eight orders of
  magnitude, in both implementations. Sitting one step above a cliff that steep is
  not where a shipped game should be, so the margin is worth its cost.
- The cost is known and affordable: 8 substeps is 0.49 ms against 0.25 ms at 4
  (ADR 0001). We are paying ~0.25 ms for the margin.
- ~~**It gives ADR 0009 a lever it thought it did not have.** Dropping to 6 is now
  a defensible move with margin remaining.~~ **Withdrawn 2026-07-20.** On device,
  **6 substeps measures *worse* than 8 in the marginal cases.** The lever does not
  exist. The measured floor of ~3–4 came from host measurements of settled piles;
  it did not license 6 as a safe operating point on real hardware, and I should
  not have advertised a lever I had explicitly noted I had not measured carefully.
  Substeps stay at 8, full stop.

**QA can now build replay fixtures against 8**, which was the reason this needed
settling before Stage 3. The number is not going to move.

## Amendment 2 — the wide-well question is answered (2026-07-20)

The "unease worth recording" below flagged that the spike's numbers came from a
narrow tower and that a wide well "produces more simultaneous contacts per body …
and that specific configuration has not been measured".

**Measured, by the backend engineer and independently reproduced above: the wide
well is the *gentler* case.** It settles at every substep count tested, including
2, where the tall tower explodes. The conservatism held in the direction hoped.
This is the shape the real game has — a 10x20 well — which is a further reason the
production floor sits lower than the spike's tower suggested.

## Amendment 3 — the per-frame broadphase justification was looser than it read (2026-07-20)

The backend engineer flagged that this ADR justifies rebuilding the grid per frame
by arguing "particles move a fraction of a cell per substep" — but the rebuild is
per *frame*, so the motion that matters is up to **8x** that. The argument as
written does not describe what the code does. Nothing is broken; the reasoning was
sloppy. Corrected statement:

- The grid is rebuilt **once per frame**; narrowphase runs per substep against it.
- The safety margin therefore has to cover **per-frame** motion, not per-substep.
  A particle travelling one cell per frame is still found, because narrowphase
  searches the **3x3 cell neighbourhood** — so the true bound is roughly one cell
  of drift per frame before a contact could be missed.
- **Floor and wall contacts never go through the broadphase at all**; they are
  solved directly for every particle every substep. The containment guarantee —
  the one that matters for "can a piece escape the well" — does not depend on grid
  freshness.
- Empirically, no penetration was observed even at 2 particle diameters of travel
  per substep (Amendment 1), and the backend engineer has a non-tunnelling test on
  hard drop.

**This is a real coupling to respect when tuning**: raising the terminal-velocity
clamp, lowering substeps, or shrinking particle radius all eat this margin. If any
of those change, re-check the non-tunnelling test rather than assuming.

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

**Unease worth recording — now resolved, see Amendment 2:** the stability numbers
came from a pile roughly 4 units wide and 46 tall, not a filled 10x20 well,
because of how the spike seeds bodies. That was a *harder* compression case, so
the substep floor was conservative — but a wide well produces more simultaneous
contacts per body than a narrow tower, and that configuration had not been
measured. **It has now been: the wide well is the gentler case.** The conservatism
held, and it was in fact conservative enough to have produced a wrong floor
(Amendment 1).
