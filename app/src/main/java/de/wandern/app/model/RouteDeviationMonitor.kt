package de.wandern.app.model

enum class RouteDeviationEvent {
    NONE,
    LEFT_ROUTE,
    OFF_ROUTE_REMINDER,
    RETURNED_TO_ROUTE,
}

data class RouteDeviationUpdate(
    val confirmedOffRoute: Boolean,
    val event: RouteDeviationEvent = RouteDeviationEvent.NONE,
)

class RouteDeviationMonitor(
    private val leaveThresholdMeters: Double = 50.0,
    private val returnThresholdMeters: Double = 40.0,
    private val confirmationsRequired: Int = 3,
    private val reminderIntervalMillis: Long = 5 * 60_000L,
    private val reliableAccuracyMeters: Float = GpsQuality.RELIABLE_ACCURACY_METERS,
) {
    private var outsideCount = 0
    private var insideCount = 0
    private var confirmedOffRoute = false
    private var lastAlertMillis: Long? = null

    fun update(deviationMeters: Double, accuracyMeters: Float?, nowMillis: Long): RouteDeviationUpdate {
        if ((accuracyMeters ?: 0f) > reliableAccuracyMeters) return current()

        when {
            deviationMeters > leaveThresholdMeters -> {
                outsideCount += 1
                insideCount = 0
                if (!confirmedOffRoute && outsideCount >= confirmationsRequired) {
                    confirmedOffRoute = true
                    lastAlertMillis = nowMillis
                    return RouteDeviationUpdate(true, RouteDeviationEvent.LEFT_ROUTE)
                }
                val previousAlert = lastAlertMillis
                if (
                    confirmedOffRoute && previousAlert != null &&
                    nowMillis - previousAlert >= reminderIntervalMillis
                ) {
                    lastAlertMillis = nowMillis
                    return RouteDeviationUpdate(true, RouteDeviationEvent.OFF_ROUTE_REMINDER)
                }
            }
            deviationMeters <= returnThresholdMeters -> {
                insideCount += 1
                outsideCount = 0
                if (confirmedOffRoute && insideCount >= confirmationsRequired) {
                    confirmedOffRoute = false
                    lastAlertMillis = null
                    return RouteDeviationUpdate(false, RouteDeviationEvent.RETURNED_TO_ROUTE)
                }
            }
            else -> {
                outsideCount = 0
                insideCount = 0
            }
        }
        return current()
    }

    fun reset() {
        outsideCount = 0
        insideCount = 0
        confirmedOffRoute = false
        lastAlertMillis = null
    }

    private fun current() = RouteDeviationUpdate(confirmedOffRoute)
}
