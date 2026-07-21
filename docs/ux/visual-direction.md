# Visual direction — 2026-07-21

Client verdict after playing the real build on the Fairphone 6: the graphics
are **"far below par."** Two references were named: **99 Bricks Wizard
Academy** and **Candy Crush Saga**. This document diagnoses why, cites what
was actually looked at, and specifies a direction concrete enough to build.

It lands as guidance for **after** the Frontend's control rework and the
Backend's tetromino-shape work settle (both in flight as this is written) —
not a third simultaneous rewrite of the same surface. See the handoff for the
agreed sequencing.

## 1. Diagnosis — read against the shipped code, not from memory

I read the actual files before writing a word of direction. What ships today:

- **The background is `GLES30.glClearColor(0f, 0f, 0f, 1f)`** —
  `GameRenderer.kt:324` — true flat black, full stop. There is no environment,
  no gradient, no depth. `WellLayout.kt` confirms the well fills essentially
  the entire safe display area (width always, height clamped to
  `[12, 30]` world units against a fixed width of `10` — `Tunables.kt:346-351`
  — which the Fairphone 6's ~19.5:9 panel doesn't hit), and `WellFrame.kt`'s
  walls are a **3%-of-width border** (`THICKNESS_WORLD = 0.3f`, well width
  `10f`). So there is nowhere for a "scene around the well" to live — a
  background has to read *through the well's own empty space*, not beside it.
