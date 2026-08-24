package de.wandern.app.data

import de.wandern.app.model.GpxTrack
import de.wandern.app.model.ActivityType
import de.wandern.app.model.ElevationSource
import de.wandern.app.model.TrackPoint
import java.io.InputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

object GpxCodec {
    fun parse(input: InputStream, fallbackName: String = "Importierte Route"): GpxTrack {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Android's bundled XML implementation does not support every optional
            // DocumentBuilderFactory switch. The security features below are the
            // actual protection; these two hardening hints are best-effort.
            runCatching { isXIncludeAware = false }
            runCatching { setExpandEntityReferences(false) }
            safeFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            safeFeature("http://xml.org/sax/features/external-general-entities", false)
            safeFeature("http://xml.org/sax/features/external-parameter-entities", false)
            safeAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            safeAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val document = factory.newDocumentBuilder().parse(input)
        val name = document.getElementsByTagNameNS("*", "name")
            .item(0)?.textContent?.trim().takeUnless { it.isNullOrBlank() } ?: fallbackName

        val trackSegments = document.getElementsByTagNameNS("*", "trkseg")
        val segments = buildList {
            for (segmentIndex in 0 until trackSegments.length) {
                val nodes = trackSegments.item(segmentIndex).childNodes
                val points = buildList {
                    for (index in 0 until nodes.length) {
                        val element = nodes.item(index)
                        if (element.localName == "trkpt") parsePoint(element)?.let(::add)
                    }
                }
                if (points.isNotEmpty()) add(points)
            }
        }.ifEmpty {
            val routePoints = document.getElementsByTagNameNS("*", "rtept")
            listOf(buildList {
                for (index in 0 until routePoints.length) parsePoint(routePoints.item(index))?.let(::add)
            }).filter { it.isNotEmpty() }
        }

        require(segments.any { it.isNotEmpty() }) { "Die GPX-Datei enthält keine Track- oder Routenpunkte." }
        val elevationSource = document.getElementsByTagNameNS("*", "elevationSource")
            .item(0)?.textContent?.trim()?.let { encoded ->
                runCatching { ElevationSource.valueOf(encoded) }.getOrNull()
            }
        val activityType = document.getElementsByTagNameNS("*", "activityType")
            .item(0)?.textContent?.let(ActivityType::fromGpxValue)
            ?: document.getElementsByTagNameNS("*", "type")
                .item(0)?.textContent?.let(ActivityType::fromGpxValue)
        return GpxTrack(name, segments, elevationSource, activityType)
    }

    fun encode(track: GpxTrack): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Wandern\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        append("xmlns:wandern=\"https://wandern.local/gpx/1\">\n")
        append("  <metadata><name>").append(escape(track.name)).append("</name>")
        if (track.elevationSource != null || track.activityType != null) {
            append("<extensions>")
            track.elevationSource?.let { source ->
                append("<wandern:elevationSource>")
                    .append(source.name)
                    .append("</wandern:elevationSource>")
            }
            track.activityType?.let { type ->
                append("<wandern:activityType>")
                    .append(type.gpxValue)
                    .append("</wandern:activityType>")
            }
            append("</extensions>")
        }
        append("</metadata>\n")
        append("  <trk>\n    <name>").append(escape(track.name)).append("</name>\n")
        track.activityType?.let { append("    <type>").append(it.gpxValue).append("</type>\n") }
        track.segments.filter { it.isNotEmpty() }.forEach { segment ->
            append("    <trkseg>\n")
            segment.forEach { point ->
                append("      <trkpt lat=\"").append(formatCoordinate(point.latitude))
                    .append("\" lon=\"").append(formatCoordinate(point.longitude)).append("\">")
                point.elevationMeters?.let { append("<ele>").append("%.2f".format(java.util.Locale.US, it)).append("</ele>") }
                point.timeMillis?.let { append("<time>").append(Instant.ofEpochMilli(it)).append("</time>") }
                if (point.isInterpolated) {
                    append("<extensions><wandern:interpolated>true</wandern:interpolated></extensions>")
                }
                append("</trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n</gpx>\n")
    }

    private fun parsePoint(node: org.w3c.dom.Node): TrackPoint? {
        val attributes = node.attributes ?: return null
        val latitude = attributes.getNamedItem("lat")?.nodeValue?.toDoubleOrNull() ?: return null
        val longitude = attributes.getNamedItem("lon")?.nodeValue?.toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        var elevation: Double? = null
        var time: Long? = null
        var isInterpolated = false
        val children = node.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            when (child.localName) {
                "ele" -> elevation = child.textContent.trim().toDoubleOrNull()
                "time" -> time = runCatching { Instant.parse(child.textContent.trim()).toEpochMilli() }.getOrNull()
                "extensions" -> {
                    val extensionNodes = child.childNodes
                    for (extensionIndex in 0 until extensionNodes.length) {
                        val extension = extensionNodes.item(extensionIndex)
                        if (extension.localName == "interpolated") {
                            isInterpolated = extension.textContent.trim().equals("true", ignoreCase = true)
                        }
                    }
                }
            }
        }
        return TrackPoint(
            latitude = latitude,
            longitude = longitude,
            elevationMeters = elevation,
            timeMillis = time,
            isInterpolated = isInterpolated,
        )
    }

    private fun DocumentBuilderFactory.safeFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private fun DocumentBuilderFactory.safeAttribute(name: String, value: String) {
        runCatching { setAttribute(name, value) }
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun formatCoordinate(value: Double): String = "%.7f".format(java.util.Locale.US, value)
}
