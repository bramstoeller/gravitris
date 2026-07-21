# Review: feat/wire-real-game (PR #19)

Verdict: approve-with-comments
Range: `origin/main` `7d81ae0` .. `origin/feat/wire-real-game` `f4766f3`

The integration that turns the Milestone-1 toy into the actual game: a real
`GameSession` that calls `Simulation.start()` and drives time through
`FrameDriver`'s `drainTick` overload, the toy staging deleted, a minimal
game-over/restart overlay reading `Phase.GameOver`, the palette 7→6 archetype
fix, a worst-case mesh capacity assertion, and clear/spawn instrumentation. This
consumes the two contracts already signed off — the FrameDriver `drainTick` seam
(#16) and the `Phase` shape (#17). I reviewed each seam against the real code.
Clear to land as the final integration; comments are non-blocking.

## Blocking

None.

## Verified — the integration is correct

- **The real mechanic runs; the toy is gone.** `GameSession.init` calls
  `simulation.start()` — the one line that was missing, that makes `Simulation`
  deal pieces and run lock/clear/re-settle. `SquishToy.kt` is deleted (only a
  doc reference remains in `GameSession`'s KDoc); no `SquishToy.addNextPiece` /
  `reset` path is live. `start()` has exactly one call site, so it fires once per
  session build and nowhere else.
- **FrameDriver `drainTick` seam — used exactly as contracted (#16).**
  `GameSession.advance` delegates straight to `driver.advance(delta, drainTick)`;
  `GameRenderer` holds the stable field lambda `drainTick = { intent.drainInto(it) }`
  (no per-frame closure allocation); `advanceSimulation` passes the **real**
  delta, never clamped (the old `min(frameDelta, MAX_FRAME_DELTA)` shell clamp is
  deleted with the toy, and `Tunables.TICK_NANOS`/`MAX_CATCH_UP_TICKS` gone with
  it). `PlayerIntent.drainInto` is `synchronized(lock)` and moves all pending
  intent into the frame then resets its accumulator — so gestures written on the
  input thread reach exactly one tick when drained on the GL thread (1:1, no N×
  under catch-up). The cross-thread bridge is correct.
- **`Phase.GameOver` handling does not strand the player, and is thread-correct.**
  `onDrawFrame` (GL thread) reads `state.phase` fresh every frame, never retains
  it (honours the reused-`Overflow` read-don't-retain contract), and raises
  game-over once via a `wasGameOver` latch. `onGameOver` hops to the UI thread
  (`runOnUiThread { showGameOver() }`) to touch the View. Restart is
  `gameView.queueEvent { renderer.restart() }` → `buildSession(sessionConfig)` on
  the **GL thread**, reconstructing `Simulation` + `FrameDriver` and clearing the
  latch. `Overflow` renders as normal play while its grace counts down — matching
  ADR 0005 and the consumer's `when (state.phase)`.
- **Thread-safety is sound.** `buildSession` (the only writer of `session` /
  `sessionConfig`) runs only on the GL thread — from `onDrawFrame` and from the
  `queueEvent`-wrapped `restart`/`setPaused`. Readout accessors
  (`clearCount`/`spawnCount`) are read inside the `onStats` callback, which fires
  on the GL thread before posting to the UI. The launch double-log the author
  flagged is benign: GLSurfaceView recreates its GL thread during startup layout,
  each incarnation builds a session once (resetting `prevActivePiece = -1`) and
  logs its first spawn; the two builds are strictly sequential on non-overlapping
  threads (the old GL thread stops before the new one starts), so there is no
  data race, only a discarded startup session and one duplicate log line.
  Explained to the author.
- **Palette 7→6 fix — no out-of-bounds.** `Palette.pieceHue(a) = floorMod(a,
  PIECE_COUNT=6)` returns `0..5`, so it can never index `SURFACE_INDEX` (6);
  `uploadStatics` now folds every archetype through it instead of the old
  `coerceIn(0, SIZE-1)` that mapped archetype 6 to the well-surface grey. The
  real sequence deals all 7 archetypes and archetype 6 now shares hue 0 — the
  collision is documented and **flagged to UX** as a design decision the shell
  does not make unilaterally, which is the right call.
- **Mesh capacity — fail-safe.** The mesh is sized to `maxBodies =
  max(64, 2·ceil(wellArea/pieceExtent²))` for the worst-case well (fixed width,
  `WELL_HEIGHT_MAX_WORLD`), mirroring `SoftBodyWorld.maxBodies`. Every real well
  is ≤ that, and `buildSession` asserts `state.particleCapacity <= maxBodies ·
  particlesPerBody`, so a formula drift crashes loudly at build-a-well time rather
  than overflowing the vertex buffer mid-draw.
- **`clearThreshold = 0.80` is live-tunable, not hot-path-hardcoded.**
  `Tunables.CLEAR_THRESHOLD = 0.80f` is fed to `SimConfig.clearThreshold`, which
  seeds the live `MechanicTuning.clearThreshold`, read once at session build. The
  core's 0.90 default is untouched, so `:core-sim` tests keep the brief's
  reference. Documented as the Product Lead's 2026-07-21 call from play-through
  data (a couple of squashed bodies fill a low band to ~0.50), provisional and
  runtime-tunable per ADR 0004.
- Debug clear-threshold override is `FLAG_DEBUGGABLE`-gated (release ignores it),
  finite/`0..1` validated, and only changes *when* a band clears, not *how* — a
  sound way to make the playthrough film a real clear on the slow emulator.

## Should fix (non-blocking)

- **No automated test for the new wiring.** `GameSession`, the game-over/restart
  latch, and the spawn/clear transition detection are verified by the manual
  play-through only — the diff adds no test. `GameSession` is pure (no GL:
  `Simulation` + `FrameDriver`), so a JVM test could cheaply lock the properties
  that matter: `start()` is called (phase leaves the never-started state), a
  blocked spawn stalls rather than throws, and `advance` delegates the delta
  unclamped. Worth adding so a future refactor of the driver seam does not
  silently regress under only-manual coverage.
- **The 7→6 palette collision is a real design debt**, correctly flagged to UX
  but not yet resolved: archetype 6 shares hue 0, so two shapes are colour-
  identical. Fine as a placeholder for this build; make sure the UX decision (a
  7th hue, six archetypes, or a designed map) is tracked, not lost.

## What is good

- Every cross-thread hop is deliberate and correct: sim/session mutation on the
  GL thread via `queueEvent`, View work on the UI thread via `runOnUiThread`,
  input mediated by a `synchronized` `PlayerIntent`. The frame-timing policy was
  moved wholesale into `:core-sim`'s `FrameDriver` and the shell's delta-clamp
  deleted, closing exactly the ADR-0013 dilation the earlier toy shell carried.
  The mesh-capacity and band-count `check()`s turn two invisible-from-either-side
  integration risks into loud build-time failures. And the mechanic is *proven*
  to run — the play-through's 6 clears on a real accumulated pile is the evidence
  the Milestone-1 finding (handoff 0022) said was missing.

---
*— **Code Reviewer***
