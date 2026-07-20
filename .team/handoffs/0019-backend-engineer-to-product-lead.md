# 0019 — Backend Engineer to Product Lead

Branch `feat/mechanic`. Commits `60dd83d..d2af399` (this session). Pushed.

**`./gradlew check` is fully green** — 49 `:core-sim` tests, 114 `:app` tests,
the ADR 0008 no-Android check, the manifest checks and lint. The one thing worth
your attention is a geometry decision I had to make to get there; it is in its
own section below and it has an open architecture question in it for the
Architect.

## What is finished — Stage 3A mechanic

All of `docs/build-order.md` Stage 3A, in `:core-sim`:

- **Pieces spawn one at a time and settle.** `Simulation.start()` deals the
  first; each subsequent piece is dealt only after the previous one locks, in
  the same tick (the game never idles with no piece). A shuffled-bag sequence
  (`PieceSequence`, seeded xorshift64*, no allocation).
- **Lock detection via kinetic energy**, with the settled-pile drift handled
  deliberately, not tuned around. `hasSettled` requires contact + per-particle
  energy below `lockKineticEnergy` for `lockDebounceTicks`, **and a
  `lockTimeoutTicks` ceiling** because a settled pile in this solver never fully
  stops (the 142→149 creep). Tested at its extreme: with an impossible energy
  threshold the piece still locks by the ceiling, and with the ceiling removed
  it never locks — so the timeout, not the energy test, is what bounds the wait.
- **Coverage bands** (`CoverageBands`, ADR 0004), measured every tick as a
  property of the world (like `kineticEnergy`), damped for the eye.
- **The clear rule**, threshold **tunable at runtime** via `MechanicTuning`.
  A band clears when its damped fill ≥ threshold AND a body's centroid sits in
  it. The old stack-energy quiescence gate is gone — measured, it made clears
  unreachable (a real game never cleared in 6000 ticks); the damping is now the
  quiescence gate. Contract corrected in ADR 0004 + `docs/contracts.md`.
- **Stack drop and re-settle**, tick-counted (ADR 0013), never wall-clock.
  Ignition/hold/dissolve envelope = `clearEnvelopeTicks` (24), material removed
  at the envelope end, play held for `clearMinTicks`..`clearMaxTicks` (48..84 =
  800..1400 ms, `feel-feedback.md`). `removeBody` swap-removes high-index-first;
  bands snap so the cleared zone does not glow over empty space.
- **Spawn band is a coverage band** (Stage-4 precondition): `spawnBandIndex`
  now publishes the real band (18 by default) — see the bug I fixed below.

### Real bug found and fixed

`spawnBandIndex` was published as **0 (the floor band)**, not the spawn band.
`State` is constructed before the outer `spawnCenterY` property, so it read a
still-zero value (Kotlin initialises in declaration order). ADR 0005's overflow
test keys off the spawn band, so this would have watched the wrong end of the
well. Fixed by declaring `spawnCenterY` before `stateImpl` (commit `6afda04`).

### Tests, determinism, allocation, measured cost

- 8 new mechanic behaviour tests (`MechanicTest`), replacing the scratch probes.
- Running-game **determinism**: bit-identical across runs incl. a clear
  (removeBody), split-loop invariance, seed-varied piece order. Note: at Stage 3
  an archetype is a colour index only, so two seeds give physically identical
  piles — determinism of the *sequence* is asserted on `bodyArchetype`.
- **Zero allocation**: `AllocationTest` proves the spawn/settle/lock steady
  state allocates **0 bytes/frame** (8-byte tolerance). The only allocation is
  per *clear event* (one `IntArray` + one `Phase.Clearing`, a handful/minute,
  by design — `SimState.Phase.Clearing`).
- **Measured cost**: coverage bands 0.05% of a 16.67 ms frame (ADR 0004); lock
  detection is an O(particlesPerBody) scan of the active piece per tick; the
  clear path runs a few times a minute. No per-frame allocation.

## For the Frontend Engineer / QA (band contract)

- `SimState.bandFill` is populated **every tick, unconditionally** (not gated on
  the game running), so the gel shader reads live fill from `SquishToy` without
  `start()`. 0..1, damped (rise 0.25, fall 0.5), snaps to raw on the removal tick.
