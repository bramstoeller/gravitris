# Interface contracts

Date: 2026-07-20 · Author: Architect

The seam between `:core-sim` and `:app`. Precise enough that the backend engineer
(who owns `:core-sim`) and the frontend engineer (who owns `:app`) can work in
parallel and have the parts fit on the first try.

Signatures are the contract. Implementations behind them are not.

---

## 1. Constructing a simulation

```kotlin
package gravitris.game

/**
 * Every tunable in the product. Constructed once; a change means a new
 * Simulation, not mutation — that is what keeps determinism intact.
 */
data class SimConfig(
    // --- solver (ADR 0001, 0003) ---
    val substeps: Int = 8,              // PINNED. See ADR 0003 — below 8 stacks jitter.
    val distanceCompliance: Float = 1e-4f,  // the squash dial — see note below
    val areaCompliance: Float = 1e-6f,      // 100x stiffer, so squash bulges rather than shrinks
    val linearDamping: Float = 0.005f,
    val friction: Float = 0.55f,
    val gravity: Float = -30f,

    // --- quality tier (ADR 0009) ---
    val lattice: Int = 5,               // particles per piece edge: 4 | 5 | 6

    // --- well geometry (ADR 0010 — derived from insets at runtime) ---
    val wellWidth: Float = 10f,
    val wellHeight: Float = 20f,

    // --- coverage bands (ADR 0004) ---
    val bandCount: Int = 20,
    val bandColumns: Int = 40,          // changing resolution invalidates the
    val bandRows: Int = 4,              // tuned threshold below — see ADR 0004
    /**
     * PER-TIER. Coarser lattices stamp larger particle disks, so the same pile
     * reads as a different fill percentage at each quality tier. Left as one
     * shared constant, the startup quality tier would silently become a
     * gameplay difference. Calibrate medium properly and derive the others.
     * See ADR 0004 and ADR 0009.
     */
    val clearThreshold: Float = 0.90f,

    // --- losing condition (ADR 0005) ---
    val overflowThreshold: Float = 0.50f,
    val graceTicks: Int = 90,           // ~1.5s at 60Hz
    val quietKineticEnergy: Float = 0.05f,

    // --- difficulty ramp ---
    val initialPieceMass: Float = 1f,
    val massPerLevel: Float = 0.5f,
    val initialFallSpeed: Float = 1.5f,

    val seed: Long = 0L,
)

class Simulation(config: SimConfig) {
    val state: SimState

    /** Advances exactly one fixed 1/60s tick. Pure given (state, input). */
    fun step(input: InputFrame)

    /** Reference benchmark for startup calibration (ADR 0009). */
    companion object {
        fun benchmarkReferenceConfig(): SimConfig
    }
}
```

**Compliance: which dial is the squash dial.** Milestone 1 shipped with
`distanceCompliance` at `1e-6` and the blocks were visually rigid — a hard
landing took 2% off a body's height. It is `1e-4` from Milestone 2 on. Two
things about this are worth `:app` knowing:

- **`distanceCompliance` controls shape; `areaCompliance` controls volume.**
  They are kept 100x apart on purpose, so a squashed body bulges sideways
  instead of shrinking. Bulging into gaps is the coverage-band mechanic.
- **`particleCompression` is an area ratio, and area is deliberately
  near-rigid.** It spans roughly `0.895..1.0` at impact both before and after
  the fix, so it is *not* a measure of how squashy the material looks — judging
  squash by it reports "11% deformation" for a block that is visually a perfect
  square. The compression-darkening gain tuned against `0.888..1.0` therefore
  remains correct and needs no retuning. The quantity that actually changed is
  the silhouette: peak landing aspect ratio went from 1.17 to 1.41.

Changing either value means constructing a new `Simulation` — configs are
immutable so that determinism holds (ADR 0006). `:app` already rebuilds on a
well-geometry change, which is the same path a tuning panel would use.

**Determinism contract.** Same `seed` + same `InputFrame` sequence ⇒ bit-identical
`SimState`. Guaranteed only if the core obeys ADR 0006: no transcendental
functions (use a lookup table for rotation), no concurrency in the constraint
solve. Both are review-enforced.

---

## 2. Input — `:app` → `:core-sim`

