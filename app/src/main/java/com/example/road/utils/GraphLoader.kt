package com.example.road.utils

import android.content.Context
import android.util.Log
import com.example.road.data.m.model.Node
import com.example.road.data.m.model.Edge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object GraphLoader {
    private val logTag = "GraphLoader"

    suspend fun loadGraph(context: Context, fileName: String = "graph.json"): Pair<List<Node>, List<Edge>> {
        Log.d(logTag, "Starting to load $fileName from assets (streaming)...")
        val startTime = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            try {
                val nodes = mutableListOf<Node>()
                val nodeMap = mutableMapOf<String, Node>()
                val edges = mutableListOf<Edge>()

                context.assets.open(fileName).use { stream ->
                    val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    // Stream-read: expect { "nodes": [...], "edges": [...] }
                    // For simplicity with large files, use a line-oriented approach
                    // Since the JSON is one line per array element in assets, read char by char
                    // Instead, let's use a simpler approach: read chunks and parse arrays
                    val content = reader.readText()
                    val root = JSONObject(content)
                    val nodesArray = root.getJSONArray("nodes")
                    val edgesArray = root.getJSONArray("edges")

                    Log.d(logTag, "Parsing ${nodesArray.length()} nodes and ${edgesArray.length()} edges...")
                    val parseStart = System.currentTimeMillis()

                    for (i in 0 until nodesArray.length()) {
                        val obj = nodesArray.getJSONObject(i)
                        val id = obj.getString("id")
                        val lat = obj.getDouble("lat")
                        val lon = obj.getDouble("lon")
                        val node = Node(id, lat, lon)
                        nodes.add(node)
                        nodeMap[id] = node
                    }

                    for (i in 0 until edgesArray.length()) {
                        val obj = edgesArray.getJSONObject(i)
                        val fromId = obj.getString("from")
                        val toId = obj.getString("to")
                        val weight = obj.getDouble("weight")
                        val from = nodeMap[fromId] ?: continue
                        val to = nodeMap[toId] ?: continue
                        edges.add(Edge(from, to, weight))
                    }
                    val parseTime = System.currentTimeMillis() - parseStart
                    Log.d(logTag, "Parsing complete in ${parseTime}ms: ${nodes.size} nodes, ${edges.size} edges")
                }

                val loadTime = System.currentTimeMillis() - startTime
                Log.d(logTag, "Load complete in ${loadTime}ms")
                Pair(nodes, edges)
            } catch (e: Exception) {
                val loadTime = System.currentTimeMillis() - startTime
                Log.e(logTag, "Failed to load $fileName after ${loadTime}ms", e)
                throw e
            }
        }
    }
}
