package de.wandern.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HorizontalSwipeClassifierTest {
    @Test
    fun recognizesBothHorizontalDirections() {
        assertEquals(
            HorizontalSwipeDirection.LEFT,
            HorizontalSwipeClassifier.classify(-300f, 20f, 250L, 100f),
        )
        assertEquals(
            HorizontalSwipeDirection.RIGHT,
            HorizontalSwipeClassifier.classify(300f, -20f, 250L, 100f),
        )
    }

    @Test
    fun rejectsShortVerticalAndVerySlowGestures() {
        assertNull(HorizontalSwipeClassifier.classify(-80f, 5f, 250L, 100f))
        assertNull(HorizontalSwipeClassifier.classify(-300f, 280f, 250L, 100f))
        assertNull(HorizontalSwipeClassifier.classify(-300f, 10f, 1_500L, 100f))
    }
}
