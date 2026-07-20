package gravitris.game

/**
 * The order pieces arrive in: a shuffled bag of all [Simulation.ARCHETYPE_COUNT]
 * archetypes, reshuffled when it empties.
 *
 * A bag rather than independent random draws. Uniform sampling produces
 * droughts — the probability of not seeing a given archetype in twenty draws
 * is around 5%, and a player on the receiving end of that reads it as the game
 * being unfair rather than as luck. A bag bounds the gap between two
 * appearances of the same archetype, which is why every game in this genre
 * uses one.
 *
 * At Stage 3 the archetype is a **colour index only** — every piece is the same
 * square lattice (see [Simulation.ARCHETYPE_COUNT]), so the bag is currently
 * shuffling hues. It is built now anyway because the sequence is the thing that
 * has to be deterministic, and retrofitting determinism onto a sequence after
 * fixtures exist is worse than building it before they do.
 *
 * ### Determinism
 *
 * ADR 0006 requires bit-identical results across runs and devices. `java.util.Random`
 * would in fact be specified tightly enough, but it is a shared, synchronised,
 * allocating object from the platform library, and `:core-sim` is deliberately
 * free of anything it does not need. This is xorshift64*: a handful of shifts
 * and one multiply on `Long`, all of which are exact and platform-independent
 * in a way floating point is not.
 *
 * Nothing here allocates after construction.
 */
internal class PieceSequence(seed: Long, private val archetypes: Int) {

    /**
     * Zero is xorshift's fixed point — it maps to itself forever and the bag
     * would stop shuffling. [SimConfig.seed] defaults to 0, so this is the
     * default path, not an edge case. The constant is the 64-bit golden ratio,
     * used only to move a zero seed somewhere with bits set in it.
     */
    private var rngState: Long = if (seed == 0L) -0x61c8864680b583ebL else seed

    private val bag = IntArray(archetypes) { it }
    private var next = archetypes

    /** The next archetype in the sequence. */
    fun next(): Int {
        if (next >= archetypes) {
            shuffle()
            next = 0
        }
        return bag[next++]
    }

    /** Fisher-Yates, in place. */
    private fun shuffle() {
        for (i in archetypes - 1 downTo 1) {
            val j = (nextBits() % (i + 1)).toInt()
            val swap = bag[i]
            bag[i] = bag[j]
            bag[j] = swap
        }
    }

    /** A non-negative pseudorandom `Long`. xorshift64*, Vigna 2016. */
    private fun nextBits(): Long {
        var x = rngState
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        rngState = x
        return (x * 0x2545F4914F6CDD1DL) ushr 1
    }
}
