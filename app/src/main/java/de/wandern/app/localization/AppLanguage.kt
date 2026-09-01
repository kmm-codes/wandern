package de.wandern.app.localization

import android.content.Context
import java.util.Locale

enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    GERMAN("de");

    val nameProperties: List<String>
        get() = when (this) {
            GERMAN -> listOf("name:de", "name_de", "name", "name_en")
            ENGLISH -> listOf("name:en", "name_en", "name", "name:latin")
        }

    val locale: Locale
        get() = if (this == GERMAN) Locale.GERMANY else Locale.ENGLISH

    companion object {
        fun forLocale(locale: Locale): AppLanguage =
            if (locale.language.equals(GERMAN.tag, ignoreCase = true)) GERMAN else ENGLISH

        fun forContext(context: Context): AppLanguage {
            val locales = context.resources.configuration.locales
            return forLocale(if (locales.isEmpty) Locale.ENGLISH else locales[0])
        }

        fun forSystem(): AppLanguage = forLocale(Locale.getDefault())
    }
}

fun localizedSystemText(english: String, german: String): String =
    if (AppLanguage.forSystem() == AppLanguage.GERMAN) german else english
