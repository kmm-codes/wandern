package de.wandern.app.ui

import androidx.annotation.StringRes
import de.wandern.app.R
import de.wandern.app.model.ActivityType

@StringRes
fun ActivityType.labelRes(): Int = when (this) {
    ActivityType.HIKING -> R.string.activity_hiking
    ActivityType.CYCLING -> R.string.activity_cycling
    ActivityType.E_BIKE -> R.string.activity_e_bike
    ActivityType.RUNNING -> R.string.activity_running
}
