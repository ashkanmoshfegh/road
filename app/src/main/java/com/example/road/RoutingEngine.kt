package com.example.road

import java.util.PriorityQueue
import kotlin.math.sqrt
import kotlin.math.pow

data class Node(val id: String, val lat: Double, val lon: Double)
data class Edge(val from: Node, val to: Node, val weight: Double)

class RoutingEngine {
    private val graph = mutableMapOf<String, MutableList<Edge>>()
    private val nodes = mutableMapOf<String, Node>()

    fun addNode(node: Node) {
        nodes[node.id] = node
    }

    fun addEdge(fromId: String, toId: String, weight: Double) {
        val from = nodes[fromId] ?: return
        val to = nodes[toId] ?: return
        val edge = Edge(from, to, weight)
        graph.getOrPut(fromId) { mutableListOf() }.add(edge)
    }

    /**
     * Dijkstra's Algorithm for offline routing.
     */
    fun findPathDijkstra(startId: String, endId: String): List<Node> {
        val distances = mutableMapOf<String, Double>().withDefault { Double.MAX_VALUE }
        val previous = mutableMapOf<String, String?>()
        val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second })

        distances[startId] = 0.0
        queue.add(startId to 0.0)

        while (queue.isNotEmpty()) {
            val pollResult = queue.poll() ?: break
            val u = pollResult.first
            val dist = pollResult.second

            if (u == endId) break
            if (dist > (distances[u] ?: Double.MAX_VALUE)) continue

            graph[u]?.forEach { edge ->
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
                nodes[curr]?.let { path.add(0, it) }
                curr = previous[curr]
            }
        }
        return path
    }

    /**
     * A* Algorithm (Heuristic based optimization).
     */
    fun findPathAStar(startId: String, endId: String): List<Node> {
        val target = nodes[endId] ?: return emptyList()
        val distances = mutableMapOf<String, Double>().withDefault { Double.MAX_VALUE }
        val previous = mutableMapOf<String, String?>()
        val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second })

        distances[startId] = 0.0
        queue.add(startId to 0.0)

        while (queue.isNotEmpty()) {
            val pollResult = queue.poll() ?: break
            val u = pollResult.first

            if (u == endId) break

            graph[u]?.forEach { edge ->
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
                nodes[curr]?.let { path.add(0, it) }
                curr = previous[curr]
            }
        }
        return path
    }

    private fun heuristic(a: Node, b: Node): Double {
        // Euclidean distance as a simple heuristic for A*
        return sqrt((a.lat - b.lat).pow(2.0) + (a.lon - b.lon).pow(2.0))
    }
}
