# 0012 — Architect → Product Lead

Date: 2026-07-20
Branch: `chore/architecture` @ `5f9bacb`. **Pushed to `origin`. Not merged.**
Responds to device-testing findings routed via the coordinator. Follows 0008.

## Summary

Five findings, two new ADRs, four corrections to existing ones. **Two of the
corrections are to claims of mine that were wrong**, and one of those was wrong
in the direction that costs headroom.

## 1. Piece size varied 11% with the quality tier — fixed at the root

`pieceExtent = latticeExtent + 2r` gave **2.40 / 2.25 / 2.16** at lattice 4/5/6.
The performance dial was changing how many pieces fit per row, and therefore the
game. The backend engineer measured it and correctly declined to act unilaterally.

**Fix (ADR 0011): the gameplay constant is the piece's *material* extent, not its
lattice extent.**

```
PIECE_EXTENT = 2.40
spacing      = PIECE_EXTENT / lattice
r            = spacing / 2
```

The lattice becomes a pure implementation detail — changing it now preserves piece
size exactly. I chose `2.40` deliberately because it equals today's lattice-4
value, so **this alters no behaviour at all right now**; spacing stays 0.600, r
stays 0.300. It only makes the definition mean what it says.

## 2. The tiers do not survive the measured derating — so I removed them

**12.06x measured, against my 3–7x estimate.** Re-deriving:

| lattice | device ms (+3% at 1e-4) | frame left |
| ------- | ----------------------- | ---------- |
| 3 | 3.29 | 13.38 |
| **4** | **6.12** | **10.55** |
| 5 | 10.51 | 6.16 |
| 6 | 15.92 | 0.75 |

**Only lattice 4 is viable.** Lattice 6 leaves 0.75 ms for everything that is not
the solver. Lattice 5 leaves 6.16 ms for a fragment shader that is still
unmeasured and is our largest remaining risk.

So the tier system was paying a gameplay leak *and* a threefold tuning tax —
per-tier compliance, per-tier clear threshold, per-tier overflow threshold, plus a
QA test asserting equivalent clear decisions across tiers — to buy optionality
that measurement says does not exist. **I pinned the lattice at 4 and superseded
the three-tier system.** ADR 0009's render-side runtime scaling is untouched and
is now the only scaling mechanism.

**Worth noting: ADR 0002's revisit trigger fired and the cheap response was
enough.** It read "if simulation at the default tier exceeds 8 ms … drop to the
low tier; if still short, arm64-v8a-only NDK". Old default: 10.51 ms — fires.
Drop a tier: 6.12 ms — stops. **No NDK.** Writing the trigger *and its response*
in advance is the part of this that worked, and I'd repeat it on the next
uncertain number.

## 3. Bounce is manufactured — made explicit; and I reject the proposed mass fix

The backend engineer's diagnosis is right: there is no restitution coefficient,
and `deriveVelocities` derives velocity from position *after* the contact solve,
so resolved penetration becomes outward velocity from nothing. Softer material
penetrates deeper, so it manufactures more bounce — which is exactly why the
client noticed only after the compliance change.

**ADR 0012 makes restitution an explicit, tunable coefficient.** The client likes
some bounce, so the target is not zero. The defect is that bounce is currently a
*side effect of softness*, so every tuning change to feel has an uncontrolled
second effect.

**I reject scaling compliance with mass, and this is the call the backend engineer
correctly escalated rather than made.**

XPBD's steady state is `C = -alpha*lambda`, and under gravity `lambda ∝ mg`, so
deformation is proportional to mass. That is not an artefact — it is Hooke's law,
and it is the brief's own mechanic:

> Pieces get heavier as the game progresses, so the tower increasingly sags and
> shifts under its own load.

Stiffening heavy pieces to protect the solver would **delete the visual signature
of the difficulty ramp**. We would be paying for stability with the mechanic.

Instead: bound the mass ramp by measurement. And the two fixes are coupled in a
useful way — **both the manufactured bounce and much of the high-mass instability
come from the same source**, deep penetration becoming velocity. So fixing
restitution is also partial mitigation for the mass ramp, and it is the fix that
does not cost the mechanic.

**Sequencing that matters for Stage 4:** measure the stable mass range *after* the
restitution fix lands, or you will be measuring against a defect. `massPerLevel`
should not be set from a guess — the existing 1–8x sweep was run at compliance
1e-6, and shipping is 1e-4, a hundredfold softer. That combination is untested.

## 4. Two claims of mine were wrong