**Control model: one player-controlled descent (ADR 0017).** A piece falls under
real, accelerating gravity from the moment it spawns, and the player steers it
left/right **and** rotates it for the *whole* descent, until it contacts other
material (or the floor) and settles. There is no phase where control is taken
away — no positioning window, no release-to-drop, no hard drop. (This supersedes
ADR 0016's two-phase POSITIONING→FALLING model and the pre-0016 hard-drop model
this section used to document.)

```kotlin
/**
 * One tick of player intent. `:app` translates gestures into this; the core
 * applies them to the active piece. The core never sees a touch event.
 * Reused (mutable) to avoid per-tick allocation.
 */
class InputFrame {
    /**
     * Horizontal steering delta this tick, in well units. Applied to the active
     * piece EVERY tick it is live — the whole descent — clamped to the well by
     * the core. Kinematic: it moves the piece without injecting velocity.
     */
    var dragX: Float = 0f

    /**
     * Tap → rotate a quarter turn. Applied to the active piece EVERY tick it is
     * live — the whole descent. A rotation that would overlap settled material
     * is rejected outright (the piece does not turn) and injects no velocity.
     * A one-shot: it affects exactly the tick on which it is read, and `:app`
     * clears it (the core reads this object and never writes to it, so a
     * recorded InputFrame sequence replays identically — ADR 0006).
     */
    var rotate: Boolean = false
}
```

**Both intents apply for the entire descent** whenever `activePieceBody >= 0`.
There is no per-piece phase, so the core does not gate input by phase: it applies
`dragX` and `rotate` to whatever piece is active **on the tick they are read**.
The piece locks by the core's own contact-and-settle rule (`hasSettled`, ADR
0005/0017) — `:app` does not signal a lock or a drop.

**Intent is per-tick, not buffered across pieces.** Gravity is always on, so
there are brief windows with no active piece (between a lock and the next spawn,
or a finger held across a spawn). Intent produced in those windows is **discarded
when `activePieceBody < 0`**, never carried to the next piece: the recognizer is
piece-agnostic and holds no piece identity, and `PlayerIntent` zeroes every field
each tick as the core drains it. Do not add buffering — a slide meant for a piece
that has already locked must not jump the next one sideways as it spawns.

**`dragX` and `rotate` are requests the core clamps against collision** — this
is the client's "move left/right and rotate *until it hits another block*." A
slide is clamped to the well and stops against material; a rotation that would
overlap is rejected outright. A blocked slide or rotation is **not an error**:
the recognizer keeps emitting the raw 1:1 intent regardless, and the core
decides how far it actually moves. `dragX` is **horizontal only — vertical
finger movement is ignored**; there is no soft-drop and no hard-drop, so a
downward drag or flick does nothing to the fall (which is plain accelerating
gravity).

**The gesture→intent mapping `:app` owns** (full spec in `docs/ux/gestures.md`;
lives entirely in `:app`, JVM-testable, phase-agnostic because there is no
phase):

| gesture | intent | notes |
| ------- | ------ | ----- |
| horizontal drag past the platform touch-slop | accumulate into `dragX` | 1:1 mapping — 1 dp of finger travel = 1 dp of piece travel in world space (`worldPerDp`). Drag anywhere on screen; the thumb's absolute position never matters. |
| tap (pointer-up within the touch-slop) | latch `rotate` | debounced (`ROTATE_DEBOUNCE_NANOS`) against touch-controller double-reports. |
| pointer-up ending a drag | **nothing** | there is no release-to-drop; the steering already happened continuously. |

- **`dragX` accumulates** across the sub-tick between two 60 Hz ticks; every dp
  of a fast slide must survive to the next tick, so `:app` feeds **every
  timestamped historical `MotionEvent` sample**, not a per-frame delta — Android
  samples touch above the refresh rate and the core must not lose that
  resolution to the tick. **`rotate` latches** — a tap between two ticks is not
  dropped; it is consumed on the tick it is read. (This is the `PlayerIntent`
  bridge between the UI thread and the GL thread.)
- **Multi-touch:** only the first pointer down is tracked; additional pointers
  are ignored entirely, so an accidental second finger (common one-handed)
  cannot hijack a gesture.
- **No hard drop and no `drop` field.** Removed with ADR 0017; a flick-down
  hard-drop is a deliberate non-decision, reversible by adding a field
  additively later if the client asks. Do not reintroduce it without an ADR.

