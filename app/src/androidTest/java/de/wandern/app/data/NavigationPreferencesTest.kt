package de.wandern.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = NavigationPreferences(context)

    @Before
    fun resetStoredValue() = clearStoredValue()

    @After
    fun cleanUp() = clearStoredValue()

    @Test
    fun voiceGuidanceIsEnabledByDefault() {
        assertTrue(preferences.voiceGuidanceEnabled)
    }

    @Test
    fun persistsDisabledAndReEnabledVoiceGuidance() {
        preferences.voiceGuidanceEnabled = false
        assertFalse(NavigationPreferences(context).voiceGuidanceEnabled)

        preferences.voiceGuidanceEnabled = true
        assertTrue(NavigationPreferences(context).voiceGuidanceEnabled)
    }

    private fun clearStoredValue() {
        context
            .getSharedPreferences(NavigationPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
