# Design tokens

Small on purpose — ten spacing values beat forty. Gameplay-specific timing
numbers (glow ramps, clear sequence, haptics, shake) live in `band-glow.md`
and `feel-feedback.md` and are not duplicated here; this file is the index
for everything else: colour, type, spacing, radii, shadow, and the
UI-chrome-level motion tokens.

## Colour

**Superseded 2026-07-21 (round 3) — the canvas is no longer dark.** Every
subsection below up to "Piece hues" describes the dark-canvas theme that
shipped for round 2. It is kept, struck through in spirit rather than
deleted, because the reasoning that built it (OLED power, glow-contrast
headroom) was sound *for that direction* and the record should show it was
reversed by client verdict, not quietly forgotten. **The light candy-world
palette that replaces it is in "Light candy world (2026-07-21, current),"
below the strikethrough section.** Do not build against the dark values;
they are history.

<details><summary>Dark-canvas theme (round 2, superseded) — kept for the record</summary>

The game canvas was a **single, fixed dark theme** — it did not follow the
device's system light/dark setting. This was a deliberate deviation from a
generic light/dark token pair, not an oversight: a falling-block well reads
best against a dark field, contrast against the warm amber glow language
depends on a consistently dark backdrop, and the genre convention plus
likely play-in-dim-commute-light context both favour it. The "light/dark"
distinction below instead applies to text-on-surface and overlay-scrim
pairs, both computed against the one canvas.

**Updated for the client's confirmed device (Fairphone 6, OLED, 1400 nits,
1 billion colours):** the canvas is true black or near enough to it
(`#000000`–`#05050C`), not the near-black `#12141C` this document specified
before the device was known. On OLED, true black costs (near) zero power per
pixel and gives the glow the maximum possible contrast headroom to rise out
of — a dark-grey background wastes both the sustainability argument this
device's whole identity rests on and the punch the glow is supposed to have.
`color-surface` stays a lifted dark blue-grey rather than also going to
black, specifically so the well's walls/floor read as a distinct surface
against a true-black void — if the background and the walls were both
`#000000` the well would have no legible boundary on an OLED panel's true
blacks.

**Banding risk this creates, and the fix:** a true-black background raises
the odds that a smooth low-intensity gradient — specifically the band-glow
ramp's 0→0.15 climb through the 40–70% fill range (`band-glow.md`), and any
AO/subsurface darkening on the gel shader — visibly bands on an 8-bit output
surface, since that's the exact regime (a slow sweep through near-black
values) where banding shows. Mitigation, detailed in `band-glow.md`: dither
the low-intensity glow term using the same procedural noise field that
already drives the gel-grain/shimmer, rather than treating dithering as a
separate system. This must hold regardless of whether the render surface
ends up 8-bit or takes advantage of the panel's 10-bit path — assume 8-bit
as the baseline to design against.

**Updated 2026-07-21 (`visual-direction.md` §3, §9): the flat `color-bg`
fill is replaced by a procedural environment pass, split into two stops.**
The well's own empty space now shows a graduated background instead of a
single flat colour — `color-bg-deep` (`#05050C`) at the top/bottom of frame,
warming very slightly to `color-bg-core` (`#0E1730`) at the vertical centre,
plus two fixed low-opacity radial glows. This is still overwhelmingly dark
(mean luminance barely above true black) so the OLED-power and
glow-contrast arguments above still hold, and the same banding risk and
dither mitigation apply to the gradient's own slow sweep through near-black
values, not only to the glow ramp and AO darkening named above.

