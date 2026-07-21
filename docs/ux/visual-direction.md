# Visual direction — 2026-07-21

**Superseded by round 3, §11 onward — read this notice first.** §1–§10 below
shipped, were code-reviewed against real screenshots, and were signed off
(§10's "Visual sign-off" entry). Then the client field-tested that build on
the Fairphone 6 and rejected it: *"the visuals are still mediocre... the
little squares don't join properly... I expected much more glassy, glossy,
transparent, candy-stick-coloured things."* That is a real miss the team
owns, not a tuning note — a dark tactile-organic gel was the wrong material
entirely, not an under-tuned version of the right one. **§1–§10 are kept
verbatim below as the record of what shipped and why, not deleted and not
silently reversed. §11 onward is the current direction — build against that,
not against this section.**

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
- **Two implementation gaps found by the Frontend Engineer during the build
  (`feat/visual-layer`), agreed 2026-07-21, both confirmed against the actual
  contract rather than assumed:**
  - **Next-piece preview (§6, `screens/playing.md`) needs a `SimState`
    field that doesn't exist.** `PieceSequence.peek()` is `internal` to
    `:core-sim` (verified: `PieceSequence.kt:31`); nothing about the
    upcoming piece crosses the module boundary today. **Agreed: omit the
    slot entirely until the contract changes, rather than show an empty
    placeholder or fake data.** This needs a `docs/contracts.md` addition —
    at minimum `SimState.nextArchetype`, more if the preview should show
    shape rather than only hue — which is Backend's to implement and the
    Architect's to sign off (contracts.md's own ownership table). Flagged
    directly to Backend; not decided here.
  - **The score-pop "+N" (§7.3) has no delta to show** — `SimState.score`
    is hardwired to `0` pending D8, so a "+0" label would read worse than
    no label at all. **Agreed: build the ember burst and the luminance beat
    now** (both key off `Phase.Clearing`/`bandClearProgress`, which are real
    today) **and defer the score-pop until D8 lands.** The clear still reads
    as a real event without it — the pop was always the smallest of the
    three additions in §7, not the one carrying the moment.
  - Everything else in this document is buildable now, including the
    seven-hue palette (already on `main` in `Palette.kt` by the time this
    was checked) and the rest of the HUD frame against the placeholder
    score/level values (see `screens/playing.md`'s note on this — a static
    "LV 1" is an honest current value, not a fake one, which is a different
    situation from next-piece's missing field entirely).
- **Visual sign-off (2026-07-21) against `.team/reviews/0035-visual-layer/`
  (PR #25): direction confirmed, signed.** Background reads as a world, HUD
  matches `playing.md`, ember burst reads as a real event, Game Over closely
  matches the wireframe. Two things I couldn't confirm from the first set of
  stills, resolved on follow-up rather than left open:
  - **The two §3 background glows measured invisible, not merely subtle** —
    the Frontend Engineer found the original code added the near-black
    `#0E1730`/`#241B3D` tokens at 4-8%, and adding a near-black colour cannot
    brighten anything (measured: <0.1% luminance difference at the disc
    centre). Fixed, and the token row above corrected to match — see
    `tokens.md`'s `color-bg-glow-a`/`-b` entry for the real values now live.
  - **The §7.1 luminance beat is built, not cut** — `ClearFlash`, a 120ms
    triangle envelope keyed to `Phase.Clearing` onset. Confirmed by the
    Frontend Engineer's own frame-brightness measurement (the ember capture
    was the single brightest of 132 frames), not just asserted. No screenshot
    will show it clearly on its own at 8%/120ms — that's by design, per this
    document's cost table, not a shortfall.
  - **Grain-per-cell tiling on multi-cell pieces is real, and I traced it to
    source rather than relaying the Frontend Engineer's guess.** Visible in
    `02-stack-hues.png`/`03`/`04`: the mottle noise restarts at each
    tetromino cell's boundary instead of reading as one continuous mass.
    `contracts.md:149-150` documents `particleU`/`particleV` as **body-local
    lattice coord, 0..1**. The actual behaviour is deliberate, not a stray
    bug: `SoftBodyWorld.kt:431-434` sets `particleU[i] = col / edgeSpan`
    per cell, with the comment *"UV tiles per cell (each cell 0..1), so the
    material grain reads the same on a tetromino cell as on the old block"*
    — Backend chose cell-local tiling on purpose, to keep the grain
    frequency matching pre-tetromino single-cell pieces. **So this is a
    stale-contract-text problem, not an implementation bug**: `contracts.md`
    describes something the code deliberately doesn't do, and needs
    correcting either way. Underneath that, there's a real product
    trade-off, not a docs typo: keep the current per-cell tiling (grain
    scale stays consistent with single-cell pieces, at the cost of a visible
    seam on multi-cell ones) vs. make `particleU`/`particleV` genuinely
    body-local (continuous grain across a whole piece, at the cost of grain
    scale changing between single- and multi-cell pieces, and touching a
    core-sim data path several other things may depend on). That call is
    Backend/Architect's, not mine to make unilaterally — flagged to Backend
    with these exact line numbers so it isn't rediscovered from a
    screenshot. Not blocking this release either way: hue continuity and
    the rim/contact-seam — the primary and secondary identity cues — are
    confirmed correct in the same screenshots; grain is documented in
    `piece-identity.md` as the tertiary, least-reliable cue on purpose.
- **Game Over's secondary "Title" action (`screens/game-over.md`) has
  nowhere to go yet** — Title (`screens/title.md`) isn't built. Agreed with
  the Frontend Engineer: ship Play Again prominently (it's the primary
  action per `flows.md`'s "one more try" loop regardless) and omit the
  Title button until a Title screen exists, rather than stub a dead link.
  Not a redesign — `game-over.md`'s focus order already lists Play Again
  first for the same reason.
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

---

# Part two — round 3: glossy jelly candy on a light candy world (2026-07-21)

## 11. What the client actually said, and what's already decided

The client field-tested the round-2 build (§1–§10 above) on the real
Fairphone 6 and rejected the look outright — not a tuning complaint:

- "The visuals are still mediocre."
- "The little squares don't join properly — they look modelled as 4 separate
  squares instead of tetromino pieces."
- "I expected much more glassy, glossy, transparent, candy-stick-coloured
  things." Candy Crush gems, named explicitly.

Follow-up answers, which this document treats as decisions, not options:

- **Material:** glassy jelly-candy — translucent, high-gloss, a wet specular
  highlight, light passing slightly through.
- **Colour/pattern:** left to us ("verzin wat leuks"), inside the standing
  colourblind-safe constraint.
- **Background:** lighter and candy-like, away from the dark indigo, toward
  a bright candy world the glossy pieces can belong to.

A second round of feedback landed while this document was being written,
pointing the same direction and now folded in rather than treated as a
separate pass:

- **Corners:** "vreselijk strak" (terribly sharp/hard-edged) — slightly
  rounded, not die/cube rounded, just softened.
- **Antialiasing:** edges read as aliased/jaggy; smooth them.
- **Shadows:** soft shadows under the pieces, so they sit in the world
  rather than float.
- And, plainly: **"do your best to design something beautiful."** The
  client is trusting us with the creative call here, not asking for a quick
  reskin. Rounded + antialiased + soft-shadowed + glossy-translucent + light
  candy world is one coherent target, not five separate asks — §13 states it
  as one thing.

Two problems drive everything below: **the material and the background were
wrong** (§14–§19 rebuild them), and **a tetromino must read as one connected
piece, not four glued tiles** — the client's own words, and independently the
root cause the Frontend Engineer already traced to source in §10's last
bullet. §15 promotes that fix (backlog D10) from deferred to required and
makes it the foundation the rounding and specular-sweep work in §16–§17
builds on.

## 12. Real Candy Crush — what was actually looked at

Per the client's own instruction — "kijk even hoe candy crush eruit ziet,"
look at the real game, don't work from memory — four current, full-resolution
screenshots were fetched and viewed directly (not summarized from a text
description, not recalled): APKPure's mirror of the Google Play listing for
`com.king.candycrushsaga` (App Store's own listing page renders its
screenshot gallery client-side and would not yield real images through a
text-mode fetch; APKPure's `image-eo.winudf.com` CDN serves the same Play
Store assets as static JPEGs, confirmed 1080×2340, i.e. an actual current
phone screenshot, not a thumbnail). A fifth, older, low-resolution screenshot
from the Wikipedia article on Candy Crush Saga (`Candy_Crush_Saga_game_setup
_example.jpg`) was also viewed and cross-checked against the four current
ones — included because it happens to show the HUD's pill-badge language
particularly clearly, not as a substitute for current references.

**What the material actually looks like, read directly off the screenshots:**

- **One hard, near-white specular highlight per candy, biased upper-left,
  elongated along the object's own long axis rather than a small round
  dot.** On the round/oval candies (blue marble-like orbs, yellow drops,
  orange ovals, red jelly-beans) it's a bright streak that follows the
  curve of the surface, sharply bright at its brightest point and feathering
  out — not a soft, wide Phong lobe. On the green cylindrical candies it's a
  vertical bright band down one side. It reads as *glass/hard-candy*
  specifically because the highlight is small, hard-edged and
  high-contrast, not because of anything else in the material.
- **The base body colour is flat and highly saturated** — almost no gradient
  of its own beyond a touch of edge-darkening at the true silhouette
  boundary. Nearly all of the "3D-ness" a candy has comes from the
  highlight and that edge-darkening, not from a soft body-wide shading
  gradient. This matters for cost: it means the expensive-looking part of
  this material (looking wet, hard, glassy) is actually the *cheap* part —
  one bright, tightly-shaped highlight term — and the parts that would be
  expensive to fake (a true multi-bounce gloss, real refraction) are simply
  not present even in the reference.
- **A small number of pieces (the stippled purple flower-shaped ones, the
  white swirl/pinwheel ones) are matte and pattern-textured instead of
  glossy** — a stippled dot texture, a pinwheel-swirl texture, both flat and
  soft-edged with no specular streak. This is the same "pattern variation is
  cheap, geometry variation is not" lesson §2 already drew from Candy
  Crush's specials, now with two additional concrete pattern references
  rather than the abstracted "striped/wrapped/bomb" description in the
  earlier pass.
- **Every candy sits on a soft, dark, offset drop shadow directly beneath
  it**, cast onto the tray or whatever is behind/below it — not a hard
  silhouette double, a soft, low-contrast blur that reads as "resting on a
  surface," most visible in the gutters between candies where the tray shows
  through.
- **The board is a muted slate-blue-grey tray** (a cooler, far less
  saturated blue-grey than any candy on it), individual cells inset with a
  soft rounded corner, not a sharp right angle, and the *gaps between
  candies* reveal thin slivers of that tray colour even in a densely packed
  grid — the tray is always the thing a candy's rounded corner reveals, not
  the background scene beyond the board.
- **The scene around and behind the tray is bright, saturated, and
  atmospheric** — in the gameplay screenshot: teal/cyan cave light with
  soft vertical light rays and heavy background blur (depth-of-field); in
  the level-complete screen: a warm sunset gradient (blue to purple to
  pink) with confetti and sparkle particles. Neither is a flat fill; both
  are bright, saturated, and clearly a "world," reinforcing §2's original
  99-Bricks-derived lesson (graduated environment, not flat colour) but now
  at a light key instead of a dark one.
- **Small four-point sparkle glints** sit on and near the glossiest candies
  and HUD elements — a cheap "catching the light" accent, same juice-methodology
  category as this document's existing ember-burst work (§7.2), not a new
  technique to invent.
- **HUD chrome is pink, pill-shaped, and cream/white-outlined** — a
  light-coloured ring around every jelly-coloured badge (the star icons, the
  level-complete "1" medallion) is a small, recurring, cheap detail that
  reads as "candy" almost by itself.

**What this direction takes, and what it deliberately does not:**

We take: the hard, elongated, biased specular highlight (§14); the flat,
highly-saturated base body with the highlight and edge-darkening doing
almost all the shaping work, which is good news for cost (§14); the soft
tray-ward contact shadow (§18); the muted, calmer tray the pieces sit in,
against a bright saturated world around it (§19); small sparkle accents,
layered onto the existing ember-burst system rather than a new one.

We do not take: the literal match-3 board mechanic (not this game — obvious,
stated for completeness); an authored, per-level bespoke background scene —
this project's standing procedural-only constraint (ADR 0007, the brief)
rules out the photographic, blurred, depth-of-field backgrounds in these
references the same way §3 already ruled out 99 Bricks' authored sky art,
and §19 specifies the honest procedural equivalent; a literal alpha-blended
transparent body — real translucency (seeing through material to what's
behind it) would need `GL_BLEND` and draw-order sorting this renderer
deliberately doesn't have (§14 states the cheap proxy actually used instead
and why it reads as translucent without paying for real transparency); the
match-3 candies' pure-round/lozenge silhouettes — our pieces are tetromino
lattices, and §16 specifies rounding as a corner treatment on that existing
silhouette, not a wholesale shape replacement.

Sources: [Candy Crush Saga — Google Play, mirrored by APKPure](https://apkpure.com/candy-crush-saga/com.king.candycrushsaga)
(four screenshots fetched directly from `image-eo.winudf.com`, viewed at full
1080×2340 resolution); [Candy Crush Saga — Wikipedia](https://en.wikipedia.org/wiki/Candy_Crush_Saga)
(one older screenshot, `Candy_Crush_Saga_game_setup_example.jpg`, viewed for
the HUD pill-badge cross-check only).

## 13. The target look — for the client to confirm before we build it

*(This section is written to be lifted into a client-facing summary
directly — it is the "here's the vibe" version, not the implementation
spec. The implementation is §14–§19.)*

Picture the well sitting in a bright, soft candy-coloured world — a pale
sky-blue-to-warm-cream gradient behind it, not the near-black canvas from
before. The well's own tray (walls and floor) is a calm, muted blue-grey —
noticeably quieter than everything else on screen, so the pieces are the
only saturated, eye-catching thing in the frame, the same way a real Candy
Crush board stays calm so its candies can pop.

The pieces themselves: still the same seven jewel-tone hues (jade, teal,
azure, violet, magenta, rose, emerald — unchanged, still colourblind-safe),
but now rendered as glassy jelly candy rather than matte rubber — a single
bright, hard highlight sweeping across each whole piece like light catching
a wet, glassy surface, a soft inner glow that suggests light passing
slightly through the material rather than stopping at its surface, and
corners softened just enough that a piece reads as one solid, huggable
jelly shape rather than a hard-edged block. Where a tetromino has more than
one cell, the whole thing reads as **one continuous piece** — one highlight
sweeping across all four cells, one soft glow through the whole body, no
visible seam where the cells were joined — which is the direct fix for "the
little squares don't join properly."

Every piece casts a small, soft, tray-coloured shadow onto whatever's beneath
it, so the stack reads as physical objects resting on and against each other,
not flat cutouts. Every edge in the scene is smoothed rather than jagged.

What we are **not** doing: turning this into a match-3 game, or authoring a
bespoke illustrated background scene per level (procedural-only stays a hard
constraint) — the candy-world feeling comes from colour, gradient and the
material on the pieces themselves, done with shader maths, the same
honest-procedural approach that already built the rest of this game.

## 14. The material — glossy translucent jelly candy

Every term below is additive to, or a retune of, the existing gel material
(`Shaders.kt`) — nothing here proposes a second shader or a second draw
path. §2's earlier rejection of "glossy hard-candy specular highlights" (*"a
different material entirely... would fight the tactile, organic, squashable
identity"*) is explicitly overridden by the client's own follow-up answer —
noted here so the reversal is visible, not silently different from what §4
already said.

**Base translucency/subsurface — kept, not reinvented.** The existing
subsurface term (`Shaders.kt` tier 1: `depth = min(vBodyUv, 1-vBodyUv)*2`,
darkened+saturated toward the body's core, lighter toward its edge) is
*already* the right physical story for "light passing slightly through a
jelly body" — thin material near an edge lets more light through and reads
brighter/less saturated; thick material at the core reads deeper and richer.
Round 2 used it as a subtle darkening; round 3 leans on it harder (raise
`uSubsurfaceGain`) as the primary "this is translucent" cue, since a true
alpha-blended see-through body is explicitly rejected below on cost grounds.
**Once §15 lands, this term runs on true body-wide UV** rather than
per-cell UV, so the depth gradient reads across the whole tetromino's
silhouette, not each cell's — this is the same underlying fix that also
resolves the seam complaint, restated here because it also directly serves
translucency.

**True alpha transparency — considered and rejected.** Seeing background or
neighbouring-piece colour literally through a piece would need `GL_BLEND`
and per-frame depth sorting of overlapping translucent geometry — a real
architectural change to a renderer built around one opaque draw call
(`BodyMesh.draw()`, `Shaders.kt`'s own header: "the renderer deliberately
keeps blending off"). The subsurface term above is the cheap, honest proxy:
it reads as "light passing through" without ever compositing two fragments.
Flagged, not attempted, in the same spirit §3 flagged a literal parallax
scene as out of scope.

**Specular highlight — new term, the client's explicit ask.**

- **Shape and position:** a single fixed-direction highlight streak, biased
  toward the upper-left of each piece (matching every real reference in
  §12), computed as a smoothed threshold of `bodyUv · lightDir` where
  `lightDir` is a fixed 2D constant (≈ pointing up-and-left in body space) —
  the same "cheapest approximation that still reads" discipline as the
  existing rim term (no `pow`, no `normalize`; a dot product and a
  `smoothstep`). Width and sharpness are one gain/one sharpness uniform,
  `uSpecularGain`/`uSpecularSharpness`, tuned on-device like every other gain
  in this shader — not fixed here.
- **Colour:** fixed, near-white, neutral (`color-specular`, `tokens.md`) —
  **never tinted per piece**, for the identical reason `RIM_COLOR` is fixed:
  a coloured highlight would shift the apparent hue exactly where players
  read a piece, and hue is the primary identity cue (`piece-identity.md`).
- **Suppressed where `vContact` is high** — a face pressed against a
  neighbour or the tray is not a free glossy surface, same logic the
  existing rim term already applies, same uniform, no new state.
- **Runs on body-wide UV** (§15) so the highlight sweeps across an entire
  multi-cell piece as one continuous streak — this is the literal mechanism
  behind the brief's "the specular highlight sweeping across the entire
  piece," not a separate feature.
- **Cost:** one dot product, one `smoothstep`, one `mix` — the same order of
  cost as the existing rim term it sits alongside. Not the expensive part of
  this direction; see §17 for what actually is.

**Fresnel / edge behaviour — the existing rim term, retuned and dual-purposed.**
The existing rim light (`Shaders.kt`: cubic falloff of `vEdge`, fixed
neutral cool-white) already is a cheap fresnel approximation (brightening at
a free, grazing surface). It keeps its existing role as the primary
small-screen boundary/identity cue (`piece-identity.md`) and takes on a
second one: retuned warmer/brighter toward `color-specular`, it reads as the
glassy edge-catch-the-light cue real glass/gel material shows at its
silhouette. No new term, no new uniform beyond retuning `uRimGain` and the
rim colour constant — both already exist.

**Rounded glossy bevel** is specified separately in §16, because it needs
its own data (a new per-particle corner signal) rather than being a shading
retune — read it as part of this same material, not a separate feature.

**Net shader cost of this section:** one new term (specular: dot product +
smoothstep + mix, ~4 ops) plus retuned constants on two existing terms
(subsurface gain, rim gain/colour) — no new uniforms beyond the two named
above, no new transcendentals, no new draw call. This is the cheapest of
the four round-3 asks; §16–§18 carry the real costs.

## 15. Continuous whole-piece surface — D10 promoted from deferred to required

**Backlog D10 is no longer deferred.** It was filed as a look-call to be
judged on the real device rather than SwiftShader; the client's round-3
verdict — *"they look modelled as 4 separate squares instead of tetromino
pieces"* — is exactly that judgement, made on the real Fairphone, and it
comes back "fix it." This is the client independently confirming the defect
the Frontend Engineer had already traced to source in §10's last bullet.

**What has to change, restated precisely against the code:**
`SoftBodyWorld.kt:431-434` sets `particleU[i] = col / edgeSpan` and
`particleV[i] = row / edgeSpan`, per **cell** — a fresh 0..1 range at every
cell boundary, which is why the grain restarts at each cell and reads as
"four tiles." `docs/contracts.md`'s own text already says `particleU`/
`particleV` are "body-local lattice coord, 0..1" (line ~149) — the contract
was always aspirational for the whole tetromino; the code has never matched
it. **This document requires closing that gap: `particleU`/`particleV`
become genuinely body-wide** — 0..1 across each tetromino's actual bounding
footprint (four cells for every current shape per `PieceShapes.kt`), not
per-cell.

**What this fixes, all from one data-path change:** the grain/mottle term
(the client's literal complaint), the subsurface depth term (§14 — now reads
correctly across a whole piece's silhouette instead of resetting at cell
seams), and the specular highlight (§14 — now sweeps continuously across a
multi-cell piece instead of repeating per cell). Three visual fixes, one
contract change — this is why D10 is required rather than one of three
separate asks.

**The size/frequency trade-off backlog D10 already named, and its fix.**
Body-wide UV means the grain/pattern frequency will vary with a piece's
footprint (an I-piece spans four cells in a line; an O-piece spans a 2×2
square) unless compensated. Fix: fold a per-archetype **area/aspect
compensation factor** into the existing `uGrainScale[PALETTE_SIZE]` uniform
array (`Shaders.kt` already indexes this per-archetype for the identity
grain-scale cue, `piece-identity.md`) — a static, precomputed constant per
of the seven shapes, no new uniform, no new array. `PieceShapes.kt` already
knows every shape's cell layout (`cells`, frozen and pinned by
`PieceShapesTest`), so this compensation factor is derivable once, offline,
from data that already exists.

**This is Backend Engineer + Architect work, not Frontend or UX's to
implement** — a core-sim data-path change (`SoftBodyWorld.kt`) and a
`docs/contracts.md` correction (the contract text already claims body-local;
the fix is making the code match it, plus documenting the new
`particleCorner` field §16 needs). Flagged directly to both — see the
message log, not just this document, per the team's "talk to each other
directly" norm. **Recommendation, not a requirement:** do this and §16's
`particleCorner` addition in the same PR — they are the same category of
fix (renderer needs whole-piece awareness `PieceShapes` already has
internally but doesn't expose) and Backend already offered to build the
body-local UV change on a fresh branch per the existing D10 backlog entry.

## 16. Rounded corners — softened, not cube-rounded, without new geometry

**The client's ask is specific: "slightly rounded — not as much as a
die/cube, just softened corners."** This has to apply to the tetromino's
**true outer silhouette corners only** — rounding every individual cell's
four corners independently would visibly pinch/notch at the seam between
two same-piece cells (a rounded square fused to another rounded square
along a flat edge shows a waist right at the join), which would *reintroduce*
the "four separate squares" read this whole document exists to fix. So the
mechanism must be whole-piece-aware, the same requirement §15 already
established for grain and specular — and it piggybacks on that same data
gap rather than opening a second one.

**Mechanism: a new per-particle signal, interpolated the same cheap way
`vEdge` already is.** A new field, `particleCorner` (0..1; 1 exactly at a
particle that is a true convex outer corner of the whole tetromino's
silhouette — i.e. a lattice-corner particle whose cell has no neighbouring
cell in *either* of the two directions that would make that corner interior
— 0 everywhere else), computed once at body spawn from `PieceShapes`'
already-known cell layout (`PieceShapes.neighbour()` already exists and
answers exactly this question) the same way `particleEdge`/`shapeEdge`
already are. Uploaded once per body-set change, in the existing "static"
material buffer (`BodyMesh`'s `materialVbo`, already carries `particleU`/
`particleV`/`particleEdge` on exactly this update cadence) — **zero new
per-frame cost**, one more static float per particle.

Because it is vertex-interpolated across the existing triangle mesh exactly
like `vEdge`, `vCorner` naturally ramps from 1 at a true corner to 0 over
one lattice spacing (0.36–0.6 world units depending on quality tier,
`SimConfig.kt:199,274`) with **no new geometry** — the existing per-cell
vertex density (16–36 particles per cell depending on tier) already provides
enough resolution near a corner to express a soft curve.

**Shaping the fade to the client's "slightly, not cube" ask.** A full
one-lattice-spacing linear fade is a large fraction of a 1.8-unit cell —
likely too much rounding taken raw. Raise it to a power
(`pow(vCorner, N)`≈`vCorner*vCorner` at `mediump`, avoiding a real `pow`
call the same way the rest of this shader avoids one) to pull the visible
effect in tight around the true corner while reusing the exact same
underlying data — `tokens.md`'s `radius-piece-corner` records the *apparent*
target (~10–15% of a cell width), not a raw geometry number, and `N` is a
tunable gain like every other constant in this shader.

**Where the fade goes: `color-tray`, never the sky background.** A piece's
corner is physically adjacent to the tray or to another piece almost
everywhere it appears in the well — deep in a stack, at the floor, against
a wall — and essentially never adjacent to the outer sky gradient (§19),
which only shows through the well's *empty* space above the stack. Fading a
corner toward `color-sky-*` would look correct only at the very top of an
otherwise-empty well and visibly wrong everywhere else (a light gradient
patch appearing mid-stack). Fading toward the fixed `color-tray` constant
(`tokens.md`'s `corner-fade-mode`) is correct everywhere in the well, at the
cost of zero position-dependent sampling — cheaper *and* more correct than
the alternative, which is why it was chosen over reusing §19's background
gradient function a second time.

**What this does to `WellFrame` (the walls/floor):** one more generic
attribute to disable, exactly the pattern the existing table in
`WellFrame.kt`'s doc comment already uses for `compression`/`contact`/
`edge`/`bodyUv` — `particleCorner` set to a constant 0 for the frame's
draw, so the walls never round (they aren't a piece and have no corners to
round).

**Cost: one new static per-particle float (zero per-frame cost, §above),
one extra fragment-shader term (a power/multiply and a `mix`, same order of
cost as the specular term in §14).** Not the expensive item in this
document — that is §17.

**Owner:** Backend Engineer for the `particleCorner` computation (rides with
§15's body-local UV change, same PR recommended), Architect for the
contract addition, Frontend Engineer for the shader/mesh-attribute wiring
and the shaping-exponent tuning pass.

## 17. Antialiasing — a real, named GPU cost, not a free toggle

**The client explicitly named this: edges read jagged, and they want them
smoothed.** This is a genuine, non-trivial cost on the Fairphone 6 and this
document is not hiding that behind "just turn on AA."

**Recommended mechanism: hardware MSAA on the render surface (`EGLConfig`
with a multisample buffer, e.g. 4×), resolved by the driver, not a
shader-side technique.** This is the right tool for exactly this problem —
smoothing the *silhouette* edges of opaque, single-draw-call geometry — and
it is compatible with everything else in this document without changes:
unlike a shader-side edge-antialiasing trick (distance-field edges,
supersampled alpha), MSAA needs **no change to `Shaders.kt`, no `discard`,
no `GL_BLEND`** — it operates entirely at the rasteriser/resolve stage, so
every existing "no blend, no discard" cost argument in `Shaders.kt`'s header
stays true. This is also why it is the right general-purpose fix rather
than something narrower: it smooths the tetromino silhouette edges *and*
the rounded-corner treatment from §16 *and* the well frame's edges, all at
once, for one configuration change rather than three shader-level ones.

**Cost, named plainly:** 4× MSAA roughly quadruples colour/depth attachment
memory bandwidth and adds a resolve pass, on a GPU whose entire remaining
frame budget this project has been measuring in fractions of a millisecond
since Stage 1 (`Shaders.kt`'s own header: 15.0ms mean against a 16.67ms
budget, with a *nearly flat* shader). Stacked on top of the light-background
full-screen pass (§19, itself already flagged as the single most expensive
new thing in the round-2 direction) and the new specular/corner terms above,
this is a real risk to the frame budget, not a decoration. **Recommend
folding the MSAA sample count into the existing shade-tier ladder
(`uShadeTier`) as its own dial** — e.g. 4× at the default tier, 2× or off at
a lower one — following the exact discipline `Shaders.kt`'s own tier table
already established: measure on the real device, cut first if the budget
disagrees, and the tiers are already ordered cheapest-to-most-expensive for
exactly this reason. **Not decided here which tier MSAA sits at** — that is
an on-device measurement call for the Frontend Engineer, flagged so it
gets made deliberately rather than shipped at a guessed default.

**Alternative considered and rejected:** shader-side edge smoothing via
per-fragment alpha + `GL_BLEND`, blending each body's edge fragments against
whatever is behind them. Rejected for the same reason `Shaders.kt` already
rejects blending everywhere else — it would require sorting overlapping
translucent edges correctly, which a single flat draw call of the whole
stack cannot do, and it would reopen exactly the architectural question
§14 already closed for the body's main surface. MSAA sidesteps this
entirely because it operates on opaque coverage, not alpha.

## 18. Soft shadows — pieces resting in the world, not floating on it

**Client: "schaduwen, etc" — soft shadows, named as a big part of why Candy
Crush pieces feel physical.** Confirmed directly in §12's screenshots: every
candy casts a soft, low-contrast, tray-coloured (not pure black) shadow
directly beneath it.

**Mechanism: a second, cheap draw pass of the existing body geometry**,
before the real body draw, reusing `BodyMesh`'s exact vertex buffer with two
changes applied in the vertex shader: an offset (`shadow-offset-piece`,
`tokens.md` — down and slightly right, world units so it scales with the
piece rather than the screen) and a small outward scale from each body's own
centroid (so the shadow reads as a soft, slightly-larger blurred silhouette
rather than a hard duplicate outline — the cheap, no-blur-kernel way to fake
a soft-edged shadow, in the same spirit as this shader's existing "cheapest
approximation that still reads" discipline). The shadow pass's fragment
shader is much simpler than the gel material: a flat `color-shadow`
(`tokens.md` — a darkened tray tone, never pure black, matching the real
references' coloured-not-black shadow read) at low, roughly constant alpha,
optionally softened at its own edge using the existing `vEdge` varying so
the shadow itself fades rather than cutting off hard.

**This needs `GL_BLEND` enabled, but only for this one pass** — the shadow
is the one place in this whole document where turning blending on is the
right trade rather than the rejected option: it is a single low-complexity
pass, drawn once, with a trivial fragment shader, not a general-purpose
transparency system competing with the main material every frame.

**Cost, named plainly:** roughly doubles per-frame vertex throughput for the
body geometry (two draws of the same vertex count instead of one) and adds
one blend-enabled draw with its own state change. The *fragment* cost of the
shadow pass itself is cheaper than the main gel material (a handful of ops
against the gel shader's several dozen), but the vertex cost and the state
change are real and additive to everything else in this document. **Same
recommendation as §17: fold shadow rendering into the shade-tier ladder as
its own cuttable step**, not a second, separate quality dial — `Shaders.kt`'s
own tier-ordering principle (cheapest, highest-legibility terms survive
longest) applies directly: the shadow is high legibility value (it is a
large part of why the candy references read as physical objects) but not
free, so it belongs in the same measured ladder as everything else, not
assumed free by default.

**Owner:** Frontend Engineer (a new small draw pass + program, following the
exact pattern `Background`/`WellFrame` already establish for "a second
cheap program alongside the main gel shader") — no core-sim or contract
change needed, since the shadow pass consumes data (`SimState.positionX/Y`,
body membership) already crossing the boundary.

## 19. The light candy background — replacing §3's dark environment

**§3's dark environment pass (`Background.kt`) is superseded, not
patched.** The client's ask — "lighter and candy-like... away from the dark
indigo" — is a reversal of the premise, not a request to lighten the same
two-stop gradient by a few percent. `tokens.md`'s "Light candy world"
section is the colour source of truth; this section is the shader/mechanism
half.

**Same architecture as §3, recoloured and recalibrated for a bright field,
not rebuilt:** one full-screen quad, drawn once per frame before the well
frame and the bodies, O(screen pixels) cost exactly as before —
`Background.kt`'s existing structure (vertical gradient + two soft
CPU-drifted radial patches + ordered dither) is kept; only the colour
constants and one piece of arithmetic change.

- **Vertical gradient:** `color-sky-top` (`#BFE9F7`, soft sky-blue) at the
  top of frame to `color-sky-bottom` (`#FDEFE0`, warm pale cream) at the
  bottom — replacing `color-bg-deep`/`color-bg-core`'s near-black-to-
  deep-indigo sweep. Per §12's references (the sunset gradient on the
  level-complete screen, the cave light in the gameplay screenshot): bright,
  saturated-but-soft, never a flat single value.
- **The two soft radial patches survive as a mechanism, recoloured and
  re-derived from scratch, not re-tinted.** This is the one place round 2's
  own bug (`tokens.md`'s corrected `color-bg-glow-a`/`-b` row: adding a
  near-black tint to a near-black field is invisible regardless of opacity)
  matters again, in the opposite direction: adding a *bright* tint
  (`color-sky-glow-a`/`-b`, near-white/warm-white) at the same disc radius
  and falloff will read as an even *more* washed-out, low-contrast patch
  against an already-bright field than the old bug did against a dark one,
  unless the peak values are pushed close to full white rather than the old
  4–8%. **This needs the same on-device brightness measurement the
  Frontend Engineer already did to catch the round-2 bug** — don't assume
  the old peak-opacity numbers transfer, they were derived for the opposite
  background regime.
- **Well tray:** `color-surface` → `color-tray` (`#7C93A6`, muted slate-blue)
  for the walls/floor, `color-tray-inset` for the one-line boundary
  bevel — a **darkened** inset now, replacing round 2's emissive
  (brightened) inset edge, since the legibility move on a light tray is a
  soft shadow-line, not a glow-line (same logic as this section's shadow
  reversal generally: round 2's "add light" tricks become round 3's "add
  shade" tricks, because the base field flipped from dark to light).
- **Scrim:** `color-overlay-scrim` moves off pure black (`tokens.md`) — a
  black scrim over a light-key canvas reads colder and heavier than this
  game's new tone wants.

**Cost:** unchanged in kind from §3 — still the single most expensive *new*
full-screen item, still zero transcendentals per pixel by the same
CPU-hoisted-drift trick `Background.kt` already uses. **Stacks with §17's
MSAA cost and §18's shadow pass** rather than replacing either — this
section does not reduce the round-3 budget risk, it's one of three
simultaneous new costs (§17 antialiasing, §18 shadows, this background
recolour) that together need the same on-device measurement discipline
`Shaders.kt`'s tier ladder already exists for. None of the three is
individually prohibitive; stacked, they are the real open risk this
document is flagging, not any single one of them.

## 20. Consequences flagged, not resolved here

- **`band-glow.md`'s glow model needs a revisit this document does not
  make.** It is additive-only amber over the base hue, tuned for near-black
  headroom (`band-glow.md`, `tokens.md`'s pre-correction history). Against
  the new light sky, the same additive term will under-read for the
  identical reason the old background glows did before they were corrected
  (`tokens.md`'s own corrected `color-bg-glow` row is the precedent) —
  adding warmth to an already-bright scene barely changes it. A fix likely
  needs the emissive term to lean on saturation/hue-intensification rather
  than pure additive brightening once a band is inside a bright well, closer
  to how Candy Crush's own specials (§12: striped, wrapped, bomb) read as
  bold saturated pattern changes rather than "brighter." **Not designed
  here** — flagged to whoever next opens `band-glow.md`, so it is found
  before a release gate rather than at one.
- **HUD text contrast is addressed in `tokens.md` (`color-hud-chip`), not
  here** — restated only so it isn't missed: `color-text` flipped from
  light-on-dark to dark-on-light, and every canvas-overlaid HUD text element
  now needs the chip backing, not only the level chip that already had one.
- **CVD verification (§10, `piece-identity.md`) still stands, now against
  the glossy/lit render, not the matte one** — the seven hues are unchanged,
  but the material rendering them is not, and CVD simulation has to run
  against what actually ships.

## 21. What this changes elsewhere in `docs/ux/`

- `tokens.md` — the full "Light candy world" colour section, new
  `radius-piece-corner`, and the new "Material" token category
  (`color-specular`, `color-shadow`, `shadow-offset-piece`,
  `corner-fade-mode`) — done in this branch.
- `piece-identity.md` — no change to the palette itself; a pointer belongs
  there noting the hues are now rendered as glossy jelly rather than matte
  gel, so a future reader isn't confused by `Shaders.kt` no longer matching
  §4's old "gel stays, tetromino shapes need one new read" framing. Not
  done in this pass — flagged, small, cosmetic, doesn't block Frontend.
- `band-glow.md` — needs the §20 revisit. Not done in this pass.
- `screens/playing.md` — background/environment description needs updating
  to the light-sky read instead of the dark-canvas one, and every HUD text
  element's layout note needs the chip-backing call-out. Not done in this
  pass — flagged, same reasoning as `piece-identity.md` above: cosmetic
  relative to what Frontend needs to start building, shouldn't block this
  handoff.
- `accessibility.md` — the Contrast section's worked example (`color-text`
  on `color-bg`/`color-surface`) needs updating to the new dark-on-light
  pair and the chip-backing requirement. Not done in this pass — same
  reasoning.

**Why the four items above are flagged rather than done in this branch:**
this document's job was to get the material/background/rounding/AA/shadow
direction concrete enough to build against and get it in front of the
client before Frontend starts — per the dispatch, that's the actual
deadline. The four flagged docs are real but don't block that; they're
correctness/consistency cleanup that can land alongside or just after the
first build, and saying so here is more honest than quietly doing a rushed
version of five documents instead of a careful version of one.

## 22. Sequencing and open items

- **Confirm the target look with the client before Frontend builds the
  whole thing** — §13 is written for exactly this, and the dispatch asks for
  it explicitly. Nothing in §14–§19 should be built against until that
  confirmation lands, per the same "don't build a third simultaneous
  rewrite before the direction is confirmed" discipline §10 already
  established for round 2.
- **§15 (body-local UV) and §16 (`particleCorner`) are Backend Engineer +
  Architect work, recommended as one PR** — flagged directly, not routed
  through the Product Lead only (see the message log). This is the one
  genuine data-path/contract dependency in this document; everything else
  (§14, §17, §18, §19) is Frontend-only, against data already crossing the
  boundary.
- **§17 (MSAA) and §18 (shadows) both need on-device cost measurement
  before a default tier is chosen** — recommended folding both into the
  existing shade-tier ladder rather than adding new, separate quality
  dials, for the same reasons `Shaders.kt`'s own tier table gives.
  Confirmed sensible with the Frontend Engineer directly, not decided
  unilaterally here — see the message log for what was actually agreed.
- **§19's recoloured background glow patches need the same on-device
  brightness check that caught the round-2 bug** — do not assume the old
  peak-opacity numbers transfer to a light-field background.
- **§20's flagged items** (`band-glow.md`'s additive-glow-on-light-bg
  problem, and the four docs in §21) are real and not silently dropped —
  they're sequenced after this document's actual deadline, not skipped.
- **Shape-to-hue mapping remains Backend/Frontend's call** (§5, unchanged) —
  nothing in round 3 touches which archetype gets which hue, only how each
  hue is rendered.
