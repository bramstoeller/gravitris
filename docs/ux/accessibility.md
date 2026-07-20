# Accessibility

Two requirements are in scope from the brief itself — reduced motion and a
colourblind-safe palette — plus the general baseline (contrast, target size,
focus order) that applies to any screen with real UI chrome. This document
says exactly what each setting changes, not just that it exists.

## Reduced motion — what it actually changes

A single ON/OFF toggle in Settings (default **off**). When on:

| Effect | Normal | Reduced motion |
|---|---|---|
| Screen shake | Up to 10dp / 200ms per impact (`feel-feedback.md`) | **Forced to 0dp** — not reduced, off |
| Post-squash jiggle/ring-down | Full amplitude and duration | Amplitude and duration scaled by `reducedMotionJiggleScale = 0.3` (≈70% reduction) |
| Primary squash-on-impact | Full | **Unchanged** — this is the core weight cue, not the repetitive motion the setting exists to remove |
| Camera/viewport otherwise | Static (no parallax/follow already) | Unchanged |
| Band-glow pulse rate | 2.4s period (70–85% fill) tightening to 0.9s (85–90%+) | Floor of 1.2s period even at the fastest (final-approach) stage — brightness ramp logic is unchanged, only the oscillation *rate* is slowed |
| Ignition flash (120ms, single event) | Plays | **Unchanged** — it's a single event, not a repeating flash, so it isn't the kind of motion this setting targets |
| Clear-sequence timing (dissolve, minimum watch window) | As specified | **Unchanged** — reduced motion addresses oscillatory/jitter/camera motion, not the core feedback loop the player needs to read the game |

Rationale for what's *not* touched: this game's core feedback genuinely is
motion (squash reads as weight, the re-settle is the payoff), so reduced
motion here means "remove the repetitive, disorienting, screen-wide motion,"
not "remove feedback." Cutting the wrong thing would silently break success
criterion 3 (understanding the clearing rule) for exactly the players this
setting is meant to serve.

**This matters more, not less, on the confirmed device.** The Fairphone 6
is a high-refresh (adaptive 10–120Hz) OLED panel — sustained screen shake
and jiggle rendered smoothly at up to 120Hz is a stronger vestibular/nausea
trigger than the same motion on a 60Hz LCD would be, not a milder one, since
higher-fidelity motion sampling reads as more physically real. Reduced
motion stays firmly in scope as specified above; nothing about it is
softened or made optional-in-spirit by this device update. **Flag to the
Architect:** confirm the zeroing/damping in the table above applies at
whatever layer does render interpolation, not only at the physics-tick
level — if rendering interpolates a physics-level shake/jiggle value up to
match the panel's refresh rate, the interpolation itself must not
reintroduce motion that reduced motion was supposed to remove.

**Nice-to-have, not required for the prototype:** default this toggle to on
if Android's system "Remove animations" accessibility setting is already on,
as a sensible starting guess. Flagged for the engineer to pick up if cheap;
not a blocking requirement.

## Photosensitivity (applies always, not gated by the reduced-motion toggle)

Hard rule for anyone tuning glow or shake in the future: **no visual element
may flash faster than 3 times per second** (WCAG 2.3.1's general flash
threshold). The ignition flash (single 120ms event, never repeats) and the
fastest normal glow pulse (0.9s period ≈ 1.1Hz) both sit well under this
ceiling today — write it down explicitly so nobody "tunes" the final-approach
pulse rate past it later while chasing more urgency.

## Colourblind-safe palette

Full palette, rationale, and verification status live in
`piece-identity.md`. Summary for this document: six piece hues spaced ≥37°
apart, a reserved amber band (15°–65°) that no piece may use (kept exclusive
to the band-glow signal), a lightness ladder and a grain-scale cue as
backups. Checked on paper against Okabe-Ito and IBM's published CVD-safe
references for protanopia, deuteranopia and tritanopia — **not** run through
an actual simulator, because none was available in this environment. Two
pairs are flagged there as higher-risk and covered by the backup cues.

**Action item, restated here because it's an accessibility gate item:** QA
must run the final rendered, lit, glowing pieces through a CVD simulator
(Android Accessibility's "Simulate color space," or Coblis on screenshots)
before the release gate.

## Haptics

Never the sole channel for any information — a device may lack amplitude
control, have vibration disabled by the OS or the user, or simply be in a
pocket. Everything haptics communicates (impact weight) has an always-on
visual equivalent (squash, propagation, shake) that does not depend on it.

## Contrast

WCAG AA applies to HUD text, buttons and labels — the game canvas itself is
imagery, not text, so AA ratios don't apply to in-game material.

- Primary text `color-text` (`#F2F1EC`) on `color-bg`/`color-surface`
  (`#000000`/`#1B1E29`) is a light-on-very-dark pairing with a large nominal
  gap and should clear AA's 4.5:1 (normal text) / 3:1 (large text, UI
  component boundaries) comfortably.
- **I have not computed exact contrast ratios with a verified tool** — don't
  take the pairing above on faith. The rule to build against is 4.5:1 for
  normal text, 3:1 for large text (≥18sp bold or ≥24sp regular) and UI
  component boundaries. Run `color-text-muted` (65% opacity) specifically
  through a contrast checker during implementation — dropping opacity for
  secondary text is exactly the kind of change that can quietly fall under
  4.5:1, and the rule should win over the specific opacity number if they
  ever conflict.

## Target size

All tappable UI targets (pause icon, settings toggles, buttons) are minimum
**48×48dp** with **8dp minimum spacing** between adjacent targets (Android/
Material accessibility baseline). The core gameplay surface (drag/tap/swipe
across the whole screen) has no target-size concern — it's the entire
screen — and its tap-vs-drag discrimination is handled by touch slop, not
target size (see `gestures.md`).

## Keyboard / switch / focus navigation

Gameplay itself is touch-only by genre convention and by the brief's
explicit control scheme — no keyboard equivalent is specified for
drag/tap/swipe. **Menu-level screens must support focus navigation**
(D-pad, Switch Access) since Android accessibility services rely on it:

- Title, Paused, Settings and Game Over each need a defined linear focus
  order (specified per-screen in `screens/`), top-to-bottom, with every
  focusable control carrying a content-description (e.g. "Reduced motion,
  toggle, currently off").
- The game canvas itself is exposed to TalkBack as a **single** accessibility
  element with a static label ("Game board") — no per-piece semantics are
  attempted. **This is a stated limitation, not a solved requirement:** a
  fully screen-reader-playable version of this game is not attainable given
  direct-manipulation gesture controls, and pretending otherwise would be
  worse than saying so plainly.

## Reaction-time accommodation — deferred, not omitted

I raised the difficulty curve's lack of a reaction-time accommodation
(fall speed rises over a run; reduced motion only touches camera/jiggle,
not speed) as an open question. The client's first answer was to make it a
first-class requirement; on reflection, they asked for it deferred to a
later iteration instead, consistent with "keep it simple for the first
iteration." **This is a resolved decision, not an open gap:** no
speed/reaction-time accommodation ships in iteration one. The full design
(decoupling the fall-speed ramp from the mass ramp, live-adjustable
mid-game) is written up and preserved, clearly marked deferred, in
`pace.md`, so it doesn't need re-deriving when this comes back on the
backlog.
