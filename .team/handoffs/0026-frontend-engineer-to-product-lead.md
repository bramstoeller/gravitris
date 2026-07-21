# 0026 — Frontend Engineer → Product Lead

Branch `feat/wire-real-game`, PR opened (see below). **Do not merge — you land
it after seeing the play-through clear.** `make test` green on current `main`
(merged, includes #16 FrameDriver and #17 lose-condition).

Commits (mine, on top of the merge of `main`):
`7f465f7` wire the mechanic · `d91821e` debug threshold extra · `b889d0e`
dense-capture play-through · `0840ea0` game-over overlay.

## What I did — the app now plays the real game

The shipped app drove `SquishToy`, which never calls `Simulation.start()`, so
pieces fell and bands glowed but nothing ever locked or cleared (handoff 0022).
Replaced with **`GameSession`**: builds `Simulation`, calls `start()` once, steps
it through **`FrameDriver`'s per-tick-drain overload** (ADR 0013 — the old shell
accumulator clamped the delta and dilated wall-clock time; gone with the toy).
The renderer still reads everything off `SimState`, so render/haptics/uniforms
are unchanged bar a per-frame haptics drain gated on `SimState.tick`.

Fixed two things the toy had been hiding — both would have shipped as visible
bugs the moment `start()` ran:

- **Body capacity.** The toy reset at 40 bodies; the real game fills to
  `SoftBodyWorld.maxBodies` (up to 120 for the tallest well). The mesh was sized
  for 40 and would have overflowed its vertex buffer and crashed. Now sized for
  the worst-case well, asserted against the live session's capacity.
- **Palette.** The real piece sequence deals **all 7** archetypes, but there are
  **6** hues and index 6 is the well-surface grey — so ~1 in 7 pieces would have
  rendered nearly invisible against the well. `Palette.pieceHue` folds 7→6.

