# 0039 — UX Designer → Frontend Engineer

**Branch:** `docs/material-beauty-correction`, PR #33 (docs-only).
**Commit:** `c9324cc`.
**Applies to:** `feat/candy-material-render` (PR #31) — this is a retune on
your open branch, not a competing rewrite.

## What I did

The Product Lead reviewed the frames you captured for PR #31
(`.team/reviews/0037-candy-material-render/02-L-rounded-corners-sharp-elbow-shadow.png`,
`03-O-rounded-corners-shadow-and-remaining-seam.png`,
`04-settled-rounded-with-shadows.png`) and relayed the client's verdict: the
light candy background is good, keep it — *"die blokken zijn zo lelijk"* —
the blocks are ugly. This is the third visual miss, and this time the ask was
explicitly the material, not the background/corners/shadow (all confirmed
good).

I read the actual shipped frames myself before writing anything, then read
`Shaders.kt` and `Tunables.kt` on `origin/feat/candy-material-render` line by
line to ground the diagnosis in the real code rather than the frames alone.
Corrected `docs/ux/visual-direction.md` §14 (new §14.1–§14.4, original text
kept verbatim as §14.5 — not deleted, per the project's "keep the record"
discipline) and `docs/ux/tokens.md`'s Material section.

## The three fixes, concrete and code-grounded

**1. Grain (§14.1) — the dominant defect in the frames.** `mottle()`
(`Shaders.kt`: `sin(uv.x*3.1+uv.y*1.7) * sin(uv.y*2.6-uv.x*1.1)`) at
`GRAIN_FREQUENCY = 7.0` (scaled 0.8×–2.0× per archetype) aliases into a
dense cross-hatch/waffle grid at the piece sizes visible in the frames —
exactly the "grainy rough rubber" read. `piece-identity.md` already ranks
this as the tertiary, CVD-backup identity cue, meant to be invisible in
normal play. Fix: `GRAIN_FREQUENCY` 7.0 → ~2.5, `GRAIN_GAIN` 0.06 → ~0.02.
**Check before shipping:** raw `grain` (not scaled by `GRAIN_GAIN`) also
drives the ember shimmer phase and the dissolve break-up threshold in tier
3 — lowering frequency coarsens both. Look at it against
`feel-feedback.md`'s dissolve description; if it reads wrong, add a second,
separate frequency constant for tier 3 rather than reverting this fix.

**2. Subsurface gradient direction (§14.2) — currently inverted.**
`depth = min(vBodyUv, 1-vBodyUv)*2` is 0 at the true silhouette edge, 1 at
the core. `color = mix(color, deep, depth * uSubsurfaceGain)` therefore
darkens toward the piece's **centre** today and leaves the **edge** at full
undarkened brightness — backwards from the real Candy Crush references
(§12: flat, saturated body with only a touch of edge-darkening) and from
the "plump jelly bean, brighter centre / richer edge" read this correction
is chasing. Both checks agree on the same direction; the code has the
other one. Fix:

```
float edgeCloseness = 1.0 - depth;               // 1 at edge, 0 at core
float edgeBand = edgeCloseness * edgeCloseness;  // free square — same
                                                  // trick as the rim cubic,
                                                  // concentrates the darkening
                                                  // near the true edge
color = mix(color, deep, edgeBand * uSubsurfaceGain);
```

One swapped mix operand plus one free multiply — not a new term. Drop
`SUBSURFACE_GAIN` 0.80 → ~0.35–0.45; the corrected, edge-concentrated
version needs less gain to read without becoming a dark ring. `deep`'s own
construction (saturate 1.35, darken 0.62, same hue) is correct and
untouched.

**3. Specular shape (§14.3) — no falloff along its own length.** Today
`across = dot(vBodyUv - SPEC_CENTER, SPEC_DIR)` measures only the width of
the streak; there's no falloff along its run direction, so the highlight is
a full-length band bounded only by the piece's silhouette — which is why it
reads as a hard diagonal scratch on the cyan piece in frame 03 rather than a
localized gleam. Fix: add a perpendicular `along` axis, taper the streak in
that direction too (new `uSpecularLength`, ~0.35–0.45 first pass), and add a
small tight brighter hotspot at the streak's centre reusing the same
across/along fields (new `uSpecularHotspotRadius`, ~0.06–0.08). Full
pseudocode in §14.3. Direction, position, colour (`color-specular`, fixed,
never tinted), piece-gating, and contact-suppression are all unchanged.

## What I did not do

- Did not touch mesh/topology, `particleU`/`particleV`, `particleCorner`,
  or anything about the internal "+" seam — that's `feat/candy-seamless-mesh`
  (Backend Engineer), a separate defect with a separate owner
  (handoff 0038, frontend→backend). §23 of the corrected doc says this
  explicitly so a reviewer can judge "does the material look like candy"
  independently of "do the cells join."
- Did not re-tune `SPECULAR_GAIN` (0.85), re-derive the palette, or touch
  `color-tray`/`color-sky-*`/the shadow pass — none of those were named as
  wrong, and the client explicitly confirmed the background.
- Did not decide whether the specular hotspot needs its own gain uniform or
  can ride a fixed ratio of `uSpecularGain` — flagged as your call in §14.3.
- Did not add any of these gains/frequencies as `tokens.md` entries —
  they're tunable shader constants (`Tunables.kt`'s territory, "first-pass,
  tune on-device" throughout that file already), not stable design tokens;
  adding them to `tokens.md` would break that file's own "resist growth"
  discipline. `tokens.md` only got a pointer to the correction and one
  unrelated flag (below).
- Noticed in passing, not fixed (different lane, not this correction's
  scope): `tokens.md`'s `corner-fade-mode` row and §16's "fades toward
  `color-tray`" text are stale — `Tunables.kt`'s `CORNER_ROUND` comment
  records the mechanism was reworked to MSAA alpha-to-coverage (dropping
  fragment alpha, not fading toward a colour) after the fade read as a dark
  smudge against the sky. Flagged in `tokens.md` inline; whoever next opens
  the corner/rounding work should fix the row rather than trust it.

## Considered and rejected

- **Killing the grain term entirely** rather than retuning it. Rejected:
  `piece-identity.md` still wants a CVD-backup cue and the term costs
  nothing extra to keep at a quiet amplitude — reducing frequency/gain gets
  the "no visible pattern" outcome without removing a documented identity
  cue or touching the tier-3 reuse (shimmer, dissolve) that depends on the
  same noise field existing.
- **A literal hue-shift across the body gradient**, which the Product Lead's
  dispatch floated as a possibility ("possibly a subtle hue shift"). Rejected
  and corrected in §14.4: real Candy Crush candies don't hue-shift across a
  single body (§12), and `piece-identity.md`'s standing rule for the deep
  tone is explicit that it must stay the same hue, never shift toward
  brown/grey, because hue is the primary identity cue. Saturation/value
  gradation (already what `deep` does) achieves the "richer" read without
  breaking that rule.
- **Decoupling the tier-3 noise frequency from the tier-1/2 grain frequency
  now, pre-emptively.** Rejected for this pass: it's an extra uniform and an
  extra thing to tune before anyone has looked at whether the coarser
  dissolve/shimmer actually reads wrong. Flagged as a fallback in §14.1
  instead of built speculatively.

## Open questions for the Product Lead / client

- §23 of the corrected doc has a one-line plain-language description of the
  target material for a sanity-check before the next build is shown: *"a
  smooth, flat-saturated candy body with a subtle darker/richer rim right at
  its silhouette edge, no visible surface texture, and one soft wet-looking
  gleam (a small bright hotspot fading into a longer soft streak) biased
  toward the upper-left of each piece."* Worth confirming before the tuning
  pass in §14.3, which is the one genuinely time-consuming piece of this.

## What I'm uneasy about

- I could not re-fetch fresh high-resolution Candy Crush screenshots this
  pass — APKPure (the source the round-3 UX pass used successfully) now
  blocks automated fetches (403). I got one genuine low-res corroborating
  fetch (Wikipedia's current screenshot) and leaned on §12's own detailed,
  already-cited high-res analysis from the prior pass rather than
  re-deriving it from memory — I believe that's sound (it was a careful,
  grounded pass and nothing about the material's real-world reference has
  changed), but flagging the gap in freshness rather than quietly presenting
  it as newly verified.
- The specular reshape (§14.3) is the one piece of this I have not seen
  rendered — the grain and gradient fixes are simple enough that I'm
  confident of the direction from the code alone, but the hotspot + taper
  shape needs an actual on-device look before it's called done. Said so in
  §23's sequencing note (tune this one last, after the cheaper two are
  confirmed).
