# Gravitris — candy target imagery (v3: winegum pass)

Rendered target mockups, not a spec document. v2 (see git history / the
previous revision of this file for its own research) modernized the material
to compete with 2026 flagships and was approved as "ok-ish" — but the client's
own words on seeing it were **"I still find it minimal. I expected more
wine-gum-like blocks."** That's a material-identity note, not a polish note:
the pieces were reading as flat glossy hard-candy gems (a boiled-sweet
material), and the client wants **dense, chewy, three-dimensional winegums**
(Haribo/Maoam-style) instead. This pass is that material rebuild, plus the
client's second, explicit question — **"Is er nagedacht over rotatie?"** ("has
rotation been thought about?") — answered by construction, not assertion: a
new shared, fixed light direction and a fourth image that proves it holds
across every quarter-turn and under deformation.

Everything here is still a real PNG rendered from SVG + HTML/CSS through
headless Chromium (see `tools/`) — no photos, no hand-painted assets, no new
tooling. That constraint didn't change: the real game renders procedurally in
GLSL on deformable soft-body pieces with no authored image assets, so every
new effect below had to be something a fragment shader can actually do
(gradients, blurred highlights, blend modes, and per-frame transforms) —
nothing that only works as a pre-rendered picture.

## Images

| File | What it is |
| --- | --- |
| `01-full-screen.png` | Full game screen, portrait 1080×2340: a layered mesh-gradient candy world with bokeh and a whisper of vignette, the well mid-game with a settled interlocking stack of winegum pieces (one piece mid-landing-squash, contact-AO seams between touching pieces), two pieces falling, a coverage-band clear rendered as an actual light burst, and a tightened HUD (score top-left, level pill top-centre with a functional glow ring, pause top-right). |
| `02-candy-plate.png` | All seven tetromino shapes at large size, each labelled with its hue name and hex — the unambiguous per-piece winegum material reference. This is the single best image for judging the material change on its own. |
| `03-style-board.png` | One piece (a T, not an O — see below), annotated: every layer that produces the material, in paint order, plus the full palette and a note on the world-anchored lighting rule. |
| `04-rotation-strip.png` | **New.** The direct answer to "has rotation been thought about?" — the same T piece in all four quarter-turns plus one squashed (mid-deformation) variant, each with its actual computed highlight anchor marked, proving the highlight stays on the side of the piece facing the one fixed light rather than spinning with the piece's own shape. |

## Benchmarking the actual 2026 bar, not just Candy Crush

The brief asked explicitly not to re-derive the target from Candy Crush alone
this time. What was actually pulled and looked at:

