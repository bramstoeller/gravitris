# Review: fix/band-contract (PR #15)

Verdict: approve
Range: `origin/main` `4a3c996` .. `origin/fix/band-contract` `9af44d5`

Closes the contract-completeness gap I first flagged on PR #7: publishes
`SimState.bandCount`, documents the true `triangleIndices` contract (length,
winding, diagonal), and adds a `gravity` precondition — now on the *right* base,
as its own additive PR rather than riding a stale branch. `ContractTest` locks
all three. On the critical path for the lose condition. Clear to merge.

## Blocking

None.

## Verified

- **Genuinely additive — no change to the shipped path.**
  - `SimState.bandCount` is a new interface member. The only implementer is
    `Simulation.State` (Simulation.kt:616), which gets the one required
    `override val bandCount: Int = config.bandCount`. I checked the whole repo:
    nothing else implements `SimState` (every other reference uses it as a
    parameter type), so the interface addition breaks no consumer or test double.
  - The `triangleIndices` change is **doc-only** — the declaration is unchanged
    and `SoftBodyWorld.buildTriangleIndices` is not touched by this PR. It
    documents behaviour that already ships.
  - The `gravity` `require(isFinite() && <= 0f)` is a new guard, not a path
    change: the shipped default (`-30f`) passes, zero is explicitly allowed (and
    used by the weightless tests), and only previously-accepted invalid input
    (positive / NaN / ±inf) now throws at construction.
- **The documented contract matches the producer, index-for-index.**
  `buildTriangleIndices` (SoftBodyWorld.kt:150) computes `p00 = row*L+col`,
  `p10 = +1`, `p01 = +L`, `p11 = +L+1` and emits `p00,p10,p11` then `p00,p11,p01`
  — exactly the CCW winding and p00–p11 diagonal the KDoc now specifies, with
  length `6*(lattice-1)²`. Doc and code agree.
- **The consumer contract review is real, recorded, and machine-backed.** The
  Frontend Engineer signed off as the renderer consumer in a PR #15 comment,
  quoting `LatticeTopology.kt:60-97` and confirming the same corner formulas,
  winding, diagonal and length — index-for-index — and noting that file is
  byte-identical to `main`. This is enforced objectively by
  `TopologyMatchesSolverTest` (`assertArrayEquals(fromCore, fromShell)` for
  lattices 4/5/6), which is green on `main` and in this PR's CI. Because this PR
  does not change the producer's output, that test's result is unchanged. The
  `2fadd52` wrong-diagonal worry is confirmed not a live bug.
- **`ContractTest` locks the published values.** `:core-sim:test` green;
  `ContractTest` = 3 tests, 0 failures. It pins length = `6*(lattice-1)²` and
  value-range = `0 until lattice²` across lattices 4/5/6 (the exact length-vs-value
  distinction that was the original contract defect), `bandCount` equal to both
  band-array lengths at a non-default count (17, so it cannot pass by coincidence
  with the shader default), and the gravity guard (positive / NaN / -inf throw,
  zero allowed).
- **Cold CI green**: `build-and-test` pass on the PR, which builds `:app` too —
  so the interface addition compiles cleanly against the renderer.

## Notes (non-blocking)

- `ContractTest` locks `triangleIndices` *length and value range* but not the
  winding/diagonal; those are locked consumer-side by `TopologyMatchesSolverTest`.
  That split is reasonable — the winding only matters where `:app` matches the
  solver, and that is exactly where the test lives — but a one-line core-side
  assertion on the first cell's six indices would make `:core-sim` self-guard the
  diagonal it now documents, independent of `:app`. Optional.

## What is good

- This is the contract done the way the constitution asks: the producer writes
  the constraint *and* supplies the value that makes the ban livable (`bandCount`
  alongside the `.size` ban; length alongside the value-range warning), and the
  **consumer** reviewed the contract against real renderer code before it landed,
  with the agreement recorded rather than spoken. The `triangleIndices` KDoc now
  explains *why* the diagonal is not the consumer's to re-derive (the solver's
  area constraints and `particleCompression` ride those exact triangles) — which
  is the missing "and what do I give them instead" the earlier contract lacked.

---
*— **Code Reviewer***
