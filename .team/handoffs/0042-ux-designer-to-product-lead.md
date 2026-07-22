# UX Designer → Product Lead: A-class candy target imagery (attempt 4)

**Branch:** `design/candy-target` (worktree `agent-a3ab014f25267b2aa`)
**PR:** [#37](https://github.com/bramstoeller/gravitris/pull/37) — CI green, no review comments yet
**Commit:** `013ce3c` — `docs(design): render A-class candy target imagery for client approval`

## What I did

Per the brief: three real rendered PNGs, not a spec document, in
`design-target/`:

- `01-full-screen.png` — full portrait game screen (1080×2340).
- `02-candy-plate.png` — all seven tetromino shapes at large size, labelled
  with hue name and hex.
- `03-style-board.png` — one piece, annotated layer by layer, plus the
  full palette.
- `README.md` — palette (hex, CVD-checked), the material recipe in
  shader-chaseable terms, and exactly what real reference was checked
  against.

Everything is built from SVG + HTML/CSS (a small set of Node scripts in
`design-target/tools/`) rendered to PNG through headless Chromium — no
photos, no hand-painted assets, deliberately: the shipped game renders
procedurally in GLSL on deformable soft-body pieces with no authored image
assets, so the target had to be built only from things a fragment shader
can actually do (gradients, blurred highlights, blend modes).

**Real reference, actually looked at.** Five current, full-resolution
(1080×2340) Candy Crush Saga screenshots, fetched directly (APKPure's
mirror of the Play listing, `?fakeurl=1&type=.jpg` on the CDN URL — a bare
fetch of the image URL 404s, worth knowing before anyone re-tries this).
Three are real gameplay boards. Confirmed directly: the hard elongated
upper-left specular highlight, the flat near-shading-free saturated body,
the soft low-contrast tray-ward shadow, the muted cool tray against a
bright saturated world, matte pattern-textured pieces as the cheap variety
mechanism. Full write-up and sources are in the README, not just asserted
here.

**Two real bugs found and fixed during the build, both worth flagging
because they're the kind of thing that silently ships otherwise:**

1. The highlight/glow was originally positioned by bounding-box fraction.
   On an L, S, or J piece (whose mass sits far from its bbox corner) this
   placed the highlight in clipped-away empty space — it simply didn't
   render. Found by looking at the first render, not assumed. Fixed by
   anchoring every highlight/glow to the piece's actual topmost-left
   *filled cell* instead (`tools/piece.mjs`).
2. A double-margin bug (`outlinePath()`'s own breathing room plus a second,
   independent pad in `piece.mjs`, only one of which `layout.mjs`
   compensated for) put every settled piece about 65px below its intended
   grid row — pieces visibly poked out past the well's floor. Confirmed
   with pixel sampling (not eyeballing) before and after the fix.

## What I deliberately did not do

- No app or shader code touched — imagery and rationale only, as scoped.
- No true alpha-blended transparency for the candy body — costed and
  rejected on the same single-draw-call grounds the previous UX round
  (`docs/visual-redirect-candy`) already established; the subsurface-glow
  layer is the proxy, and I said so in the README rather than re-deciding
  it silently.
- No per-cell corner rounding — same reason as above, would reintroduce
  the "four separate squares" read.
- Didn't exactly match Candy Crush's near-flat body shading. Ours has more
  base-gradient (top-light → bottom-deep) than the reference, on purpose,
  to serve the brief's "plump volume" ask — documented as a deliberate
  departure in the README, not left implicit.

## Considered and rejected

- **A CSS "goo" filter** (blur + threshold alpha) to fuse the tetromino
  cells into one rounded silhouette, instead of the explicit
  boundary-walk-and-round algorithm in `tools/shape.mjs`. Rejected: it's a
  raster technique with an unpredictable/blurry result at the exact seams
  that matter most, and it would have blurred the gradient fills the
  material depends on. The explicit rounded-polygon approach gives full
  control over exactly where the specular/glow anchor sits, which the goo
  approach can't.
- **Hand-plotting the settled stack's cell coordinates** instead of a real
  hard-drop simulator. Rejected once I'd written the simulator anyway
  (`tools/stack.mjs`) — it guarantees no overlaps and a physically
  plausible skyline by construction, which is both less error-prone and a
  more honest "this is what the mechanic produces" demonstration than a
  hand-arranged picture would be.

## Open questions for you (and, through you, the client)

- Does this look clear a bar you'd show the client, or does it need
  another pass first? I've been my own harshest critic through several
  iterations (documented via the fixes above) but a second pair of eyes
  reviewing critically, as you said you would, is exactly the next step —
  not me deciding I'm done.
- The coverage-band glow (`01-full-screen.png`, bottom row) is a visual
  placeholder — a warm gold glow on the fullest row — since I don't know
  the actual coverage-clear threshold rule. If that rule is meaningfully
  different from "row is full," the visual device likely still holds, but
  worth a sentence of confirmation from whoever owns that mechanic
  (Architect/Backend) before Frontend builds against it.
- I did not message Frontend/Backend directly — there's nothing for them
  to build yet (this round is imagery-first, approval-gated, per the
  brief). Flagging here so it's clear that's a deliberate sequencing
  choice, not an oversight of the "talk to each other directly" norm.
