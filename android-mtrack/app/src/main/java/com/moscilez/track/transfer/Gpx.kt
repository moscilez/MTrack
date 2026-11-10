package com.moscilez.track.transfer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.Writer
import java.time.Instant

data class GpxPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val time: Long,
    val elapsedMs: Long?,
    val speed: Float?,
    val accuracy: Float?,
    val bearing: Float?,
    val verticalAccuracy: Float?,
    val segment: Int,
)

/** Streaming GPX 1.1 writer with MTrack extensions for sensor fields. */
class GpxWriter(private val out: Writer) {
    private var openSegment: Int? = null

    fun begin(name: String, type: String, startTime: Long) {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.write("<gpx version=\"1.1\" creator=\"MTrack\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:mt=\"$NS\">\n")
        out.write("<metadata><name>${esc(name)}</name><time>${Instant.ofEpochMilli(startTime)}</time></metadata>\n")
        out.write("<trk><name>${esc(name)}</name><type>${esc(type)}</type>\n")
    }

    fun point(p: GpxPoint) {
        if (openSegment != p.segment) {
            if (openSegment != null) out.write("</trkseg>\n")
            out.write("<trkseg>\n")
            openSegment = p.segment
        }
        out.write("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">")
        p.ele?.let { out.write("<ele>$it</ele>") }
        out.write("<time>${Instant.ofEpochMilli(p.time)}</time>")
        out.write("<extensions>")
        p.elapsedMs?.let { out.write("<mt:el>$it</mt:el>") }
        p.speed?.let { out.write("<mt:speed>$it</mt:speed>") }
        p.accuracy?.let { out.write("<mt:acc>$it</mt:acc>") }
        p.bearing?.let { out.write("<mt:brg>$it</mt:brg>") }
        p.verticalAccuracy?.let { out.write("<mt:vacc>$it</mt:vacc>") }
        out.write("<mt:seg>${p.segment}</mt:seg>")
        out.write("</extensions></trkpt>\n")
    }

    fun end() {
        if (openSegment != null) out.write("</trkseg>\n")
        out.write("</trk>\n</gpx>\n")
        out.flush()
    }

    companion object {
        const val NS = "urn:mtrack:gpx:1"
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}

/** Pull-parser based GPX reader; tolerant of foreign GPX files without MTrack extensions. */
object GpxParser {
    fun parse(input: InputStream, onPoint: (GpxPoint) -> Unit): Int {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        var count = 0
        var segIndex = -1
        var lat = 0.0
        var lon = 0.0
        var inPoint = false
        val fields = HashMap<String, String>()
        var tag: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfter(':')
                    when (name) {
                        "trkseg" -> segIndex++
                        "trkpt" -> {
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                            inPoint = true
                            fields.clear()
                        }
                        else -> if (inPoint) tag = name
                    }
                }
                XmlPullParser.TEXT -> if (inPoint && tag != null) fields[tag!!] = parser.text.trim()
                XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfter(':')
                    if (name == "trkpt") {
                        inPoint = false
                        val time = fields["time"]?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                        if (!lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0 && time != null) {
                            onPoint(
                                GpxPoint(
                                    lat, lon, fields["ele"]?.toDoubleOrNull(), time,
                                    fields["el"]?.toLongOrNull(), fields["speed"]?.toFloatOrNull(),
                                    fields["acc"]?.toFloatOrNull(), fields["brg"]?.toFloatOrNull(),
                                    fields["vacc"]?.toFloatOrNull(), fields["seg"]?.toIntOrNull() ?: segIndex.coerceAtLeast(0),
                                )
                            )
                            count++
                        }
                    }
                    tag = null
                }
            }
            event = parser.next()
        }
        return count
    }
}