---

## 3. State — `:core-sim` → `:app`

```kotlin
/**
 * Read-only by CONVENTION, not by compiler. Arrays are exposed directly to keep
 * per-frame allocation at zero (ADR 0008). The renderer must never write to
 * them. This is enforced in code review.
 *
 * All arrays are valid in [0, particleCount) / [0, bodyCount) respectively.
 */
interface SimState {
    // --- particles, for the vertex buffer ---
    val particleCount: Int
    val positionX: FloatArray
    val positionY: FloatArray
    val prevPositionX: FloatArray   // for render interpolation (ADR 0006)
    val prevPositionY: FloatArray
    val particleBody: IntArray      // index into body arrays
    /**
     * Body-WIDE surface coordinate, spanning the whole tetromino, not one cell
     * (§15 / D10 — the fix for "the squares don't join"). Both axes divide by
     * the piece's longer footprint side, so it is 0..1 on that side and 0..k
     * (k≤1) on the shorter — aspect-preserving, isotropic, continuous across
     * seams. The grain, subsurface depth and specular sweep all read it and so
     * read across the whole piece. `min(uv,1-uv)` still gives silhouette→core
     * depth; a thin piece's short axis simply never reaches centre (reads more
     * translucent — correct for a thin jelly). → vBodyUv
     */
    val particleU: FloatArray
    val particleV: FloatArray
    /**
     * Per-archetype grain-frequency compensation (length ARCHETYPE_COUNT),
     * static for the run. Body-wide UV makes footprint set grain frequency;
     * fold this into the shader's per-archetype grain scale
     * (`uGrainScale[a] = paletteGrainScale[a] * grainScaleCompensation[a]`) to
     * restore the SAME per-cell frequency every piece had, now continuous. The
     * palette's own per-archetype grain (the identity cue) is unaffected.
     */
    val grainScaleCompensation: FloatArray
    /**
     * True outer-silhouette corner (§16): 1 at a particle that is a convex
     * corner of the WHOLE piece, 0 elsewhere — internal cell corners and seams
     * included. A corner is flagged only where the outline turns outward (the
     * cell has free surface on both meeting sides), so an L rounds its five real
     * corners but stays sharp at its inner elbow, and an O has exactly four.
     * `:app` vertex-interpolates it like particleEdge and shapes the ramp with a
     * power to round only the real silhouette, no new geometry. It is a subset
     * of particleEdge (never corner 1 while edge 0). Static, set once at spawn.
     * The well frame draws with the body program and material attributes off, so
     * its generic `vCorner` must be set to 0 in `WellFrame.draw()`. → vCorner
     */
    val particleCorner: FloatArray
    val particleCompression: FloatArray  // own current/rest area → vCompression
    /**
     * Free-surface boundary: 0 interior, 1 on the body's outer edge.
     * Drives the BRIGHTENING rim light. → vEdge
     */
    val particleEdge: FloatArray
    /**
     * Contact occlusion: 0 = no neighbour, 1 = fully pressed against other
     * material. Derived from the contact solve (neighbour count and depth).
     * Drives the DARKENING seam/crease between touching pieces, and the
     * subsurface "deep colour where pieces overlap or squash thin".
     *
     * This is separate from particleEdge on purpose: a free surface against
     * empty space and a contact surface against a neighbour render OPPOSITELY
     * (one brightens, one darkens), and docs/ux/piece-identity.md ranks the
     * contact seam above the lightness ladder as a small-screen legibility cue.
     * → vContact
     */
    val particleContact: FloatArray

    // --- bodies ---
    val bodyCount: Int
    /**
     * Piece archetype index, NOT a hue. The palette varies saturation,
     * lightness and grain scale per piece independently (docs/ux/
     * piece-identity.md), and the alternating lightness is the colour-vision-
     * deficiency backup cue. An index lets :app own the full palette in a
     * uniform block and extend it without a shader change. → vBodyIndex (flat)
     */
    val bodyArchetype: IntArray
    val bodyLattice: Int            // same for all bodies in a run
    /**
     * How far the material surface extends beyond a particle CENTRE, in world
     * units. Half the lattice spacing: 0.30 / 0.225 / 0.18 at lattice 4/5/6.
     *
     * Every position in this contract is a particle centre. The solver treats
     * the material as reaching particleRadius past it — contacts hold two
     * touching bodies' centres exactly 2*particleRadius apart, and a body
     * resting on the floor has its lowest centres exactly particleRadius above
     * y=0. Both are measured exactly (ContactGapTest).
     *
     * A mesh built from positions alone is therefore INSET by this much on
     * every side, and two bodies whose surfaces touch draw with a
     * 2*particleRadius gap between them — 0.45 world units at lattice 5, a
     * quarter of a piece's width. That is the "margin around the blocks" the
     * client reported at Milestone 1. The renderer must expand its outline by
     * this radius; ADR 0004's occupancy stamp must stamp disks of it.
     *
     * Consume this value; do not re-derive it from PIECE_WIDTH and bodyLattice.
     */
    val particleRadius: Float

    // --- rendering topology (static per tier, ADR 0007) ---
    val triangleIndices: IntArray   // valid for one body; reused with offsets

    // --- coverage (ADR 0004, 0007) ---
    val bandFill: FloatArray        // size bandCount, 0..1 — drives glow + clears
    val bandBottomY: Float
    val bandHeight: Float           // = wellHeight / bandCount
    /**
     * Progress through the clear envelope for each band: -1 = not clearing,
     * else 0..1 across ignition flash → hold → dissolve.
     *
     * Fill alone cannot drive the clear animation: a band at fill 1.0 is
     * indistinguishable from a band mid-dissolve, and the shader must know it
     * is inside the ~120ms ignition flash because that is the one moment the
     * emissive blend is allowed past its normal cap.
     * See docs/ux/feel-feedback.md and docs/ux/band-glow.md.
     */
    val bandClearProgress: FloatArray

    // --- game ---
    val phase: Phase
    val score: Int
    val level: Int
    val activePieceBody: Int        // -1 when none (during overflow / clear)
    val landing: LandingEstimate

    // --- feedback, drained by the shell each frame ---
    val impacts: ImpactList         // for haptics, shake, audio
    val kineticEnergy: Float        // stack quietness — also the loss predicate
}

sealed interface Phase {
    object Playing : Phase
    /** Spawn region blocked; stack is being given time to settle (ADR 0005). */
    data class Overflow(val remainingTicks: Int) : Phase
    /** A band cleared; the stack is dropping and re-settling. */
    data class Clearing(val bands: IntArray, val remainingTicks: Int) : Phase
    object GameOver : Phase
}

/**
 * Projected settle position for the active piece. A single Y is not enough:
 * the silhouette draws a top-surface line across the piece's projected
 * HORIZONTAL extent, and that extent must come from the same clamp logic the
 * real piece obeys — otherwise the silhouette claims a position the piece
 * cannot reach.
 *
 * yLow/yHigh express the estimate as a range. If the single-line form proves
 * overconfident (the documented risk — this is the element most likely to
 * fight the physics), the range band is the fallback and needs no interface
 * change. When confident, yLow == yHigh.
 *
 * Updating at 15-20Hz with render-side interpolation is explicitly fine; a
 * full soft-body pre-simulation is explicitly rejected.
 */
interface LandingEstimate {
    val yLow: Float
    val yHigh: Float
    val xMin: Float
    val xMax: Float
    /** False when no usable estimate this tick. :app holds the last valid
     *  value briefly, then fades out rather than showing stale data. */
    val valid: Boolean
}

/** Fixed-capacity, no allocation. Cleared by the core at the start of each tick. */
interface ImpactList {
    val count: Int
    val x: FloatArray
    val y: FloatArray
    val strength: FloatArray   // scaled by mass and impact speed, 0..1
}
```

