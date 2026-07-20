package gravitris.app.gl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for the shared lattice index buffer.
 *
 * A malformed index buffer does not produce a *wrong* picture — it produces a
 * black screen or garbage, and this container has no GPU to notice that on. So
 * these tests stand in for the visual check that cannot be performed here:
 * every index in range, every cell covered exactly once, consistent winding,
 * and the per-body offset correct so bodies cannot reference each other's
 * vertices.
 */
class LatticeTopologyTest {

    /** The three quality tiers in `SimConfig.lattice` (ADR 0009). */
    @ParameterizedTest
    @ValueSource(ints = [4, 5, 6])
    fun `triangle and index counts follow the lattice size`(lattice: Int) {
        val cells = (lattice - 1) * (lattice - 1)
        assertEquals(cells * 2, LatticeTopology.trianglesPerBody(lattice))
        assertEquals(cells * 6, LatticeTopology.indicesPerBody(lattice))
    }

    @Test
    fun `the default tier matches ADR 0007's budget`() {
        // 60 bodies x 25 particles = 1500 particles, the figure ADR 0007
        // budgets bandwidth against.
        val indices = LatticeTopology.buildIndices(maxBodies = 60, lattice = 5)
        assertEquals(60 * 32 * 3, indices.size)
        assertEquals(32, LatticeTopology.trianglesPerBody(5))
    }

    @ParameterizedTest
    @ValueSource(ints = [4, 5, 6])
    fun `every index stays inside its own body`(lattice: Int) {
        val particlesPerBody = lattice * lattice
        val bodies = 8
        val indices = LatticeTopology.buildIndices(bodies, lattice)
        val perBody = LatticeTopology.indicesPerBody(lattice)

        for (body in 0 until bodies) {
            val low = body * particlesPerBody
            val high = low + particlesPerBody
            for (i in 0 until perBody) {
                val index = indices[body * perBody + i].toInt()
                assertTrue(
                    index in low until high,
                    "body $body index $index escaped its range [$low, $high)",
                )
            }
        }
    }

    @Test
    fun `every lattice cell is covered by exactly two triangles`() {
        val lattice = 5
        val indices = LatticeTopology.buildIndices(maxBodies = 1, lattice = lattice)

        // Each of the 16 cells should contribute two triangles whose combined
        // vertex set is that cell's four corners. A gap or an overlap here is
        // a hole or a double-drawn triangle on screen.
        val triangles = indices.toList().chunked(3)
        assertEquals(32, triangles.size)

        val cellCoverage = mutableMapOf<Set<Int>, Int>()
        for (triangle in triangles) {
            val rows = triangle.map { it / lattice }
            val columns = triangle.map { it % lattice }
            val cell = setOf(rows.min() * lattice + columns.min())
            cellCoverage[cell] = (cellCoverage[cell] ?: 0) + 1
        }

        assertEquals(16, cellCoverage.size, "expected 16 distinct cells")
        assertTrue(
            cellCoverage.values.all { it == 2 },
            "every cell needs exactly two triangles, got $cellCoverage",
        )
    }

    @Test
    fun `no triangle is degenerate`() {
        val indices = LatticeTopology.buildIndices(maxBodies = 4, lattice = 5)
        indices.toList().chunked(3).forEachIndexed { number, triangle ->
            assertEquals(
                3, triangle.toSet().size,
                "triangle $number repeats a vertex: $triangle — it would draw nothing",
            )
        }
    }

    @Test
    fun `winding is consistently counter clockwise`() {
        val lattice = 5
        val indices = LatticeTopology.buildIndices(maxBodies = 1, lattice = lattice)

        // Lay the lattice out on a unit grid with y up, exactly as world space
        // does, and check the signed area of every triangle has the same sign.
        indices.toList().chunked(3).forEachIndexed { number, triangle ->
            val x = triangle.map { (it % lattice).toFloat() }
            val y = triangle.map { (it / lattice).toFloat() }
            val signedArea =
                (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0])
            assertTrue(
                signedArea > 0f,
                "triangle $number is clockwise or degenerate (area $signedArea)",
            )
        }
    }

    @Test
    fun `bodies share the same pattern at a fixed offset`() {
        val lattice = 5
        val indices = LatticeTopology.buildIndices(maxBodies = 3, lattice = lattice)
        val perBody = LatticeTopology.indicesPerBody(lattice)
        val stride = lattice * lattice

        // This is what makes one draw call for the whole stack possible.
        for (i in 0 until perBody) {
            assertEquals(
                indices[i] + stride, indices[perBody + i].toInt(),
                "body 1 must be body 0 offset by $stride",
            )
            assertEquals(
                indices[i] + 2 * stride, indices[2 * perBody + i].toInt(),
                "body 2 must be body 0 offset by ${2 * stride}",
            )
        }
    }

    @Test
    fun `a vertex count past the unsigned short ceiling is rejected loudly`() {
        // Silent truncation here would corrupt geometry on a device we cannot
        // debug from this container, so it must fail at construction instead.
        assertThrows<IllegalArgumentException> {
            LatticeTopology.buildIndices(maxBodies = 4000, lattice = 6)
        }
    }

    @Test
    fun `a degenerate lattice is rejected`() {
        assertThrows<IllegalArgumentException> {
            LatticeTopology.buildIndices(maxBodies = 1, lattice = 1)
        }
    }
}
