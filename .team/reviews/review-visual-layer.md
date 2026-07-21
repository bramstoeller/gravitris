# Review: feat/visual-layer (PR #25) — the modern visual layer

Verdict: approve-with-comments
Range: `origin/main` .. `origin/feat/visual-layer` `e33b295`

Procedural background, band-clear juice (luminance beat + ember burst), a real
Android-View HUD, and a designed game-over screen. I reviewed for code
correctness and the **frame budget** — the risk the coordinator flagged, on a
device already at the edge (lattice-4 pin). The design is cheap by construction
and every new pass is bounded and correctly gated. One real budget item to fix or
measure before the client build — loud, below. Not a blocker on correctness.

## Blocking

None.

## The one budget concern — fix or measure before shipping to the Fairphone

- **The background fragment computes four `sin`/`cos` per pixel for a
  frame-constant value** (`Background.kt:205-206`). `driftA`/`driftB` depend only
  on `uTime` (a uniform), so they are identical for every pixel in a frame, yet
  they are evaluated in `main()` — i.e. potentially per-pixel across a full-screen
  pass (~2.6M pixels on the client's panel). A competent GLSL compiler hoists
  uniform-invariant expressions and this collapses to ~0; a driver that does not
  leaves ~4 transcendentals/pixel on the *one* sustained O(screen-pixels) pass the
  visual layer adds — precisely where "looks great, runs at 30" would hide. The
  Frontend's own comment marks the drift "first to cut if the budget disagrees,"
  so they know it is the marginal cost.
  **Fix (≈5 lines, removes all doubt):** compute `driftA`/`driftB` on the CPU once
  per frame (four `sin`/`cos` total) and pass them as two `vec2` uniforms; the
  fragment then has zero transcendentals. I'd do this rather than gamble on driver
  hoisting — but if it ships as-is, it **must** be read off the on-device
  frame-time readout on the real device before the client sees it (the readout was
  kept for exactly this). Not blocking the code review; blocking-worthy only if the
  on-device number misses budget.

## Verified — everything else is cheap and correct

- **No other sustained new cost, and nothing per-body or unbounded.**
  - `ClearFlash`: a full-screen quad but the fragment is a *single multiply*
    (`HOT * (PEAK*uIntensity)`), additive blend scoped to its own draw and
    restored, and a hard `if (intensity <= 0f) return` — a no-op except during the
    120 ms beat. Transient, occasional.
  - `EmberBurst`: a fixed `POOL = 256` SoA pool allocated once (ring buffer;
    overflow overwrites the oldest — graceful degradation, not a leak, not
    unbounded), analytic trajectories (no physics/solver), a tiny falloff fragment
    over small quads, additive blend scoped and restored, and it draws nothing when
    no ember is live. O(embers), bounded, not per-frame-sustained.
  - `Background`: one full-screen quad, opaque, blend off, its own flat program
    (not pushed through the gel shader), radial falloff on *squared* distance so
    there is **no per-pixel `sqrt`**. Cheap ALU aside from the drift trig above.
- **No silent cuts of anything the spec flagged.** The drift is kept and flagged
  (not cut), "no bloom / no post-process" is honored (`ClearFlash` is a plain
  additive triangle, explicitly not a blur/HDR pass), the shade dial and
  frame-time instrumentation are retained for on-device pricing, and the dither is
  `highp` where `gl_FragCoord` precision demands it. Transparent about cost.
- **The HUD is in the Android View layer — zero GPU.** `GameHud` is a
  `FrameLayout` of `TextView`s plus a `Canvas`-drawn `PauseButton` view, added to
  the platform view tree in `MainActivity` and updated via `readout.view.post{}`
  on the UI thread. Nothing HUD is drawn by the GLES renderer. (Verified in
  detail.)
- **The debug frame-time readout cannot leak to the client.** `readoutVisible =
  false` and `readout.view.visibility = View.GONE` at startup; the only reveal
  path (`revealReadoutOnDebugBuild`, via a volume-key affordance) early-returns
  unless `applicationInfo.flags and FLAG_DEBUGGABLE != 0`, so a release build never
  surfaces it, and it is per-session (not persisted).
- **Game-over / restart path — correct, and improved.** The juice and the
  game-over both key off the *same* `Phase.Clearing` / `Phase.GameOver` reads the
  instrumentation uses, so the beat and burst fire only on a real clear onset
  (`clearing && !wasClearing`), reading `phase.bands` without retaining the reused
  `Clearing` instance. Game-over is still latched once (`wasGameOver`), still
  raised on the UI thread (`runOnUiThread`), and restart is still queued back to
  the GL thread (`gameView.queueEvent { renderer.restart() }`) — no View touched
  off the UI thread, `restart()` never called off the GL thread. The screen is now
  a designed `GameOverView` that consumes touches (no leak to the game beneath;
  only "Play Again" is actionable), and `onGameOver` now carries `st.score`
  captured on the GL thread rather than racing a UI-thread session read — a genuine
  correctness improvement over the #19 overlay.
- **Score/level are honest.** `score()`/`level()` read the real `SimState` fields
  (0 / 1 until scoring D8 lands); the HUD presents the frame, it does not fake it.

## Notes (non-blocking)

- CI has not reported on this branch yet ("no checks reported"). Verdict is code
  correctness; the branch still needs a green `build-and-test` before landing (new
  `ClearFlashEnvelopeTest` / `EmberTrajectoryTest` back the analytic halves, which
  is the right way to test GPU-adjacent timing without a GPU). I could not build
  `:app` locally (no Android SDK); CI + the on-device readout are the oracles.

## What is good

- The juice follows its own "cheap by construction" discipline honestly: the two
  effects that could have been expensive (bloom, particle physics) are
  deliberately a single-multiply additive wash and analytic non-physical embers,
  and the split of every effect into a pure, unit-tested trajectory/envelope plus
  a thin GPU shim is exactly right. Keeping the frame-time instrumentation instead
  of hiding it, and marking the drift as first-to-cut, is the transparency a
  budget-sensitive feature needs.

---
*— **Code Reviewer***
