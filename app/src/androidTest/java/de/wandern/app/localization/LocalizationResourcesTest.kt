package de.wandern.app.localization

import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.wandern.app.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationResourcesTest {
    @Test
    fun germanSystemGetsGermanResources() {
        val context = localizedContext(Locale.GERMANY)

        assertEquals("Meine Touren", context.getString(R.string.my_tours))
        assertEquals("Nürnberg", preferredMapName(context, german = "Nürnberg", english = "Nuremberg"))
    }

    @Test
    fun englishAndUnsupportedSystemsUseEnglishResources() {
        assertEquals("My tours", localizedContext(Locale.ENGLISH).getString(R.string.my_tours))
        assertEquals("My tours", localizedContext(Locale.FRENCH).getString(R.string.my_tours))
    }

    private fun localizedContext(locale: Locale): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = android.content.res.Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        return base.createConfigurationContext(configuration)
    }

    private fun preferredMapName(context: Context, german: String, english: String): String =
        if (AppLanguage.forContext(context) == AppLanguage.GERMAN) german else english
}
