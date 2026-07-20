# Interface contracts

Date: 2026-07-20 · Author: Architect

The seam between `:core-sim` and `:app`. Precise enough that the backend engineer
(who owns `:core-sim`) and the frontend engineer (who owns `:app`) can work in
parallel and have the parts fit on the first try.

Signatures are the contract. Implementations behind them are not.

---

## 1. Constructing a simulation

```kotlin
package squish.game

/**
 * Every tunable in the product. Constructed once; a change means a new
 * Simulation, not mutation — that is what keeps determinism intact.
 */
data class SimConfig(
    // --- solver (ADR 0001, 0003) ---
    val substeps: Int = 8,              // PINNED. See ADR 0003 — below 8 stacks jitter.
    val distanceCompliance: Float = 1e-6f,
    val areaCompliance: Float = 1e-6f,
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

**Determinism contract.** Same `seed` + same `InputFrame` sequence ⇒ bit-identical
`SimState`. Guaranteed only if the core obeys ADR 0006: no transcendental
functions (use a lookup table for rotation), no concurrency in the constraint
solve. Both are review-enforced.

---

## 2. Input — `:app` → `:core-sim`

```kotlin
/**
 * One tick of player intent. The shell translates gestures into this; the core
 * decides what they mean. Reused (mutable) to avoid per-tick allocation.
 */
class InputFrame {
    var dragX: Float = 0f       // horizontal drag delta this tick, well units
    var rotate: Boolean = false // tap — consumed on the tick it is read
    var hardDrop: Boolean = false
    /**
     * Flick speed for the hard-drop, well units/sec. Computed by :app from a
     * trailing ~60ms window of TIMESTAMPED touch samples (including
     * MotionEvent historical samples), NOT from a per-frame delta — Android
     * samples touch above the refresh rate and the core must not lose that
     * resolution to a 60Hz tick. See docs/ux/gestures.md.
     */
    var hardDropVelocity: Float = 0f
}
```

Gesture recognition (drag anywhere / tap to rotate / swipe down to hard-drop,
per the brief) lives entirely in `:app`. The core never sees a touch event.

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
    val particleU: FloatArray       // body-local lattice coord, 0..1  → vBodyUv
    val particleV: FloatArray
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
| `vBodyUv` | `particleU/V` | material coordinate for noise and subsurface depth |
| `vWorldPos` | interpolated position | lighting, and band-glow lookup by `y` |
| `vBodyIndex` (flat) | `bodyArchetype[particleBody[i]]` | indexes the palette UBO — hue, saturation, lightness, grain scale |
| `vCompression` | `particleCompression` | how squashed this material is; drives "heavy" |
| `vEdge` | `particleEdge` | free surface → **brightening** rim light |
| `vContact` | `particleContact` | contact with neighbour → **darkening** seam / AO / deep colour through overlap |

Uniforms:

| uniform | meaning |
| ------- | ------- |
| `uPalette[N]` | per-archetype `{hue, sat, light, grainScale}` — owned by `:app`, extensible without a shader change |
| `uBandFill[20]` | per-band fill 0..1 |
| `uBandClear[20]` | per-band clear-envelope progress, -1 when not clearing |
| `uBandBottomY`, `uBandHeight` | band geometry for world-Y lookup |
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
