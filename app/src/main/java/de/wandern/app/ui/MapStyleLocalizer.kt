package de.wandern.app.ui

import de.wandern.app.localization.AppLanguage
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.SymbolLayer

object MapStyleLocalizer {
    fun localize(style: Style, language: AppLanguage) {
        style.layers.forEach { localizeLayer(it, language) }
    }

    fun localizeKnownLayers(layerLookup: (String) -> Layer?, language: AppLanguage) {
        LOCALIZABLE_LAYER_IDS.forEach { id ->
            layerLookup(id)?.let { localizeLayer(it, language, requireNameField = false) }
        }
    }

    internal fun shouldLocalizeTextField(serializedExpression: String): Boolean =
        NAME_FIELD_PATTERN.containsMatchIn(serializedExpression)

    private fun localizeLayer(
        layer: Layer,
        language: AppLanguage,
        requireNameField: Boolean = true,
    ) {
        val symbol = layer as? SymbolLayer ?: return
        val current = symbol.textField
        if (current.isNull) return
        if (requireNameField) {
            val serialized = when {
                current.isExpression -> current.expression?.toString().orEmpty()
                current.isValue -> current.value?.toString().orEmpty()
                else -> ""
            }
            if (!shouldLocalizeTextField(serialized)) return
        }
        symbol.setProperties(textField(localizedNameExpression(language)))
    }

    private fun localizedNameExpression(language: AppLanguage): Expression =
        Expression.coalesce(
            *language.nameProperties.map(Expression::get).toTypedArray(),
        )

    private val NAME_FIELD_PATTERN = Regex("name(?::[a-z]{2}|_[a-z]{2})?|name:latin|name:nonlatin")

    private val LOCALIZABLE_LAYER_IDS = arrayOf(
        "waterway_line_label",
        "water_name_point_label",
        "water_name_line_label",
        "poi_r20",
        "poi_r7",
        "poi_r1",
        "poi_transit",
        "highway-name-path",
        "highway-name-minor",
        "highway-name-major",
        "airport",
        "label_other",
        "label_village",
        "label_town",
        "label_state",
        "label_city",
        "label_city_capital",
        "label_country_3",
        "label_country_2",
        "label_country_1",
    )
}
