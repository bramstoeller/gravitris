# Band-glow — the core visual language

This is the most important spec in this directory. The game has no grid, so
the player cannot eyeball "is this band 94% full?" the way a Tetris player
eyeballs a row. This system is the *entire* substitute for that readability —
it must teach coverage-band clearing implicitly, within one session, with no
tutorial screen and no percentage readout (both already rejected by the
client).

## What a band is (dependency on the Architect)

For this spec, a "band" is a fixed-height horizontal slice of the well, and
"fill" is the fraction of that slice's horizontal extent occupied by
material, sampled at some resolution the solver already needs for
clear-detection. **Open question for the Architect:** what is the band
height, and at what resolution/rate is per-band fill available (per physics
tick? per settle event?). This spec assumes a per-band fill scalar in
[0,1] is available to the renderer at least a few times a second — flag back
to UX if that's wrong, the ramp timings below would need to move from
"visual frames" to "ticks."

## The mapping: fill% → visual response

Not linear, and not literal. The curve is deliberately front-loaded to do
nothing for a long time and then accelerate hard near the threshold — that
asymmetry *is* the teaching signal: nothing to watch below ~40%, an
unmistakable "it's happening" in the last 10%.

| Fill range | Emissive intensity | Pulse | Internal shimmer |
|---|---|---|---|
| 0–40% | 0.0 (no glow — base material shading only) | none | none |
| 40–70% | ramps 0.0 → 0.15 (smoothstep, not linear) | none (static warmth) | none |
| 70–85% | ramps 0.15 → 0.45 | slow breathing, 2.4s period, small amplitude | begins, slow |
| 85–90% (final approach) | ramps 0.45 → 0.85 | breathing tightens to 0.9s period, larger amplitude | speeds up ("ember" flicker) |
| ≥ threshold (~90%, tunable), on settle | ignition: flash to 1.0, white-hot core | — | — |

Use an eased curve (smoothstep or cubic ease-in) for every ramp segment, not
linear interpolation. Linear would make 40–70% look like "something is
already happening" and undercut the accelerate-at-the-end read that teaches
the rule.

Threshold value itself (~90%) is an open tuning question owned by the
Architect/Product Lead, not fixed here — the curve above is defined in terms
of "fraction of the way to whatever the threshold is," so it re-scales
automatically if the threshold is tuned.

All pulse periods and the ignition flash above are specified in wall-clock
seconds/milliseconds, not frames — see `tokens.md`'s note on frame-rate
independence. The confirmed device's panel runs 10–120Hz adaptively; this
curve must look identical regardless of which refresh rate it's sampled at.

## Rendering: additive emissive over base hue, never a hue replacement

- Glow colour is a fixed warm amber, reserved and never used as a piece hue
  (see `piece-identity.md`): core `#FFB347`, ignition-flash colour
  `#FFF4E0`.
- Blend as an **additive emissive term over the piece's existing shaded
  colour** — a blue piece glowing is a warm-tinted blue, not an orange piece.
- **Cap the blend** so the base hue always contributes at least ~35% of the
  final colour, *except* during the 120ms ignition flash where a genuine
  white-hot flash is intentional and momentary. Never let sustained glow
  fully white-out a piece — that would erase the identity signal at exactly
  the moment multiple differently-hued pieces are sharing a band, which is
  when identity matters most for reading the stack.

## OLED banding risk (confirmed device: Fairphone 6, true blacks, 1400 nits)

The client's device is an OLED panel with true blacks and high peak
brightness (see `tokens.md` for the resulting `color-bg` change to
`#000000`). That's genuinely good news for this system — the contrast
between a dark unlit band and a nearly-full one can be dramatic rather than
a wash, which is exactly what the teaching curve above depends on to be
noticeable at all in the 40–70% range.

The failure mode it also invites: the 0.0→0.15 emissive climb through
40–70% fill is precisely the kind of slow sweep through near-black/low
values that visibly bands (shows discrete steps instead of a smooth ramp)
on an 8-bit output surface, and OLED's true blacks make banding artifacts
*more* visible against them, not less, because there's no ambient panel
glow or LCD backlight bleed to mask the steps.