- **Correction to frontend handoff 0018 trap #1**: the clear rule now reads the
  **same damped `bandFill`**, not a private `fillRaw`. Recorded in ADR 0004
  Amendment 1 and `docs/contracts.md` (commit `ec50ac7`). Guidance unchanged:
  fill crossing threshold is *not* a clear — drive clear visuals off
  `bandClearProgress` leaving -1.
- `bandClearProgress`, `bandBottomY`, `bandHeight` (→ `uBandInvHeight = 1/h`,
  untouched), `spawnBandIndex` all published and stable.
- I updated `SquishToyTest > no game rules have appeared`: it asserted
  `bandFill == 0`, which my always-on measurement made false. It now asserts no
  *rule* fired (`bandClearProgress` stays -1; score/level/phase/landing inert).
  I tried to reach the Frontend Engineer directly first; the agent was not
  reachable, so this is flagged here for their review + the code reviewer's.

## Geometry — I reverted `pieceExtent = 2.40`, and why (one open question)

The WIP I inherited had, alongside the mechanic, a geometry change I made in an
earlier session that **changed the client-approved feel**. I reverted it. This
is the one judgement call in the session, so here is all of it, measured:

- The WIP I inherited (begin commit `0a4be46`) changed `:core-sim` geometry from
  the Frontend Engineer's branch's **constant centre-span** derivation
  (`PIECE_WIDTH = 1.8`, spacing `PIECE_WIDTH/(lattice-1)`) to **constant
  extent** (`pieceExtent = 2.40`, spacing `pieceExtent/lattice`). At lattice 5
  (shipping default) this grew the piece 2.25 → 2.40 and its radius 0.225 → 0.24,
  and raised `SquishToy`'s spawn drop-height 1.8 → 2.40.
- **Consequence 1 (the red test):** a settled center-spawn tower's bottom now
  compresses to 0.834 → 55% darkening (capped) vs the frontend's <30% bar.
  Passes at 2.25. This is real physics (deeper tower = more bottom load), not a
  solver bug — gently-seeded multi-column piles compress only to ~0.978.
- **Consequence 2 (a latent contract mismatch):** the two derivations agree only
  at lattice 5. The frontend renderer was built for constant-centre-span; at
  lattice 4/6 the constant-extent geometry draws material below the floor / with
  a gap (`RenderFootprintTest` fails if I set 2.25 without also reverting the
  renderer). It is masked today at 2.40/lattice-5.
**What I did (commit `d2af399`):** reverted `:core-sim` to the constant
centre-span derivation (`PIECE_WIDTH = 1.8`, extent derived per lattice). This
restores the approved lattice-5 feel, realigns with the frontend renderer +
their branch (no frontend-file change needed — it was already built for this),
and makes the whole suite green. `pieceExtent` is now a derived getter; nothing
constructs a `SimConfig` with it, so this was a safe signature change.

**Why I decided rather than escalated:** it was my own unratified WIP change
(only a code comment, not a ratified ADR — ADR 0011 is about the silhouette, not
this), it violated an explicit constraint I was given (do not change the
approved feel), and reverting is the *only* geometry that is green, because the
frontend renderer draws correctly at lattices 4/6 solely under constant
centre-span. So it is a WIP overreach backed out, not a live decision reversed.

**OPEN QUESTION for the Architect (not blocking):** constant-*extent* had a real
merit — piece packing identical across quality tiers, so a slower device's tier
does not fit a different number of pieces per row. Constant centre-span (what I
restored) reintroduces a ~11% extent spread across tiers 4/5/6. If we want the
tier-invariant packing, it needs the frontend renderer updated to match *and* a
re-confirm of the lattice-5 feel — an ADR, deliberately, not a code comment.
Flagged so the trade-off is not silently lost.

## What I deliberately did not do

- Retuning the frontend's darkening cue — their domain; the revert removed the
  need, and I only handed across the measured compression fact besides.
- Anything in Stage 4 (losing condition, scoring, difficulty). The spawn band is
  a coverage band, so the overflow test remains buildable.

## Unease

- `SquishToyTest` and the geometry revert touch the Frontend Engineer's
  surface. I tried to reach them directly first and could not (agent not
  reachable), so both are flagged here for their review and the Code Reviewer's.
  Neither needed a change to their *code* — the toy test now asserts the right
  invariant, and the renderer was already built for the geometry I restored.
- The open architecture question above is the one thing I could not close and
  that only the Architect (with a client feel re-confirm) can.
