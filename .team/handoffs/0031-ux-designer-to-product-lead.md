# 0031 — UX Designer → Product Lead (cc Frontend Engineer, Backend Engineer)

**Task:** client played the real build and called the graphics "far below
par," naming 99 Bricks Wizard Academy and Candy Crush Saga as references.
Diagnose honestly, research the references, produce a visual-direction spec
concrete enough to implement. Docs only, `chore/visual-direction`.

## What I did

Branch off `origin/main` at `b237ec2`. Diagnosed the shipped state by reading
the actual code (not assuming) — `GameRenderer.kt`, `MainActivity.kt`,
`Palette.kt`, `WellFrame.kt`, `WellLayout.kt`, `Shaders.kt`, `contracts.md`,
and the full existing `docs/ux/` set (`tokens.md`, `piece-identity.md`,
`band-glow.md`, `feel-feedback.md`, `screens/*`) — before writing any
direction, so it builds on what's already good (the gel material and
band-glow are already well specified and implemented) rather than restating
or contradicting it.

Researched both references with actual fetched screenshots (App Store
listings, `is1-ssl.mzstatic.com` — full URLs cited in the doc), not from
memory — both cited with what was specifically taken and specifically
rejected.

**New:** `docs/ux/visual-direction.md` — the master document. Diagnosis,
cited references, overall art direction (procedural background/environment
replacing flat black), piece presentation under the tetromino move, the
seven-hue palette decision, a real HUD (in the Android View layer — costs
nothing on the GPU), the band-clear juice upgrade (three additions, each
costed), motion/animation tokens, and a sequencing/open-items section.

**Edited**, each extending rather than replacing what was there:
`docs/ux/piece-identity.md` (seventh hue, "Scaling the palette" marked
resolved), `docs/ux/tokens.md` (7-hue palette, `color-bg-deep`/`color-bg-core`/
two glow tokens, `motion-pop`/`motion-celebrate`), `docs/ux/feel-feedback.md`
(pointer to the juice upgrade; new D6 overflow-warning section — `uOverflow`
is already reserved in `contracts.md` and unused), `docs/ux/screens/playing.md`
(real HUD replacing the frame-time-readout-as-only-chrome framing; frame-time
readout demoted to a debug-only toggle), `docs/ux/screens/game-over.md`
(short visual-treatment section — layout/states were already good, just
never built), `docs/ux/accessibility.md` (reduced-motion row for the two new
motion tokens; palette summary updated to seven hues),
`docs/ux/README.md` (index entry).

Commits on `chore/visual-direction`, pushed to `origin`. PR opened as
`[UX Designer]`, signed, watched to green — see the PR link in the branch's
GitHub page (CI is docs-only here, so it's the standing build+test suite,
unaffected by these changes).

## The piece-hue decision (blocks Frontend's palette work) — resolved

**Seven hues, not six.** `Palette.kt:96-103` already flagged this itself:
`Simulation.ARCHETYPE_COUNT = 7` but only six hues existed, so archetype 6
was folded onto hue 0 via `floorMod` as an explicit placeholder. Seven
tetromino shapes = seven archetypes = seven hues is the clean close. New
seventh hue: **Emerald, `#3BA12B`, H112° S58% L40%, grain scale 2.0×** —
derived by the same rule as the original six (≥30° separation, outside the
reserved 15°–65° amber band, continues the lightness alternation), appended
as archetype index 6 so the existing six indices are untouched (additive,
no remapping). Full derivation, including why the *other* open hue-wheel arc
(335°→15°) is mathematically too narrow to hold a seventh hue at all, is in
`visual-direction.md` §5.