**Mitigation: dither the low-intensity glow term, don't add a separate
dithering system.** The gel material shader already needs a procedural
noise field for surface grain (`piece-identity.md`'s grain-scale cue) and
for the glow's own "ember shimmer" (above). Give that same noise field a
small, always-on floor amplitude even when band fill is 0% — effectively a
per-fragment dither applied specifically to the darkest part of the ramp —
rather than building a second noise/dither pass. This is close to free (it
reuses an existing texture/procedural fetch) and ties the fix to the same
system that's already doing double duty as aesthetic texture.

**Flag to the Architect:** confirm whether the render/compositor path
already applies any spatial or temporal dithering before output (some GPU
drivers do this at composition); if not, this needs the explicit shader-side
treatment above, applied at minimum to the band-glow ramp and any AO/
subsurface darkening in the gel shader — those are the two places in this
visual language with a deliberately smooth gradient through dark values.
Design against an 8-bit output surface as the baseline even though the
panel supports 10-bit/1 billion colours — the fix should not depend on the
render pipeline actually using a wide colour surface, since that's a
separate pipeline decision this document doesn't assume.

## Distinguishing "glowing" from "just an orange piece" (moot, but designed defensively)

Two properties make the glow unmistakably a property of a *zone*, not of a
piece, even though no piece is ever actually amber:

1. **Animated shimmer.** The glow's internal noise/flicker moves; a piece's
   base hue is static. Motion is the tell.
2. **Cross-piece continuity.** The glow is masked to the band's horizontal
   slice across the *entire width of the well*, so two different-hued pieces
   sitting in the same band show the *identical* warmth, pulse rate and
   shimmer, at the identical height range, at the identical moment. Put a
   jade piece and a violet piece in the same 88%-full band and both must
   glow identically. That shared, synchronized behaviour across unrelated
   pieces is the strongest single legibility cue this system has — it reads
   as "this horizontal zone is charging up," independent of what's sitting
   in it. Do not let per-piece variation (e.g. glow intensity scaled by that
   piece's own local density) creep in — it would break the exact thing that
   teaches the rule.

## Band edges: soft, not a ruler

Feather the emissive mask at the top/bottom of each band by roughly
10–15% of band height (soft falloff, not a hard cutoff). A crisp edge reads
as a debug overlay or a HUD line, which the client explicitly rejected
("no HUD chrome"). A soft edge reads as a property of the material glowing
from within, which is the brief.

## Multiple bands at once: contained by the curve, not by a rule

No artificial cap on how many bands may glow at once, and no global
bloom/vignette that would dim things down to "protect" legibility. The curve
already contains the light-show risk: bands below 40% show nothing, so in
ordinary play only 1–3 bands are ever bright enough to notice. If skilled or
lucky play puts several bands near-threshold simultaneously, that **should**
look like an event — a wall of embers — not be suppressed. That's a payoff
moment, not a bug.

## How this teaches the rule with zero tutorial

The teaching happens through repetition of one visual grammar: **cause
(material fills a zone) → warning (that zone's warmth grows and its pulse
speeds up) → payoff (flash, then release)**. A player doesn't need to
succeed at clearing to learn from this — every near-miss where a band creeps
into the 70%+ range and then stalls (because the player stacked elsewhere)
is still a lesson: they saw a zone warm up independent of anything they
"intended," and they will start testing that observation.

**The single highest-leverage moment in the whole game is the first time any
band ever reaches the obvious-glow zone (70%+) in a session.** If that
doesn't happen within the first 60–90 seconds of a first-ever session, the
core hook is never demonstrated and a confused player may quit. This is a
tuning problem (piece size distribution, band height, early fall speed), not
something UX can guarantee from the visual language alone — **flagged to the
Architect and QA as a playtesting acceptance check for the prototype
milestone**, not solved by this document.

## Performance flags for the Architect

- Needs a per-band fill scalar exposed to the fragment shader (small
  array/uniform buffer indexed by band), updated at physics-tick rate, not
  necessarily every render frame. If the solver already tracks this for
  clear-detection, exposing the same scalar to rendering should be close to
  free — please confirm.
- Needs per-fragment world-space Y available in the material shader (to look
  up which band a fragment belongs to, and to compute the edge feather).
- **No screen-space bloom / HDR post-process.** The whole "glow" effect
  described here is faked via colour math in the existing material
  fragment shader — an additive emissive term plus a capped blend. This is
  intentionally cheap because it shares a frame budget with a soft-body
  solver. Please confirm this fits; if a genuine bloom pass turns out to be
  affordable, it would let the ignition flash read as brighter/more
  celebratory, but it is explicitly not assumed here.
- Shimmer/noise: reuse whatever procedural noise field already drives the
  base gel-grain shading (per `piece-identity.md`'s grain-scale cue) and
  modulate its amplitude/speed by band fill, rather than sampling a second
  noise source — avoids an extra texture fetch.

## Open question carried to the handoff

Clearing should only ever fire on a genuine settle, not a bounce-induced
overshoot mid-impact (a band's fill could spike briefly during a heavy
landing and drop back before truly settling). Whether the ignition sequence
starts strictly *after* a quiescence check (see `feel-feedback.md`) or the
solver already guarantees fill only reads high at rest is an open question
for the Architect — this document assumes the "≥ threshold, on settle" row
only ever fires once such a check has passed.
