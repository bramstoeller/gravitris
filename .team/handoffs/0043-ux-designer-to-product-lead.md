# UX Designer → Product Lead: candy target elevated to a 2026-flagship bar

**Branch:** `design/candy-target-v2` (fresh branch off attempt 4's tip,
`013ce3c` — the worktree holding `design/candy-target` itself was assigned to
another session and per the constitution I don't edit outside my own
worktree, so a new branch was cleaner than fighting for it)
**PR:** [#38](https://github.com/bramstoeller/gravitris/pull/38) — supersedes
[#37](https://github.com/bramstoeller/gravitris/pull/37) (attempt 4, still
open, not touched, not deleted — that's your call per the constitution)
**Commit:** `245a142` — `feat(design): elevate candy target to a 2026-flagship
visual bar`

## What I did

Per your instruction: take attempt 4's approved foundation and push it from
"great candy puzzle" to "would hold its own on a 2026 store chart," imagery
only, no app/shader code, no client contact.

**Benchmarked broadly, not just Candy Crush.** Fetched real, full-device-
resolution screenshots via the iTunes lookup API (`itunes.apple.com/lookup`
— the App Store's own page is a client-rendered SPA and doesn't expose
`screenshotUrls` to a plain fetch, cost some time to work out, recorded in
the README so it isn't rediscovered):

- **Royal Match** (Dream Games) — current #1-grossing casual puzzle game.
  Confirmed a cinematically-lit 3D scene behind play (real bloom, DOF blur,
  warm/cool contrast) and strongly-beveled HUD chips.
- **Block Blast — Puzzle Game & PVP** — the closer genre comp: a
  block-fitting grid-fill puzzle, the same family Gravitris actually is.
  This is where the reworked clear effect came from directly: their clear
  FX bursts bloom + confetti **over** the tiles, not hidden behind them,
  which is exactly the gap I found in attempt 4's own coverage-band glow.
- General 2026 mobile UI-trend research (WebSearch), which produced a real
  course-correction from my first instinct: current writeups are explicit
  that 2026's best interfaces are "backbone with flair," not more gloss
  everywhere — glow/bloom belongs on meaningful moments, not decorating
  every chip. That's why the HUD below got *tighter*, not busier.

All sources cited with URLs in `design-target/README.md`.

**What actually changed, in shader-chaseable terms** (full detail + the
"considered and rejected" list in the README):

- **Depth & lighting**: a bloom halo behind the specular gleam (a real
  bright-pass-and-blur), a warm "core pool" glow at each piece's own
  cell-count centroid, and — new — a **concave self-occlusion AO**: every
  reflex corner of a notched piece (S/T/L/J/Z) now gets a soft dark radial,
  read directly off `outlinePath()`'s own corner classification (extended
  to return a `concave` vertex list, not re-derived by piece.mjs).
- **Depth *between* pieces**: `tools/stack.mjs` grew `crossPieceSeams()`,
  which finds every grid edge shared by two *different* settled pieces and
  draws a soft contact-AO seam there — the adjacency a real soft-body
  solver already has to track, not invented mockup data.
- **A distinctive, ownable accent**: one fixed pale-cyan "coating sliver"
  tint (`COATING_SHIMMER`, a constant, not a seventh per-hue stop) riding
  every gleam's edge — reads as a shared glaze coat, keeps the material
  from being a straight Candy Crush reskin without inventing a new palette.
- **Juice, shown in the still**: the most-recently-landed piece gets a
  squash transform + impact flash (a CSS transform on the wrapper div only
  — `placePieceDiv()` grew an `extraStyle` passthrough so this can never be
  mistaken for a material change); the coverage-clear row is now a real
  "event" — bright sweep + rays + hued shard particles drawn **after** the
  piece layer, erupting over the row instead of hiding behind it like
  attempt 4's did.
- **Atmosphere**: the flat diagonal gradient is now a five-pool mesh
  gradient, richer bokeh, dust flecks, one soft light-ray sweep, and a
  whisper of vignette — still light and candy, just with a sense of place.
- **HUD**: type scale pulled in a step, pill shadows swapped from uniform
  muddy brown to shorter/cooler, one functional glow ring on the level pill
  only (the "you are here" indicator) — restraint, not more decoration.

Rendered, cropped, and pixel-inspected at full resolution throughout (not
eyeballed at thumbnail size) — caught and fixed a bloom radius that washed
out single-cell (O) pieces too much, a concave-AO blob that read as a hard
smudge before more blur/lower opacity, and a style-board leader-line bug
where the line from a piece anchor to a label sliced diagonally across the
label's own paragraph (fixed with an automatic top/bottom connector choice
based on which side of the label block the anchor sits outside of — detail
is in the code comments, not repeated here).

## What I deliberately did not do

- No dark/moody backdrop like Royal Match's own — the brief says stay
  "light and candy," a dark backdrop would fight that.
- No per-hue iridescent tint — one shared constant instead, to keep the
  "one recipe, seven pieces" mechanical consistency attempt 4 established.
- No darker/cooler tray — changed the world around the board, not the
  board itself.
- Didn't touch `02-candy-plate.png`'s own background/layout beyond the
  material update flowing through — it's a swatch reference sheet, not a
  gameplay scene, and didn't need atmosphere.
- No app or shader code, no client contact, per scope.

## Open questions for you (and, through you, the client)

- **Does this clear the bar this time?** I benchmarked against real
  screenshots and rendered/cropped/inspected at full resolution through
  several iterations, but you said you'd review critically before it goes
  to the client — that's still the next step, not me deciding I'm done.
- The landing-squash is subtle by design (a real squash only holds a few
  frames in motion; a still can't lean on motion to sell it) — worth your
  eye on whether it reads as "just landed" or just "slightly off
  proportions" in a static image.
- Bloom and concave-AO are both soft effects tuned by eye at this render's
  1080px width. Worth flagging to whoever implements the real shaders: at
  actual on-device piece size these could fall under visible contrast and
  may need a minimum-size floor, not a pure percentage-of-cell radius.
- Same unresolved question as attempt 4: the coverage-clear visual is still
  a placeholder for whatever the real clear-threshold rule turns out to be.
- **PR #37 (attempt 4) is superseded but still open** — I didn't close or
  delete it; that's a call for you, not a specialist, per the constitution.

## CI

Pushed and PR #38 opened; watching `gh pr checks 38` until green before
considering this done, per the constitution.