- **The only on-screen text is a debug frame-time readout**
  (`FrameTimeReadout`, wired in `MainActivity.kt:73`), explicitly a
  measurement instrument (`playing.md` already calls this "PROTOTYPE
  MILESTONE ONLY"). There is no score, no level, no next-piece — because
  there is no score yet to show (D8, scoring is still backlog, not built).
- **Game Over is a bare `TextView`** — `MainActivity.kt:389-411` — plain text
  on a scrim, tap-anywhere-to-restart, and the code comment says exactly what
  it is: *"Minimal on purpose — the polished game-over screen is Stage 5
  (docs/ux/screens/game-over.md); this only has to not strand the player."*
  The designed screen (`screens/game-over.md`) already exists on paper and has
  never been built.
- **The band-clear payoff (ignition → hold → dissolve, `Shaders.kt` tier 3,
  `band-glow.md`, `feel-feedback.md`) is already implemented and is good** —
  smoothstep ramps, additive amber emissive with an identity cap, a dither
  fix for OLED banding, ember shimmer reusing the grain field. This is not
  the part that reads as "tech demo." What's missing around it — no score
  tick-up to *see* the payoff pay off, no screen-wide acknowledgment, no
  particle release — is what makes an already-good effect feel small.
- **Piece palette is 6 hues serving 7 archetypes.** `Palette.kt:96-103`
  already flags its own defect: `pieceHue(archetype) = archetype % 6` folds
  archetype 6 onto hue 0 as an explicit, commented placeholder, "a placeholder
  for a design decision, flagged to UX." This document closes that flag (§5).

**Conclusion, stated plainly:** the material (gel shading, band-glow) is not
the problem the client is describing, and this direction does not discard it
— the brief's own steelmanning of "tactile-organic gel" is preserved and
extended. The problem is that a real game has a world, a HUD, a payoff that
announces itself, and a way to end that doesn't feel like a crash. None of
those existed to fail — they simply weren't built yet. This document specifies
all four.

## 2. References — what was actually looked at, not recalled

### 99 Bricks Wizard Academy

Fetched three App Store screenshots directly
(`apps.apple.com/us/app/99-bricks-wizard-academy/id784444243` — screenshots
served from `is1-ssl.mzstatic.com`, IDs `156fe11d…`/`50ca720c…`/
`9356d52a…`) and viewed them:

- **A vertical sky gradient background that never goes black** — blue at the
  top fading into a warm sunset pink/orange lower down, with soft, flat-shaded
  cloud silhouettes at multiple depths (a cheap parallax read, not a texture
  photograph) and stylised mountain silhouettes anchoring the bottom of frame.
  The tower (their "well," inverted — building up, not down) is the only cool
  dark-ish geometry against a warm sky; ours is the reverse polarity (bright
  gel against a dark canvas) but the *lesson transfers directly*: a graduated
  environment, not a flat colour, is what turns "canvas" into "world."
- **Thick, cel-shaded outlines on every block** — a dark 3–4px stroke plus one
  hard highlight and one hard shadow facet per piece, plus a small pastel
  palette (blush pink, cream, sage, lavender) with a light surface-pattern
  overlay on some pieces (a scale/shingle texture on the capstone block).
- **Score presented as an object in the world**, not a HUD bar: a cloud-shaped
  speech-bubble banner with rounded, bold display type and a coin icon,
  floating above the tower rather than pinned to a corner grid.
- Source: [99 Bricks Wizard Academy — App Store](https://apps.apple.com/us/app/99-bricks-wizard-academy/id784444243).
  Background/genre confirmed via
  [Wikipedia](https://en.wikipedia.org/wiki/99_Bricks_Wizard_Academy) and
  [Android Police's 2014 writeup](https://www.androidpolice.com/2014/07/01/new-game-99-bricks-wizard-academy-is-tetris-in-reverse-with-physics-and-leprechauns-and-also-wizards/)
  (neither carries useful art-direction detail beyond confirming "colourful,"
  which is why the screenshots, not the text, are the real source here).

**What we take, what we don't.** We take: graduated environment behind the
play column: rich colour and depth living in a stable background rather than
scattered as texture on interactive pieces, and a HUD element that reads as a
designed object rather than a status bar. We explicitly **don't** take the
thick cel outline — a hard black stroke reads as flat vector cartoon material,
and it would fight the translucent, subsurface-lit gel our client already
approved, not complement it. §4 keeps piece-boundary legibility working
through the existing rim/contact-seam language instead.

### Candy Crush Saga

Fetched four real screenshots from the App Store listing (`is1-ssl.mzstatic.com`,
IDs `75a74983…`/`dd84b072…`/`87cb30d3…`, all tagged `aso-true-to-gameplay-ss`
— Candy Crush's own "this is what it actually looks like" marketing set — plus
one title-card screenshot showing the logo lockup over a live board):

- **The board itself sits on a muted, dark-slate tray** (`#4a5568`-ish grey
  panel with a subtle inset bevel per cell) — while the *scene around and
  behind the tray* carries the saturated theming (pink mountains, a meadow
  with oversized flowers, a teal-and-coral underwater reef, one per level).
  The candies are glossy, hard, saturated, and pop precisely because the
  board they sit in and the frame around them are both deliberately calmer.
  This is the same lesson as 99 Bricks from the opposite direction: **rich
  colour goes in the environment; the interactive surface stays legible.**
- **Every candy is lit the same way**: one bright, biased specular hotspot
  (upper-left), a saturated flat body colour, a darker lower-edge shadow for
  volume — three terms, consistently applied, is what makes a shelf of a
  dozen different candy types read as *one material system* rather than a
  dozen unrelated icons.
- **HUD chrome is unApologetically rounded and pink** — pill-shaped bars,
  a striped 3-star progress track, a moves-counter in a bean-shaped badge, a
  circular mascot portrait, a gold-gradient beveled logo lockup with a drop
  shadow. None of this is subtle, and it doesn't need to be — it's UI chrome,
  not gameplay material, and it is allowed to be as graphic as it likes
  because it never competes with the board.
- **Special-candy "juice" is cheap pattern overlays, not extra geometry**: a
  striped candy is diagonal stripes on the same base shape, a wrapped candy
  is a foil-texture ring, a colour bomb is a dark sphere with small bright
  dot "sprinkles" — all three are patterns on an existing shape, not new
  meshes. That is directly transferable to a shader-driven material: pattern
  variation is cheap, geometry variation is not.
- Cascade/combo mechanics (chain scoring, the "sweet" celebratory beat) via
  [Candy Crush Saga Wiki — Cascades](https://candycrush.fandom.com/wiki/Cascades)
  and [Wikipedia](https://en.wikipedia.org/wiki/Candy_Crush_Saga).
- Source screenshots:
  [Candy Crush Saga — App Store](https://apps.apple.com/us/app/candy-crush-saga/id553834731).

**What we take, what we don't.** We take: a calmer, contained playfield frame
against a richer surrounding environment; a single, consistent per-piece
lighting recipe (we already have one — rim + subsurface + AO seam, §4 keeps
it, doesn't replace it); UI chrome that's allowed to be graphically bold
because it's cheap (Android View layer, §6) and never touches the fragment
budget. We explicitly **don't** take glossy hard-candy specular highlights —
that's a different material entirely (hard confectionery vs. our soft gel)
and copying the highlight shape would fight the "tactile, organic, squashable"
identity that's this game's actual point of difference.

### Cheap-juice methodology

The technique of layering small, cheap feedback effects (screen shake,
particles, brief pauses, animation) onto a functional core is not new to this
brief — it's literally named "juice" after the field's own canonical talk:
[**"Juice it or lose it" — Martin Jonasson & Petri Purho, Nordic Game Jam 2012**](https://www.youtube.com/watch?v=Fy0aCDmgnxg),
which builds every effect from cheap primitives (offset, scale, delay,
particle count) rather than expensive ones (real physics, real lighting,
post-process). §5's clear-juice upgrade follows that discipline explicitly:
every new element is specified as a cheap approximation with its cost stated,
per the brief's constraint.

## 3. Overall art direction

**Keep the dark gel canvas. Give it a world.** Concretely: replace the flat
`glClearColor` with a **procedural environment pass** — one full-screen quad,
drawn once per frame before the well frame and the bodies, with a trivial
fragment shader independent of body count (its cost is O(screen pixels), not
O(bodies), and its whole shader is a handful of ALU ops — see the cost table
in §7).

**The environment, concretely:**

- **A vertical gradient**, not a flat fill: `color-bg-deep` (near-black,
  cool-tinted, `#05050C`) at the very top and bottom of the frame, warming
  very slightly toward `color-bg-core` (`#0E1730`, a deep indigo) around the
  vertical centre of the screen. This is deliberately subtle — the resulting
  image is still overwhelmingly dark (mean luminance barely above true black,
  preserving most of the OLED power argument in `tokens.md`) but it is no
  longer a flat, dead value, which is most of what reads as "test pattern"
  rather than "world."
- **Two soft, fixed radial glows** ("distant crystal light," not
  representational) at roughly the upper-left and lower-right of frame, cool
  hues only (deep teal/violet — **never** amber; amber is band-glow's alone,
  §4 restates why), each a `smoothstep`-falloff disc at ~4–8% peak opacity.
  Optional slow drift (one extra `sin(uTime)` term, ~90–120s period) — cheap
  enough to keep, cut first if the budget disagrees.
- **The well frame** (`WellFrame.kt`) gets a one-line addition: a thin
  emissive inner edge on the wall-facing side, reusing `color-surface` at
  slightly raised luminance — no new attribute, no new draw call, it's a
  constant added to the existing flat quad colour. This is what turns "three
  grey rectangles" into "a vessel holding something," which is the framed-well
  read both references independently support.

This is not a re-skin and not a re-theme — there is no new narrative, no
mascot, no literal "world" to author art for (that would need real assets,
which the brief's procedural/tiny-APK constraint rules out). It is graduated
colour and a contained frame, done with maths, not textures.

**Why not go further (a literal scene, parallax layers, silhouette
mountains):** every reference above earns its background with either
authored 2D art (99 Bricks) or per-level bespoke scenery (Candy Crush) — both
texture-asset-heavy by construction. This project's own constraint (ADR 0007,
the brief itself) is procedural shaders and no texture pipeline. The gradient
+ two glows above is the honest ceiling of "environment done with maths
alone"; going further would mean either breaking the no-texture-assets
constraint or writing a second, more expensive procedural system competing
with the gel shader for the same frame budget. Flagged, not attempted.

## 4. Piece presentation — gel stays, tetromino shapes need one new read

The move to four-cell tetromino pieces (seven classic shapes — I/O/T/S/Z/J/L,
per the Backend's `feat/tetromino-pieces` work) changes what a "piece" looks
like on screen but **does not change the material**. The gel shading
(`Shaders.kt`), rim/AO boundary language and grain cue
(`piece-identity.md`) all continue to apply per-body exactly as specified —
nothing in this section replaces them.

What's new: **a tetromino piece is one archetype (one hue) painted across
four connected cells**, where today's toy piece is one archetype painted
across one body. The existing rim-light/contact-seam pair
(`docs/contracts.md` §3 — `particleEdge` brightens free surface,
`particleContact` darkens neighbour contact) already does exactly the right
thing here with **zero shader change**: where two cells of the *same* piece
touch, that's a `particleContact` seam like any other internal contact — it
should read as *part of the same material*, softly creased, not as a hard
boundary. Where a piece's cell touches a *different* piece or the settled
stack, the identical seam reads as the piece boundary. The material doesn't
need to know the difference, and per §2's rejection of a hard cel outline,
it shouldn't try to draw one — a continuous, softly-seamed four-cell gel
piece reading as "one squishy thing with an internal crease" is more
consistent with the brief's tactile-organic direction than a rigid four-square
outline would be.

**Confirm before Frontend builds against this:** whether `bodyArchetype` is
still one value per whole tetromino (all four cells sharing one archetype
index, i.e. `:core-sim` still emits one hue per piece) or whether the
four-cell structure introduces any sub-body archetype variation. I've asked
the Frontend Engineer this directly (their `feat/tetromino-pieces`-adjacent
control work makes them the one who'll know first) — if the answer is
"still one archetype per piece," this section needs no further action; if it
turns out to be otherwise, the seam argument above needs revisiting and I
should hear about it before it's built.

## 5. The piece-hue decision — seven hues, closing `Palette.kt`'s own flag

`Palette.kt:96-103` already names the defect: `Simulation.ARCHETYPE_COUNT = 7`
but `piece-identity.md` specifies six hues, so archetype 6 was folded onto hue
0 as an explicit placeholder pending a UX decision. Seven tetromino shapes
give exactly seven archetypes — **one hue per shape** is the clean mapping,
closing the collision outright rather than trading it for a different one.

**New seventh hue: Emerald.**

| # | Name | Hex | H° | S | L | Grain scale |
|---|------|-----|----|---|---|------|
| 7 | Emerald | `#3BA12B` | 112 | 58% | 40% | 2.0× |

Constructed by the same rule as the original six
(`piece-identity.md`'s "Scaling the palette" section, followed literally):

- **≥30° from every existing hue and outside the reserved 15°–65° amber
  band.** The six existing hues (150/187/224/261/298/335) leave two open
  arcs: 65°→150° (85° wide) and 335°→15° (40° wide, wrapping through 0°). The
  second arc is too narrow to fit a hue ≥30° from Rose (335°) without
  crowding the reserved band's edge at 15° — the arithmetic doesn't work
  (a ≥30°-clear hue on both sides needs ≥60° of room, and there's only 40°).
  Emerald sits at 112°, in the wider arc: 47° clear of the reserved boundary
  (65°) and 38° clear of Jade (150°), the nearest existing hue.
- **Positioned to lean toward green, away from yellow-green**, deliberately —
  112° is closer to pure green (120°) than to chartreuse (90°), which keeps
  it visually further from the reserved amber/gold zone than the arc's
  midpoint would be, for the same reason the reserved band exists at all
  (band-glow's identity-cap argument in `band-glow.md` already accepts that
  *any* glowing piece drifts warmer under the additive amber term — Emerald
  isn't a new risk there, just not one that starts closer to the line than
  it has to).
- **Lightness continues the alternation.** The existing ladder alternates
  dark/light down the table (Jade 40 → Teal 54 → Azure 44 → Violet 56 →
  Magenta 42 → Rose 55); Emerald at L40% continues it as the next "dark"
  step, matching Jade's and Azure's register rather than sitting between
  two lights.
- **Grain scale continues the existing 0.2×-per-step ladder** (0.8× through
  1.8× across six pieces) to 2.0× — free, reuses the existing per-archetype
  uniform array, no shader change.

**What this changes structurally, for Frontend:** `Palette.PIECE_COUNT`
6 → 7, `Palette.SURFACE_INDEX` 6 → 7, `Palette.SIZE` 7 → 8, one new row in
the `RGB`/`GRAIN` arrays, and — the actual fix — `pieceHue(archetype)` no
longer needs the `floorMod` collision workaround at all once there are seven
hues for seven archetypes; it can become the identity function (or be
deleted in favour of `bodyArchetype` indexing the palette directly, which is
simpler and was always the eventual intent per that function's own doc
comment). Nothing else in `Shaders.kt` changes — the palette is a uniform
array sized by `Palette.SIZE`, already built to grow by one row.

**Which physical tetromino shape (I/O/T/S/Z/J/L) gets which archetype index,
and therefore which hue, is a Backend/Frontend contract decision, not a UX
one** — I'm not assigning shape-to-hue here, only supplying seven
well-separated hues in a stable index order. Whatever mapping they settle on,
the only hard rule carried over from `piece-identity.md` is: **don't reorder
the hues to "look right" for a particular shape** — the lightness alternation
and hue spacing were derived as a set, and reordering rows breaks the
CVD-separation argument that motivated the spacing in the first place.

## 6. A real HUD, and why it costs nothing on the GPU

**Score, level, and next-piece preview replace the frame-time readout as the
primary on-screen chrome.** Full layout, states and focus order for this are
specified in `screens/playing.md` (updated alongside this document) — this
section states the one design decision that makes it affordable.

**The HUD lives in the Android View layer, not in GL.** `MainActivity.kt`
already composes plain Views over the `GameView` surface — the debug readout
and the current game-over `TextView` are both proof this pattern exists and
works (`MainActivity.kt:116-130`). Score, level, and the next-piece preview
continue that pattern: ordinary `Canvas`-drawn Views, composited by the
platform's own (essentially free, hardware-composited) View pipeline, sitting
above the GL surface. This is a deliberate reading of "feedback lives on the
material, not in HUD elements" (`playing.md`'s founding principle) —
**gameplay** feedback stays in the shader; **status** chrome (a number, a
label, a small static shape) was never the kind of feedback that principle
was protecting, and drawing it in GL would spend fragment budget on
something a `TextView` does for free.

- **Next-piece preview** is a small flat silhouette (rounded-rect cells in
  the piece's hue with a simple two-stop vertical gradient suggesting the
  same "material has volume" cue as the real gel shading, at a fraction of
  the cost) — drawn with `Canvas`, not the gel shader. It does not need to
  look identical to the in-well material; it needs to communicate hue and
  shape at a glance, which a flat swatch does.
- **Score** ticks up during the clear-sequence dissolve exactly as
  `playing.md` already specifies — a `ValueAnimator`-driven text update is
  free next to a physics tick.

**Cost: effectively zero**, which is why this is the first thing to build of
everything in this document, and why it does not appear in the GPU cost
table in §7 — there is nothing there to cost.

## 7. The band-clear juice — the payoff moment, upgraded

`feel-feedback.md`'s existing timeline (ignition flash → 80ms hold → dissolve
→ physics drop, ≈800–1400ms total) is **not being replaced.** It is correct
and it is already implemented. What's missing is everything *around* it that
would make a player register it as an event, per Candy Crush's own lesson:
the clear itself (candies disappearing) is almost instant — what reads as
satisfying is the layered feedback around that instant (particle burst,
score pop, screen response), not the disappearance.

Three additions, each layered onto the existing timeline, each specified
with its cost:

**1. A screen-wide luminance beat, synced to the ignition flash (T+0→120ms).**
A single full-screen quad, flat additive colour (a desaturated warm white,
reusing `color-glow-hot` at very low peak opacity, ~6–10%), faded in/out
over the same 120ms the ignition flash already ramps. This is **not** bloom
and **not** HDR — it's one flat-shaded quad, same cost class as the
background pass in §3, and it answers the "no bloom" constraint by not being
one: it's a plain additive blend on a single triangle, not a per-fragment
glow-spread computation. **Cost: one extra draw call, trivial fragment
shader, ~120ms lifetime.**

**2. An ember-particle burst at the clearing band, T+0→~350ms.** 8–16 small
quads (not full soft bodies — no physics, no lattice), spawned at randomised
positions along the clearing band's width, given a simple analytic
trajectory (a small upward-then-falling arc, computed from elapsed time
alone — no simulation, matching the "Juice it or lose it" cheap-particle
discipline in §2) and faded out via an alpha ramp. Colour: `color-glow`
amber, same reserved hue as the band-glow itself — these are the *ignition's*
embers escaping the material, not a new colour language. Reuses the existing
flat-shaded draw path (same program, disable the gel-specific attributes the
way `WellFrame.draw()` already does for the walls — see the pattern at
`WellFrame.kt:176-184`) rather than writing a second shader.
**Cost: 8–16 extra quads (16–32 triangles) for ~350ms, once per clear event**
— clears are not a steady-state cost, they're an occasional burst, so this
never competes with the sustained per-frame body-rendering budget.
**Flag to Frontend:** this needs a small reusable vertex buffer (ring-buffer
style, sized for the max simultaneous burst across all bands that could
clear at once) — new geometry, not a shader change, and should be sized once
at startup like `BodyMesh`'s `maxBodies`, not allocated per clear.

**3. A score pop at the clear location.** A short, HUD-layer (§6 — Android
View, not GL) floating "+N" label that scales in with overshoot
(`motion-pop`, §8) and drifts up slightly while fading, synced to the score
tick-up already specified in `playing.md`. **Cost: zero on the GPU**, same
reasoning as §6.

**What is deliberately not added:** no screen-space bloom, no post-process
pass, no per-pixel volumetric light shaft — all three would either need a
second full-scene render pass (multiplies total fill-rate cost) or a blur
kernel (multiple texture samples per pixel, the exact kind of cost `Shaders.kt`'s
own header explicitly ruled out for the base material). The three additions
above are chosen specifically because each is O(1) extra draw calls or O(1)
extra tiny geometry, never O(screen pixels × extra passes) and never
O(bodies × extra shader complexity) — the two categories of cost this
project cannot afford, per the brief and per `Shaders.kt`'s own documented
budget.

**Multi-band clears** (already specified as "ignite together, not staged" in
`feel-feedback.md`) get the full-width version of all three additions for
free — the luminance beat and score pop are already screen/HUD-level events,
and the ember burst simply spawns across every clearing band's width in the
same call.

## 8. Motion / animation language

Two motion vocabularies now exist and should stay visually distinct:

| Token | Duration | Easing | Use |
|---|---|---|---|
| `motion-fast` / `motion-base` / `motion-slow` | 100/200/400ms | ease-out / ease-in-out | **Unchanged** — UI-chrome transitions (`tokens.md`), screens sliding/fading in and out. Nothing here overshoots. |
| `motion-pop` | 160ms | `cubic-bezier(0.34, 1.56, 0.64, 1)` (back-out, overshoots ~12% past target then settles) | **New.** Anything that should feel like a small celebratory arrival: the score-pop label (§7.3), the next-piece preview swapping in a new shape, the "NEW BEST" badge on Game Over. |
| `motion-celebrate` | 280ms | `motion-pop`'s curve, larger scale delta (1.0 → 1.15 → 1.0) | **New.** Reserved for the single biggest per-session moment — the "NEW BEST" reveal on Game Over (`screens/game-over.md`). Used once per run, at most; using it for routine feedback would cheapen the one moment it's meant to mark. |

**Reduced motion.** `motion-pop`/`motion-celebrate`'s overshoot is decorative
bounce, not core feedback — under Reduced Motion (`accessibility.md`), both
collapse to a plain `motion-fast` cross-fade (no scale, no overshoot),
exactly the same category of change already specified there for screen shake
and jiggle. The score number and the "NEW BEST" badge still *appear*
immediately; only the celebratory motion is removed. This needs one line
added to `accessibility.md`'s reduced-motion table — done in this branch,
§9 below.

**Everything in §7 that has a duration** (the luminance beat, the ember
burst) is wall-clock milliseconds like every other timing in this spec set,
for the same frame-rate-independence reason `tokens.md` and
`feel-feedback.md` already give.

## 9. What this changes elsewhere in `docs/ux/`

- `piece-identity.md` — the seventh hue (§5 here) is now the source of truth
  there too; that document's "Scaling the palette" section is marked
  resolved rather than open.
- `tokens.md` — palette table extended to seven hues; `color-bg`/
  `color-bg-deep`/`color-bg-core` distinction added (§3); `motion-pop`/
  `motion-celebrate` added to the motion table (§8).
- `screens/playing.md` — HUD section rewritten around the real score/level/
  next-piece chrome (§6); frame-time readout demoted to an explicitly
  toggleable dev element; background/environment added to the layout
  description; the D6 overflow-warning cue (below) added.
- `screens/game-over.md` — a short "visual treatment" section added,
  connecting its existing (already good) layout/states to the new
  environment and to `motion-celebrate`.
- `feel-feedback.md` — a pointer to §7 of this document at the top of "The
  band-clear sequence," so the timeline and the juice upgrade don't drift
  into two competing descriptions of the same moment; plus a new short
  section for the **D6 overflow-warning cue**:

  > **Overflow warning (D6).** `docs/contracts.md` already reserves an
  > `uOverflow` uniform (0..1, ADR 0005) that nothing currently reads —
  > `GameRenderer.kt` never sets it. `Phase.Overflow(remainingTicks)` starts
  > at `graceTicks = 90` (~1.5s at 60Hz, `SimConfig` default). Treatment:
  > the spawn band's top edge pulses in `color-warn` (`#FF5A5A`, already
  > reserved in `tokens.md` for exactly this, previously unused), rate tied
  > to `remainingTicks / graceTicks` — slow at the start of the grace,
  > accelerating as it runs out, same "cause → warning → payoff" grammar
  > `band-glow.md` already established for clearing, reused for losing. This
  > is a warning, not a glow: `color-warn` is red, never amber, so it cannot
  > be confused with a band about to clear even though both use the same
  > pulse-rate-communicates-urgency language. **Cost: reuses the existing
  > per-band-fill uniform-array upload path already in the shader — no new
  > varying, one new uniform, one new `if` branch keyed on `vArchetype <
  > PIECE_COUNT` exactly like the existing band-glow gate.** No screen shake,
  > no haptic — this is a sustained state, not an impact event, and a
  > sustained haptic would violate `accessibility.md`'s photosensitivity/
  > "never the sole channel" rules in spirit even though it's a different
  > modality.
- `accessibility.md` — one row added to the reduced-motion table for
  `motion-pop`/`motion-celebrate` (§8).

## 10. Sequencing and open items

- **Do not build against this until the controls (Frontend) and tetromino
  shapes (Backend) branches merge.** Confirmed with the Frontend Engineer
  directly (not routed through the Product Lead) — see the handoff.
- **Confirm on-device cost of the background pass (§3) and the ember
  particles (§7.2) before shipping them at the default quality tier.** Both
  are designed to be cheap by construction, but "designed to be cheap" is not
  "measured to be cheap," and this project's whole standing discipline
  (`Shaders.kt`'s tier ladder, the volume-key measurement ritual in
  `MainActivity.kt`) is to price things on the real device rather than trust
  the estimate. I've recommended to the Frontend Engineer that both get
  folded into the existing shade-tier measurement pass rather than becoming
  a second, parallel dial — one ladder, not two.
- **Shape-to-hue mapping (§5) is Backend/Frontend's call**, not decided here.
- **CVD verification is still an open action item inherited from
  `piece-identity.md`** — now for seven hues, not six. Restated, not
  re-litigated: QA runs the final rendered, lit, glowing pieces through a
  CVD simulator before the release gate.
