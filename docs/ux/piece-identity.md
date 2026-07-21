# Piece identity — hue palette

Shape is not a reliable identity cue in this game: pieces squash, bulge and
merge visually with their neighbours as they settle. **Hue is the primary
identity cue**, which the brief calls out as a hard requirement, not a
nicety. This document specifies the actual palette and states exactly what it
was checked against.

## The one rule that makes this work: reserve a hue band for glow

The band-glow system (`band-glow.md`) communicates coverage by adding a warm
amber emissive term to whatever a piece already is. If a piece's *base* hue
were also warm amber, the player could not tell "this piece is orange" from
"this band is nearly full" — the two channels would collide.

**Hue 15°–65° (amber/orange/gold/yellow) is reserved for the glow signal and
must never be used as a piece base hue.** No piece, in the current set or any
added later, may sit in that band.

That constraint, taken seriously, also solves the aesthetic brief: a cool
jewel-tone piece palette reads as vivid and "gel-like" precisely because the
warm glow has no competition — when a band lights up, it is visibly the only
warm thing on screen.

## Palette

**Updated 2026-07-21 (`visual-direction.md` §5): seven hues, not six.**
`Simulation.ARCHETYPE_COUNT = 7` and the game is moving to seven tetromino
shapes, so `Palette.kt`'s own flagged collision (archetype 6 folded onto hue
0 via `floorMod`, `Palette.kt:96-103`) is closed by adding a seventh hue
rather than by re-mapping the existing six. Evenly spaced (~35-37° apart)
across the cool half of the wheel plus one new green pulled from the widest
open arc, chosen for CVD separation first and aesthetics second.

| # | Name | Hex | H° | S | L | Grain scale (tertiary cue) |
|---|------|-----|----|---|---|------|
| 1 | Jade | `#1FB37A` | 150 | 62% | 40% | 0.8× |
| 2 | Teal | `#1B9FC0` | 187 | 68% | 54% | 1.0× |
| 3 | Azure | `#3162E0` | 224 | 68% | 44% | 1.2× |
| 4 | Violet | `#7148E0` | 261 | 64% | 56% | 1.4× |
| 5 | Magenta | `#B23FC7` | 298 | 52% | 42% | 1.6× |
| 6 | Rose | `#D63C6E` | 335 | 62% | 55% | 1.8× |
| 7 | Emerald | `#3BA12B` | 112 | 58% | 40% | 2.0× |

Emerald is appended as archetype index 6 (not inserted in hue-sorted order)
so the first six archetypes' existing indices are untouched — additive only,
no remapping of pieces already in play. See `visual-direction.md` §5 for the
full derivation (why 112°, why that's the only open arc wide enough to place
a seventh hue at all, why lightness continues the dark/light alternation).

Reserved (do not assign to a piece, ever): `#FFB347` amber core / `#FFF4E0`
ignition-white — see `band-glow.md`.

### Why lightness alternates (light/dark/light/dark…)

Hue is primary, but a piece's *apparent* lightness varies continuously across
its own surface because of rim-light, subsurface tinting and ambient
occlusion (see the shading note below) — lighting can legitimately brighten
or darken a patch of a piece. If lightness also carried the *only* backup
identity signal, that shading would corrupt it. So lightness is deliberately
alternated piece-to-piece as a **coarse, low-resolution backup cue**
(dark/light/dark/light/dark/light down the table) — enough to help a
grayscale or low-vision read without asking lighting to hold still.

### Why grain scale exists as a third cue

Each piece already renders with a procedural noise/grain term (part of the
gel shader). Assigning each piece a distinct grain frequency is free — no new
texture, no new pass — and gives a texture-based cue that survives full
colour blindness (achromatopsia) or a monochrome screenshot. This is a
tertiary cue: don't design around it being noticeable at a glance, design
around it being available if hue and lightness both fail a given viewer.

## Rendering constraints that protect the hue signal

- **Rim light is a fixed neutral cool-white/pale-blue, never tinted per
  piece.** A coloured rim would shift the apparent hue at the exact edges
  where players read piece boundaries.
- **Subsurface "deep colour"** (the darker interior tone visible through
  translucency where pieces overlap or squash thin) is a darkened,
  more-saturated version of the *same* hue — never shifted toward brown or
  grey. Muddying the hue at low light is the same failure mode as tinting the
  rim.
- **Band-glow emissive is additive, capped.** See `band-glow.md` — the base
  hue must always contribute at least ~35% of the final colour except during
  the 120ms ignition flash, so a glowing piece still reads as "blue, glowing"
  and not "a differently-hued piece."

## Verification performed, and its limits

This palette was checked on paper against two published colour-vision-safe
references: Okabe & Ito's "Color Universal Design" set and IBM's CVD-safe
palette, both designed and validated against protanopia, deuteranopia and
tritanopia simulation. The hues above were **not** lifted directly from
either set — both were designed for flat 2D swatches at fixed lightness, and
this game's pieces are lit with rim-light/AO/subsurface gradients that vary
lightness continuously across a single piece's own surface. A palette that
relies on lightness *and* hue to separate two colours (as both source sets
occasionally do, e.g. Okabe-Ito's "sky blue" and "blue" are the same hue at
different lightness) would collapse under this game's shading. So the six
hues here were instead hand-spaced for hue-angle separation alone
(≥37° between any two, all outside the reserved amber band), using those two
palettes only as a sanity check on individual hue choices.

