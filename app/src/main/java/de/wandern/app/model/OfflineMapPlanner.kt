package de.wandern.app.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

data class GeoBounds(
    val north: Double,
    val east: Double,
    val south: Double,
    val west: Double,
)

data class OfflineMapPlan(
    val bounds: GeoBounds,
    val minZoom: Int,
    val maxZoom: Int,
    val estimatedTileCount: Long,
)

object OfflineMapPlanner {
    const val DEFAULT_PADDING_METERS = 1_500.0
    const val DEFAULT_TILE_LIMIT = 4_500L
    private const val MIN_ZOOM = 8
    private const val PREFERRED_MAX_ZOOM = 16
    private const val METERS_PER_LATITUDE_DEGREE = 111_320.0
    private const val MAX_MERCATOR_LATITUDE = 85.05112878

    fun plan(
        track: GpxTrack,
        paddingMeters: Double = DEFAULT_PADDING_METERS,
        tileLimit: Long = DEFAULT_TILE_LIMIT,
    ): OfflineMapPlan {
        require(track.points.isNotEmpty()) { "Für eine leere Route kann keine Offline-Karte erstellt werden." }
        require(paddingMeters >= 0.0) { "Der Kartenpuffer darf nicht negativ sein." }
        require(tileLimit > 0) { "Das Kachellimit muss positiv sein." }

        val latitudes = track.points.map { it.latitude }
        val longitudes = track.points.map { it.longitude }
        val centerLatitude = (latitudes.min() + latitudes.max()) / 2.0
        val latitudePadding = paddingMeters / METERS_PER_LATITUDE_DEGREE
        val longitudeScale = cos(Math.toRadians(centerLatitude)).coerceAtLeast(0.05)
        val longitudePadding = paddingMeters / (METERS_PER_LATITUDE_DEGREE * longitudeScale)
        val bounds = GeoBounds(
            north = (latitudes.max() + latitudePadding).coerceAtMost(MAX_MERCATOR_LATITUDE),
            east = (longitudes.max() + longitudePadding).coerceAtMost(180.0),
            south = (latitudes.min() - latitudePadding).coerceAtLeast(-MAX_MERCATOR_LATITUDE),
            west = (longitudes.min() - longitudePadding).coerceAtLeast(-180.0),
        )
        require(bounds.east - bounds.west < 180.0) {
            "Routen über den 180. Längengrad werden für Offline-Karten noch nicht unterstützt."
        }

        var maxZoom = PREFERRED_MAX_ZOOM
        var tileCount = estimateTileCount(bounds, MIN_ZOOM, maxZoom)
        while (maxZoom > MIN_ZOOM && tileCount > tileLimit) {
            maxZoom--
            tileCount = estimateTileCount(bounds, MIN_ZOOM, maxZoom)
        }
        require(tileCount <= tileLimit) {
            "Der GPX-Bereich ist selbst in niedriger Detailstufe zu groß für den automatischen Download."
        }
        return OfflineMapPlan(bounds, MIN_ZOOM, maxZoom, tileCount)
    }

    internal fun estimateTileCount(bounds: GeoBounds, minZoom: Int, maxZoom: Int): Long =
        (minZoom..maxZoom).sumOf { zoom ->
            val tileScale = 2.0.pow(zoom).toLong()
            val westTile = floor(longitudeToTileX(bounds.west, zoom)).toLong().coerceIn(0, tileScale - 1)
            val eastTile = floor(longitudeToTileX(bounds.east, zoom)).toLong().coerceIn(0, tileScale - 1)
            val northTile = floor(latitudeToTileY(bounds.north, zoom)).toLong().coerceIn(0, tileScale - 1)
            val southTile = floor(latitudeToTileY(bounds.south, zoom)).toLong().coerceIn(0, tileScale - 1)
            (eastTile - westTile + 1).coerceAtLeast(1) * (southTile - northTile + 1).coerceAtLeast(1)
        }

    private fun longitudeToTileX(longitude: Double, zoom: Int): Double =
        (longitude + 180.0) / 360.0 * 2.0.pow(zoom)

    private fun latitudeToTileY(latitude: Double, zoom: Int): Double {
        val radians = Math.toRadians(latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE))
        return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * 2.0.pow(zoom)
    }
}

