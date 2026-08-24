package de.wandern.app.data

import android.content.Context
import de.wandern.app.model.HikingFitnessLevel

class FitnessPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var level: HikingFitnessLevel
        get() = HikingFitnessLevel.fromPreference(preferences.getString(KEY_LEVEL, null))
        set(value) {
            preferences.edit().putString(KEY_LEVEL, value.preferenceValue).apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "hiking_forecast"
        const val KEY_LEVEL = "fitness_level"
    }
}
