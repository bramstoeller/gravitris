# Design tokens

Small on purpose — ten spacing values beat forty. Gameplay-specific timing
numbers (glow ramps, clear sequence, haptics, shake) live in `band-glow.md`
and `feel-feedback.md` and are not duplicated here; this file is the index
for everything else: colour, type, spacing, radii, shadow, and the
UI-chrome-level motion tokens.

## Colour

The game canvas is a **single, fixed dark theme** — it does not follow the
device's system light/dark setting. This is a deliberate deviation from a
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
| `color-glow` | `#FFB347` | Band-glow core, amber. Reserved — never a piece hue. Also the clear-juice ember colour (`visual-direction.md` §7). |
| `color-glow-hot` | `#FFF4E0` | Band ignition flash, and the screen-wide luminance beat that now syncs to it (`visual-direction.md` §7.1) |
| `color-warn` | `#FF5A5A` | **Now spec'd**: the D6 overflow-warning pulse on the spawn band, `visual-direction.md` §9 / `feel-feedback.md`. Red, never amber — must never be confused with band-glow. |

Piece hues: **seven** values as of 2026-07-21 (`visual-direction.md` §5
closes the six-hues-for-seven-archetypes collision `Palette.kt` flagged),
specified in full in `piece-identity.md` (reproduced here for convenience
only — that document is the source of truth):

`#1FB37A` `#1B9FC0` `#3162E0` `#7148E0` `#B23FC7` `#D63C6E` `#3BA12B`

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
