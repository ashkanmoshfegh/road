package com.example.road

import java.util.PriorityQueue
import kotlin.math.sqrt
import kotlin.math.pow

class RoutingEngine(private val nodes: List<Node>, private val edges: List<Edge>) {
    private val nodeMap = nodes.associateBy { it.id }
    private val adjacency = mutableMapOf<String, MutableList<Edge>>()

    init {
        edges.forEach { edge ->
            adjacency.getOrPut(edge.from.id) { mutableListOf() }.add(edge)
        }
    }

    fun findPathDijkstra(startId: String, endId: String): List<Node> {
        val distances = mutableMapOf<String, Double>().withDefault { Double.MAX_VALUE }
        val previous = mutableMapOf<String, String?>()
        val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second })

        distances[startId] = 0.0
        queue.add(startId to 0.0)

        while (queue.isNotEmpty()) {
            val (u, dist) = queue.poll()
            if (u == endId) break
            if (dist > (distances[u] ?: Double.MAX_VALUE)) continue

            adjacency[u]?.forEach { edge ->
                val v = edge.to.id
                val alt = dist + edge.weight
                if (alt < (distances[v] ?: Double.MAX_VALUE)) {
                    distances[v] = alt
                    previous[v] = u
                    queue.add(v to alt)
                }
            }
        }

        val path = mutableListOf<Node>()
        var curr: String? = endId
        if (previous.containsKey(endId) || startId == endId) {
            while (curr != null) {
                nodeMap[curr]?.let { path.add(0, it) }
                curr = previous[curr]
            }
        }
        return path
    }

    fun findPathAStar(startId: String, endId: String): List<Node> {
        val target = nodeMap[endId] ?: return emptyList()
        val distances = mutableMapOf<String, Double>().withDefault { Double.MAX_VALUE }
        val previous = mutableMapOf<String, String?>()
        val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second })

        distances[startId] = 0.0
        queue.add(startId to 0.0)

        while (queue.isNotEmpty()) {
            val (u, _) = queue.poll()
            if (u == endId) break

            adjacency[u]?.forEach { edge ->
                val v = edge.to.id
                val gScore = (distances[u] ?: Double.MAX_VALUE) + edge.weight
                if (gScore < (distances[v] ?: Double.MAX_VALUE)) {
                    distances[v] = gScore
                    val fScore = gScore + heuristic(edge.to, target)
                    previous[v] = u
                    queue.add(v to fScore)
                }
            }
        }

        val path = mutableListOf<Node>()
        var curr: String? = endId
        if (previous.containsKey(endId) || startId == endId) {
            while (curr != null) {
                nodeMap[curr]?.let { path.add(0, it) }
                curr = previous[curr]
            }
        }
        return path
    }

    private fun heuristic(a: Node, b: Node): Double {
        return sqrt((a.lat - b.lat).pow(2.0) + (a.lon - b.lon).pow(2.0))
    }

    fun getNodeById(id: String): Node? = nodeMap[id]
}