| Token | Value | Use |
|---|---|---|
| `color-bg-deep` | `#05050C` | Environment gradient — top/bottom of frame (was `color-bg` `#000000`) |
| `color-bg-core` | `#0E1730` | Environment gradient — vertical centre of frame |
| `color-bg-glow-a` / `color-bg-glow-b` | `#050C12` / `#0D0815`, added directly at each disc's centre, radial falloff | The two fixed "distant crystal light" discs, `visual-direction.md` §3. Cool hues only — never amber, that's `color-glow`'s alone. **Corrected 2026-07-21**: the original row (`#0E1730`/`#241B3D` — this table's own `color-bg-core`/a near-neighbour, alpha-blended at 4-8%) was measured invisible by the Frontend Engineer — blending toward a near-black colour cannot brighten a dark field, whatever the opacity. These values are the peak colour **added** directly (not alpha-blended toward a base hue, unlike every other opacity-suffixed token in this table) and read as ~4-5% white luminance at each centre — verified against the refreshed `01-background-hud.png` in `.team/reviews/0035-visual-layer/`, both discs now clearly visible as soft localized light. |
| `color-surface` | `#1B1E29` | Well walls/floor, HUD chip backgrounds |
| `color-overlay-scrim` | `#000000` @ 82% | Dims the game canvas behind Paused/Settings/Game Over sheets |
| `color-text` | `#F2F1EC` | Primary text |
| `color-text-muted` | `#F2F1EC` @ 65% | Secondary/caption text |

</details>

### Light candy world (2026-07-21, current)

The client field-tested round 2 and rejected it outright: *"the visuals are
still mediocre... I expected much more glassy, glossy, transparent,
candy-stick-coloured things"* and *"[the background should be] lighter and
candy-like"* — a straight reversal of the dark-canvas premise above, not a
tuning note. `visual-direction.md` §11 onward is the full direction; this
table is its colour half.

**Why the OLED/power argument above no longer governs.** It was a real
argument for a genuinely dark canvas, but it was never the client's goal —
it was UX's justification for a direction the client has now explicitly
rejected twice. A bright canvas costs more OLED power; the client has not
raised power once across three feedback rounds and has raised "make it look
like Candy Crush" three times. Legibility and the client's own stated taste
win.

| Token | Value | Use |
|---|---|---|
| `color-sky-top` | `#BFE9F7` | Background gradient — top of frame. Soft sky-blue, per the real Candy Crush references (`visual-direction.md` §12) — never white, never grey; a "world," not a canvas. |
| `color-sky-bottom` | `#FDEFE0` | Background gradient — bottom of frame. Warm, pale cream/peach, so the vertical sweep reads as a light source overhead rather than a flat tint. |
| `color-sky-glow-a` / `color-sky-glow-b` | `#FFFDF5` / `#FFF3E8`, added directly, soft radial falloff | Two fixed soft warm patches (sun-through-haze, not "crystal light" — that language was the dark direction's), same drift/positions as the superseded discs, recalibrated bright-on-bright rather than dark-on-dark. See `visual-direction.md` §18 for why these must be **added at near-full peak opacity** to read at all against a light field — the additive-onto-near-black trick from round 2 does not transfer, and this row is *not* a value the old discs can just be re-tinted to. |
| `color-tray` | `#7C93A6` | Well walls/floor (was `color-surface`). A muted slate-blue, calmer and less saturated than every piece hue and the sky — directly the Candy Crush "tray" lesson (`visual-direction.md` §12): rich colour lives in the pieces and the sky, the tray they sit in stays quiet so they pop. Deliberately not light-neutral/white: against `color-sky-bottom`'s cream, a white tray would vanish; the tray needs to read as a distinct surface the way `color-surface` did against true black. |
| `color-tray-inset` | `#647B8E` | A single-shade-darker inset line/bevel on the tray's inner edge (walls meeting floor), replacing the old "emissive inner edge" (`visual-direction.md` §3) — that trick brightened an edge against a dark field; against a light tray the equivalent legibility move is a soft *darkening* inset, same cost (one constant added to the existing flat quad colour, now subtracted instead of added). |
| `color-overlay-scrim` | `#1B1E29` @ 70% | Dims the game canvas behind Paused/Settings/Game Over sheets. Colour changed from pure black — a black scrim over a light-key canvas reads as a heavier, colder interruption than this game's tone wants; a dark warm-neutral scrim (the retired `color-surface` value) dims exactly as effectively at a slightly lower opacity and doesn't fight the candy palette while it's fading in/out. |
| `color-text` | `#2B2A33` | Primary text. **Flipped from light-on-dark to dark-on-light** — `#F2F1EC` on a bright sky would fail contrast outright. See "Contrast against a light canvas," below — this is not a straight swap, it comes with a chip-backing requirement. |
| `color-text-muted` | `#2B2A33` @ 65% | Secondary/caption text. |
| `color-hud-chip` | `#FFFFFF` @ 78% | **New.** A translucent light pill behind every piece of canvas-overlaid HUD text (score, best, level — previously undecorated per `screens/playing.md`). Needed because the background is no longer a stable near-black field: it's a gradient plus two soft glow patches plus whatever piece hues are visible through the well's empty space, so no single fixed text colour clears 4.5:1 against all of it. A consistent light chip under every HUD text element guarantees the contrast pair is always `color-text` on `color-hud-chip`, never `color-text` on whatever the background happens to be at that pixel. Same `radius-sm`/`elevation-1` chip language the level chip already uses (`screens/playing.md`) — this makes every HUD text element use it, not only the level chip. |

**Contrast against a light canvas.** WCAG AA still applies to HUD text only
(`accessibility.md`), not to in-game material. `color-text` `#2B2A33` on
`color-hud-chip`'s effective surface (white at 78% over the sky gradient,
which is itself light) is a dark-on-light pair with a large nominal gap and
should clear 4.5:1 comfortably — **not verified with a contrast tool, same
standing caveat `accessibility.md` already carries for the round-2 pairing.**
Run it during implementation, and treat `color-hud-chip`'s 78% floor as the
one to check first: it's the only new opacity value in this table, and
opacity is exactly where contrast quietly slips (`accessibility.md`'s own
warning about `color-text-muted`, restated here for the new pair).

