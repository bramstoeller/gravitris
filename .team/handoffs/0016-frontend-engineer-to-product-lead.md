# 0016 — Frontend Engineer to Product Lead

Stage 3B, the art direction: procedural gel/rubber shading in the fragment
shader.

Branch `feat/gel-shading`, pushed. Commits `3730077..4c470b8`, on top of
`d771689`.

---

## The branch point is a deviation, and it is the first thing to check

**I was told to branch off `feat/squish-toy`. I branched off
`fix/render-footprint` instead**, and I would make the same call again, but it
should be confirmed rather than assumed.

`feat/squish-toy` is twelve commits behind. Branching off it would have
discarded, silently:

- the **softening** (`48a0dc6`) — the aspect 1.41 deformation the client
  approved;
- the **contact gap fix** (`ab49788`, `d98b333`) — bodies touching with no
  visible margin, which the client called out approvingly;
- **ADR 0011**, the boundary extrusion — which the same brief told me to read,
  and which only exists on this line.

So the instruction contradicted the rest of the brief: it named three things to
preserve and one branch that has none of them. `fix/render-footprint` is a
linear descendant of `feat/squish-toy` and contains everything, so this is a
fast-forward rather than a divergence — but a merge to `main` should confirm
`fix/render-footprint` is landing too, or this branch carries its commits along
with it.

---

## What was built

All of it is colour math in one fragment shader. No texture assets, no second
pass, no bloom, no HDR — as the brief required and ADR 0007 §7 confirms.

| Term | Driven by | Spec |
| ---- | --------- | ---- |
| Subsurface | body UV depth | deep tone is same hue, darker + **more saturated**, never toward brown/grey |
| Contact seam / AO | `vContact` | `piece-identity.md`'s **primary** small-screen boundary cue |
| Rim light | `vEdge`, suppressed at contacts | fixed neutral cool-white, **never tinted per piece** |
| Grain | body UV, per-archetype frequency | tertiary CVD/monochrome identity cue, 0.8x–1.8x |
| Dither | screen position | the OLED near-black banding fix |
| Compression darkening | `vCompression` | **preserved, gain 4.0, unretuned** |
| Band glow + pulse + shimmer | `uBandFill` by world Y | the coverage curve, capped at 35% base hue |
| Ignition flash | `uBandClearProgress` | the one moment that cap lifts |
| Dissolve | `uBandClearProgress` | replaces a one-frame pop |

**The compression darkening is folded in, not replaced.** It runs last, as a
multiply on the finished material colour, specifically so its tuning stays
valid — folding it in earlier would let the later terms lift material back up
and quietly change the darkening the client already signed off.

ADR 0007's varying contract is now complete. Two of the three new inputs turned
out to be **static per particle** (`vBodyUv`, `vEdge`) rather than per-frame as
Stage 1 predicted, so they ride with the archetype and cost nothing per frame.
Only `vContact` is dynamic: 12 → 16 bytes per particle, 7.0 → 9.4 KB/frame at
24 bodies, against the 2 MB/s ADR 0007 measured and told us not to treat as a
bottleneck.

---

## Frame cost: estimated, not measured, and I want to be blunt about that

**I have no GPU in this container and I did not measure this on hardware.**
Everything below is a static ALU op count and an arithmetic projection. Treat
it as a shape, not a number — the whole reason the dial in the next section
exists is that this estimate needs replacing with a real reading.