- **[Royal Match](https://apps.apple.com/us/app/royal-match/id1482155847)**
  (Dream Games) — the current #1-grossing casual puzzle game
  ([Udonis, "100 Top Grossing Mobile Games Ranking [2026]"](https://www.blog.udonis.co/mobile-marketing/mobile-games/top-grossing-mobile-games),
  reporting ~$1.3B and the top casual-puzzle spot for another year running).
  Three real gameplay/meta screenshots fetched at full device resolution via
  the iTunes lookup API (`itunes.apple.com/lookup?id=1482155847`, which
  returns the actual `screenshotUrls` — the App Store's own page is a
  client-rendered SPA and doesn't expose them to a plain fetch, worth
  recording so it isn't rediscovered from scratch). Confirmed directly: a
  thick warm-gold ornate frame around the board, a cinematically-lit 3D
  scene behind/around play (real bloom on chandelier highlights, depth-of-
  field blur on background elements, warm/cool contrast), and chunky
  strongly-beveled pill chips for counters/timers.
- **["Block Blast" — Puzzle Game & PVP](https://apps.apple.com/us/app/block-blast-block-puzzle-pvp/id1562613779)**
  — the closer genre comp: a block-fitting grid-fill puzzle, not a match-3,
  the same family Gravitris actually is. Six screenshots fetched the same
  way (`itunes.apple.com/lookup?id=1562613779`). This is where the clear
  effect reference came from directly: clearing a line bursts bright bloom
  **over** the tiles (not hidden behind them) plus a scatter of coloured
  gem-shard confetti with a soft outer glow on each shard, and the whole
  board sits inside a warm, moodier frame than the flat pastel Candy Crush
  uses. (Block Blast! by Hungry Studio is one of several same-named block
  games; this is the one that actually returned real screenshots — noted so
  the id isn't re-guessed next time.)
- **General 2026 mobile UI/art-direction research** (WebSearch, not a single
  source): mesh gradients — layered, multi-point radial colour fields rather
  than one flat linear ramp — are the dominant background treatment of the
  last two years and read as the current "premium app" signal
  ([CSS-Zone gradient trends 2026](https://css-zone.com/blog/css-gradient-trends-2026);
  [gradientshub mesh-gradient guide](https://gradientshub.com/blog/how-to-use-mesh-gradient-generator-design-tool/)).
  Equally important, and a real course-correction from a first instinct to
  add *more* gloss everywhere: current UI-trend writeups are explicit that
  2026's best interfaces are **backbone with flair, not glow-maximalism** —
  cleaner HUD chrome, and glow/bloom reserved for functional, meaningful
  moments rather than decorating everything
  ([tubikstudio, "What's Next: 7 UI Design Trends of 2026"](https://tubikstudio.com/blog/ui-design-trends-2026/)).
  That single finding is why the HUD below got *tightened*, not
  *more decorated*, while the atmosphere and material got richer.

This is layered on top of, not a replacement for, attempt 4's own Candy Crush
screenshot research (five real gameplay screenshots, full resolution) —
still valid, though this pass changes the base material the previous rounds
established (see "Winegum reference" below). See the previous revision of
this README (`git log -p -- design-target/README.md`) for the v2 write-up in
full; it isn't repeated here.

## Winegum reference (v3)

The client's note named a specific real product category, so the actual
material was looked up rather than approximated from memory:

- **Texture and density.** Haribo's own product copy for Wine Gums describes
  them as "aromatic fruit gum pieces with a particularly soft texture" and a
  "firm yet chewy texture that delivers a satisfying bite"
  ([Haribo UK, Wine Gums](https://www.haribo.com/en-gb/products/haribo/wine-gums);
  [Lehr's German Specialties, product listing](https://www.lehrssf.com/product/haribo-german-wine-gums-gummy-candy/2463)) —
  firm and dense, not soft and glassy: a different material family from a
  boiled hard-candy gem entirely, confirming the client's own framing.
- **Surface sheen.** Maoam's own ingredient listing names a glazing agent
  (white and yellow beeswax) as the source of the surface sheen on a related
  Haribo chewy-candy line
  ([GermanDeliStore, Maoam](https://germandelistore.com/maoam/)) — a thin wax/
  sugar glaze producing a soft, low, broad sheen, not the tight mirror-hard
  streak a boiled-sugar hard candy or a glass gem reflects. This is the direct
  source for "soften and broaden the specular, lower its peak whiteness"
  below.
- **Translucency.** General stock-photography research on backlit/macro
  gummy and fruit-jelly candy ([Dreamstime, "Translucent Candy"](https://www.dreamstime.com/photos-images/translucent-candy.html);
  [StockCake, glowing gummy imagery](https://stockcake.com/i/glowing-gummy-bear_1541709_1178582))
  confirms the standard visual behaviour of any dense, saturated gel under
  light: it glows richer and more saturated at its own thickest point, and a
  thin cross-section (an edge, a thin arm) transmits and scatters more light
  than a thick one — ordinary subsurface scattering, not a winegum-specific
  trick, but the exact physical basis for both the "core pool" and the new
  "fresnel edge-glow" layers below.

Net finding: three concrete, physically-grounded shifts, not a vague
"richer" — a softer/broader specular, harder subsurface at both the piece's
thick centre and its thin edges, and enough silhouette/bevel volume that the
piece reads as a solid chewy mass. All three are in the material recipe below.

## Palette

Base hues unchanged from attempt 4 — the client's note was about material
(volume, translucency, specular quality), not colour, and the palette was
already CVD-checked and genre-conventional. Seven tetromino hues (I=cyan,
O=yellow, T=purple, S=green, Z=red, J=blue, L=orange):

| Piece | Name | Base | Light | Deep | Rim light | Rim deep | Shadow |
| --- | --- | --- | --- | --- | --- | --- | --- |
| I | Blue Raspberry | `#22C7E5` | `#85E0F1` | `#157B8E` | `#BDEEF7` | `#0F5A67` | `#416D6C` |
| O | Mango Lemon | `#FFCC33` | `#FFE38F` | `#9E7E20` | `#FFF0C2` | `#735C17` | `#946E29` |
| T | Grape Fizz | `#B463FF` | `#D6A9FF` | `#703D9E` | `#E9D0FF` | `#512D73` | `#774776` |
| S | Green Apple | `#42D97A` | `#97EAB6` | `#29874C` | `#C6F4D7` | `#1E6237` | `#4D7444` |
| Z | Cherry | `#FF5470` | `#FFA1B0` | `#9E3445` | `#FFCCD4` | `#732632` | `#944140` |
| J | Blueberry | `#4A7DFF` | `#9BB8FF` | `#2E4E9E` | `#C9D8FF` | `#213873` | `#505176` |
| L | Tangerine | `#FF9A3D` | `#FFC794` | `#9E5F26` | `#FFE1C5` | `#73451B` | `#945C2D` |

Every derived stop is still a mechanical mix of the single base hex (light =
base → white 45%, deep = base → black 38%, rim-light = base → white 70%,
rim-deep = base → black 55%, shadow = base → `#8a5a3a` 50% → black 25%, core =
base → black 15%, **new this pass:** bright = base → white 68%, edgeGlow =
base → white 55%, edgeShade = base → black 50%) — see
`tools/color.mjs:materialStops()`. One formula, seven pieces; nothing
hand-tuned per colour. The three new stops each answer one specific winegum
note: `bright` replaces flat white in the subsurface glow (translucent light
picks up the medium's own colour, it doesn't stay neutral); `edgeGlow` is the
fresnel/edge-translucency band; `edgeShade` is the all-around bevel shadow
that gives the piece "chunky 3D volume."

**New constant, not a per-hue stop:** `COATING_SHIMMER = '#D8F0FF'` — a single
fixed pale-cyan tint used for the iridescent coating sliver on every piece
regardless of hue (see layer 11 below). This is deliberately *not* derived
from each base hex: a glaze coating is the same physical layer on top of
every flavour of real candy, so giving it one shared constant is more
mechanically honest than tuning it seven times, not a shortcut.

Neutrals unchanged: sky top `#EAF1FF`, sky bottom `#FFE6D6`, well cream
`#FFFBF3`, well deep `#F4E1C4`, score pink `#FF3D77`, level-pill purple
`#8A4FE0`.

## Material recipe (see `03-style-board.png` for the annotated version)

The style board annotates a **T piece, not an O** — a single-cell O has no
concave vertex, so it can't demonstrate layer 7 below. Every layer is still a
gradient, blur or blend mode a fragment shader can do on a deformable mesh,
in paint order. Layers marked *(new)* are this pass; the rest carry over from
v2 unchanged in mechanism, though several have retuned values (noted inline)
because the winegum material needed them calmer or stronger, not different:

1. **Silhouette** — the fused, whole-piece rounded outline
   (`tools/shape.mjs:outlinePath()`), convex corners bulging outward and
   concave corners fillet inward. **Retuned:** corner radius `0.30 → 0.38` of
   a cell — a thicker, more pronounced bevel, so the piece reads as a solid
   chewy block with real mass rather than a flat rounded rectangle.
2. **Base gradient** — unchanged: linear, top-light → bottom-deep, ~20° off
   vertical, doing almost no shading of its own (matches the genre).
3. **Ambient per-cell fill** *(new)* — one small, very soft, hue-tinted
   (`bright`) blob per filled cell, `soft-light`-blended at low opacity.
   Independent of the lit anchor and the core pool below on purpose: a
   multi-lobe piece's far arm (an L's foot, an S's other lobe) sits outside
   both of their reach, and without this it read flat and muddy — all
   bevel-shadow and no "lit from within," exactly where the client's own
   "juicy" ask most needed to hold (found by rendering an L on its own and
   looking, not assumed). Sized *per cell*, not to the piece's whole
   bounding box — a bbox-sized version was tried first and it overcorrected,
   washing a piece's own single-cell-wide arm out almost to white, because
   an ellipse sized for a piece's *widest* arm is exactly as wide when
   that same piece's *other* arm is a single cell across. See "Considered
   and rejected."
4. **Concave self-occlusion AO** — every reflex (concave) vertex of a
   multi-lobe piece (where an S/T/L/J/Z's two lobes meet) gets a soft dark
   radial, multiply-blended, nudged inward from the boundary along the
   vertex-to-centroid direction, reading directly off
   `tools/shape.mjs:outlinePath()`'s own returned concave-vertex list so it
   can never drift out of sync with where the notches actually are.
5. **Chunky-3D bevel** *(new)* — a stroke on the piece's own silhouette
   path, tinted with the new `edgeShade` stop, half of it clipped away by
   that same silhouette (the whole material is painted inside a `<g>`
   clipped to the outline), leaving an inset dark band hugging the INSIDE of
   every edge — top, sides and bottom alike, not only the bottom-anchored
   layer 10 below. This is the "soft inner shadow / edge darkening that
   reads as thickness" the brief named directly: a real self-shadow where a
   thick chewy mass turns the corner at its own silhouette, on every side,
   not only where it sits on the tray.
6. **Warm core pool** — a warmer radial (tinted with the piece's own `core`
   stop) centred on the piece's cell-count centroid, `soft-light`-blended,
   reading as light gathering where the gel is thickest. **Retuned:**
   opacity and radius both up, and radius now scales with the piece's own
   bounding box rather than a flat constant, so a long piece's pool actually
   reaches its own extent.
7. **Subsurface glow** — the large soft radial anchored to `mostLitCell()`
   (see "World-anchored lighting" below), `soft-light`-blended. **Retuned:**
   recoloured from flat white to the new `bright` stop (translucent light
   picks up the medium's colour, it doesn't stay neutral) and pushed
   stronger, per "push subsurface hard" — this is now the dominant read, not
   a subtle accent.
8. **Fresnel / edge-translucency** *(new)* — a thin, brighter band
   (`edgeGlow`) right at the silhouette boundary itself, screen-blended,
   thinner and less blurred than the bevel above so the two read as
   distinct: a bright rim, then a darker crease a little further in. A thin
   cross-section passes more light than a thick one under the same light,
   so a real gummy's own edge rims brighter than its middle — the signature
   winegum translucency cue, made visible at the silhouette, not only at
   the centre (layer 6).
9. **Bloom halo** — a second, larger and much softer white radial sits
   behind the specular gleam, `screen`-blended, heavily blurred — the
   bright-pass-and-blur a real bloom pass performs. **Retuned:** opacity
   eased down from v2's strength, so it reads as a soft satin sheen once
   stacked with the richer layers above, not a glass highlight.
10. **Specular gleam** — the elongated white ellipse, fixed -24° screen-
    space tilt (a page constant — see "World-anchored lighting"). **Retuned,
    this is the big one:** broadened (wider, rounder footprint), much more
    blurred, and peak opacity roughly halved — a soft satin sheen from a thin
    wax/sugar glaze (see "Winegum reference"), not a hard glass streak.
11. **Coating sliver** — a small, thin, pale-cyan (`COATING_SHIMMER`)
    ellipse riding the gleam's own edge, screen-blended, same rotation as
    the gleam. **Retuned:** opacity eased down to match the calmer gleam
    above. Still the ownable "this candy has a glassy coat" signature.
12. **Sparkle** — the tiny bright dot at the gleam's tip, opacity eased down
    slightly to match.
13. **Rim / edge bevel** — unchanged: a thin light-to-deep stroke on the
    silhouette itself.
14. **Contact shadow** — unchanged: same silhouette, warm tray-tinted, offset
    down, heavily blurred, grounding the piece without a hard edge.

Falling pieces still get the separate page-composition cast-shadow ellipse
from earlier rounds.

**On thin cross-sections and highlight size:** layers 7, 9 and 10 (subsurface
glow, bloom, gleam) are all anchored to `mostLitCell()` and sized in
cellSize units, which reads as a nice soft highlight *spot* on a piece that
is several cells wide there — but a piece that is only one cell wide at the
anchor (a vertical I, the plain stem of a rotated L/J) has no width for a
gradient that size to fall off across, so an unclamped highlight paints the
whole available width uniformly instead of fading: a flat wash, not a
highlight. `piece.mjs`'s `hiRx`/`hiRy` helpers cap those three layers'
radii to the piece's own real extent through the anchor cell
(`rowSpan`/`colSpan`, counted from the actual cells, not guessed) so the
gradient is always forced to do at least some of its own falloff *inside*
the piece. (Layer 3, the ambient per-cell fill, doesn't have this problem in
the first place — it's already sized per-cell, which is exactly why it
replaced an earlier bbox-sized attempt; see "Considered and rejected.") The
`hiRx`/`hiRy` caps help a lot but don't fully erase a residual pale tip on
the thinnest (single-cell) arms in the full-screen render — see "Open
questions."

## World-anchored lighting (new — answers "has rotation been thought about?")

The client's second note was specific: the pieces snap-rotate a quarter turn
during play and also deform (soft-body), and the material has to keep
reading correctly in *every* orientation — specifically, the highlight/sheen
must not spin with the piece. A highlight that rotates with the object it's
drawn on reads as a decal glued to the surface; a highlight that stays put
while the object turns underneath it reads as a real light source, because
that's what a real light source does.

**The rule, for whoever implements the real shader:** compute specular /
subsurface / fresnel intensity per-fragment from the CURRENT world-space (or
camera-space) surface normal — after rotation and after soft-body
deformation — dotted against one FIXED light direction. Never bake a
highlight's position or tilt into the mesh's local/object space (a UV
coordinate, a vertex authored at rest-pose) and simply let the piece's own
rotation transform carry it along; that is the exact failure mode being
guarded against here. Done correctly, the bright region *slides across the
surface* every frame to stay facing the fixed light as the piece turns and
squashes — exactly like a real solid object under a fixed lamp.

**What this mockup actually does, since a still PNG can't compute a
per-fragment normal:** `tools/light.mjs` defines one constant, `LIGHT_DIR`
— screen-space, mostly from above and ~21° to the left, matching the body
gradient's own tilt and the gleam's -24° screen-space rotation, both of
which are *also* page constants, never derived from a piece's own
orientation. `mostLitCell(cells)` scores every one of a piece's own filled
cells by how far it sits toward `LIGHT_DIR` from the piece's own centroid,
and picks the winner — never a bounding-box fraction (which can land in the
clipped-away empty space of an asymmetric piece) and never the old
topmost-then-leftmost grid sort v2 used (which happened to agree with the
light direction at a piece's spawn orientation only because grid iteration
order and the light's own lean both run top-to-bottom, left-to-right — it
was never actually reading the light vector, so nothing guaranteed it would
keep agreeing once a piece's cells were reshuffled by a quarter-turn).
Scoring against the same fixed vector every rotation reads generalizes
correctly: the anchor a piece picks is always "whichever real, filled part
of this shape the fixed light would reach first," which is the one thing a
rotation must never change.

**Proof, not assertion:** `04-rotation-strip.png` renders one T piece at all
four quarter-turns plus one squashed variant, each with its actual computed
`mostLitCell()` anchor marked as a small ring — read directly off the same
call `piece.mjs` makes, not illustrated separately, so the page can't drift
out of sync with what the material actually does. Look at where the ring
sits across all five cards: always on the side of the piece's own mass that
faces the fixed upper-left light, never trailing around to a different side
because the piece's shape rotated.

## Depth between pieces, not just within one (new, page composition)

The concave AO above only covers where *one* piece's own two lobes meet.
Two *different* settled pieces sitting edge to edge is the "AO where pieces
meet" the brief actually asked for, and that can't be a per-piece material
effect — no single piece's shader knows what's sitting next to it. It's
computed once, after layout, exactly like the existing falling-piece cast
shadow already is:

`tools/stack.mjs:crossPieceSeams()` walks the settled stack's cell grid and
returns every grid edge shared by two cells that belong to *different*
pieces (an edge shared by two cells of the *same* piece is the concave-AO
case above, already handled, and is excluded). `compose-screen.mjs` draws a
short, heavily blurred, low-opacity dark seam along each one, underneath the
pieces. This is exactly the adjacency information a real soft-body solver
already has to maintain (two separate deformable meshes in contact) — the
mockup isn't inventing data the engine wouldn't have.

## Juice, shown in the still (new, page composition)

Two devices, both applied as compositional transforms/overlays rather than
material changes — the same category the falling-piece cast shadow already
established as legitimate in attempt 4:

- **Landing squash.** The most-recently-landed piece in the settled stack
  (`well.placements`' last entry) is rendered with `transform:
  scale(1.06, 0.90)` from its own base-centre — a transform on the wrapping
  div, never touching `renderPiece`'s material — plus a soft white impact
  flash at its footprint. `tools/layout.mjs:placePieceDiv()` grew an
  `extraStyle` option for exactly this: CSS applied to the wrapper only, so
  it can never be mistaken for (or accidentally merged into) a material
  parameter. A real squash is a transform on the deformable mesh for a few
  frames after contact, not a different shader — this is that, honestly
  represented.
- **Coverage-band clear, reworked as an actual event.** Attempt 4's gold bar
  sat entirely *behind* the settled pieces, visible only through the row's
  one remaining gap — accurate to "this row is one cell from clearing" but
  not to "clearing is happening," and it was the one place a direct
  Block Blast comparison showed the gap: their clear FX overlays the tiles.
  The glow is now split into two passes: an under-layer (unchanged ambient
  bleed, still behind the pieces) and a new over-layer — a bright,
  screen-blended horizontal sweep plus soft radiating rays plus a scatter of
  small hued shard particles (one per palette hue, reusing the existing
  sparkle polygon), all drawn **after** the piece layer so they visibly
  erupt over the row rather than hide behind it.

## Atmosphere (new)

The flat single diagonal gradient is now five layered radial colour pools
(a mesh gradient: soft blue top-left, lavender top-right, mint bottom-left,
peach bottom-right, over the original linear ramp as a base) plus richer,
more numerous bokeh circles, a field of tiny low-opacity dust flecks, one
very low-opacity conic light-ray sweep, and a whisper of vignette (a radial
darken that only bites in the extreme corners, `rgba(70,40,80,0.14)` at
its strongest). None of this touches saturation or value enough to
compete with the candy itself for attention — the point was a sense of
*place* behind the board, not a mood shift away from "light and candy."
Unchanged this pass — the brief was explicit that the winegum material is
the main lever for "still minimal," and it is: the same scene reads
noticeably fuller with the new material alone (compare `01-full-screen.png`
against the v2 revision in git history), so this round didn't add more
atmosphere on top.

## Refined HUD (new)

Per the 2026 research above (flair should sit in material/atmosphere, not
HUD chrome), the HUD got *tighter*, not busier: the score/level type scale
was pulled in a step (score value 56px→50px, level text 34px→29px, tighter
letter-spacing throughout), pill drop-shadows swapped from a uniform muddy
brown to a shorter, cooler-neutral shadow, and the level pill — the one
"you are here" indicator on screen — got a soft coloured glow ring
(`box-shadow: 0 0 0 5px rgba(180,120,255,0.16)`) as the one functional
accent glow, instead of decorating every chip equally. Layout, icon
inventory (score / level / pause) and the "reduce decisions" minimalism are
otherwise unchanged from attempt 4.

## Coverage-band glow: still a placeholder

Same caveat as attempt 4, unresolved: the settled stack is generated by a
real hard-drop simulator (`tools/stack.mjs`), so the fullest row and its
one-cell gap are physically real, not staged. The glow/clear-burst device
is the target regardless of the exact coverage-clear rule, which this doc
still doesn't know.

## What we deliberately did not do

- **No dark or moody backdrop.** Royal Match's own background is a richly
  lit but fairly dark 3D scene; considered matching that directly and
  rejected it — the brief is explicit that Gravitris stays "light and
  candy," and a dark backdrop would fight the palette's own readability and
  shift the genre-signal from daylight casual-puzzle toward something
  heavier. The mesh-gradient + bokeh treatment gets atmosphere without that
  trade.
- **No per-hue iridescent tint.** Considered hue-rotating the coating sliver
  per piece (a small rainbow-fringe effect) instead of one shared constant.
  Rejected: it would have read as chromatic-aberration gimmick rather than
  "wet glaze," and it breaks the "one recipe, seven pieces" mechanical
  consistency attempt 4 deliberately established.
- **No darker/cooler tray.** Royal Match's board is a cool slate against a
  bright scene; the well here stays the same warm cream. Changing the world
  around it, not the board itself, was the brief's ask.
- **Still no true alpha-blended transparency, no baked texture, no per-cell
  corner rounding** — same reasoning as attempt 4, unchanged.
- **No grainy/waffle surface texture.** The client rejected this direction
  twice before (recorded in earlier revisions). The winegum richness comes
  entirely from translucency + volume + bevel + specular quality, not from
  noise — every new layer this pass is still a gradient, a blur or a blend
  mode, nothing sampled or textured.
- **No change to the corner-radius / silhouette *shape language* beyond the
  bevel size.** The pieces are still the same fused-outline tetromino
  silhouettes; "chunky 3D" is achieved through shading (bevel, fresnel, core
  pool) and a bigger corner radius, not through a different silhouette
  algorithm.

## Considered and rejected

- **Whole-frame post-process bloom** (a single blurred+screened copy of the
  entire composed image) instead of per-element bloom halos. Rejected: it
  would bloom the HUD and background bokeh too, undoing the HUD tightening
  above, and a real engine doing per-piece bloom on isolated bright pixels
  (the gleam) is closer to what a forward-renderer would actually do than a
  full-screen post pass on a mobile GPU budget.
- **A literal confetti/particle burst library** (dozens of varied shard
  shapes, motion blur streaks) for the clear moment. Rejected for a still
  image: a static frame can't show motion, and overselling particle count
  in a single frame reads as clutter rather than "about to happen" — twelve
  hue-matched shards at varied size/rotation was enough to read as a burst
  without looking like debris.
- **Squashing every piece slightly** for uniform "bounciness" instead of
  just the most-recently-landed one. Rejected: if every piece looks
  squashed, none of them reads as *mid-event* — the whole point is that
  exactly one piece looks like something just happened to it.
- **Ambient subsurface fill sized to the piece's whole bounding box**,
  tried first for the winegum pass. One big ellipse centred on the bbox,
  radius proportional to bbox width/height, was the obvious first attempt at
  "give every cell some subsurface lift." Rendered and rejected: a bbox
  ellipse sized for a piece's *widest* arm is, by construction, exactly as
  wide when that same piece's *other* arm is a single cell across, so it
  washed a thin stem out almost to white while barely helping the wide arm
  it was sized for. Replaced with one small blob *per filled cell* (see
  layer 3 in the material recipe), which answers to the cell it sits on
  instead of the shape's overall extent.
- **Deriving the gleam's screen-space tilt from the piece's own rotation
  state**, considered while building the rotation strip. Rejected
  immediately once written out: a highlight's screen-space tilt is a
  property of the fixed light and viewing angle, not of the object under it
  — a real specular streak's orientation on a rotating object changes only
  because the *surface* it's riding changes shape, never because "the whole
  highlight" rotates as a rigid decal. The gleam's -24° stays a page
  constant in every orientation in `04-rotation-strip.png`, exactly like
  `LIGHT_DIR` itself.
- **Topmost-then-leftmost cell sort, kept as the highlight anchor rule.**
  This was the actual v2 mechanism and reads correctly for a piece's spawn
  orientation, purely by coincidence of grid iteration order. Kept it
  briefly while writing the "World-anchored lighting" section and realized
  it doesn't generalize (see that section for the full reasoning) —
  replaced with `mostLitCell()`, which scores against the same `LIGHT_DIR`
  every other effect reads.

## Open questions

- **Residual pale tip on the thinnest (single-cell-wide) arms**, e.g. a
  rotated L's plain stem in `01-full-screen.png`'s settled stack. The
  `hiRx`/`hiRy` caps (material recipe, "On thin cross-sections and
  highlight size") substantially reduce this but don't fully erase it — the v2 material had the same
  characteristic on the same piece, smaller (see git history), because a
  crisper/smaller specular has less to compound; broadening and stacking
  more subsurface layers on top, exactly as the winegum brief asked, made it
  more visible on this specific narrow-cross-section case. There's a
  legitimate reading where this is *correct*, not a bug — a genuinely thin
  cross-section of a translucent material does transmit more light and look
  paler — but it's worth a second, harsher look specifically at narrow
  pieces before this ships as the target. Flagged, not hidden.
- Whether the landing-squash is pronounced enough in a still frame to read
  as "just landed" rather than "slightly wrong proportions" — it's subtle
  by design (a real squash only holds for a few frames in motion), but a
  still can't rely on motion to disambiguate. Same open question as v2,
  still unresolved; the winegum squash card in `04-rotation-strip.png` uses
  a slightly more pronounced scale for the same reason.
- Bloom, the fresnel band and the concave-AO crease are all soft,
  low-contrast effects tuned by eye at this render's resolution (1080 px
  wide, cellSize ~120). Worth a flag to whoever implements the actual
  shaders: at a smaller on-device piece size these could fall below visible
  contrast entirely and may need a minimum-size floor rather than a pure
  percentage-of-cell radius. This applies doubly now: the bevel and fresnel
  bands are inherently thin effects, by design, more likely to disappear at
  small sizes than the older, broader layers.
- Same open question as v2/attempt 4, still unresolved: confirm the real
  coverage-clear rule against the placeholder "fullest row" visual device.

## Regenerating

Still plain Node ESM + headless Chromium, no build step:

```
node tools/compose-screen.mjs       # -> 01-full-screen.png
node tools/compose-plate.mjs        # -> 02-candy-plate.png
node tools/compose-styleboard.mjs   # -> 03-style-board.png
node tools/compose-rotation.mjs     # -> 04-rotation-strip.png
```

`tools/shape.mjs` (outline/rounding + concave-vertex list), `tools/color.mjs`
(palette + material stops, including the new `bright`/`edgeGlow`/`edgeShade`
+ `COATING_SHIMMER`), `tools/light.mjs` (the fixed `LIGHT_DIR` constant +
`mostLitCell()` — **new this pass**), `tools/piece.mjs` (the fourteen-layer
material), `tools/stack.mjs` (the hard-drop simulator + `crossPieceSeams()` +
`rotate90()`) and `tools/layout.mjs` (grid→pixel placement + `extraStyle`
passthrough) are shared by all four composers. `tools/*.html` are regenerated
scratch output (git-ignored); the four PNGs at the top of this directory are
the deliverable.