**Banding.** The light gradient sweeps through pale, high-luminance values
rather than near-black ones, which is a *lower*-risk regime for 8-bit
banding than round 2's near-black sweep — perceptual sensitivity to banding
is highest in shadows, lowest in highlights. The existing dither term is
kept anyway (cheap, already written, and the tray-inset bevel is a new small
gradient of its own) rather than proving it's now unnecessary.

Piece hues: **seven** values as of 2026-07-21 (`visual-direction.md` §5
closes the six-hues-for-seven-archetypes collision `Palette.kt` flagged),
specified in full in `piece-identity.md` (reproduced here for convenience
only — that document is the source of truth). **Unchanged by the round-3
pivot** — see `visual-direction.md` §13 for why: all seven sit at
L 40–56%, comfortably below the new sky's L ~90%+, so the contrast argument
that shaped them (hue-angle separation, CVD safety) is orthogonal to
background lightness and does not need re-deriving. What changes is the
*material* rendered in these hues (§13–§17), not the hues themselves:

`#1FB37A` `#1B9FC0` `#3162E0` `#7148E0` `#B23FC7` `#D63C6E` `#3BA12B`

Glow/warn tokens, unchanged in value but flagged for a review the light
background makes necessary — not resolved in this pass:

| Token | Value | Use |
|---|---|---|
| `color-glow` | `#FFB347` | Band-glow core, amber. Reserved — never a piece hue. Also the clear-juice ember colour (`visual-direction.md` §7). **Flagged, `visual-direction.md` §19**: `band-glow.md`'s glow is additive-only, tuned for near-black headroom; against the new light sky the same additive amber will under-read the way the old background glows did before they were corrected (this table's own history, above). Needs a pass, not designed here. |
| `color-glow-hot` | `#FFF4E0` | Band ignition flash, and the screen-wide luminance beat that syncs to it (`visual-direction.md` §7.1). Same flag as `color-glow` — an ignition flash this close to `color-sky-bottom` in value may no longer read as "white-hot" against a light frame. |
| `color-warn` | `#FF5A5A` | The D6 overflow-warning pulse (`visual-direction.md` §9 / `feel-feedback.md`). Red against a light tray still separates cleanly from amber — this pairing is not affected by the background pivot the way the two rows above are. |

## Type

