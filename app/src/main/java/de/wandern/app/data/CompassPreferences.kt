package de.wandern.app.data

import android.content.Context

class CompassPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    val hasHeadingOffset: Boolean get() = preferences.contains(KEY_HEADING_OFFSET)

    var headingOffsetDegrees: Float
        get() = preferences.getFloat(KEY_HEADING_OFFSET, 0f)
        set(value) {
            preferences.edit().putFloat(KEY_HEADING_OFFSET, value).apply()
        }

    fun clearHeadingOffset() {
        preferences.edit().remove(KEY_HEADING_OFFSET).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "compass_preferences"
        const val KEY_HEADING_OFFSET = "heading_offset_degrees"
    }
}
