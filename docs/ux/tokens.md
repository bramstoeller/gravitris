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
1 billion colours):** `color-bg` is now true black (`#000000`), not the
near-black `#12141C` this document specified before the device was known.
On OLED, only true black costs (near) zero power per pixel and gives the
glow the maximum possible contrast headroom to rise out of — a dark-grey
background wastes both the sustainability argument this device's whole
identity rests on and the punch the glow is supposed to have. `color-surface`
stays a lifted dark blue-grey rather than also going to black, specifically
so the well's walls/floor read as a distinct surface against a true-black
void — if the background and the walls were both `#000000` the well would
have no legible boundary on an OLED panel's true blacks.

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

| Token | Value | Use |
|---|---|---|
| `color-bg` | `#000000` | Well background |
| `color-surface` | `#1B1E29` | Well walls/floor, HUD chip backgrounds |
| `color-overlay-scrim` | `#000000` @ 82% | Dims the game canvas behind Paused/Settings/Game Over sheets |
| `color-text` | `#F2F1EC` | Primary text |
| `color-text-muted` | `#F2F1EC` @ 65% | Secondary/caption text |
| `color-glow` | `#FFB347` | Band-glow core, amber. Reserved — never a piece hue. |
| `color-glow-hot` | `#FFF4E0` | Band ignition flash |
| `color-warn` | `#FF5A5A` | Reserved for a possible top-out warning zone — **not yet spec'd as a screen element**; the losing condition is an open architecture question (see brief). Do not build a UI around this token until that's resolved. |

Piece hues: six values, specified in full in `piece-identity.md` (reproduced
here for convenience only — that document is the source of truth):

`#1FB37A` `#1B9FC0` `#3162E0` `#7148E0` `#B23FC7` `#D63C6E`

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

Gameplay timings (glow ramps and pulses, ignition flash, dissolve, minimum
watch window, haptic duration/amplitude, screen-shake amplitude/duration) are
specified in `band-glow.md` and `feel-feedback.md` and referenced from here
rather than repeated, to avoid the two copies drifting apart during tuning.

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