One typeface family: any clean geometric sans available in the Android
toolchain (no brand typeface in scope). A monospace fallback is needed only
for the prototype-milestone frame-time debug readout (tabular figures so the
numbers don't jitter in width frame to frame) — any monospace system font is
fine, this is a debug element, not a design surface.

| Token | Size | Weight | Line height | Use |
|---|---|---|---|---|
| `type-display` | 40sp | 700 | 1.2× | Game-over score, title logo |
| `type-title` | 24sp | 600 | 1.2× | Screen headers ("Paused", "Settings") |
| `type-body` | 16sp | 500 | 1.4× | Settings labels, buttons |
| `type-caption` | 13sp | 500 | 1.4× | Secondary HUD text ("BEST"), helper copy |
| `type-debug-mono` | 12sp | 400, monospace, tabular figures | 1.2× | Prototype frame-time readout only |

## Spacing

8dp grid, 8 steps: `4 · 8 · 12 · 16 · 24 · 32 · 48 · 64` (dp)

## Radii

| Token | Value | Use |
|---|---|---|
| `radius-sm` | 4dp | Small badges/chips |
| `radius-md` | 12dp | Buttons |
| `radius-lg` | 20dp | Modal sheets (Paused/Settings/Game Over) |
| `radius-pill` | 999dp | Primary CTA button |
| `radius-piece-corner` | ~10–15% of a cell's 1.8-world-unit width, apparent | **New, `visual-direction.md` §16.** The soft bevel radius at a tetromino's true outer silhouette corners — client: "slightly rounded — not as much as a die/cube, just softened corners." Not a raw geometry radius: the underlying vertex data (`particleCorner`, §16) only interpolates to zero over one full lattice spacing (0.36–0.6 world units depending on quality tier, `SimConfig.kt:199,274` — a large fraction of the 1.8-unit cell), so this apparent radius is reached by shaping that interpolation with a power curve (`vCorner^N`), the same "tunable gain over hardcoded geometry" pattern every other shader constant in this file already uses (`uRimGain`, `uSubsurfaceGain`, …) — tune `N` on-device, don't hardcode it here. |

## Material (in-game surface, not UI chrome)

**New category, 2026-07-21** — the round-3 glossy-jelly-candy pivot adds
in-game surface tokens for the first time; every token above this point in
the file is UI chrome. Kept to four values on the same "resist growth"
discipline as the rest of this document — full derivation and the shader
mechanics in `visual-direction.md` §13–§17.

**Round-4 correction (2026-07-21):** the client rejected the shipped
execution of this material as "grainy rough rubber" — the grain amplitude/
frequency, the body-gradient direction, and the specular's shape were all
retuned in `visual-direction.md` §14.1–§14.3. **No value in this table
changed** — `color-specular` is still the right fixed colour, `color-shadow`
and `shadow-offset-piece` are untouched (those are §18's shadow pass, a
different track). What changed is the *shader terms* consuming
`color-specular`, described below.

| Token | Value | Use |
|---|---|---|
| `color-specular` | `#FFFFFF` @ 92%, warm-neutral (not tinted per piece) | The gloss highlight on every piece — fixed, like the retired `RIM_COLOR`, for the same reason: a coloured highlight would shift the apparent hue exactly where players read pieces. Confirmed against real Candy Crush references (`visual-direction.md` §12): the highlight is near-white, not tinted to the candy's hue. **Corrected shape, same colour** (`visual-direction.md` §14.3): no longer a single hard-edged streak with no length falloff (read as a diagonal scratch); now a soft-ended elongated patch (tapered along its own length, not just across it) plus a small brighter hotspot at its centre — still fixed-direction, fixed-position, suppressed at contact. |
| `color-shadow` | `color-tray` darkened 35%, @ 40% opacity | The soft contact shadow cast by every piece onto whatever is beneath it (tray or another piece). Not pure black — a black shadow on a saturated candy world reads as a hole; a darkened-tray-colour shadow reads as the tray in shade, per the real references' soft, coloured-not-black shadow read. |
| `shadow-offset-piece` | 0.08 world units, down + slightly right | Fixed offset for the piece contact-shadow pass, §17. World units so it scales with the piece, not the screen. |
| `corner-fade-mode` | fade-to-`color-tray` | Not a colour, a rule: §15's corner mask fades a piece's true outer corners toward `color-tray`, never toward `color-sky-*` — a corner is always physically adjacent to the tray/another piece in this game's layout, never to the outer sky, so fading to the sky would be visibly wrong deep in a stack. See §15 for why this was the one place the mechanism had to be checked against where pieces actually sit in the well, not just against the outermost visible row. **Stale as of the shipped code:** `Tunables.kt`'s `CORNER_ROUND` comment records that this was reworked to MSAA alpha-to-coverage instead (dropping fragment alpha at the corner, not fading toward a colour) after the fade read as a dark smudge against the sky. Not corrected here — corner/rounding is a mesh-and-coverage concern, a different lane than this material correction — flagged so whoever next opens this table fixes the row rather than trusting it. |

## Shadow / elevation

Two steps only, and only for UI chrome floating over the game canvas — no
drop shadows on in-game material, that job belongs entirely to the shader.

| Token | Offset | Blur | Colour | Use |
|---|---|---|---|---|
| `elevation-1` | 0/2dp | 6dp | black @ 24% | HUD chips (score badge) |
| `elevation-2` | 0/8dp | 24dp | black @ 40% | Modal sheets |

## Motion (UI chrome)

| Token | Duration | Easing | Use |
|---|---|---|---|
| `motion-fast` | 100ms | `cubic-bezier(0.2,0,0,1)` (ease-out) | Micro feedback (toggle flip) |
| `motion-base` | 200ms | same | Modal fade/slide-in |
| `motion-slow` | 400ms | `cubic-bezier(0.4,0,0.2,1)` (ease-in-out) | Screen transitions (Title → Playing fade) |
| `motion-pop` | 160ms | `cubic-bezier(0.34,1.56,0.64,1)` (back-out, ~12% overshoot) | **New, `visual-direction.md` §8.** Small celebratory arrivals: score-pop label, next-piece preview swap-in, Game Over's "NEW BEST" badge. |
| `motion-celebrate` | 280ms | `motion-pop`'s curve, larger scale delta (1.0→1.15→1.0) | **New, `visual-direction.md` §8.** Reserved for the single biggest moment per session — the "NEW BEST" reveal on Game Over. Not for routine feedback; using it there would cheapen the one moment it marks. |

Under Reduced Motion, `motion-pop` and `motion-celebrate` both collapse to a
plain `motion-fast` cross-fade — no scale, no overshoot — the same category
of change `accessibility.md` already specifies for screen shake and jiggle;
the content they animate in still appears immediately.

Gameplay timings (glow ramps and pulses, ignition flash, dissolve, minimum
watch window, haptic duration/amplitude, screen-shake amplitude/duration) are
specified in `band-glow.md` and `feel-feedback.md` and referenced from here
rather than repeated, to avoid the two copies drifting apart during tuning.
The band-clear juice upgrade (screen-wide luminance beat, ember particles,
score pop) is specified in `visual-direction.md` §7 for the same reason.

**Every duration in this document and in `band-glow.md`/`feel-feedback.md`
is wall-clock milliseconds, deliberately, not a frame count.** The confirmed
client device (Fairphone 6) has an adaptive 10–120Hz panel, and the
Architect is likely fixing physics at 60Hz with rendering interpolated up to
the panel's rate. None of this design depends on 120Hz to work — every ramp,
pulse, flash and shake is defined as a curve evaluated against elapsed
real time, so it plays out identically whether sampled at 60Hz or
interpolated at 120Hz. 120Hz is a smoothness bonus (shake and pulse motion
will simply be sampled more finely), never a requirement. If any future
addition to this spec set is tempted to reference "frames," flag it — that's
a sign it's implicitly assuming a fixed refresh rate this device doesn't
guarantee.

## Resisting growth

This set is deliberately incomplete relative to what a general design system
would define (no elevation-3+, no second typeface, no tertiary/quaternary
colour roles). Don't add a token because a future screen might need it — add
it when a concrete screen in this directory needs it and doesn't already fit
an existing one.
