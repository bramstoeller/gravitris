# Client interview — 2026-07-20

Product Lead. Clean project, no prior brief. Result: `/work/.team/brief.md`.

## What was asked and decided

| Question | Decision | Notes |
| -------- | -------- | ----- |
| Physics model | Soft-body squash & settle | Client chose the most ambitious option over rigid-body and rigid-core-springy-skin. This is the product's whole differentiator and its whole risk. |
| Clear rule | Coverage band (~90%) | Chosen over pressure/weight-crush and no-clear survival. |
| Polish target | Play-Store-ready | Client chose the highest of three. PL reframed as *destination*, with playable prototype as milestone one — client agreed. |
| Controls | Direct drag + tap rotate | Chosen over on-screen buttons and tilt. |
| Play Console | None yet — plan for it | No credentials in the container. Client publishes. |
| Monetization | None | Free, no ads, no IAP, no tracking. |
| Devices | Mid-range, Android 10+ | Caps the physics budget. |
| Stack | Native Kotlin + custom XPBD | Confirmed after PL explained the reasoning. |
| Art direction | Tactile & organic | Client chose this over PL's "juicy minimal" recommendation. |
| Art pipeline | Procedural shaders | PL's counter-proposal after separating *look* from *production cost*. Accepted. |
| Band feedback | Blocks glow from within | Chosen over edge markers, pressure lines, and deferring to UX. |
| Game modes | Endless, rising difficulty | Sprint mode explicitly deferred to post-demo. |

## Where the Product Lead pushed back

**On scope.** Client selected "Play-Store-ready" as the first target. PL stated
once that soft-body physics at 60fps on mid-range hardware is the project's
dominant risk, and that store polish cannot rescue a core that does not feel
good. Resolved by sequencing rather than by cutting scope: store-ready is the
release target, prototype is milestone one. Client agreed.

**On art direction.** Client chose "tactile & organic" over PL's recommendation.
Rather than re-argue, PL separated the two costs bundled in that option — the
*look* (mostly fragment-shader work, affordable) from the *production pipeline*
(authored assets, expensive, and the part that collides with a store-ready
timeline). Counter-proposed deriving the organic look procedurally. Client
accepted. Client keeps the look they wanted; the team keeps the frame budget and
a tunable art direction. Authored assets remain revisitable at the release gate.

## Considered and rejected

- **Godot 4** — rejected. Its 2D soft-body support is inadequate, so the solver
  would be custom anyway, while carrying ~30MB of runtime. Would have been the
  right call if a port path existed; PL asked directly and the client confirmed
  Android-only.
- **Unity** — rejected. Painful to build reproducibly headless in this
  container, licensing overhead, large binaries. No compensating benefit for a
  single-mode game with a custom solver.
- **On-screen buttons** — rejected despite being more precise. Cost ~20% of a
  portrait screen, which this game needs because a sagging stack spreads action
  vertically. Also breaks the tactile fantasy.
- **Tilt controls** — rejected. Novel and thematically apt, but fiddly and an
  accessibility problem.
- **Percentage readout for band fill** — rejected. Accurate but pulls attention
  off the stack and adds HUD chrome to a game whose feedback should live on the
  material.
- **Levels / puzzle stages** — rejected for iteration one. Needs a level format,
  an editor, and per-stage balancing, none of which tests whether the core
  squish feels good.

## What the PL is uneasy about

1. **The performance risk is not yet quantified.** Nobody has measured how many
   particles and constraints a 2020-era mid-range device can solve at 60fps. If
   that number is low, blocks must be stiffer, and "spongy" quietly becomes
   "slightly springy" — which would be a different product. The architect must
   establish this budget *before* anything else is designed around it.
2. **The losing condition is genuinely unsolved.** A fixed top-out line is
   unfair when the stack sags and settles back down below it. This needs a real
   answer, not a default.
3. **The coverage threshold is unknowable without play.** ~90% is a guess. It
   must be tunable at runtime so it can be tuned against a real build.

## Client's standing instruction

> "I agree, keep it simple for the first iteration"

Given in response to the sequencing plan. Treated as a standing constraint on
iteration one and passed to every dispatched specialist: prefer the simple
solution, defer optional complexity, no speculative extension points.
