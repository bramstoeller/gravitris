# Gravitris — candy target imagery (attempt 4)

Rendered target mockups, not a spec document. The brief for this round: stop
trying to shader our way to "glossy candy" from memory and prose, produce an
actual beautiful picture of the target look first, get it approved, then
implement to match. This is that picture.

Everything here is a real PNG rendered from SVG + HTML/CSS through headless
Chromium (see `tools/`) — no photos, no hand-painted assets. That was
deliberate, not just convenient: the real game renders procedurally in GLSL
on deformable soft-body pieces with no authored image assets, so if a look
can't be built from gradients, blurred highlights and blend modes, it isn't a
usable target. Everything in these images is one of those primitives.

## Images

| File | What it is |
| --- | --- |
| `01-full-screen.png` | Full game screen, portrait 1080×2340: light candy-world background, the well mid-game with a settled interlocking stack, two pieces falling, a coverage-band glow, and the minimal HUD (score top-left, level pill top-centre, pause top-right). |
| `02-candy-plate.png` | All seven tetromino shapes at large size, each labelled with its hue name and hex — the unambiguous per-piece material reference. |
| `03-style-board.png` | One piece, annotated: exactly which layer produces which visual effect, plus the full palette. |

## What we looked at first

Five current, full-resolution (1080×2340) Candy Crush Saga screenshots were
fetched directly and viewed — via APKPure's mirror of the Google Play
listing (`apkpure.com/candy-crush-saga/com.king.candycrushsaga`; its CDN
serves the same Play Store screenshot assets as static JPEGs once the
`?fakeurl=1&type=.jpg` query the page itself uses is included — a plain
fetch of the bare image URL 404s, which cost some time to work out and is
worth recording so it isn't re-discovered from scratch next time). Two are
the title and level-complete screens; three are real gameplay boards, which
is the material reference that matters here. (Wikimedia Commons' own Candy
Crush photos, checked first, turned out to be promotional photos of foam
mascot costumes and a blurry phone snapshot — not usable, noted so it's
clear that path was tried and ruled out rather than skipped.)

Directly off the gameplay screenshots:

- **One hard, near-white specular highlight per candy, biased upper-left,
  elongated along the piece's own long axis** — a bright streak that
  follows the surface's curve, sharp at its brightest point and feathering
  out, not a soft wide Phong lobe. On the orbs it's a curved crescent; on
  the cylindrical pieces it's a vertical bright band down one side.
- **The base body is flat and highly saturated with almost no gradient of
  its own** beyond a touch of edge-darkening at the silhouette boundary —
  nearly all the dimensionality comes from the highlight, not from a
  body-wide light-to-dark shade.
- **A soft, low-contrast shadow sits under every piece**, visible mainly in
  the gaps between candies where the muted slate-blue-grey tray shows
  through — not a hard silhouette double.
- **The tray is cool and muted; the scene beyond it is bright, saturated
  and atmospheric** (teal cave light with heavy background blur in the
  gameplay shot) — the tray is calm so the candies against it read as the
  brightest, most saturated thing on screen.
- A handful of pieces (stippled dotted hexagons) are **matte and
  pattern-textured instead of glossy** — confirms pattern variation is the
  cheap way to add piece variety, not a second geometry family.

This matches the previous UX round's own research
(`docs/ux/visual-direction.md` §11-13 on the `docs/visual-redirect-candy`
branch, written before this round started) closely enough that we didn't
need to re-derive the target from scratch — it let us spend the time on
actually building the image instead of re-confirming the same reference a
third time. One deliberate departure from the reference, not an oversight:
our base-body gradient (light-to-deep, top-to-bottom) is more pronounced
than the real Candy Crush's near-flat body. The brief asks for "plump
volume," and a body that does a little more shading work reads plumper at
the cost of being slightly less flat-saturated than the reference — a
trade we made on purpose, not a miss.

We also cross-checked the material itself (not just the look) against how
gummy/jelly candy is actually shaded in 3D — Blender/Octane/RenderMan
gummy-candy breakdowns consistently reach for subsurface scattering + a
glossy specular + high roughness variance for the "wet jelly" read, which
is the same recipe in different language: a glow term standing in for
subsurface scattering, a hard specular for the glossy term.

Sources: [Candy Crush Saga — Google Play, mirrored by APKPure](https://apkpure.com/candy-crush-saga/com.king.candycrushsaga)
(three gameplay + two menu screenshots fetched directly from
`image-eo.winudf.com`, viewed at full 1080×2340 resolution);
[Category:Candy Crush Saga — Wikimedia Commons](https://commons.wikimedia.org/wiki/Category:Candy_Crush_Saga)
(checked, not used — promotional photos, not gameplay material).

## Palette

Seven tetromino hues, kept genre-conventional (I=cyan, O=yellow, T=purple,
S=green, Z=red, J=blue, L=orange) so player muscle memory transfers, each
CVD-checked by lightness separation (all seven base hues sit at very
different L values, and shape already disambiguates S from Z, the one
adjacent red/green pair, independent of colour):

| Piece | Name | Base | Light | Deep | Rim light | Rim deep | Shadow |
| --- | --- | --- | --- | --- | --- | --- | --- |
| I | Blue Raspberry | `#22C7E5` | `#85E0F1` | `#157B8E` | `#BDEEF7` | `#0F5A67` | `#416D6C` |
| O | Mango Lemon | `#FFCC33` | `#FFE38F` | `#9E7E20` | `#FFF0C2` | `#735C17` | `#946E29` |
| T | Grape Fizz | `#B463FF` | `#D6A9FF` | `#703D9E` | `#E9D0FF` | `#512D73` | `#774776` |
| S | Green Apple | `#42D97A` | `#97EAB6` | `#29874C` | `#C6F4D7` | `#1E6237` | `#4D7444` |
| Z | Cherry | `#FF5470` | `#FFA1B0` | `#9E3445` | `#FFCCD4` | `#732632` | `#944140` |
| J | Blueberry | `#4A7DFF` | `#9BB8FF` | `#2E4E9E` | `#C9D8FF` | `#213873` | `#505176` |
| L | Tangerine | `#FF9A3D` | `#FFC794` | `#9E5F26` | `#FFE1C5` | `#73451B` | `#945C2D` |

Every derived stop is a mechanical mix of the single base hex (light = base
→ white 45%, deep = base → black 38%, rim-light = base → white 70%, rim-deep
= base → black 55%, shadow = base → `#8a5a3a` 50% → black 25%) — see
`tools/color.mjs:materialStops()`. One formula, seven pieces; nothing
hand-tuned per colour.

Neutrals: sky top `#EAF1FF`, sky bottom `#FFE6D6`, well cream `#FFFBF3`, well
deep `#F4E1C4`, score pink `#FF3D77`, level-pill purple `#8A4FE0`.

## Material recipe (see `03-style-board.png` for the annotated version)

Six layers, every one a gradient, blur or blend mode a fragment shader can
do on a deformable mesh — no baked texture, no photo, nothing that stops
working when the piece squashes:

1. **Silhouette** — a single fused, whole-piece rounded outline (not four
   separately-rounded cells). Built by taking the union of the piece's unit
   cells, walking the resulting boundary, and rounding every corner with a
   radius: convex corners bulge outward (normal rounded-rect corner),
   concave (reflex) corners fillet *inward*, so an S/T/L/J piece reads as
   one plump candy with a notch, never four stitched squares. This was the
   single biggest visual complaint in every prior round — see
   `tools/shape.mjs:outlinePath()`.
2. **Base gradient** — linear, top-light → bottom-deep, ~20° off vertical.
   The one deliberate departure from the real reference (see above): Candy
   Crush's own body is closer to flat, with the highlight doing nearly all
   the shaping work. Ours does a bit more of that work itself, trading a
   little of that flatness for the "plump volume" the brief asks for.
3. **Subsurface glow** — a large soft radial (white, 0.65→0 opacity),
   heavily blurred, `soft-light` blend, centred on the piece's topmost-left
   *filled cell* (not its bounding-box corner — an L/S/J piece's mass sits
   far from its bbox corner, and the first render of this literally
   produced invisible highlights on three of four test pieces before this
   was caught and fixed). This is the translucency proxy: it reads as
   "light passing through" without needing real alpha blending or
   depth-sorted transparency, which a single-draw-call renderer doesn't
   have room for.
4. **Specular gleam** — a hard, elongated white ellipse (~58%×24% of one
   cell), rotated -24°, `screen`-blended, anchored to the same topmost-left
   cell. This is the one feature every real reference agreed on — the
   "wet gleam" that reads from across a store screenshot.
5. **Sparkle** — one tiny bright dot at the gleam's upper-right tip. Cheap,
   and it's the difference between "glossy" and "glossy and alive."
6. **Rim / edge bevel** — a thin stroke on the silhouette itself, light at
   the top and deep at the bottom (own linear gradient) — the glassy edge
   line, most visible where two pieces sit edge to edge.
7. **Contact shadow** — the same silhouette again, filled flat with a warm
   tray-tinted colour (never pure black — `shadow` column above, mixed
   toward `#8a5a3a` before going toward black), offset down ~24% of a cell,
   heavily blurred, ~45% opacity, slightly foreshortened vertically.

Falling (airborne) pieces get one more element that's page-composition, not
per-piece material: a second, separate soft blurred ellipse projected
further down onto the floor beneath them, independent of their own built-in
contact shadow, so height reads before anything touches down.

## Coverage-band glow

`01-full-screen.png`'s bottom row is real, not staged by hand: the stack is
generated by an actual hard-drop simulator (`tools/stack.mjs`) that lands
each tetromino on the tallest column it spans, exactly like the real game
would, so the interlocking is physically valid, not hand-plotted. The
row-fill count is then computed from that same simulated stack and the
fullest row gets the gold glow band — in this render, one real cell short
of clearing. This is a placeholder for whatever the real coverage-clear
threshold turns out to be (this doc doesn't know the exact rule); the visual
device — a warm gold horizontal glow, brighter toward the row's centre —
is the target, independent of the exact trigger condition.

## What we deliberately did not do

- **No true alpha-blended transparency.** Costed and rejected the same way
  the previous UX round rejected it: a single-draw-call renderer can't
  depth-sort overlapping translucent geometry. The subsurface-glow term is
  the honest substitute.
- **No baked texture or grain of any kind**, per the brief's "smooth, no
  grainy texture" constraint — every layer here is a procedural
  gradient/blur, reproducible at any resolution.
- **No per-cell corner rounding.** Considered and rejected for the same
  reason the previous UX round rejected it: it pinches at same-piece cell
  seams and reintroduces the "four separate squares" look this whole
  redirect exists to fix.
- **Didn't invent a screenshot we couldn't verify.** Where real reference
  images were blocked, that's stated above rather than quietly worked
  around with a description that reads as if it were observed.

## Regenerating

Everything is plain Node ESM + headless Chromium, no build step, no
dependency install beyond what's already on this machine (`chromium`,
`node`, `rsvg-convert`/`imagemagick` for one-off inspection only — the
actual renders go through Chromium, not librsvg, so CSS blend modes and
Google Fonts work):

```
node tools/compose-screen.mjs       # -> 01-full-screen.png
node tools/compose-plate.mjs        # -> 02-candy-plate.png
node tools/compose-styleboard.mjs   # -> 03-style-board.png
```

`tools/shape.mjs` (outline/rounding), `tools/color.mjs` (palette + material
stops), `tools/piece.mjs` (the six-layer material), `tools/stack.mjs` (the
hard-drop simulator) and `tools/layout.mjs` (grid→pixel placement) are
shared by all three. `tools/*.html` are regenerated scratch output
(git-ignored); the three PNGs at the top of this directory are the
deliverable.