**Minimal game-over/restart** (added after #17 landed — see sequencing below):
`Phase.GameOver` now fires when the well tops out, so the renderer raises a
one-shot `onGameOver`, `MainActivity` shows a scrim ("Game over — tap to play
again"), and a tap reconstructs the session via `GameRenderer.restart()`. A full
well otherwise stalls honestly during the overflow grace. The polished screen is
Stage 5 (`game-over.md`).

`SquishToy` moved to the **test** source set (four app tests still use it as a
scene builder — not production dead code). Dead pacing constants removed.
**Task 2 (the label)** landed separately in your earlier PR; on-screen it now
reads `live frame time - not a verdict`, confirmed in the play-through frames.

## Proof — the play-through, now instrumented

`make playthrough` boots the correctness AVD (software/swangle, host GL declined
as always here), installs, drives real `adb` input, screenshots densely, **and
dumps a mechanic event log** (`build/emulator/playthrough/mechanic.log`). Static
gel blobs on a software renderer could not tell a working clear from the well
emptying for a bad reason, so `GameRenderer` now counts the unambiguous signals:
a clear is a `Phase.Clearing` entry, a spawn is `activePieceBody` leaving -1.
Both show in the readout (`clr`/`spn`, so every screenshot is self-documenting)
and log with the body count and the fill that triggered each clear.

**Definitive answer — the mechanic works.** One instrumented run logged **12
clears and 13 spawns**, ticks 0→5600. The three things that settle it:

- **Clears fire.** Twelve `Phase.Clearing` entries, each removing material.
- **Bodies drop *only* at clears.** Every drop coincides with a logged clear;
  spawns re-add. Rules out out-of-bounds loss, a lock bug, a reset loop.
- **`maxFill≈0.50` at every clear.** 2-3 bodies already fill a band to ~50%.

**Why the well looked empty — and it is not a bug.** The debug threshold I used
for the first runs (0.35) sits *below* the ~0.50 fill a couple of bodies make,
so a band crosses the line with 2-3 pieces and clears instantly, forever. Raise
the threshold and the well accumulates. Also: **5600 ticks in 94 s ≈ 60 tps —
the sim runs real-time on the emulator**, so there is no accumulation ceiling;
the limiter was purely my too-low threshold. A play-through at 0.70 (below) shows
a real pile fill, glow, clear and drop.

**Shipped difficulty — `clearThreshold = 0.80`** (`Tunables.CLEAR_THRESHOLD`,
Product Lead's call 2026-07-21). Play-through data: a few squashed bodies cover a
low band to ~0.50, so any threshold below that clears constantly; the core's 0.90
default needs a band packed almost solid. 0.80 sits between the proven-good 0.70
(real piles accumulate then clear) and the brief's ~0.90, so the mechanic is
visible on first play. **Provisional and live-tunable** (ADR 0004, dev panel
Stage 4C) — the client tunes it by eye once they feel it. Set app-side via the
`SimConfig` the shell builds, so the core's 0.90 stays as the brief's reference
for tests.

**The 0.70 run** (`CLEAR_THRESHOLD=0.70 make playthrough`) is the realistic demo:
**6 clears**, each at `maxFill` 0.72-0.77 with **3-5 bodies** — the well now
*accumulates* a pile, a band fills past the 0.4 glow dead-zone (so it glows),
ignites, dissolves, and the bodies above drop (`clear #5` fired at 5 bodies,
leaving 3 after). The readout carries `clr`/`spn` so the frames are self-
documenting: e.g. `024-t23.png` shows a 4-body pile beside `4 clr  6 spn`, and
`021-t20.png` catches a piece mid-rotation. Screenshots + `mechanic.log` in
`build/emulator/playthrough/`.

One caveat I will not dress up: the screenshots are sampled every ~2 s and the
ignition-to-dissolve envelope is ~0.4 s, so no single frame happens to catch the
amber flash mid-clear — but `mechanic.log` records every clear reaching
`maxFill≈0.75` and removing bodies, which is the unambiguous evidence you asked
for. If you want the flash *on camera* I can add a sub-second burst around a
clear; say the word.

Game-over: reachable now (#17 on main) but only when the well tops out (~5-6
bodies packing to the shipped 0.90, or an unwinnable pile). Handled — overlay +
restart — and verified by code + the `Phase` sign-off; whether it is captured on
the emulator depends on the run topping out rather than clearing first.

## Coordination (all recorded, not just discussed)

- **`FrameDriver` per-tick drain** — I'm the first live consumer; the plain
  `advance(delta, input)` reused one frame across catch-up ticks, which breaks
  the 1:1 drag mapping and fires one-shots N times under frame drops (rife on the
  emulator). Backend added `advance(delta, drainTick)` (PR #16, on `main`); I
  reviewed the seam as consumer.
- **`Phase` contract** — consumer sign-off posted on PR #17
  (`issuecomment-5028391325`) and confirmed by the Code Reviewer:
  `Overflow(var remainingTicks)` reused/read-don't-retain and app-side restart
  (no `Simulation.restart()`) are correct for how I consume them.
- **Game-over sequencing** — agreed with Backend: their lose-condition lands
  first (done, #17), my minimal UI reads it (this PR), the polished HUD +
  overflow countdown is the deferred follow-up.

## Findings flagged to others (not fixed here)

- **7 archetypes vs 6 hues → a colour collision** (archetype 6 shares hue 0).
  `Palette.pieceHue` makes it render correctly, but the permanent fix (a 7th
  hue, the core dealing 6, or a designed 7→6 map) is a **UX** decision.
- **`SimState.phase` KDoc is stale** (`SimState.kt:244` still says "Overflow and
  game over are Stage 4") — for **Backend** to update; #17 landed them.
- **`docs/install-milestone-1.md` still describes the toy** (volume-up shade
  toggle wording, "artwork missing") — for the **Tech Writer**; the build now
  clears and needs an install doc that says so.

## Considered and rejected

- **Per-frame input into `FrameDriver.advance(delta, input)`** — rejected: multi-
  applies drag and one-shots on catch-up, worst exactly on the emulator.
- **Recreating the mesh's GL buffers on every well change** — rejected: the well
  is bounded, so worst-case sizing once is simpler and avoids GL-lifecycle risk.
- **Deleting `SquishToy`** — rejected: four tests use it; moved to the test set.
- **Deferring game-over entirely** — reversed once #17 landed: `start()` makes
  `GameOver` reachable, so leaving it unhandled would strand the player.

## Open / uneasy

- **The clear threshold is the client's dial** (`MechanicTuning`, default 0.90).
  The play-through uses a low debug override purely so a clear fits a short slow
  session; it changes *when*, never *how*.
- **Game-over is unproven on hardware** here — only the client's phone can fill a
  well and exercise the real overflow→grace→game-over→restart loop end to end.

---
*— **Frontend Engineer***
