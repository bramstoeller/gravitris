# Code review — `:core-sim` contract surface (PR #2, `feat/core-sim`)

Reviewer: Frontend Engineer
Date: 2026-07-20
Scope: `SimState`, `SimConfig`, `InputFrame` as the surface `:app` builds
against. Explicitly NOT the XPBD solver, substep count, constraint maths,
`UniformGrid` or the broadphase — declined as outside my competence.
Requested by: Backend Engineer, directly (new working agreement, 2026-07-20).
Posted to: https://github.com/bramstoeller/gravitris/pull/2#issuecomment-5023150877

Note: all PRs are authored under one GitHub account, so a formal
"request changes" review state is rejected by the API. This review is a PR
comment; the verdict below is the binding version.

---

**Review: Frontend Engineer.** You asked me for the consumer surface, not the XPBD — agreed, that's the split where I'm useful and the solver maths is where I'd be guessing. I reviewed `SimState`, `SimConfig`, `InputFrame` as things I have to build against, and took your note that the constants here are superseded: I've judged shape, not values.

Method, since it's the only thing I trust about myself now: I did not look for what reads wrong. This contract reads *very* well — the documentation is better than most shipping code and the rejected-alternative notes (mutable `InputFrame`, `Clearing.equals`, defensive copies) are exactly what makes it reviewable. Reading it more carefully would not have found anything, and I know that because **I already read this contract, believed it, and then shipped four defects out of it.** So I went looking for what the contract *doesn't say* and then checked each gap against what later had to be added to close it.

All four findings below are the same defect, four times: **the contract tells the consumer what not to do, without giving them the number they need to do the right thing.** Each one was closed later by a commit that exists because something broke.

---

### 1. `SimState` forbids `array.size` and then provides no alternative (this one cost a crash-adjacent fix)

The class doc says, correctly and in bold:

> They are longer than that — capacity is allocated once at construction so the per-frame path allocates nothing — so **never** use `array.size` as a loop bound.

Right rule. But the renderer doesn't only need the *count*, it needs the *capacity* — vertex and index buffers are sized once at startup against the maximum, not per frame. This contract publishes `particleCount` and no capacity at all. Body capacity is derived inside the core from well area; `SimConfig` has no capacity field.

So the consumer's options were: reproduce the core's derivation in `:app`, or use `positionX.size` — the one thing the doc forbids. I added `SimState.particleCapacity` in `118bcfe` precisely because neither was acceptable, and I hit it as `80f010f fix(app): fail legibly if the body cap outgrows the vertex buffers` — i.e. I found it by overflowing a buffer, not by reading this file.

**Ask:** fold `particleCapacity` into this PR's contract rather than leaving it as a later addition. It's additive per `docs/contracts.md` §5.

### 2. `bandFill` / `bandClearProgress` have no count field — same rule, same gap

`Simulation` builds both as `FloatArray(config.bandCount)`. But `SimState` exposes `particleCount` and `bodyCount` and **no `bandCount`**. So to iterate bands the consumer must either use `bandFill.size` — forbidden by the class doc — or reach into `SimConfig` and couple the renderer to a config field for something the state should tell it.

Right now it's harmless because these are all-zero in Stage 1. It stops being harmless the moment I wire `uBandFill` for real, which is this week.

**Ask:** add `bandCount: Int` to `SimState`. One line, and it makes the "never use `array.size`" rule actually followable for every array in the interface instead of most of them.

### 3. Units: the contract never says where the material surface is — this is the contact gap

You asked directly about units, so: well units are clear, `PIECE_WIDTH` is clear and well-justified as fixed rather than derived. **Particle radius is entirely absent.** Every position in this contract is a particle *centre*, and the contract never says so or says how far material extends past it.

I built the mesh from those positions, which insets the silhouette by one radius on every side. At lattice 5 that is 0.45 well units of false gap — a quarter of a piece width. That is the "margin around the blocks" the client reported on their Fairphone. It read as a physics separation and it was a rendering inset. Closed by your `3e53cc9` and my `688a6d5`.

Note what happened here, because it's the strongest argument for fixing the pattern and not just the instance: **the solver was correct, the renderer was correct given what it was told, and the client saw a bug.** Neither side could have caught it alone — it lived exactly in the gap in the contract.

**Ask:** `particleRadius` belongs in this PR. Your later doc comment on it is the best thing in the whole contract; it should not be a patch.

### 4. `triangleIndices` documents the value range in a way that reads as the length

```
Body-local triangle indices, `0 until bodyLattice * bodyLattice`.
```

`bodyLattice * bodyLattice` is the number of *particles*, so that's the range of the index *values*. The array's length is `6 * (lattice - 1)^2` — 96 for lattice 5, against a `bodyLattice * bodyLattice` of 25. A consumer who reads that line as a length is wrong by ~4×, and it's phrased the way lengths are usually phrased.

Also unstated on the consumer side, though both are explicit in `SoftBodyWorld.buildTriangleIndices`: the winding is CCW, and the cell diagonal is `p00–p11`. I built my own index buffer and split the cells on the **other** diagonal; fixed in `2fadd52 fix(app): split render cells on the solver's own diagonal`. On a deforming mesh the diagonal choice is visible — the cell creases the wrong way under shear.

This is literally your question "is anything shaped so that the obvious consumer usage is the wrong one": yes, this. **Ask:** state the length as a formula, the winding, and the diagonal, in the `SimState` doc where the consumer reads it rather than in the physics class where they don't.

---

### Smaller, non-blocking

- `SimConfig.gravity = -30f` has no `require`. Every other field is validated. A positive gravity is silently accepted and would be baffling to debug. One line.
- `Phase.Clearing(bands, remainingTicks)` and `bandClearProgress` encode overlapping state. Two sources of truth for "which bands are clearing" will drift. Worth saying in the doc which is authoritative for rendering — I'd like it to be `bandClearProgress`, since that's the one with an envelope I can drive a shader from.
- `initialPieceMass` per-particle-vs-per-piece: you already flagged it and named the ambiguity honestly. I'd just rename it `initialParticleMass` and delete the paragraph. The comment is doing work the name should do.

### What I did not review

XPBD correctness, substep count, compliance values, integrator stability, the broadphase, `UniformGrid`. Not my competence, and a confident-sounding opinion from me there is worse for you than this sentence.

### Verdict

**Approve the shape, with items 1–4 requested before merge.** The module boundary is in the right place and the interface is the right *kind* of interface — SoA, allocation-free, read-only-by-convention-and-honest-about-it. Every one of my four findings is additive; none of them changes a decision, they close gaps that already cost us three fixes and one client-visible defect.

One meta point worth putting in the record: I could not have found any of these by reading harder in the way I read the first time. I found them by asking "what did we have to add later, and why" — and every answer landed in this file. If there's a fifth gap of the same shape, it's in a Stage 3/4 field that has never executed: my bet is on `LandingEstimate` (permanently `valid = false` today, so no consumer has ever exercised the valid path) and on `ImpactList.strength` "scaled by mass and impact speed, 0..1" — which does not say whether it's clamped, and I already shipped a non-finite-energy defect underneath that exact number (`18e7131`).
