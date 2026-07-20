package gravitris.coresim

/**
 * Placeholder proving :core-sim compiles, is consumable from :app, and runs
 * its tests on the plain JVM (ADR 0008) before any solver or game-rule code
 * exists.
 *
 * Delete this once `physics/` or `game/` (docs/adr/0008-module-boundaries.md)
 * has real code — see docs/build-order.md Stage 1, Track A. It exists only so
 * Stage 0's "one command to test" and "one command to run" have something
 * real to exercise end to end.
 */
object CoreSimBuildScaffold {
    const val MODULE_NAME = "core-sim"
}