---

## 4. Shading contract — renderer → fragment shader (ADR 0007)

The vertex stage supplies exactly these varyings. The UX Designer's procedural
look is authored as a fragment function consuming only them.

| varying | source | meaning |
| ------- | ------ | ------- |
| `vBodyUv` | `particleU/V` | **body-wide** material coordinate (§15) for noise, subsurface depth and the specular sweep — continuous across the whole piece |
| `vWorldPos` | interpolated position | lighting, and band-glow lookup by `y` |
| `vBodyIndex` (flat) | `bodyArchetype[particleBody[i]]` | indexes the palette UBO — hue, saturation, lightness, grain scale (× `grainScaleCompensation`) |
| `vCompression` | `particleCompression` | how squashed this material is; drives "heavy" |
| `vEdge` | `particleEdge` | free surface → **brightening** rim light |
| `vCorner` | `particleCorner` | true outer-silhouette corner (§16) → rounded-corner bevel |
| `vContact` | `particleContact` | contact with neighbour → **darkening** seam / AO / deep colour through overlap |

Uniforms:

| uniform | meaning |
| ------- | ------- |
| `uPalette[N]` | per-archetype `{hue, sat, light, grainScale}` — owned by `:app`, extensible without a shader change |
| `uBandFill[20]` | per-band fill 0..1. **Anticipation glow only** — never ignition or dissolve |
| `uBandClearProgress[20]` | per-band clear-envelope progress, -1 when not clearing. Drives ignition, hold and dissolve |
| `uBandBottomY`, `uBandInvHeight` | band geometry for world-Y lookup |