**ADR 0001's "cost is independent of compliance" was overstated.** Measured +3%
for 1e-6 → 1e-4, not zero. The *constraint solve* is genuinely
compliance-independent; what I missed is that softer material overlaps more and
therefore generates more contact pairs. The headline survives — 3% is not what
turns "spongy" into "slightly springy" — but with no spare frame budget, **an
absolute claim that is 97% true is the kind that gets spent as if it were 100%
true.** Corrected in the ADR.

**My 3–7x derating estimate was wrong, and optimistically.** The real number
exceeded the product of both my maxima. I drew the band by multiplying two
uncertain factors (2.5–3.5x CPU, 1.3–2x ART) and quoting the result as a range,
which implied more confidence than two guesses multiplied together deserve. That
is a method error worth naming, not just a bad number.

**And the 8→6 substep lever I advertised is withdrawn.** 6 measures *worse* than 8
on device. My own caveat in handoff 0008 — that I had not measured 6 carefully
before anyone spent it — should have been reason enough not to advertise it at
all. There is no substep headroom. Withdrawn in both ADR 0003 and 0009, struck
through rather than deleted.

## 5. ADR 0007 never said where the rendered surface sits — that's how the gap shipped

The visible gap around every block was rendering, not physics: bodies touch to
within float noise, but the mesh was drawn through particle centres, one radius
small on every side. **My ADR never specified the offset**, so there was nothing
for the frontend engineer to have got wrong — the omission was mine.

Now specified: the boundary ring is offset **outward by one particle radius**, so
the rendered silhouette coincides with the collision surface. Interior vertices
need no offset, and `particleEdge` already identifies the ring. **The landing
silhouette must project the material extent too**, or it will promise a fit the
piece cannot make.

## Changes

Superseded rather than deleted throughout; struck-through text is left in place.

- **`docs/adr/0011`** (new) — piece geometry, lattice pinned at 4, tiers superseded.
- **`docs/adr/0012`** (new) — explicit restitution, compliance stays mass-independent.
- `docs/adr/0001` — stiffness-independence claim corrected to +3%.
- `docs/adr/0003` — 8→6 lever withdrawn.
- `docs/adr/0007` — new section 0: rendered surface offset by one particle radius.
- `docs/adr/0009` — Amendment 1: 12.06x, tiers re-derived and superseded, lever withdrawn.
- `docs/contracts.md` — `pieceExtent` added, `lattice` pinned, `restitution` added,
  `massPerLevel` documented as do-not-guess.
- `docs/architecture.md`, `.team/decisions.md` — updated.
- `.team/blockers.md` — **derating blocker closed.**

## What I deliberately did not do

- **Did not change any tuned value.** `PIECE_EXTENT = 2.40` reproduces current
  behaviour exactly. The client's approved feel is untouched.
- **Did not implement.** Restitution and the geometry change are backend work.
- **Did not set `massPerLevel`.** It needs measurement after restitution lands,
  and guessing it is how the ramp would get discovered as broken at Stage 4.
- **Did not keep a lattice-3 fallback as a runtime tier.** Recorded in ADR 0011 as
  a documented build-time re-pin if a weak-device problem ever materialises.

## Open, and what I'm uneasy about

1. **The fragment shader is still unmeasured and is now the whole risk.** 10.55 ms
   is left after the solver. That is not spare — it is the budget for a procedural
   subsurface shader on a 6.31" high-density panel, plus render, input and OS.
   I would prioritise measuring it over almost anything else.
2. **We now ship with no simulation-side fallback for weak hardware.** A device
   meaningfully slower than the Fairphone 6 runs in slow motion rather than
   degrading, because the timestep is fixed and substeps cannot be scaled. That is
   a deliberate price for not shipping three subtly different games, but it is a
   real loss of reach and the client should know it is a choice.
3. **`PIECE_EXTENT` is now load-bearing**: clear threshold, pieces-per-row and the
   landing silhouette are all calibrated against it. Commented as such, but it is
   the kind of coupling that gets forgotten.
4. **Restitution adds a second velocity-level pass**, which adds an ordering
   dependency to the determinism contract. **Replay fixtures will need regenerating
   when it lands** — QA should expect that and treat it as procedure, not as a bug.
5. **Three of my claims have now needed correcting** — the substep floor, the
   stiffness-independence claim, and the derating estimate. The common thread is
   stating a result more absolutely than the measurement supported. I would rather
   that pattern be visible in the record than tidied away.
