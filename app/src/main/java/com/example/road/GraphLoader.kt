package com.example.road

import android.content.Context
import org.json.JSONObject

object GraphLoader {
    fun loadGraph(context: Context, fileName: String = "graph.json"): Pair<List<Node>, List<Edge>> {
        val jsonString = context.assets.open(fileName).bufferedReader().use { it.readText() }
        val root = JSONObject(jsonString)
        val nodesArray = root.getJSONArray("nodes")
        val edgesArray = root.getJSONArray("edges")

        val nodes = mutableListOf<Node>()
        val nodeMap = mutableMapOf<String, Node>()

        for (i in 0 until nodesArray.length()) {
            val obj = nodesArray.getJSONObject(i)
            val id = obj.getString("id")
            val lat = obj.getDouble("lat")
            val lon = obj.getDouble("lon")
            val node = Node(id, lat, lon)
            nodes.add(node)
            nodeMap[id] = node
        }

        val edges = mutableListOf<Edge>()
        for (i in 0 until edgesArray.length()) {
            val obj = edgesArray.getJSONObject(i)
            val fromId = obj.getString("from")
            val toId = obj.getString("to")
            val weight = obj.getDouble("weight")
            val from = nodeMap[fromId] ?: continue
            val to = nodeMap[toId] ?: continue
            edges.add(Edge(from, to, weight))
        }

        return Pair(nodes, edges)
    }
}