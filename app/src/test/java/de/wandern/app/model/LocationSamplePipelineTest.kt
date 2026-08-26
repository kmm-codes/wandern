package de.wandern.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSamplePipelineTest {
    @Test
    fun normalWalkingFixesAreAcceptedWithoutDelay() {
        val pipeline = LocationSamplePipeline(ActivityType.HIKING)

        val first = pipeline.process(sample(northMeters = 0.0, elapsedMillis = 0L))
        val second = pipeline.process(sample(northMeters = 8.0, elapsedMillis = 3_000L))

        assertEquals(1, first.trustedSamples.size)
        assertEquals(1, second.trustedSamples.size)
        assertEquals(LocationSampleDecisionReason.ACCEPTED, second.reason)
    }

    @Test
    fun isolatedJumpIsHeldAndThenDiscarded() {
        val pipeline = LocationSamplePipeline(ActivityType.HIKING)
        pipeline.process(sample(northMeters = 0.0, elapsedMillis = 0L, accuracyMeters = 5f))

        val jump = pipeline.process(
            sample(northMeters = 200.0, elapsedMillis = 3_000L, accuracyMeters = 5f),
        )
        val returned = pipeline.process(
            sample(northMeters = 7.0, elapsedMillis = 6_000L, accuracyMeters = 5f),
        )

        assertTrue(jump.trustedSamples.isEmpty())
        assertEquals(LocationSampleDecisionReason.AWAITING_CONFIRMATION, jump.reason)
        assertEquals(LocationSampleDecisionReason.OUTLIER_REPLACED, returned.reason)
        assertEquals(1, returned.trustedSamples.size)
        assertEquals(7.0, northMeters(returned.trustedSamples.single().point), 0.5)
    }

    @Test
    fun moderateJumpIsStillHeldWhenAccuracyRadiusLooksGenerous() {
        val pipeline = LocationSamplePipeline(ActivityType.HIKING)
        pipeline.process(sample(northMeters = 0.0, elapsedMillis = 0L, accuracyMeters = 5f))

        val jump = pipeline.process(
            sample(northMeters = 40.0, elapsedMillis = 3_000L, accuracyMeters = 25f),
        )

        assertEquals(LocationSampleDecisionReason.AWAITING_CONFIRMATION, jump.reason)
        assertTrue(jump.trustedSamples.isEmpty())
    }

    @Test
    fun persistentDisplacementStartsNewSegmentInsteadOfDrawingTeleport() {
        val pipeline = LocationSamplePipeline(ActivityType.HIKING)
        pipeline.process(sample(northMeters = 0.0, elapsedMillis = 0L, accuracyMeters = 5f))
        pipeline.process(sample(northMeters = 200.0, elapsedMillis = 3_000L, accuracyMeters = 5f))

        val confirmed = pipeline.process(
            sample(northMeters = 205.0, elapsedMillis = 6_000L, accuracyMeters = 5f),
        )

        assertTrue(confirmed.startNewSegment)
        assertEquals(LocationSampleDecisionReason.DISCONTINUITY_CONFIRMED, confirmed.reason)
        assertEquals(2, confirmed.trustedSamples.size)
    }

    @Test
    fun laterFixCanConfirmBufferedMovementWithoutCreatingSegment() {
        val pipeline = LocationSamplePipeline(ActivityType.HIKING)
        pipeline.process(sample(northMeters = 0.0, elapsedMillis = 0L, accuracyMeters = 5f))
        val earlyFix = pipeline.process(
            sample(northMeters = 30.0, elapsedMillis = 3_000L, accuracyMeters = 5f),
        )

        val confirmed = pipeline.process(
            sample(northMeters = 35.0, elapsedMillis = 8_000L, accuracyMeters = 5f),
        )

        assertTrue(earlyFix.trustedSamples.isEmpty())
        assertFalse(confirmed.startNewSegment)
        assertEquals(2, confirmed.trustedSamples.size)
    }

    @Test
    fun activityTypeControlsGenerousMaximumSpeed() {
        val hiking = LocationSamplePipeline(ActivityType.HIKING)
        val running = LocationSamplePipeline(ActivityType.RUNNING)
        val start = sample(northMeters = 0.0, elapsedMillis = 0L, accuracyMeters = 3f)
        hiking.process(start)
        running.process(start)
        val fastFix = sample(northMeters = 26.0, elapsedMillis = 3_000L, accuracyMeters = 3f)

        assertTrue(hiking.process(fastFix).trustedSamples.isEmpty())
        assertEquals(1, running.process(fastFix).trustedSamples.size)
    }

    @Test
    fun tightSwitchbacksRemainValid() {
        val pipeline = LocationSamplePipeline(ActivityType.HIKING)
        val decisions = listOf(
            sample(0.0, 0L),
            sample(9.0, 3_000L),
            sample(2.0, 6_000L),
            sample(11.0, 9_000L),
        ).map(pipeline::process)

        assertTrue(decisions.all { it.trustedSamples.size == 1 })
        assertTrue(decisions.none { it.startNewSegment })
    }

    @Test
    fun poorAccuracyMarksGapButDoesNotMoveTrustedTrack() {
        val pipeline = LocationSamplePipeline(ActivityType.HIKING)
        pipeline.process(sample(0.0, 0L))

        val decision = pipeline.process(sample(80.0, 3_000L, accuracyMeters = 60f))

        assertTrue(decision.trustedSamples.isEmpty())
        assertTrue(decision.marksGpsGap)
        assertEquals(LocationSampleDecisionReason.POOR_ACCURACY, decision.reason)
    }

    @Test
    fun recentGpsSuppressesNetworkFixButNetworkRemainsFallback() {
        val pipeline = LocationSamplePipeline(ActivityType.HIKING)
        pipeline.process(sample(0.0, 0L, source = LocationSampleSource.GPS))

        val suppressed = pipeline.process(
            sample(10.0, 8_000L, source = LocationSampleSource.NETWORK),
        )
        val fallback = pipeline.process(
            sample(20.0, 16_000L, source = LocationSampleSource.NETWORK),
        )

        assertEquals(LocationSampleDecisionReason.NETWORK_SUPPRESSED, suppressed.reason)
        assertTrue(suppressed.trustedSamples.isEmpty())
        assertEquals(1, fallback.trustedSamples.size)
    }

    @Test
    fun olderProviderCallbackIsRejectedUsingMonotonicTime() {
        val pipeline = LocationSamplePipeline(ActivityType.HIKING)
        pipeline.process(sample(0.0, 10_000L))

        val decision = pipeline.process(sample(1.0, 9_000L))

        assertEquals(LocationSampleDecisionReason.OUT_OF_ORDER, decision.reason)
        assertTrue(decision.trustedSamples.isEmpty())
    }

    @Test
    fun farFirstFixAfterRecoveryNeedsConfirmation() {
        val pipeline = LocationSamplePipeline(ActivityType.HIKING)
        pipeline.reset(point(northMeters = 0.0, accuracyMeters = 5f))

        val first = pipeline.process(sample(400.0, 100_000L, accuracyMeters = 5f))
        val confirmation = pipeline.process(sample(405.0, 103_000L, accuracyMeters = 5f))

        assertTrue(first.trustedSamples.isEmpty())
        assertTrue(confirmation.startNewSegment)
        assertEquals(2, confirmation.trustedSamples.size)
    }

    @Test
    fun stationaryTrustedFixesStillReachAutoPauseDetector() {
        val pipeline = LocationSamplePipeline(ActivityType.HIKING)
        val detector = AutoPauseDetector(pauseAfterMillis = 6_000L)
        val inputs = listOf(
            sample(0.0, 0L),
            sample(10.0, 3_000L),
            sample(10.2, 6_000L),
            sample(10.1, 9_000L),
            sample(10.2, 12_000L),
        )

        val updates = inputs.flatMap { input ->
            pipeline.process(input).trustedSamples.map { trusted ->
                detector.update(trusted.point, trusted.elapsedRealtimeMillis)
            }
        }

        assertTrue(updates.last().autoPaused)
    }

    private fun sample(
        northMeters: Double,
        elapsedMillis: Long,
        accuracyMeters: Float = 5f,
        source: LocationSampleSource = LocationSampleSource.GPS,
    ) = LocationSample(
        point = point(
            northMeters = northMeters,
            accuracyMeters = accuracyMeters,
            timeMillis = BASE_TIME_MILLIS + elapsedMillis,
        ),
        elapsedRealtimeMillis = elapsedMillis,
        source = source,
    )

    private fun point(
        northMeters: Double,
        accuracyMeters: Float,
        timeMillis: Long? = null,
    ) = TrackPoint(
        latitude = BASE_LATITUDE + northMeters / METERS_PER_LATITUDE_DEGREE,
        longitude = BASE_LONGITUDE,
        timeMillis = timeMillis,
        accuracyMeters = accuracyMeters,
    )

    private fun northMeters(point: TrackPoint): Double =
        (point.latitude - BASE_LATITUDE) * METERS_PER_LATITUDE_DEGREE

    companion object {
        private const val BASE_LATITUDE = 48.0
        private const val BASE_LONGITUDE = 8.0
        private const val METERS_PER_LATITUDE_DEGREE = 111_320.0
        private const val BASE_TIME_MILLIS = 1_700_000_000_000L
    }
}
