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
    val particleCompression: FloatArray  // current/rest area → vCompression
    val particleEdge: FloatArray    // 0 interior, 1 boundary → vEdge

    // --- bodies ---
    val bodyCount: Int
    val bodyHue: FloatArray         // colourblind-safe palette, set by :app config
    val bodyLattice: Int            // same for all bodies in a run

    // --- rendering topology (static per tier, ADR 0007) ---
    val triangleIndices: IntArray   // valid for one body; reused with offsets

    // --- coverage (ADR 0004, 0007) ---
    val bandFill: FloatArray        // size bandCount, 0..1 — drives glow + clears
    val bandBottomY: Float
    val bandHeight: Float

    // --- game ---
    val phase: Phase
    val score: Int
    val level: Int
    val activePieceBody: Int        // -1 when none (during overflow / clear)
    val landingSilhouetteY: Float   // projected settle height for the active piece

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
| `vHue` | `bodyHue[particleBody[i]]` | piece identity — the primary cue |
| `vCompression` | `particleCompression` | how squashed this material is; drives "heavy" |
| `vEdge` | `particleEdge` | rim lighting without a normal buffer |

Plus uniforms: `uBandFill[20]`, `uBandBottomY`, `uBandHeight`, `uTime`, and the
UX Designer's named look parameters.

**Adding a varying later means touching the vertex format, the buffer fill and
both shaders. Confirm this list covers the intended look before frontend work
starts.**

---

## 5. Ownership

| Contract | Owner | Consumers |
| -------- | ----- | --------- |
| `SimConfig`, `Simulation`, `SimState`, `Phase` | backend-engineer | frontend, QA |
| `InputFrame` | backend-engineer (shape), frontend (population) | both |
| Varyings + uniforms | frontend-engineer | UX Designer |
| Well geometry from insets | frontend-engineer | backend (via `SimConfig`) |

**Changing any signature here crosses a module boundary and needs the Architect.**
Adding a field to `SimState` is additive and does not.
