# Feel and feedback

Haptics, screen shake, impact propagation, and the band-clear sequence — the
brief's "the re-settle is the payoff moment of the whole game" is the design
centre of gravity for this document. All numbers are prototype-milestone
starting points, tunable like everything else numeric in this spec set.

## Impact haptics

Trigger: the physics contact event when a falling piece first touches the
stack or floor.

`energy = clamp(mass_norm × velocity_norm, 0, 1)` where mass and impact
velocity are each normalized 0–1 against the game's current min/max range for
that stat (which rises over a run, per the brief's difficulty dials).

| Energy | Duration | Amplitude (Android `VibrationEffect`, 1–255 scale) |
|---|---|---|
| < 0.15 | **suppressed** — no haptic at all | — |
| 0.15 | 10ms | ~60 (24%) |
| 1.0 (heaviest) | 40ms | 255 (100%) |

Interpolate duration and amplitude between those two points by energy
(linear is fine here — unlike the glow curve, there's no teaching job for
this ramp, just a felt-weight gradient).

Suppressing sub-0.15 impacts matters: without a floor, continuous small
settling contacts during a busy stack would turn into a constant low buzz,
which reads as noise, not feedback.

**Devices without amplitude control:** fall back to the platform's default
click/tick effect, gated by the same energy floor (no haptic below 0.15
energy).

**Haptics are never the sole channel for anything.** Vibration may be
unsupported, disabled by the OS, or muted by the user (a separate toggle,
see `screens/settings.md`) — the visual squash and screen shake below always
carry the same information independently.

## Screen shake

Trigger: same impact event, same `energy` value.

- **Positional offset only** — never rotation. A rotating camera reads as
  nauseating; a positional jolt reads as impact.
- Mostly-vertical bias with slight horizontal noise (impacts are vertical
  events).
- Amplitude: 1dp (energy ~0.1, barely perceptible) up to a **hard ceiling of
  10dp** at energy 1.0 — capped regardless of mass, so a heavy late-game
  impact never disorients the player or obscures the stack.
- Duration: 80ms (light) to 200ms (heavy), ease-out decay (fast initial
  displacement, smooth return to rest). Never linear snap-back, never more
  than ~2 decay cycles of ringing.
- **Reduced Motion forces amplitude to 0** — not reduced, off. See
  `accessibility.md`.

## Impact propagation down the stack

This is primarily a physics-correctness question, but it has a legibility
requirement worth stating: a heavy landing should visibly cause a brief
compressive ripple in the layers below, arriving with a slight per-layer
delay and decaying in magnitude as it travels down. This draws the eye
downward at the exact moment it matters and reinforces "this is one
connected mass," not "a piece landed on some inert scenery."

**Open question for the Architect:** does the solver's own constraint
propagation already produce a visible version of this at 60fps, or is it
correct-but-too-subtle-or-too-fast to read, needing an explicit shader-side
"impact wave" embellishment layered on top? Flagging rather than assuming.

## The band-clear sequence (the payoff)

**This timeline is the source of truth and is unchanged.** `visual-direction.md`
§7 layers a Candy-Crush-grade juice upgrade onto it (a screen-wide luminance
beat, an ember-particle burst, a HUD-layer score pop) — read that section for
the additions, not a restatement of the timeline below.

Full timeline from the moment a band is confirmed to clear:

| Time | Event |
|---|---|
| T+0ms | Clear confirmed (see quiescence note below) |
| T+0 → 120ms | Ignition flash: glow rises to 1.0, white-hot core (`#FFF4E0`) |
| T+120 → 200ms | Hold at full brightness (80ms) — a beat, not an instant cut |
| T+200 → 400ms | Cleared material visually dissolves/shrinks-and-fades (not a hard pop/disappear — reads as release of pressure, matching the brief's framing); score ticks up during this window |
| T+400ms onward | Physics takes over: material above the cleared band drops under real gravity/solver response. Duration is **not fixed** — it's whatever the solver produces, could be 300–900ms+ depending on how much mass was above. |

**Enforced minimum watch window:** no new piece spawns, and any
already-falling piece is held rather than continuing to drop, for **at least
400ms after the physics settle following the drop is detected** — i.e. total
pause-for-payoff is at minimum the 400ms sequence above plus 400ms of
watching the drop/re-settle, **≈800ms minimum**. **Hard ceiling: 1400ms**
total, even if micro-jiggling technically continues past that — resume
control once the stack is visually mostly settled rather than holding the
game hostage to a long tail of tiny residual motion.

**This minimum does not shrink as difficulty rises.** Per the brief, the
re-settle is explicitly meant to be watched, not rushed, at every difficulty
tier. The consequence — effective piece cadence slows slightly during
clear-heavy play regardless of speed dial — is intentional, not a bug to
tune away.

**Multiple simultaneous clears:** if more than one band crosses threshold at
the same settle check, they ignite together in one shared flash, not staged
sequentially. A multi-band clear should read as one bigger event. (Scoring
bonus for multi-clears is an Architect/scoring-formula question, not decided
here.)

**Quiescence check before confirming a clear:** a band's fill can spike
momentarily during the bounce of a heavy impact and settle back below
threshold a moment later. This sequence should only ever start once the band
(or the whole stack) has genuinely come to rest — **flagged to the
Architect** as a debounce/quiescence requirement (e.g. band-average velocity
below some epsilon for >100ms) rather than solved here, since it depends on
what the solver already tracks.

## Overflow warning (D6, added 2026-07-21)

Losing currently has no visual warning at all — `docs/contracts.md` already
reserves an `uOverflow` uniform (0..1, ADR 0005) for exactly this, but nothing
reads it (`GameRenderer.kt` never sets it), which is why the backlog records
D6: "no visual signal during the settle-grace window before game-over, so
losing feels abrupt."

**Trigger:** `Phase.Overflow(remainingTicks)`, which starts at
`graceTicks = 90` (~1.5s at 60Hz, `SimConfig` default) and counts down. If the
stack clears the spawn band and goes quiet within the grace, play resumes
with no penalty (ADR 0005) — the warning must resolve cleanly in that case,
not linger or leave an afterimage.

**Treatment:** the spawn band's top edge pulses in `color-warn` (`#FF5A5A`,
`tokens.md` — reserved for this since before the losing condition existed,
never assigned until now). Pulse rate tied to `remainingTicks / graceTicks`:
slow at the start of the grace, accelerating as it runs out — the same
"cause → warning → payoff" grammar `band-glow.md` already established for
clearing, reused here for losing instead. This is a warning, not a glow:
**red, never amber**, so a player can never mistake "about to lose" for
"a band about to clear" even though both share the accelerating-pulse
language.

**Cost:** reuses the existing per-band-fill uniform-array upload path already
in the shader — one new uniform (`uOverflow`, already named in the contract),
one new `if` branch gated on `vArchetype < PIECE_COUNT` exactly like the
existing band-glow gate. No new varying, no new draw call.

**No screen shake and no haptic for this state.** Overflow is sustained, not
an impact event — shaking the screen or buzzing continuously for up to 1.5s
would read as an alarm rather than a warning, and would sit uncomfortably
close to `accessibility.md`'s "no visual element may flash faster than 3
times per second" rule in spirit even in a different modality. The pulse is
the whole warning.

## Frame-rate independence

Every duration above (haptic duration, shake duration, the full band-clear
timeline) is wall-clock milliseconds, evaluated against elapsed real time —
not a frame count. The confirmed client device (Fairphone 6) runs an
adaptive 10–120Hz panel, and the Architect is likely fixing physics at 60Hz
with rendering interpolated up to the panel's rate. None of the timings
above assume or require 120Hz; higher refresh only makes the existing
curves (shake decay, glow pulse, dissolve) sample more finely, which is a
smoothness bonus, not a dependency. See `tokens.md`'s note on this same
point for the UI-chrome motion tokens.

## Everything in this document is a prototype-milestone starting point

Same status as the gesture thresholds and the coverage percentage: numbers
to build with, numbers to retune once there's a playable build on the
client's actual device (see the brief's Performance Verification section —
the loop closes on real hardware, not in this container).
