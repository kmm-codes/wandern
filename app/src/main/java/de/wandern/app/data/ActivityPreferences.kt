package de.wandern.app.data

import android.content.Context
import de.wandern.app.model.ActivityType

class ActivityPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var defaultType: ActivityType
        get() = ActivityType.fromStoredValue(preferences.getString(KEY_DEFAULT_TYPE, null))
        set(value) {
            preferences.edit().putString(KEY_DEFAULT_TYPE, value.name).apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "activity_preferences"
        const val KEY_DEFAULT_TYPE = "default_type"
    }
}
