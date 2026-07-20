package gravitris.app.sim

/**
 * # STAGE 1 STAND-IN — DELETE THIS FILE AT STAGE 2 INTEGRATION
 *
 * These declarations mirror `docs/contracts.md` §2 and §3 exactly, field for
 * field and name for name. They exist only because Stage 1 runs Track A
 * (`:core-sim`, backend-engineer) and Track B (`:app`, frontend-engineer) in
 * parallel off the same foundation branch, so the real `gravitris.game.*`
 * types do not exist on this branch yet.
 *
 * **This is not a second definition of the contract, and it must never become
 * one.** The contract is owned by the backend-engineer (shape) per
 * `docs/contracts.md` §5. If these ever disagree with `gravitris.game.*`,
 * `gravitris.game.*` wins and this file is wrong.
 *
 * ## What Stage 2 integration does, precisely
 *
 * 1. Delete this file.
 * 2. Change `import gravitris.app.sim.*` to `import gravitris.game.*` in the
 *    handful of files that reference these types (the renderer, the input
 *    bridge and the haptics driver).
 * 3. Replace [gravitris.app.harness.RenderHarness] with the real
 *    `Simulation`, which already exposes `state: SimState` and `step(input)`.
 *
 * Nothing else changes. The field names and semantics are identical, which is
 * the entire point of writing it this way rather than inventing a private
 * shape and adapting later.
 *
 * ## What is deliberately NOT reproduced here
 *
 * `SimConfig`, `Simulation`, `Phase`, `LandingEstimate` and the band/coverage
 * members of `SimState`. Stage 1 has no clearing, no losing, no scoring and no
 * landing silhouette (docs/build-order.md Stage 2 scope), so copying those
 * would be dead code — and dead code that duplicates someone else's contract
 * is the worst kind. `SimState` below is therefore a strict SUBSET of the real
 * interface: every member present is spelled identically, and the real
 * `SimState` satisfies it. Members the renderer does not yet consume are
 * simply absent.
 */

/**
 * One tick of player intent. The shell translates gestures into this; the core
 * decides what they mean. Reused (mutable) to avoid per-tick allocation.
 *
 * Mirrors `gravitris.game.InputFrame` — docs/contracts.md §2.
 */
class InputFrame {
    /** Horizontal drag delta this tick, **well units**. */
    var dragX: Float = 0f

    /** Tap — consumed on the tick it is read. */
    var rotate: Boolean = false

    var hardDrop: Boolean = false

    /**
     * Flick speed for the hard-drop, **well units/sec**. Computed by `:app`
     * from a trailing ~60ms window of timestamped touch samples (including
     * `MotionEvent` historical samples), NOT from a per-frame delta.
     */
    var hardDropVelocity: Float = 0f

    /** Returns this frame to the neutral state after the core has read it. */
    fun clear() {
        dragX = 0f
        rotate = false
        hardDrop = false
        hardDropVelocity = 0f
    }
}

/**
 * The subset of `gravitris.game.SimState` the Stage 1 renderer consumes.
 *
 * Read-only by CONVENTION, not by compiler. Arrays are exposed directly to
 * keep per-frame allocation at zero (ADR 0008). **The renderer must never
 * write to them.**
 */
interface SimState {
    // --- particles, for the vertex buffer ---
    val particleCount: Int
    val positionX: FloatArray
    val positionY: FloatArray

    /** For render interpolation (ADR 0006 §3). */
    val prevPositionX: FloatArray
    val prevPositionY: FloatArray

    /** Index into the body arrays. */
    val particleBody: IntArray

    // --- bodies ---
    val bodyCount: Int

    /**
     * Piece archetype index, NOT a hue. `:app` owns the palette — see
     * [gravitris.app.Palette].
     */
    val bodyArchetype: IntArray

    /** Same for all bodies in a run. Particles per piece edge: 4 | 5 | 6. */
    val bodyLattice: Int

    // --- feedback, drained by the shell each frame ---
    val impacts: ImpactList
}

/**
 * Fixed-capacity, no allocation. Cleared by the core at the start of each tick.
 *
 * Mirrors `gravitris.game.ImpactList` — docs/contracts.md §3.
 */
interface ImpactList {
    val count: Int
    val x: FloatArray
    val y: FloatArray

    /** Already scaled by mass and impact speed, 0..1. This is the `energy`
     *  term in docs/ux/feel-feedback.md — the shell does not recompute it. */
    val strength: FloatArray
}
