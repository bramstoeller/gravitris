# Gesture thresholds

Concrete numbers, in dp (density-independent pixels) and ms, so the Frontend
Engineer can implement without guessing. Rotation is deliberately a tap, not
a gesture, per the brief — gestures that need discrimination from a drag
always misfire, so this document's entire job is protecting that boundary
with real numbers rather than vibes.

All values below are **prototype-milestone starting points**, explicitly
tunable (same status as the coverage threshold) — expose them as named
constants, not literals, so they can be retuned against the client's real
device without a rebuild-from-spec.

## State machine

One active pointer is tracked at a time (see Multi-touch below). On
touch-down, nothing commits yet — start tracking position, time, and a
short-window velocity (last ~60ms of samples).

1. **Hard-drop check (evaluated continuously while a pointer is down, can
   fire before release):** if cumulative vertical displacement has exceeded
   `hardDropMinDisplacement` **and** the instantaneous drag stays within
   `hardDropAngleCone` of straight down **and** downward velocity exceeds
   `hardDropMinVerticalVelocity` → commit **hard drop** immediately. Don't
   wait for release; this is the one gesture that should feel instant.
2. **Drag (once movement exceeds touch slop and isn't a hard-drop):** if
   horizontal displacement exceeds `touchSlop` and motion is not dominated
   by the hard-drop cone → enter drag mode. Piece x-position updates
   continuously using **delta since last sample × `dragSensitivity`**, not
   delta from touch-down (so the thumb's absolute position on screen never
   matters — a drag started anywhere behaves the same). Clamp at well
   walls.
3. **Tap (resolved on release):** if cumulative movement never exceeded
   `touchSlop` for the whole gesture → rotate. No duration limit — a slow,
   stationary press-and-release is still a tap, because nothing in this game
   uses a long-press, so there's no reason to penalize a hesitant tap.
   Rotation is a fixed 90°, one direction (clockwise) only; there is no
   second rotation direction in scope (see Rejected below).
4. If released while in drag mode, without ever crossing the hard-drop
   thresholds: the piece simply stays at its last dragged x-position and
   continues falling normally.

## Numeric thresholds

| Constant | Value | Note |
|---|---|---|
| `touchSlop` | 8dp | Matches Android's `ViewConfiguration` default touch slop — don't invent a new value, reuse the platform one so it already matches the player's muscle memory from every other app. |
| `dragSensitivity` | 1.0 (1:1 dp mapping) | 1dp of finger movement = 1dp of piece movement in world space. Direct, unaccelerated — start here, retune only if playtesting says the well is wider/narrower than the comfortable drag range. |
| `hardDropMinDisplacement` | 16dp | Minimum downward travel before the velocity check is even evaluated — prevents a fast micro-jitter (e.g. thumb settling) from firing a drop. |
| `hardDropAngleCone` | ±25° from vertical | A swipe that's mostly-down-but-a-bit-sideways still counts; a diagonal drag does not. |
| `hardDropMinVerticalVelocity` | 1000dp/s | Measured over the trailing ~60ms window. |
| `rotateDebounce` | 60ms | Ignore a second touch-down within this window of a committed tap, to absorb touch-controller bounce/double-report. |

## Frame-rate independence

All thresholds above are in dp and dp/s (velocity) or are purely
displacement-based — none are expressed as a frame or sample count. This
matters because the confirmed client device (Fairphone 6) has an adaptive
10–120Hz panel, and Android touch input is commonly sampled well above
display refresh rate regardless of which Hz the panel is currently
running at. Implement velocity from real elapsed time (a trailing ~60ms
window of timestamped samples), not from a fixed per-frame delta, so these
thresholds behave identically regardless of render or touch-sampling rate.

## Multi-touch

Only the first pointer to go down is tracked. Additional simultaneous
pointers are ignored entirely — no multi-touch gestures are in scope. This
also means a second accidental finger (common one-handed, e.g. the game
resting against a palm) cannot hijack or cancel an in-progress gesture.

## Why slop-only (no duration) for tap

The classic failure mode is a slow, small, deliberate drag being misread as
a tap, or a decisive tap being misread as a micro-drag. Gating tap on
**cumulative displacement alone**, independent of how long the gesture took,
sidesteps the ambiguous case a duration limit would create: a hesitant,
slow-but-still-small press must still register as a rotate, not silently do
nothing because it took too long. If a genuine need for a duration-gated
action (e.g. long-press) appears later, it is a new gesture and needs its own
spec — not bolted onto tap.

## Landing silhouette interaction

The silhouette (see `landing-silhouette.md`) follows the piece's live
drag-updated x-position at whatever rate drag updates run (does not need to
match render frame rate — see that document for the update-rate
recommendation).

## Explicitly rejected

- **Second rotation direction (tap left half vs. right half of screen):**
  would add a second decision the player has to get right under one-handed,
  thumb-low play, and duplicates the exact gesture-discrimination risk this
  whole document exists to avoid. Deferred; revisit only if playtesting shows
  single-direction rotation is a real limitation, not before.
- **Tilt as an alternate rotate or move input:** already rejected by the
  client (fiddly, accessibility problem) — not reopened here.
- **Duration-gated tap** (see above) — rejected in favour of slop-only.
