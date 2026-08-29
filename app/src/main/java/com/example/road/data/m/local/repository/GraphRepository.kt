package com.example.road.data.m.local.repository

import android.content.Context
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.example.road.data.m.model.Node
import com.example.road.data.m.model.Edge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GraphRepository @Inject constructor(private val context: Context) {

    private var graphHopper: GraphHopper? = null

    suspend fun loadGraph(): GraphHopper = withContext(Dispatchers.IO) {
        graphHopper?.let { return@withContext it }

        val osmFile = File(context.filesDir, "tehran.osm.pbf")
        val graphCacheDir = File(context.filesDir, "graph-cache")

        if (!osmFile.exists()) {
            throw IllegalStateException("OSM file not found. Place it in internal storage or assets.")
        }

        val gh = GraphHopper()
        gh.setOSMFile(osmFile.absolutePath)
        gh.setGraphHopperLocation(graphCacheDir.absolutePath)
        // EncodingManager is automatically handled via profiles in 7.0
        gh.setProfiles(listOf(Profile("car").setVehicle("car").setWeighting("fastest")))
        gh.getCHPreparationHandler().setCHProfiles(listOf(CHProfile("car")))
        gh.importOrLoad()

        graphHopper = gh
        return@withContext gh
    }

    fun getGraph(): GraphHopper? = graphHopper

    fun getNodesAndEdges(): Pair<List<Node>, List<Edge>> {
        val gh = graphHopper ?: throw IllegalStateException("Graph not loaded")
        val graph = gh.graphHopperStorage
        val allNodes = mutableListOf<Node>()
        val allEdges = mutableListOf<Edge>()

        for (nodeId in 0 until graph.nodes) {
            val node = graph.getNode(nodeId)
            allNodes.add(Node("$nodeId", node.lat, node.lon))
        }

        for (edgeId in 0 until graph.edges) {
            val edge = graph.getEdge(edgeId)
            val fromNode = graph.getNode(edge.baseNode)
            val toNode = graph.getNode(edge.adjNode)
            val from = Node("${edge.baseNode}", fromNode.lat, fromNode.lon)
            val to = Node("${edge.adjNode}", toNode.lat, toNode.lon)
            allEdges.add(Edge(from, to, edge.distance))
        }

        return Pair(allNodes, allEdges)
    }
}