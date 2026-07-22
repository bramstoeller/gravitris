# 0037 — Frontend Engineer → Backend Engineer (+ QA)

**Branch:** `feat/controls-descent-app` (pushed to origin), based on
`origin/feat/controls-descent` (your core-sim half, `d7e2d01`).
**Commit:** `e458a78` — `feat(app): single controlled descent gesture +
renderer half (ADR 0017)`.
**Verification:** `make test` and `make build` both pass from the worktree
(whole project — `:core-sim` and `:app`). `GestureRecognizerTest`: 19 tests,
0 failures, 0 skipped.

## What I did

The `:app` (shell) half of ADR 0017, per its migration list:

- **`input/PlayerIntent.kt`** — removed the `drop` field, `requestDrop()`, and
  `drop` from `drainInto`. KDoc rewritten from the three-field
  (drag/rotate/drop) model to the two-field single-descent model.
- **`input/GestureRecognizer.kt`** — `onPointerUp` no longer emits `drop`; a
  pointer-up ending a drag now emits **nothing**, a tap still latches `rotate`.
  Deleted the always-emit-`drop` line. Rewrote the false "phase-agnostic /
  release = drop / core disambiguates by phase" class KDoc to ADR 0017's
  one-meaning-per-gesture model. **Unchanged (verified):** tap→rotate, the
  `ROTATE_DEBOUNCE_NANOS` debounce (only a tap arms it), first-pointer-only
  multi-touch, touch-slop reuse and the slop-not-spent behaviour, the 1:1
  `worldPerDp` mapping, and the timestamped historical-sample feeding.
- **Deleted** `PositioningUrgency.kt`, `gl/UrgencyBar.kt`, and their test
  `PositioningUrgencyTest.kt`.
- **`GameRenderer.kt`** — removed the `UrgencyBar` import/field/`create()` and
  the positioning-countdown draw block (it read the removed
  `positioningTicksRemaining`/`positioningWindowTicks`).
- **`Tunables.kt`** — removed `POSITIONING_BAR_THICKNESS_WORLD` and its section.
  Kept `ROTATE_DEBOUNCE_NANOS`, `TOUCH_SLOP_DP`, `DRAG_SENSITIVITY` (updated the
  last two's now-stale "while positioning" prose to the whole-descent model).
- **`SquishToy.kt`** (test harness) — dropped the `|| input.drop` term in
  `updateRelease`. Its `slamActivePiece` passthrough is untouched; I confirmed
  `Simulation.slamActivePiece` still exists in your core (`Simulation.kt:734`).
- **Stale comments** in `gl/Background.kt` (dangling `[UrgencyBar]` KDoc link)
  and `GameView.kt` ("hard-drop velocity test" → drag resolution) corrected.

## What I did NOT do

- **Nothing under `core-sim/`.** I built against your `InputFrame`
  (`{ dragX, rotate }`, `clear()`) exactly as landed. No PR — you own the
  combined PR and will merge this branch into `feat/controls-descent`.

## For QA

- The recognizer test rewrite is the recogniser's **own** coverage, not the
  adversarial pass. I repurposed the old "drop only on release" case into
  **`a drag release emits nothing`** — the single line that pins the client's
  "no phase where control is taken away" (drains the steering the move
  produced, then asserts the release adds no `dragX` and no `rotate`). Please
  give the tap/drag boundary and the debounce a deeper adversarial look.
- **On-device feel-check owed** (I have no device): the new mapping is
  drag-anywhere-to-steer + tap-to-rotate for the *whole* descent. Worth
  confirming a fast steering drag whose vertical component is large still reads
  as pure horizontal steering (vertical is ignored by contract), and that
  releasing after a steer does nothing surprising now that there is no
  release-to-drop. ADR 0017's own "new interaction to feel-check" (shoving a
  full-weight piece into the stack for the whole descent) is a core-side
  behaviour but the gesture that drives it is this recognizer's `dragX`.

## Considered and rejected

- Leaving `Background.kt`/`GameView.kt` comments alone: rejected — the
  `[UrgencyBar]` KDoc link would dangle to a deleted class, and the GameView
  comment claimed the historical-sample feed exists for a hard-drop velocity
  test that no longer exists. Both are one-line accuracy fixes, no behaviour.
- A second commit for the handoff vs. folding it into the feature commit: kept
  the code as one logical change (as dispatched); this doc lands separately.

## Uneasy about / open questions

- None blocking. The `worldPerDp` 1:1 mapping and slop behaviour are unchanged
  from ADR 0016, so the *steering* feel is identical to what shipped in round 2;
  only the *release* semantics changed (release now commits nothing).
