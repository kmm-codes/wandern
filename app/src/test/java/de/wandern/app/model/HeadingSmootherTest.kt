package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingSmootherTest {
    @Test
    fun `crossing north follows the short direction`() {
        val smoother = HeadingSmoother(smoothingFactor = 0.5f)

        assertEquals(350f, smoother.update(350f), 0.001f)
        val result = smoother.update(10f)

        assertTrue(result < 1f || result > 359f)
    }

    @Test
    fun `angular distance handles normalized headings`() {
        assertEquals(20f, HeadingSmoother.angularDistance(350f, 10f), 0.001f)
        assertEquals(90f, HeadingSmoother.angularDistance(-90f, 0f), 0.001f)
    }

    @Test
    fun `reset accepts the next heading immediately`() {
        val smoother = HeadingSmoother(smoothingFactor = 0.1f)
        smoother.update(90f)
        smoother.update(180f)

        smoother.reset()

        assertEquals(270f, smoother.update(270f), 0.001f)
    }
}
