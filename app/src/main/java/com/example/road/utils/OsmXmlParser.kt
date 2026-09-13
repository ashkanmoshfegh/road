package com.example.road.utils

import android.content.Context
import android.util.LongSparseArray
import android.util.Xml
import org.osmdroid.util.GeoPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Streaming parser for raw OpenStreetMap XML (.osm) exports.
 *
 * A full .osm export is a flat, unordered-ish list of <node>, <way>, and
 * <relation> elements. Ways only reference node *ids*, not coordinates, so
 * building a way's geometry needs a lookup from id -> lat/lon. To avoid ever
 * holding the whole file as a DOM (which would OOM on a phone for a
 * city-sized extract), this does two cheap streaming passes instead:
 *
 *   1) read every <node>, remember its lat/lon in a LongSparseArray keyed by id
 *   2) read every <way>, resolve its <nd> refs against that array, and
 *      classify it (road / building / water) from its <tag> values
 *
 * Relations are ignored entirely - for a road-focused map, ways already carry
 * everything that matters (highway geometry, building footprints, water
 * polygons).
 */
object OsmXmlParser {

    data class RoadFeature(
        val points: List<GeoPoint>,
        val highway: String
    )

    data class AreaFeature(
        val points: List<GeoPoint>,
        val kind: String // "building" | "water"
    )

    data class ParseResult(
        val roads: List<RoadFeature>,
        val buildings: List<AreaFeature>,
        val water: List<AreaFeature>
    )

    // Hard ceilings so a big extract can't stall the UI thread or OOM the
    // device once features are turned into overlays. Roads matter most for
    // this app, so they get the largest budget. Raise these if your device
    // can handle it, or - better - pre-trim the .osm file to your area of
    // interest with a tool like osmium before bundling it.
    // Tehran extract has ~148K classified ways. These caps cover all of them
    // with headroom. The streaming parser keeps memory bounded by only storing
    // node coords (1.5M GeoPoints ~ 30MB) plus the feature lists.
    private const val MAX_ROADS = 200_000
    private const val MAX_BUILDINGS = 100_000
    private const val MAX_WATER = 20_000

    /**
     * @param assetFileName name of the .osm file under app/src/main/assets
     */
    fun parseFromAssets(context: Context, assetFileName: String): ParseResult {
        // Pre-size to reduce reallocations on a 1.5M-node extract.
        val nodeCoords = LongSparseArray<GeoPoint>(2_000_000)

        android.util.Log.d("OsmXmlParser", "Opening asset: $assetFileName")
        val nodesStart = System.currentTimeMillis()
        context.assets.open(assetFileName).use { parseNodes(it, nodeCoords) }
        android.util.Log.d("OsmXmlParser",
            "Parsed ${nodeCoords.size()} nodes in ${System.currentTimeMillis() - nodesStart}ms")

        val roads = mutableListOf<RoadFeature>()
        val buildings = mutableListOf<AreaFeature>()
        val water = mutableListOf<AreaFeature>()

        val waysStart = System.currentTimeMillis()
        context.assets.open(assetFileName).use {
            parseWays(it, nodeCoords, roads, buildings, water)
        }
        android.util.Log.d("OsmXmlParser",
            "Parsed ways in ${System.currentTimeMillis() - waysStart}ms: " +
                    "roads=${roads.size} buildings=${buildings.size} water=${water.size}")

        return ParseResult(roads, buildings, water)
    }

    private fun parseNodes(input: InputStream, out: LongSparseArray<GeoPoint>) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "node") {
                val id = parser.getAttributeValue(null, "id")?.toLongOrNull()
                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                if (id != null && lat != null && lon != null) {
                    out.put(id, GeoPoint(lat, lon))
                }
            }
            event = parser.next()
        }
    }

    private fun parseWays(
        input: InputStream,
        nodeCoords: LongSparseArray<GeoPoint>,
        roads: MutableList<RoadFeature>,
        buildings: MutableList<AreaFeature>,
        water: MutableList<AreaFeature>
    ) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var refs: MutableList<Long>? = null
        var tags: MutableMap<String, String>? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "way" -> {
                        refs = mutableListOf()
                        tags = mutableMapOf()
                    }
                    "nd" -> refs?.let { list ->
                        parser.getAttributeValue(null, "ref")?.toLongOrNull()?.let { list.add(it) }
                    }
                    "tag" -> tags?.let { map ->
                        val k = parser.getAttributeValue(null, "k")
                        val v = parser.getAttributeValue(null, "v")
                        if (k != null && v != null) map[k] = v
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "way") {
                    val wayRefs = refs
                    val wayTags = tags
                    if (wayRefs != null && wayTags != null && wayRefs.size >= 2) {
                        classifyAndStore(wayRefs, wayTags, nodeCoords, roads, buildings, water)
                    }
                    refs = null
                    tags = null
                }
            }
            event = parser.next()
        }
    }

    private fun classifyAndStore(
        refs: List<Long>,
        tags: Map<String, String>,
        nodeCoords: LongSparseArray<GeoPoint>,
        roads: MutableList<RoadFeature>,
        buildings: MutableList<AreaFeature>,
        water: MutableList<AreaFeature>
    ) {
        val highway = tags["highway"]
        val isWater = tags["natural"] == "water" || tags.containsKey("waterway") ||
                tags["landuse"] == "reservoir"
        val isBuilding = tags.containsKey("building")

        when {
            highway != null && roads.size < MAX_ROADS -> {
                val points = resolvePoints(refs, nodeCoords) ?: return
                roads.add(RoadFeature(points, highway))
            }
            isWater && water.size < MAX_WATER -> {
                val points = resolvePoints(refs, nodeCoords) ?: return
                if (points.size >= 3) water.add(AreaFeature(points, "water"))
            }
            isBuilding && buildings.size < MAX_BUILDINGS -> {
                val points = resolvePoints(refs, nodeCoords) ?: return
                if (points.size >= 3) buildings.add(AreaFeature(points, "building"))
            }
        }
    }

    private fun resolvePoints(refs: List<Long>, nodeCoords: LongSparseArray<GeoPoint>): List<GeoPoint>? {
        val points = ArrayList<GeoPoint>(refs.size)
        for (ref in refs) {
            nodeCoords.get(ref)?.let { points.add(it) }
        }
        return if (points.size >= 2) points else null
    }
}