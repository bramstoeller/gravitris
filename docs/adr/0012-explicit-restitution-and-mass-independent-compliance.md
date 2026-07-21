# 0012. Restitution becomes explicit; compliance stays mass-independent

Status: proposed
Date: 2026-07-20
Extends the contact model in ADR 0003.

## Context

The client, on the softened build: *"Het voelt nog een beetje gek als ik 'm veel
laat bouncen. Maar bouncing is ok."* — bounce is acceptable, but odd.

The backend engineer traced the mechanism rather than chasing the symptom, and
the finding is architectural: **there is no restitution coefficient anywhere in
the solver.** Bounce is manufactured. `deriveVelocities` computes velocity from
the position change across a substep — *after* the contact solve has pushed
overlapping particles apart. That resolved penetration becomes outward velocity
out of nothing.

The consequence is that **bounce is a side effect of softness**: softer material
penetrates deeper before being resolved, so more velocity is manufactured. That is
precisely why the client noticed it only after compliance moved 1e-6 → 1e-4.

A second finding is coupled to it. XPBD's steady state satisfies `C = -alpha *
lambda`, and under gravity `lambda` is proportional to `m*g`, so **deformation is
proportional to mass at fixed compliance**. The difficulty ramp increases mass, so
late-game pieces deform progressively more and eventually destabilise. My own
stability sweep covered mass 1–8x, but at compliance **1e-6**; the shipping value
is now 1e-4, a hundredfold softer, so **the combination that will actually ship is
untested**. `massPerLevel` is Stage 4 and currently unread, so nothing is broken
today.

The backend engineer proposed scaling compliance with mass and explicitly declined
to make the call unilaterally, calling it solver semantics wanting an ADR. They
were right to escalate it.

## Decision

**1. Restitution becomes an explicit, tunable coefficient.**

Per contact, capture the relative normal velocity *before* the contact solve.
After `deriveVelocities`, correct the normal component of relative velocity toward
`-e * v_normal_pre`, clamped so it can only remove manufactured energy, never add
it. `e` is a tuned material parameter in `SimConfig`.

The client likes *some* bounce, so the target is not `e = 0`. The point is that
bounce becomes **a dial that means one thing**, rather than an emergent artefact
that changes every time someone tunes compliance or the difficulty ramp changes
mass. Today, every change to feel has an uncontrolled second effect on bounce;
that is the actual defect.

Cost: one extra pass over contact pairs. Contacts are a small fraction of solver
cost — a few hundred pairs against 3 600 constraints (ADR 0001) — so this is
expected to be low single-digit percent. **It must be measured, not assumed**,
given the frame budget after 12.06x derating has no slack (ADR 0011).

**2. Compliance stays mass-independent. The proposed fix is rejected.**

Scaling compliance with mass would make heavy pieces stiffen so they sag like
light ones. That **cancels the product's core mechanic.** The brief:

> Pieces get heavier as the game progresses, so the tower increasingly sags and
> shifts under its own load.

Deformation growing with mass is not an XPBD artefact — it is Hooke's law, and it
is the behaviour the brief asks for. The visual signature of the difficulty ramp
*is* the stack sagging more as pieces get heavier. Stiffening heavy pieces to
protect the solver would delete the thing the difficulty ramp is supposed to look
like, and would do it silently.

**3. The mass ramp is bounded by measurement instead.**

Once restitution is explicit, measure where instability actually begins at the
shipping compliance, and set `massPerLevel` and a hard mass cap inside that range.
This is a Stage 4 task and it is now on the critical path for Stage 4, not
optional.

**These two fixes are coupled, and that is the useful insight.** Both the
manufactured bounce and a large part of the high-mass instability come from the
same source: deep penetration being converted into velocity. Heavier pieces
penetrate deeper, so they manufacture more energy, which is what destabilises
them. **Fixing restitution properly is therefore also partial mitigation for the
mass ramp** — and it is the fix that does not cost the mechanic. Measure the
stable mass range *after* the restitution fix lands, not before, or the range will
be measured against a defect.

## Alternatives considered

**Scale compliance with mass (the proposed fix)** — rejected, as argued above. It
treats correct physics as a bug and pays for stability with the mechanic. Worth
recording that it would have *worked* for stability; it lost on what it would have
cost, not on whether it would have helped.

**Exclude the contact correction from velocity derivation** — rejected. Track how
much of a particle's position change came from non-penetration correction and
subtract it before deriving velocity. It directly targets the mechanism and needs
no restitution pass. It lost because it removes *all* contact response, not just
the spurious part: a piece landing on the stack would transmit no impulse, and the
brief requires impact to "propagate visibly down the stack". It also leaves bounce
undialable, which is the thing the client is actually commenting on.

**Raise global velocity damping** — rejected. It would suppress bounce and it is a
one-line change. It lost because damping is not selective: it would equally
suppress the jiggle and settle that the brief spends its whole feel budget on, and
it would make the material read as dead rather than heavy.

**Cap penetration depth so less energy is manufactured** — rejected. It amounts to
making contacts stiffer, which fights the softness the client has just approved,
and it would reintroduce exactly the softness-coupling this ADR exists to remove.

**Leave it alone; the client said bouncing is ok** — rejected, and it is a serious
option given "keep it simple". It lost on the trajectory rather than the present
state: the coupling means bounce grows with both the compliance tuning and the
Stage 4 mass ramp, so the thing the client currently calls "a bit odd" gets worse
on two independent axes before release, and the second one has not been built yet.
Fixing the mechanism now is cheaper than discovering it at Stage 4 tuning.

## Consequences

**Easy.** Bounce becomes tunable, and tuning compliance stops silently changing
it. The difficulty ramp keeps its visual signature. The mass-ramp instability gets
smaller for free as a side effect of the restitution fix. `e` is one more number
in `SimConfig`, which is where every other feel parameter already lives.

**Hard.** The contact solve gains state it did not have — pre-solve relative normal
velocity per contact pair — which means either a per-pair scratch buffer or
recomputation. The buffer must be retained, not allocated per frame (ADR 0001's
zero-allocation property is measured and asserted, and this is exactly the kind of
change that would break it). Contact ordering already affects results (ADR 0003),
and adding a second velocity-level pass adds another ordering dependency to the
determinism contract (ADR 0006) — replay fixtures will need regenerating when this
lands.

**Live with.** Restitution applied per contact pair in a Gauss-Seidel sweep is an
approximation, not a correct global impulse solve; in a deep pile with many
simultaneous contacts the effective restitution will not exactly equal `e`. That
is acceptable — `e` is a feel dial to be tuned by hand against the device, not a
physical constant we need to honour.

**Open, and it belongs to Stage 4:** the stable mass range at the shipping
compliance is unknown, and `massPerLevel` cannot be set responsibly until it is
measured. That measurement should use multiple seeds and a convergence check
rather than a fixed frame budget — the methodology lesson from ADR 0003
Amendment 1.
