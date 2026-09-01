package de.wandern.app.localization

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun germanSystemsUseGerman() {
        assertEquals(AppLanguage.GERMAN, AppLanguage.forLocale(Locale.GERMANY))
        assertEquals(AppLanguage.GERMAN, AppLanguage.forLocale(Locale("de", "AT")))
    }

    @Test
    fun englishIsTheFallbackForEveryOtherLanguage() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.forLocale(Locale.ENGLISH))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.forLocale(Locale.FRENCH))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.forLocale(Locale.JAPANESE))
    }
}
