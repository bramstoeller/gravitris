# Gravitris — candy target imagery (v2: modernized to compete with 2026 flagships)

Rendered target mockups, not a spec document. Attempt 4 (see git history / the
previous revision of this file for its own research) established a genuinely
good "glossy candy" foundation and was approved as a base to build on. This
round's brief was narrower and harder: **the client saw it, liked it, and
asked for it to be pushed further — modern enough to hold its own against
today's top mobile games, not just against Candy Crush.** This is that pass.

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
| `01-full-screen.png` | Full game screen, portrait 1080×2340: a layered mesh-gradient candy world with bokeh and a whisper of vignette, the well mid-game with a settled interlocking stack (one piece mid-landing-squash, contact-AO seams between touching pieces), two pieces falling, a coverage-band clear rendered as an actual light burst, and a tightened HUD (score top-left, level pill top-centre with a functional glow ring, pause top-right). |
| `02-candy-plate.png` | All seven tetromino shapes at large size, each labelled with its hue name and hex — the unambiguous per-piece material reference, now with the bloom/core-glow/coating-sliver layers applied. |
| `03-style-board.png` | One piece (a T, not an O — see below), annotated: every layer that produces the material, plus the full palette. |

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
still valid and still the reason the base material — flat-saturated body,
one hard elongated gleam, warm non-black contact shadow — hasn't changed.
See the previous revision of this README (`git log -p -- design-target/README.md`)
for that write-up in full; it isn't repeated here.

## Palette

Unchanged from attempt 4 — the brief said push polish and modernity, not
reinvent a palette that was already CVD-checked and genre-conventional.
Seven tetromino hues (I=cyan, O=yellow, T=purple, S=green, Z=red, J=blue,
L=orange):

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
rim-deep = base → black 55%, shadow = base → `#8a5a3a` 50% → black 25%,
**new:** core = base → black 15%) — see `tools/color.mjs:materialStops()`.
One formula, seven pieces; nothing hand-tuned per colour.

**New constant, not a per-hue stop:** `COATING_SHIMMER = '#D8F0FF'` — a single
fixed pale-cyan tint used for the iridescent coating sliver on every piece
regardless of hue (see layer 8 below). This is deliberately *not* derived
from each base hex: a glaze coating is the same physical layer on top of
every flavour of real candy, so giving it one shared constant is more
mechanically honest than tuning it seven times, not a shortcut.

Neutrals unchanged: sky top `#EAF1FF`, sky bottom `#FFE6D6`, well cream
`#FFFBF3`, well deep `#F4E1C4`, score pink `#FF3D77`, level-pill purple
`#8A4FE0`.

## Material recipe (see `03-style-board.png` for the annotated version)

The style board now annotates a **T piece, not an O** — a single-cell O has
no concave vertex, so it can't demonstrate layer 3 below. Every layer is
still a gradient, blur or blend mode a fragment shader can do on a
deformable mesh, in paint order:

1. **Silhouette** — unchanged: the fused, whole-piece rounded outline from
   attempt 4 (`tools/shape.mjs:outlinePath()`), convex corners bulging
   outward and concave corners fillet inward.
2. **Base gradient** — unchanged: linear, top-light → bottom-deep, ~20° off
   vertical.
3. **Concave self-occlusion AO** *(new)* — every reflex (concave) vertex of
   a multi-lobe piece (where an S/T/L/J/Z's two lobes meet) now gets a soft
   dark radial, multiply-blended, nudged inward from the boundary along the
   vertex-to-centroid direction. `tools/shape.mjs:outlinePath()` now returns
   this vertex list directly (`concave: [[x,y], ...]`) instead of piece.mjs
   re-deriving or guessing it — the AO literally reads off the same corner
   classification the rounding algorithm already computes, so it can never
   drift out of sync with where the notches actually are. This is the
   "depth where pieces meet" ask, applied at the one place a *single* piece
   already has a meeting-of-surfaces: its own notch.
4. **Warm core pool** *(new)* — a second, warmer radial (tinted with the
   piece's own `core` stop) centred on the piece's cell-count centroid,
   `soft-light`-blended. The existing subsurface glow (next layer) is
   anchored to the highlight corner; this one is anchored to the piece's
   centre of mass instead, so the piece reads as if light is gathering
   where the gel is thickest, not just glowing uniformly from one corner.
5. **Subsurface glow** — unchanged from attempt 4: the large soft white
   radial anchored to the topmost-left filled cell, `soft-light`-blended.
6. **Bloom halo** *(new)* — a second, much larger and softer white radial
   sits behind the specular gleam, `screen`-blended at low opacity, heavily
   blurred. This is a real bright-pass-and-blur — the same operation an
   actual bloom post-process performs on any pixel over a brightness
   threshold — applied locally to the one feature bright enough to warrant
   it, so the gleam now looks like it's genuinely overexposing the surface
   instead of a flat painted highlight.
7. **Specular gleam** — unchanged: the hard elongated white ellipse.
8. **Coating sliver** *(new)* — a small, thin, pale-cyan (`COATING_SHIMMER`)
   ellipse riding the gleam's own edge, `screen`-blended at low opacity,
   same rotation as the gleam. Reads as a faint cool fringe on the wet
   highlight — the ownable "this candy has a glassy coat" signature that
   keeps the material from reading as a straight Candy Crush reskin, without
   inventing a new palette or shape language to get there.
9. **Sparkle** — unchanged: the tiny bright dot at the gleam's tip.
10. **Rim / edge bevel** — unchanged.
11. **Contact shadow** — unchanged.

Falling pieces still get the separate page-composition cast-shadow ellipse
from attempt 4.

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

## Open questions

- Whether the landing-squash is pronounced enough in a still frame to read
  as "just landed" rather than "slightly wrong proportions" — it's subtle
  by design (a real squash only holds for a few frames in motion), but a
  still can't rely on motion to disambiguate. Worth a second opinion.
- Bloom and the concave-AO crease are both soft, low-contrast effects tuned
  by eye at this render's resolution (1080 px wide). Worth a flag to
  whoever implements the actual shaders: at a smaller on-device piece size
  these could fall below visible contrast entirely and may need a
  minimum-size floor rather than a pure percentage-of-cell radius.
- Same open question as attempt 4, still unresolved: confirm the real
  coverage-clear rule against the placeholder "fullest row" visual device.

## Regenerating

Still plain Node ESM + headless Chromium, no build step:

```
node tools/compose-screen.mjs       # -> 01-full-screen.png
node tools/compose-plate.mjs        # -> 02-candy-plate.png
node tools/compose-styleboard.mjs   # -> 03-style-board.png
```

`tools/shape.mjs` (outline/rounding + concave-vertex list), `tools/color.mjs`
(palette + material stops + `COATING_SHIMMER`), `tools/piece.mjs` (the
eleven-layer material), `tools/stack.mjs` (the hard-drop simulator +
`crossPieceSeams()`) and `tools/layout.mjs` (grid→pixel placement +
`extraStyle` passthrough) are shared by all three. `tools/*.html` are
regenerated scratch output (git-ignored); the three PNGs at the top of this
directory are the deliverable.
