package de.wandern.app.ui

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Places route, detour and closure lines directly below the map's labels so street names stay
 * readable underneath them. Markers, position circles and direction arrows are still added on
 * top of the style and therefore stay above the labels.
 */
object MapLayerOrder {
    /**
     * Id of the style's first label layer.
     *
     * The first symbol layer of the map style is not necessarily a label: in the Liberty style it
     * is the one-way arrow layer, which sits below bridges and buildings. Inserting lines there
     * would hide them under roads, so the first symbol layer that actually carries text wins.
     */
    fun firstLabelLayerId(style: Style): String? {
        val symbolLayers = style.layers.filterIsInstance<SymbolLayer>()
        return symbolLayers.firstOrNull { !it.textField.isNull }?.id ?: symbolLayers.firstOrNull()?.id
    }

    /** Adds [layer] below [belowLayerId], or on top when the style has no label layer. */
    fun addLayerBelowLabels(style: Style, layer: Layer, belowLayerId: String?) {
        if (belowLayerId == null) style.addLayer(layer) else style.addLayerBelow(layer, belowLayerId)
    }
}
