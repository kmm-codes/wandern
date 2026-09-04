package de.wandern.app.data

import android.content.Context
import android.content.SharedPreferences

class NavigationPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var voiceGuidanceEnabled: Boolean
        get() = preferences.getBoolean(KEY_VOICE_GUIDANCE_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(KEY_VOICE_GUIDANCE_ENABLED, value).apply()
        }

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val PREFERENCES_NAME = "navigation_preferences"
        const val KEY_VOICE_GUIDANCE_ENABLED = "voice_guidance_enabled"
    }
}
