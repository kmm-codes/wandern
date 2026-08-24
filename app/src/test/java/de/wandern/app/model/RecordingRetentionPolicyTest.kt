package de.wandern.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRetentionPolicyTest {
    @Test
    fun `offers inline discard only below one kilometer`() {
        assertTrue(RecordingRetentionPolicy.canDiscardInline(0.0))
        assertTrue(RecordingRetentionPolicy.canDiscardInline(999.99))
        assertFalse(RecordingRetentionPolicy.canDiscardInline(1_000.0))
        assertFalse(RecordingRetentionPolicy.canDiscardInline(12_000.0))
    }
}