**I have not run these through an actual CVD simulator** — no such tool was
available in this environment, and simulation accuracy depends on the final
shaded, lit, glowing render, not the flat hex values in the table above. Desk
review flags two pairs as the highest-risk under protanopia/deuteranopia and
tritanopia respectively:

- **Jade (150°) vs Teal (187°)** — both blue-leaning green/cyan; deuteranopia
  in particular compresses this region more than the blue/violet/magenta
  end of the set.
- **Violet (261°) vs Azure (224°)**, and **Magenta (298°) vs Rose (335°)** —
  under tritanopia (rare, ~0.01% of males, but real) the blue-yellow
  confusion axis can compress adjacent blue/violet/magenta/pink hues toward
  each other.

The lightness ladder and grain-scale cues above exist specifically to catch
these two pairs if hue alone compresses for a given viewer.

**Action item for QA before the release gate:** run the actual rendered,
lit, glowing pieces through a CVD simulator (Android Accessibility's
"Simulate color space" developer option, or the Coblis web tool on
screenshots) and confirm all six remain pairwise distinguishable, including
while a band beneath/around them is mid-glow. This is written down as an
open verification step, not asserted as done.

## Sanity-checking the palette at real size (confirmed device: 6.31" panel)

The client's device is a 6.31" phone — comfortably one-handed, which suits
the drag-anywhere control scheme, but it caps how much colour information
can be legible at once. A hue-coded identity system is only as good as the
smallest patch a player actually has to read, and a squashed, packed stack
on a small panel can reduce a piece to a thin sliver a few millimetres tall.
Two real effects work against hue-only identification at that scale:

1. **Small-area colour matching.** Perceived saturation and hue
   discrimination both degrade as a coloured patch's visual angle shrinks
   (the classic small-field colour-matching effect) — the same hex value
   that reads as a clear jewel-tone at mockup size can read as a washed-out
   grey sliver in a dense, heavily-squashed stack on a 6.31" panel.
2. **CVD confusion gets worse, not better, at small size** — the two pairs
   flagged as higher-risk above (Jade/Teal, Violet/Azure/Magenta/Rose) are
   exactly the ones a small, desaturated-by-size patch will compress further
   for an affected viewer.

**Mitigations, in priority order:**

- **A boundary cue independent of colour.** Give every piece a subtle fixed
  seam/crease (a thin AO-darkened line, not a hard black stroke) at its
  boundary with a neighbour, regardless of hue difference or how tightly
  they're squashed together. This means the player is never relying on a
  colour edge alone to tell where one piece ends and another begins — there
  is always a shape/shading cue backing it up, which matters most exactly
  when patches are too small for hue to carry full confidence.
- **Don't let the lightness ladder collapse at small size.** The ~10–15%
  lightness step between adjacent-hue neighbours (see the ladder above) is
  the cheapest thing to check at real size, since lightness differences
  survive small-area viewing better than hue/saturation differences do.
- **Grain scale is the most at-risk cue on this screen size, not the most
  reliable one.** A fine noise pattern needs enough physical pixels to read
  as texture rather than static; on a heavily-squashed sliver a few
  millimetres tall, the grain-scale differences (0.8×–1.8×) may simply not
  resolve. Treat grain scale as it was always meant to be treated — a
  tertiary backup, not a size-independent guarantee — and do not lean on it
  to rescue legibility at small sizes; lean on the boundary seam and the
  lightness ladder instead.

**Action item, not yet closed:** verify actual on-device patch sizes during
the prototype milestone specifically on the confirmed 6.31" panel, at
realistic stack density (near top-out, heavily squashed), not at the
mockup/wireframe scale this document was authored at. If pieces routinely
read as same-y grey blobs once compressed, the fix is more likely a
gameplay/layout change (larger minimum piece size, fewer simultaneous
archetypes visible at once) than a further colour tweak — flagged to the
Architect and Product Lead as a joint call, not something UX can fix with
hex values alone.

## Scaling the palette

**Resolved 2026-07-21 — this section's open question is closed.** Seven
piece archetypes are confirmed (`Simulation.ARCHETYPE_COUNT = 7`, tetromino
shapes), and the seventh hue (Emerald, above) was added by the exact rule
this section originally specified: ≥30° from every existing hue, outside the
reserved 15°–65° band, next lightness step in the alternation, own grain
scale. **The CVD desk-check has not been re-run against Emerald specifically**
— it inherits the same "not run through an actual simulator" caveat the
original six carry (see "Verification performed, and its limits," above);
folded into the same standing QA action item, not a new one.

If gameplay ever needs an *eighth* archetype, the rule still applies, with
one new constraint the current palette has already run into: the open arc
65°→150° that Emerald came from now has Emerald sitting inside it, so a
further addition there has less room; check the actual remaining gaps against
the full seven-hue table above rather than re-deriving from the original
six.
