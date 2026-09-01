package de.wandern.app.ui

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.R
import de.wandern.app.databinding.DialogRecordingStartCheckBinding
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingStartDialogColorTest {
    @Test
    fun customStartDialogUsesReadableThemeTextColorsInNightMode() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val nightConfiguration = Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_YES
        }
        val context = base.createConfigurationContext(nightConfiguration).apply {
            setTheme(R.style.Theme_Wandern)
        }
        val binding = DialogRecordingStartCheckBinding.inflate(LayoutInflater.from(context))

        assertTrue(ColorUtils.calculateLuminance(binding.startCheckText.currentTextColor) > 0.5)
        assertTrue(ColorUtils.calculateLuminance(binding.routeEntryMessage.currentTextColor) > 0.5)
        assertTrue(ColorUtils.calculateLuminance(binding.officialStartButton.currentTextColor) > 0.5)
        assertTrue(ColorUtils.calculateLuminance(binding.nearestPointButton.currentTextColor) > 0.5)
        assertTrue(ColorUtils.calculateLuminance(binding.cancelStartButton.currentTextColor) > 0.5)
        assertTrue(ColorUtils.calculateLuminance(binding.confirmStartButton.currentTextColor) > 0.5)
        assertTrue(binding.startDialogActions.isDescendantOf(binding.root))
    }

    private fun android.view.View.isDescendantOf(ancestor: android.view.View): Boolean {
        var current = parent
        while (current is android.view.View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }
}