| Level | Adds | ALU ops/fragment |
| ----- | ---- | ---------------- |
| 0 | flat colour only | ~9 |
| 1 | + compression (Stage 1's `shade:on`) | ~12 |
| 2 | + subsurface, seam, rim, dither | ~58 |
| 3 | + grain | ~81 |
| 4 | + band glow, ignition, dissolve | ~178 |

At an estimated 25–50% screen coverage that is **7–14 Gops/s at the top level**,
which on a Mali-G615 MC2 is plausibly **2–4 ms**. Within the brief's "2 ms and
ships beats 8 ms and does not", but on the upper edge of it, and against a
budget that is already gone.

Two things I did after costing it, because tier 4 was four times any other tier:

- the shimmer reuses the already-computed grain value as a phase offset instead
  of evaluating a second pair of sines (−14 ops);
- `baseLuma` moved inside tier 1, so **level 1 is a bit-identical Stage 1
  baseline** and the subtraction the dial exists for is honest.

---

## The cut list, and it is executable rather than prose

Volume-up steps a five-position dial **downwards**, so one repeated press walks
the cut list in the order things would actually be cut. The readout shows
`shade:FULL band:DEBUG` or `shade:2/4`, with the denominator, so a screenshot of
a reduced level is self-evidently a reduced level.

**If the measurement overruns, cut in this order, and the reason is in the
ordering:**

1. **Band glow (level 4 → 3).** First, and it costs the player nothing today —
   the fill driving it is a debug sweep, so nothing real is lost until Stage 3A
   lands.
2. **Grain (3 → 2).** `piece-identity.md` itself calls grain "the most at-risk
   cue on this screen size, not the most reliable one" and says not to lean on
   it. Its own spec nominates it for cutting.
3. **Stop.** Level 2 is the floor I would defend. It carries the contact seam,
   which is ranked *above* the lightness ladder as the primary cue for telling
   squashed pieces apart — and see the palette defect below, which makes the
   seam matter more than the spec assumed.

Below level 2 the product stops meeting the brief's legibility requirement, so
that is an escalation rather than a tuning decision.

---

## A real defect found on the way, not mine to fix

**The lightness ladder in `piece-identity.md` is not present in the hex values
that document ships.** Measured against its own table:

| piece | spec L | actual L | step |
| ----- | ------ | -------- | ---- |
| Jade | 40% | 41.2% | — |
| Teal | 54% | **42.9%** | **+1.8%** |
| Azure | 44% | **53.5%** | +10.6% |
| Violet | 56% | 58.0% | +4.5% |
| Magenta | 42% | **51.4%** | −6.7% |
| Rose | 55% | 53.7% | +2.4% |

The palette climbs almost monotonically instead of alternating, and three hexes
are 9–11 points from their own stated lightness.

**Why it matters:** the spec names **Jade vs Teal** as the single highest-risk
pair under deuteranopia, and names the lightness ladder as the cue that catches
that pair when hue compresses. The actual step between them is **1.8%**, not the
"10–15%" the spec promises. For the pair that most needs the backup cue, the
backup cue is effectively absent.

Stage 3B makes this worse, not better: subsurface, rim and seam now vary
apparent lightness continuously across each piece's own surface, which is
exactly what the spec said the ladder must survive.

**Not fixed here** — the hexes are UX's source of truth and were desk-checked on
hue angle; re-picking lightness could disturb that. It is pinned by
`PaletteTest` so it cannot drift further unnoticed, and it needs a UX decision.

---

## What I agreed with the Backend Engineer directly

Rather than guessing the Stage 3A contract, and it changed the code twice.

1. **`bandFill` is a per-band scalar, looked up by world Y per fragment.** Never
   per-piece — `band-glow.md` forbids per-piece variation because identical
   glow across unrelated pieces in the same band is the rule's strongest
   teaching cue.
2. **The band feather was wrong and they caught it.** I was lerping between band
   centres, feathering across a whole band where the spec asks 10–15%. Their
   number is what exposed it: a band is 1.0 world unit, a piece is 2.40, so a
   piece spans ~3 bands and a full-band feather blurs three values into one
   gradient across a single piece — destroying the horizontal zone that *is*
   the signal. Now flat per band with a ~14% boundary blend.
3. **`bandFill` is damped at the source**, asymmetrically (rise ~67ms, fall
   fast), with the clear rule reading the undamped array. This kills the false
   amber flash a landing bounce would otherwise trigger through my 70–90% ramp.
   I argued against filtering it in the renderer: that would make displayed
   coverage disagree with the coverage the clear rule fires on, and a glow that
   lies about when a band will clear is worse than one that flickers.
4. **The clear envelope** — progress 0→1 over 24 ticks with material alive
   throughout — so the ignition flash is wired rather than invented.
5. **The dissolve was going to be a one-frame pop.** They keep geometry alive
   precisely so shading can dissolve it; nobody had built the shading half. I
   did: erode toward black using the grain field as a spatial threshold, so
   material breaks into embers. No blend and no discard — `GL_BLEND` is off
   deliberately, and on this OLED with `color-bg` `#000000`, fading to black
   already is fading to the background.
6. **Quantisation: keeping 40x4.** One cell step moves the glow ~0.85 of an
   8-bit code value; the dither is 1.4 peak-to-peak, so it is absorbed. Noted
   for whoever fields it later: dither hides *spatial* banding. If the client
   ever reports the glow "ticking" as a band fills, that is a *temporal* step of
   the whole zone at once, which dither cannot fix — the answer then is cell
   resolution, and it invalidates the tuned clear threshold, so we retune
   together.

---

## The jank is not GC, and I have a better suspect

The Product Lead asked me to establish this before tuning shaders. I audited the
whole GL-thread path.

**The GL thread is allocation-free in steady state.** The only repeating
allocation on it is a ~32-byte lambda at 4Hz for the stats callback. Combined
with the Backend Engineer's zero-allocation solver, GC is off the table as the
mechanism for the 43.9 ms frame.

Two things came out of it:

- **Fixed:** `BodyMesh` was orphaning the vertex buffer at *max capacity*
  (16 KB) every frame instead of the live size (~7–10 KB), making the driver's
  allocator do roughly double the work. Driver allocators are exactly where
  multi-millisecond outliers come from.
- **Not fixed, not mine, and it is my best remaining candidate:**
  `ImpactHaptics.flush()` makes a **synchronous binder call to
  `VibratorManagerService` from the GL thread**, between the draw calls and the
  swap. That round trip is unbounded and lands on precisely the frames where an
  impact happened — i.e. the frames that jank. It matches the shape of the
  evidence: jank correlating with interesting frames rather than with load.
  Both of us named it; neither of us owns it. **It needs an owner.**

Separately, **ADR 0007 §8's revisit trigger has fired.** It says: "if frame
pacing proves uneven at Milestone 1, move to a plain `SurfaceView` with our own
EGL setup driven by `Choreographer`." 23 jank/s at 17 bodies is that condition.
That is an architect call, not mine.

---

## What I deliberately did not do

- **No band logic.** `SimState.bandFill` is allocated and never written on this
  branch; I drive it with a travelling-wave debug sweep that pushes every band
  through the full 0–1 range, so one device session exercises the entire curve.
  Inventing coverage here would have created a second definition for Stage 3A to
  collide with.
- **No scoring, losing condition, menus, or landing silhouette.** Stage 3A/4.
- **Did not touch `:core-sim`.**
- **Did not retune the compression darkening.** Confirmed it did not need it.
- **No reduced-motion wiring.** Settings do not exist. The pulse rate is a
  uniform and `accessibility.md`'s 1.2s floor is a matter of passing a different
  constant; the value and derivation are in `Tunables` and unit-tested against
  the WCAG 3Hz limit.

## Considered and rejected

- **Multi-octave value noise** for the grain — ~50 ALU ops against ~14 for two
  sine products. It would look better and it is the honest choice with budget.
  There was no budget. The dither breaks up the regularity at pixel level.
- **A hash-based dither** (Hoskins fract/dot/fract, ~12 ops) — replaced with the
  R2 low-discrepancy sequence at 3 ops, which distributes *better* at this
  amplitude. A quarter of the price for a shader whose entire problem is fill.
- **`vEdge` for subsurface depth** — it goes 1→0 across one lattice cell, a
  fifth of the body. Correct for a rim, far too tight for the depth of a
  translucent solid. Body UV gives a full-body gradient, and the two terms
  operating at different spatial scales is most of what makes it read as a
  material rather than an outlined shape.
- **Compile-time shader variants** instead of the runtime tier uniform — the
  branches are on a uniform and coherent across an entire draw call, so they
  are close to free, and variants cannot be walked down in one session on one
  stack. The measurement mattered more.
- **A `discard`-based dissolve** — forces late-Z for the whole draw on a
  tile-based GPU. Multiplying to black reaches the same pixel on this panel for
  a couple of ALU ops.

## Open questions

1. **The branch point.** Confirm `fix/render-footprint` lands too.
2. **The lightness ladder defect.** Needs UX.
3. **Who owns the haptics binder call on the GL thread.**
4. **ADR 0007 §8's pacing trigger has fired** — architect.
5. **Nobody has seen any of this.** Every aesthetic judgement in it is reasoned
   from the specs and unverified by eye.

---

## What the client should photograph

**One photo settles it: a well with 15–20 settled bodies, several pieces
touching, at `shade:FULL`, with the frame-time readout legible in the corner.**

That single image answers, in order of what I am least sure of:

1. **Do bodies still read as separate where they touch?** The contact seam is
   the whole legibility argument. If squashed neighbours merge into one mass,
   the art direction has failed at its main job and the fix is `CONTACT_GAIN`.
2. **Does it read as gel rather than as flat shapes with an outline?** That is
   the subsurface gradient doing its work, and it is the judgement I have the
   least ability to check from here.
3. **Is the frame time survivable?** The readout is in the same photo, so the
   look and its price are settled together rather than in two rounds.
4. **Does the grain resolve at all at real size,** or is it invisible on a
   6.31" panel — the open question `piece-identity.md` raised and could not
   answer.

**Then, ideally, four more of the same stack** — press volume-up between each —
at `shade:3/4`, `2/4`, `1/4`, `0/4`. Five frame times on one stack in one
session is the measurement this stage owes and the thing I could not produce.

Two things to tell them so the photos are readable:

- **The amber glow sweeping the well is fake.** It is a debug sweep, not real
  coverage — the readout says `band:DEBUG`. Judge whether the *warmth* looks
  right; ignore where it is.
- **Photograph a settled stack, not mid-fall.** The seam and the compression
  darkening both need contact to show anything.