`uBandInvHeight` is the **reciprocal** of `SimState.bandHeight`, uploaded as
`1f / state.bandHeight`, because the shader multiplies rather than divides:
`(worldY - uBandBottomY) * uBandInvHeight`. The ugly name is deliberate and is
not a candidate for tidying. Renaming it to `uBandHeight` and uploading
`bandHeight` would scale every band lookup by height² — glow landing in the
wrong bands, with no compile error and no assertion to catch it. It stays
invisible at the default `wellHeight / bandCount = 20 / 20 = 1.0`, and appears
only once the well geometry changes, which is exactly what ADR 0010 does at
runtime from the display insets.
| `uOverflow` | spawn-zone warning intensity 0..1 (ADR 0005) |
| `uTime` | seconds |
| *look parameters* | the UX Designer's named tunables |

**Confirmed to UX:** no screen-space bloom and no HDR post-process — the ignition
flash must read from the emissive blend alone. Band height is `wellHeight / 20`.
The dither noise reuses the gel-grain procedural field; **it is not a baked
texture** and must not become one.

**Open, for UX to answer:** whether the solver's own impact propagation reads
legibly down the stack, or whether a shader-side impact wave is needed. If the
latter, `:app` sets an impact origin/time uniform from `SimState.impacts` — no
new simulation output required.

**Adding a varying later means touching the vertex format, the buffer fill and
both shaders. Confirm this list before frontend work starts.**

---

## 4b. Accessibility is a render-layer concern — not a solver one

**Reduced motion must not touch the simulation.** This is a correction to my
earlier position and it matters twice over:

- **Correctness.** Damping the solver would damp the *primary squash on impact*,
  which UX specifies as Unchanged under reduced motion because it is the core
  weight cue, not the repetitive motion the setting exists to remove. Cutting it
  would silently break success criterion 2.
- **Determinism.** A solver-level accessibility toggle would make the physics —
  and therefore game outcomes and replay tests — differ per user setting. An
  accessibility preference must never change what happens in the game, only how
  it is drawn.

| Setting | Implemented as |
| ------- | -------------- |
| Screen shake off | zero the view-matrix shake vector |
| Jiggle scaled to 0.3 | temporal low-pass on per-particle deviation from body centroid, applied at the **render interpolation** layer — high-frequency ringing is damped, low-frequency squash survives |
| Primary squash | **unchanged** |
| Colourblind-safe hues | palette UBO contents |
| Flash ceiling | no periodic signal above 3Hz, including the overflow pulse, and **not** gated behind the reduced-motion toggle |

`SimConfig` therefore carries **no accessibility fields**.

---

## 5. Ownership

| Contract | Owner | Consumers |
| -------- | ----- | --------- |
| `SimConfig`, `Simulation`, `SimState`, `Phase`, `LandingEstimate` | backend-engineer | frontend, QA |
| Palette contents (hue/sat/light/grain per archetype) | ux-designer | frontend |
| `InputFrame` | backend-engineer (shape), frontend (population) | both |
| Varyings + uniforms | frontend-engineer | UX Designer |
| Well geometry from insets | frontend-engineer | backend (via `SimConfig`) |

**Changing any signature here crosses a module boundary and needs the Architect.**
Adding a field to `SimState` is additive and does not.
