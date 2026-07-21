# Review — ADR 0015 (tetromino geometry) & ADR 0016 (lifecycle + rotation)

Reviewer: **Architect** · Date: 2026-07-21
Branch reviewed: `origin/feat/tetromino-pieces` @ `90d7c2a`
Files: `docs/adr/0015-tetromino-pieces-as-bonded-soft-body-cells.md`,
`docs/adr/0016-piece-lifecycle-and-rotation-semantics.md`

Both are expensive-to-reverse and the client feels them directly, so I reviewed
them for soundness and for consistency with ADR 0005 (losing), 0006 (timestep/
determinism) and 0013 (no wall-clock dilation) — and I checked the load-bearing
claims against the code the Backend already pushed rather than trusting the prose.

## Verdict: both **APPROVED**

## ADR 0015 — tetromino as four bonded soft-body cells

Sound. The decision that carries the ADR — one body of `4·L²` particles, shape
in the cells' rest positions, `particlesPerBody` constant — is the right one: it
keeps `removeBody`'s fixed-stride swap-remove and the single reused triangle
buffer intact, which are exactly the parts that would be expensive to get wrong.
The inert-padding trick for the 4th seam (tree shapes pad to O's cycle count) is
a genuine no-op through the solver's *existing* `d < EPS` / `wsum < EPS` guards,
not a new special case — good, and it is backed by a bit-identical padded-vs-
unpadded test.

**Verified, not trusted — "feel preserved by construction" holds at the material
level.** Mass is per-*particle* (`SoftBodyWorld.kt:208-209, 223-224`:
`particleMass = config.initialPieceMass; invMass = 1/particleMass`, identical for
every particle). A `4·L²` tetromino therefore gives each particle the same
mass/invMass as today's `L²` cell; gravity is a mass-independent acceleration;
compliance, rest lengths and substeps are unchanged. So the per-material
stress-strain response is identical by construction — the claim is legitimate.

**One nuance to keep honest:** this is exact for the *per-material* response.
*Aggregate* landing deformation still scales with total mass (~4×) and impact
speed, and a tall I-piece falls further so lands faster and can squash more than
a single cell. That is emergent-correct, not a retune — worth stating so nobody
later reads "preserved exactly" as "a tetromino lands identically to a block".

**Note (record hygiene, not a defect):** the ADR now *adds* seam **area**
constraints (`2(L-1)` per seam) to stop the joint pinching under load. The design
conversation (`.team/conversations/2026-07-21-backend-engineer.md`) had *deferred*
seam area ("rejected for now"). The ADR is the newer, stronger decision and is
canonical; the conversation is now stale on that point. Fine to leave, but the
ADR supersedes the conversation there.

## ADR 0016 — lifecycle (POSITIONING → FALLING) + rotation

Sound and consistent with the cited ADRs. I checked each determinism-relevant
claim against the pushed code:

- **Phase gating is real and leak-free.** `applyInput` (`Simulation.kt:627-636`)
  is `if (positioning) { drag; drop } else { rotate }`. The recognizer stays
  phase-agnostic and the core disambiguates — a tap arrives as `drop`+`rotate`
  and POSITIONING honours `drop`, FALLING honours `rotate`. Honours contracts §2;
  no phase signal reaches `:app`. `activePiecePhase` defaults to FALLING when
  `activePieceBody < 0` (`:844-845`), so a stray `dragX` can't slide a
  non-positioning piece.
- **Window is tick-counted (consistent with ADR 0013).** `advancePositioning`
  (`:208-211`) does `positioningRemaining--; if (<=0) releaseToFall()`, once per
  tick, and the comment cites 0013 explicitly ("same duration on a device that
  drops frames"). No wall-clock anywhere in the path.
- **Freeze reuses the existing short-circuit, not a new hot-loop branch.**
  POSITIONING sets the body weightless (`invMass = 0`), and `integrate`
  (`XpbdSolver.kt:208`) already does `if (invMass[i] == 0f) continue`.
  `releaseToFall` (`:219-226`) thaws it and resets the lock counters so the
  debounce measures the fall, not the hover — idempotent against both the early-
  `drop` and timer paths.
- **Rotation is exact and determinism-safe (consistent with ADR 0006).**
  `(x,y)→(y,-x)`, no `sin`/`cos`, cannot drift. Overlap is rejected outright
  (positions restored) — no launch energy, the existing overlap-ejection fix.
- **"Fall velocity survives a rotation" — verified true.** `applyRotate`
  (`Simulation.kt:660-666`) rewrites `framePrev`/`substepPrev` but never
  `velX`/`velY`, and the solver carries velocity in `velX`/`velY`
  (`integrate` line 210: `posX += velX*h`; `deriveVelocities` recomputes it). So
  the retained downward `velX`/`velY` is applied on the next substep and the
  piece keeps falling. The prev-buffer reset is for a clean render snap and to
  avoid *injecting* velocity from the position jump — it does not zero the fall.
- **No SETTLING phase** — folded into FALLING (rotate stays legal until lock).
  Correct: FALLING already means what a SETTLING value would, and it keeps the
  state machine two-valued.

**Determinism across the whole change:** the phase machine is tick-indexed, the
rotation is exact, the freeze is a deterministic `invMass` toggle, and no input
carries wall-clock (the timestamped `VelocityWindow` is gone). A seeded replay
stays bit-identical. I did **not** re-run `DeterminismTest` myself — QA should
confirm it now exercises a seeded replay through positioning, falling and a
rotation, *and* that it has been migrated off `hardDropVelocity` (see the
InputFrame review — that field is being removed).

## Cross-cutting notes (for the Product Lead / Backend, not blocking)

- **ADR numbering.** 0015/0016 sensibly skip 0012/0013 (which exist, unmerged,
  on `chore/architecture`) and avoid the collision; `decisions.md` is updated for
  both. **0014 is now a gap** — intentional-looking, but note it so nobody hunts
  for a missing ADR. The deeper problem — `main`'s 0011 (silhouette) vs
  `chore/architecture`'s different 0011/0012/0013, which 0013 and the pinned
  lattice are cited against — is still the Product Lead's to reconcile before
  either branch merges.

---
*— **Architect***