**What this changes in code (not done by me — Frontend's call on timing):**
`Palette.PIECE_COUNT` 6→7, `SURFACE_INDEX` 6→7, `SIZE` 7→8, one new palette/
grain row, and `pieceHue()`'s `floorMod` workaround becomes unnecessary
(can become the identity function or be removed in favour of direct
archetype indexing).

**What I did not decide:** which physical tetromino shape (I/O/T/S/Z/J/L)
gets which archetype index/hue — that's the Backend/Frontend shape contract,
not a palette question. I only supply seven well-separated hues in a stable
index order and flag one hard rule: don't reorder the table to "look right"
for a particular shape, the spacing and lightness alternation were derived as
a set.

## Coordinated directly with the Frontend Engineer (not routed through you)

Messaged `a041688444ed4dc40` directly: the 7-hue resolution and the exact
`Palette.kt` diff-shape above, the GPU-budget discipline this whole spec
follows (no bloom/HDR/texture assets; every new element costed; the
background pass and clear-juice additions are all O(1) extra draw calls or
tiny fixed geometry, never O(bodies) or O(screen × extra passes)), and asked
them directly whether tetromino bodies keep one-archetype-per-whole-piece
(my seam argument in §4 depends on the answer) and what their sequencing
preference is. Their reply hadn't arrived by the time I finished writing —
flagging that as open, not resolved, below.

## What I deliberately did not do

- **No code.** This is a docs-only branch as instructed.
- **No literal scene/theme/mascot.** Both references earn their backgrounds
  with authored art assets or per-level bespoke scenery; this project's own
  constraint (procedural shaders, tiny APK, no texture pipeline) rules that
  out. The background is a gradient + two fixed soft glows, done with maths,
  and I said explicitly in §3 why I stopped there rather than going further.
- **No hard cel-outline on pieces**, despite 99 Bricks using one to good
  effect — a thick outline reads as flat vector-cartoon material and would
  fight the translucent subsurface-lit gel the client already approved.
  Explicitly rejected in §2, with the reasoning kept rather than silently
  dropped.
- **No glossy hard-candy specular highlight**, despite Candy Crush's being
  the most visually distinctive single thing in its reference set — same
  reasoning, wrong material family for a soft gel.
- **No bloom, no HDR post-process, no per-pixel volumetric light.** Every
  juice addition in §7 is specifically chosen to be O(1) extra draw calls
  or fixed tiny geometry rather than O(screen pixels × passes), because
  that's the category of cost `Shaders.kt`'s own header already says this
  project cannot afford.
- **Did not touch `Palette.kt`, `Shaders.kt`, `GameRenderer.kt`, or any
  other production file.** Docs only, everywhere.
- **Did not re-litigate anything already well-specified and correct** —
  `band-glow.md`'s curve and OLED-dither fix, `feel-feedback.md`'s clear
  timeline, `piece-identity.md`'s original six-hue construction, all stand
  as written. I extended around them, not over them.

## Considered and rejected

- **A second, independent quality dial for the new background/particle
  elements**, separate from `GameRenderer.shadeLevel`'s existing five-step
  ladder. Rejected: two dials measured separately is twice the on-device
  measurement burden for the client for no real benefit, since both new
  elements are cheap by the same design logic the existing tiers already
  use. Recommended to Frontend that these fold into the existing ladder
  (or get measured once, standalone, and then just shipped always-on if
  genuinely free) rather than becoming a second parallel system.
- **Redesigning Game Over's layout from scratch.** Rejected — the existing
  `screens/game-over.md` layout and states (score, NEW BEST treatment,
  error/loading states, focus order) are already good and were never the
  problem; the problem was that none of it got built. I added a short
  "visual treatment" section bridging it to the new environment/motion
  tokens rather than rewriting a screen that wasn't broken.
- **A literal "next piece" rendered through the gel shader**, for visual
  consistency with in-well pieces. Rejected on cost: it would need either a
  second small draw call with its own transform uniforms (the current
  renderer uploads one `uScale`/`uOffset` pair per frame for the whole well)
  or contorting the existing one, for a preview that doesn't need to survive
  physics. A flat Android-View swatch in the piece's hue costs nothing and
  communicates the same information (shape + hue) at a glance.

## Open questions / uneasy about

- **Frontend's reply on the archetype-per-tetromino question hadn't landed
  before I finished.** §4's seam argument (rim/contact-seam handles internal
  tetromino cell boundaries with zero shader change) assumes one archetype
  per whole piece. If tetromino cells turn out to carry sub-body archetype
  variation, that section needs a second pass — flagged in the doc itself
  (§4, §10), not silently assumed solved.
- **The background pass and ember particles are designed cheap, not measured
  cheap.** I was explicit about this in §10: this project's whole standing
  discipline is to price things on the client's real device rather than
  trust the estimate, and I don't have that device. Both need an on-device
  reading before shipping at the default tier.
- **I did not re-run the CVD simulator check** — no tool available in this
  environment, same limitation `piece-identity.md` already disclosed for the
  original six hues. Emerald inherits the same open QA action item, now for
  seven hues rather than six, not a new one.
- **Sequencing risk if this lands before the controls/tetromino branches
  merge.** I said explicitly, to Frontend and in the doc, that this is
  guidance for *after* those settle. If schedule pressure pushes Frontend to
  pull from this spec concurrently with their own in-flight branch, the
  parts most likely to conflict are the palette size change (§5, touches
  `Palette.kt` directly) and anything about tetromino cell rendering (§4) —
  worth sequencing explicitly rather than assuming it'll sort itself out.

## For the client (in your words, not mine)

The direction: keep the gel material (it's not what's wrong), give it a
world to sit in, make the clear moment announce itself, build the score/
level/next-piece HUD and the game-over screen that were already designed
on paper but never built, and close the six-hues-for-seven-pieces gap with a
seventh colour. All of it costed against the Fairphone 6's tight frame
budget — nothing here is a texture, a bloom pass, or an asset.

---
*— **UX Designer***
