package de.wandern.app.data

import android.content.Context
import de.wandern.app.model.GpxTrack
import de.wandern.app.model.OfflineMapPlan
import de.wandern.app.model.OfflineMapPlanner
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.security.MessageDigest
import java.util.Locale

class OfflineMapDownloader(context: Context, private val styleUrl: String) {
    private val manager = OfflineManager.getInstance(context.applicationContext)
    private val retainedRegions = mutableMapOf<String, OfflineRegion>()

    fun download(track: GpxTrack, onState: (OfflineMapDownloadState) -> Unit) {
        val plan = runCatching { OfflineMapPlanner.plan(track) }.getOrElse {
            onState(OfflineMapDownloadState.Error(it.localizedMessage ?: "Offline-Bereich ist ungültig."))
            return
        }
        val routeId = fingerprint(track)
        onState(OfflineMapDownloadState.Planning(plan))

        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val existing = offlineRegions.orEmpty().firstOrNull { region -> metadataRouteId(region.metadata) == routeId }
                if (existing != null) {
                    observeAndStart(routeId, existing, onState)
                } else {
                    createRegion(routeId, track, plan, onState)
                }
            }

            override fun onError(error: String) {
                onState(OfflineMapDownloadState.Error("Offline-Karten konnten nicht geprüft werden: $error"))
            }
        })
    }

    fun status(track: GpxTrack, onResult: (OfflineMapStatus) -> Unit) {
        val routeId = fingerprint(track)
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val region = offlineRegions.orEmpty()
                    .firstOrNull { metadataRouteId(it.metadata) == routeId }
                if (region == null) {
                    onResult(OfflineMapStatus(OfflineMapAvailability.NOT_DOWNLOADED))
                    return
                }
                region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) {
                        if (status == null) {
                            onResult(OfflineMapStatus(OfflineMapAvailability.ERROR, message = "Status ist leer."))
                        } else {
                            onResult(
                                OfflineMapStatus(
                                    availability = if (status.isComplete) {
                                        OfflineMapAvailability.DOWNLOADED
                                    } else {
                                        OfflineMapAvailability.PARTIAL
                                    },
                                    downloadedBytes = status.completedResourceSize,
                                ),
                            )
                        }
                    }

                    override fun onError(error: String?) {
                        onResult(
                            OfflineMapStatus(
                                OfflineMapAvailability.ERROR,
                                message = error ?: "Offline-Status konnte nicht gelesen werden.",
                            ),
                        )
                    }
                })
            }

            override fun onError(error: String) {
                onResult(OfflineMapStatus(OfflineMapAvailability.ERROR, message = error))
            }
        })
    }

    fun delete(track: GpxTrack, onResult: (Result<Unit>) -> Unit) {
        val routeId = fingerprint(track)
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val region = offlineRegions.orEmpty()
                    .firstOrNull { metadataRouteId(it.metadata) == routeId }
                if (region == null) {
                    onResult(Result.success(Unit))
                    return
                }
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                retainedRegions.remove(routeId)
                region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = onResult(Result.success(Unit))

                    override fun onError(error: String) {
                        onResult(Result.failure(IllegalStateException(error)))
                    }
                })
            }

            override fun onError(error: String) {
                onResult(Result.failure(IllegalStateException(error)))
            }
        })
    }

    private fun createRegion(
        routeId: String,
        track: GpxTrack,
        plan: OfflineMapPlan,
        onState: (OfflineMapDownloadState) -> Unit,
    ) {
        val bounds = plan.bounds
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west),
            plan.minZoom.toDouble(),
            plan.maxZoom.toDouble(),
            2f,
            false,
        )
        val metadata = JSONObject()
            .put("schema", 1)
            .put("routeId", routeId)
            .put("name", track.name)
            .put("createdAt", System.currentTimeMillis())
            .put("north", bounds.north)
            .put("east", bounds.east)
            .put("south", bounds.south)
            .put("west", bounds.west)
            .put("minZoom", plan.minZoom)
            .put("maxZoom", plan.maxZoom)
            .toString()
            .toByteArray(Charsets.UTF_8)

        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    observeAndStart(routeId, offlineRegion, onState)
                }

                override fun onError(error: String) {
                    onState(OfflineMapDownloadState.Error("Offline-Karte konnte nicht angelegt werden: $error"))
                }
            },
        )
    }

    private fun observeAndStart(
        routeId: String,
        region: OfflineRegion,
        onState: (OfflineMapDownloadState) -> Unit,
    ) {
        retainedRegions[routeId] = region
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.isComplete) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    onState(OfflineMapDownloadState.Complete(status.completedResourceSize))
                    retainedRegions.remove(routeId)
                } else {
                    onState(
                        OfflineMapDownloadState.Progress(
                            completedResources = status.completedResourceCount,
                            requiredResources = status.requiredResourceCount.takeIf { it > 0 },
                            downloadedBytes = status.completedResourceSize,
                        ),
                    )
                }
            }

            override fun onError(error: OfflineRegionError) {
                onState(OfflineMapDownloadState.Error("Kartendownload: ${error.message}"))
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                onState(OfflineMapDownloadState.Error("Kartendownload überschreitet das Limit von $limit Kacheln."))
            }
        })
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) {
                if (status == null) {
                    onState(OfflineMapDownloadState.Error("Offline-Status ist leer."))
                    return
                }
                if (status.isComplete) {
                    onState(OfflineMapDownloadState.Complete(status.completedResourceSize))
                    retainedRegions.remove(routeId)
                } else {
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }
            }

            override fun onError(error: String?) {
                onState(OfflineMapDownloadState.Error("Offline-Status konnte nicht gelesen werden: ${error ?: "unbekannter Fehler"}"))
            }
        })
    }

    private fun fingerprint(track: GpxTrack): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(track.name.toByteArray(Charsets.UTF_8))
        track.segments.forEach { segment ->
            digest.update(0.toByte())
            segment.forEach { point ->
                val coordinate = String.format(Locale.US, "%.7f,%.7f;", point.latitude, point.longitude)
                digest.update(coordinate.toByteArray(Charsets.UTF_8))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun metadataRouteId(metadata: ByteArray): String? = runCatching {
        JSONObject(metadata.toString(Charsets.UTF_8)).optString("routeId").takeIf { it.isNotBlank() }
    }.getOrNull()
}

sealed interface OfflineMapDownloadState {
    data class Planning(val plan: OfflineMapPlan) : OfflineMapDownloadState
    data class Progress(
        val completedResources: Long,
        val requiredResources: Long?,
        val downloadedBytes: Long,
    ) : OfflineMapDownloadState {
        val percent: Int? = requiredResources?.takeIf { it > 0 }?.let {
            (completedResources * 100 / it).toInt().coerceIn(0, 99)
        }
    }
    data class Complete(val downloadedBytes: Long) : OfflineMapDownloadState
    data class Error(val message: String) : OfflineMapDownloadState
}

enum class OfflineMapAvailability { CHECKING, NOT_DOWNLOADED, PARTIAL, DOWNLOADED, ERROR }

data class OfflineMapStatus(
    val availability: OfflineMapAvailability = OfflineMapAvailability.CHECKING,
    val downloadedBytes: Long = 0L,
    val message: String? = null,
)
