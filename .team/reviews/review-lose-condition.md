# Review: feat/lose-condition (PR #17)

Verdict: approve-with-comments
Range: `origin/main` `601dcd0` .. `origin/feat/lose-condition`

Makes `Simulation` actually produce `Phase.Overflow` and `Phase.GameOver` (ADR
0005): a due piece whose spawn band is over the line opens a tick-counted settle
grace instead of spawning; settling back below the line and going quiet resumes
with no penalty; a grace that expires still-blocked is terminal `GameOver`. The
state machine is correct and I verified every fairness property the ADR rests on.
The code is clear to land as the core state machine. Two comments: the consumer
sign-off on the `Phase` contract is not yet recorded (requested), and three
test-adequacy gaps worth closing — none block the merge.

## Blocking

None.

## Verified — the code is correct

- **No death by transient — holds, and by construction.** Overflow is only
  triggered from `spawnNext` (a piece is *due*), and `canSpawn` reads the **damped**
  `bands.fill[spawnBandIndex]` — the same value the clear rule (`beginClear`) and
  the renderer read. At spawn-time the previous piece has already locked (which
  requires it went quiet for `lockDebounceTicks`), so the fill reflects a settled
  stack, not a landing peak. A hard landing's bulge happens while a piece is still
  *active*, where `advanceMechanic` never evaluates overflow. And even if a
  transient did trip the trigger, the resume predicate (`canSpawn() &&
  kineticEnergy <= quietKineticEnergy`) goes true within ~`lockDebounceTicks`,
  far inside the 90-tick grace — so a transient cannot reach game-over. Double
  protected.
- **Tick-counted grace (ADR 0013).** `overflowTicks` is decremented once per
  `advanceOverflow`, i.e. once per `sim.step`. There is no wall-clock anywhere in
  the path. The grace is a fixed count of simulation ticks, so it survives dropped
  frames and backgrounding (dropped ticks simply do not execute, and do not count).
- **Clear takes precedence over overflow — structurally guaranteed.**
  `advanceMechanic` checks `clearTicks >= 0` before `overflowTicks >= 0`, and a
  clear is begun only in `lockActivePiece` → `beginClear`, reachable only when
  there is a settled *active* piece. During overflow there is no active piece, so
  no clear can begin; and a clear in progress holds the spawn, so overflow cannot
  begin until the clear finishes and the stack has dropped. The two states are
  mutually exclusive. A band that would clear on the tick it would overflow: the
  clear preempts the spawn decision, drops the stack, and the subsequent
  `canSpawn` sees the dropped stack — the game does not end.
- **Determinism to game-over.** The lose path reads only deterministic state
  (`bands.fill`, `kineticEnergy`, integer counters); `LoseConditionTest` asserts a
  bit-identical fingerprint at game-over across two seeded runs.
- **Zero per-frame allocation.** One reused `overflowPhase`, `remainingTicks`
  mutated in place; `overflowTicks` and `gameOver` are primitives; the `phase`
  getter returns the reused instance / the `GameOver` object. No allocation on the
  grace path.
- **Runtime-tunable dials.** `overflowThreshold` and `graceTicks` join
  `MechanicTuning`, seeded from `SimConfig`, with `require(graceTicks >= 1)`. Both
  are exercised as live dials by the tests.
- **Cold merge green.** Merged onto current `main` (`601dcd0`) with no conflicts;
  `:core-sim:test` BUILD SUCCESSFUL, `LoseConditionTest` = 7 tests, 0 failures. PR
  CI `build-and-test` pass.

## Should fix (non-blocking)

- **Record the consumer sign-off on the `Phase` contract.** Handoff 0027 says
  "the shape I agreed with the Frontend Engineer," but that is the producer's
  claim — there is no consumer-side record (no PR comment; handoff 0022 is an
  unrelated earlier finding). This contract has a real trap: `Phase.Overflow` is
  now `data class Overflow(var remainingTicks: Int)`, one reused instance mutated
  in place — a consumer that retains the reference instead of reading it gets a
  value that changes underneath it. That is exactly the kind of detail the
  consumer must confirm, as band-contract and FrameDriver both did. I have asked
  the Frontend Engineer to confirm and record it. #17 can land first (it is the
  core state machine, and the `Phase` types pre-existed), but the sign-off should
  be recorded before the Frontend wires the game-over UI against it.
- **Add a clear-precedence test.** The precedence is correct but untested: no test
  sets up a clear coinciding with an overflow, so a future reordering of
  `advanceMechanic` (overflow before clear) would pass CI. A test that locks
  "a clear on the tick overflow would trigger does not end the game" would guard
  the ADR 0005 property directly.
- **The "no death by transient" and tick-count tests are weaker than they read.**
  The transient test drives overflow through the `overflowThreshold` dial rather
  than a literal physics bulge (the Backend flagged this himself in 0027 and
  welcomed a QA hard-drop fixture — worth taking him up on). And the tick-count
  test's "split" is a literal no-op (`if (t == splitAt) { /* pretend */ }`), so it
  proves determinism, not frame-grouping independence — which in fairness lives at
  the `FrameDriver` seam (reviewed in #16), since `Simulation` has no frame concept.
  The properties hold; the tests just do not add the evidence their comments claim.

## What is good

- The fairness mechanism is the right one and is used consistently: both the
  overflow trigger and the clear rule key off the *same* damped fill, so "no death
  by transient" and "no clear on a bounce spike" are the same guarantee from the
  same signal rather than two hand-tuned thresholds. The `beginClear` KDoc's
  post-mortem on the removed stack-wide quiet gate (unreachable clear, 0.436 vs
  0.05 while a band sat at 0.994) is exactly the kind of measured reasoning that
  makes this auditable. Terminal `GameOver`, app-side restart, and the reused-var
  pattern matching `Clearing` are all the simplest correct choices.

---
*— **Code Reviewer***
