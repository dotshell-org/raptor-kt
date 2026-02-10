package io.raptor

import java.io.File
import java.io.FileInputStream

object Benchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val baseDir = args.getOrNull(0) ?: "raptor_data"
        val dir = File(baseDir)
        if (!dir.exists() || !dir.isDirectory) {
            println("[Benchmark] Directory not found: $baseDir")
            println("[Benchmark] Usage: pass the path to your raptor_data directory as first argument")
            return
        }

        // Look for stops.bin / routes.bin, or auto-detect stops_*.bin / routes_*.bin
        var stopsFile = File(dir, "stops.bin")
        var routesFile = File(dir, "routes.bin")
        if (!stopsFile.exists() || !routesFile.exists()) {
            stopsFile = dir.listFiles()?.firstOrNull { it.name.startsWith("stops") && it.name.endsWith(".bin") } ?: stopsFile
            routesFile = dir.listFiles()?.firstOrNull { it.name.startsWith("routes") && it.name.endsWith(".bin") } ?: routesFile
        }
        if (!stopsFile.exists() || !routesFile.exists()) {
            println("[Benchmark] No stops/routes .bin files found in: $baseDir")
            return
        }

        println("=== RAPTOR-KT Performance Benchmark ===")
        println("Data: $baseDir")
        println()

        // Load network
        val loadStart = System.nanoTime()
        val raptor = RaptorLibrary(
            stopsInputStream = FileInputStream(stopsFile),
            routesInputStream = FileInputStream(routesFile)
        )
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000.0
        println("Network loaded in %.1f ms".format(loadMs))
        println()

        // Define test queries (origin name, destination name)
        val queries = listOf(
            "Perrache" to "Vaulx-en-Velin La Soie",
            "Bellecour" to "Part-Dieu",
            "Gare de Vaise" to "Oullins Centre",
            "Perrache" to "Cuire",
        )

        // Resolve stop IDs once
        val resolvedQueries = queries.mapNotNull { (origin, dest) ->
            val originIds = raptor.searchStopsByName(origin).map { it.id }
            val destIds = raptor.searchStopsByName(dest).map { it.id }
            if (originIds.isEmpty() || destIds.isEmpty()) {
                println("SKIP: $origin -> $dest (stops not found)")
                null
            } else {
                Triple(origin to dest, originIds, destIds)
            }
        }

        if (resolvedQueries.isEmpty()) {
            println("No valid queries found. Check your stop names against the loaded data.")
            return
        }

        // Warm up JVM
        println("Warming up JVM...")
        repeat(5) {
            for ((_, originIds, destIds) in resolvedQueries) {
                raptor.getOptimizedPaths(originIds, destIds, 8 * 3600)
            }
        }

        // Benchmark forward routing
        val iterations = 100
        println()
        println("--- Forward Routing (getOptimizedPaths) ---")
        println("($iterations iterations per query)")
        println()

        for ((names, originIds, destIds) in resolvedQueries) {
            val (origin, dest) = names
            val startNano = System.nanoTime()
            repeat(iterations) {
                raptor.getOptimizedPaths(originIds, destIds, 8 * 3600)
            }
            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0
            println("%-45s  %.2f ms avg  (%.0f ms total)".format(
                "$origin -> $dest", elapsedMs / iterations, elapsedMs
            ))
        }

        // Benchmark arrive-by routing
        val arriveByIterations = 10
        println()
        println("--- Arrive-By Routing (getOptimizedPathsArriveBy) ---")
        println("($arriveByIterations iterations per query)")
        println()

        for ((names, originIds, destIds) in resolvedQueries) {
            val (origin, dest) = names
            val startNano = System.nanoTime()
            repeat(arriveByIterations) {
                raptor.getOptimizedPathsArriveBy(originIds, destIds, 9 * 3600)
            }
            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0
            println("%-45s  %.2f ms avg  (%.0f ms total)".format(
                "$origin -> $dest (arrive-by)", elapsedMs / arriveByIterations, elapsedMs
            ))
        }

        // Quick correctness check: display one journey
        println()
        println("--- Correctness Check ---")
        val (names, originIds, destIds) = resolvedQueries.first()
        val journeys = raptor.getOptimizedPaths(originIds, destIds, 8 * 3600)
        println("${names.first} -> ${names.second}: ${journeys.size} Pareto-optimal journey(s)")
        for ((idx, journey) in journeys.withIndex()) {
            val dep = journey.first().departureTime
            val arr = journey.last().arrivalTime
            val legs = journey.size
            val transitLegs = journey.count { !it.isTransfer }
            println("  Option ${idx + 1}: dep=${formatTime(dep)} arr=${formatTime(arr)} legs=$legs transit=$transitLegs")
            for (leg in journey) {
                if (leg.isTransfer) {
                    println("    Transfer: stop${leg.fromStopIndex} -> stop${leg.toStopIndex} (${(leg.arrivalTime - leg.departureTime)}s)")
                } else {
                    println("    ${leg.routeName}: stop${leg.fromStopIndex} -> stop${leg.toStopIndex} dep=${formatTime(leg.departureTime)} arr=${formatTime(leg.arrivalTime)}")
                }
            }
        }
    }

    private fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
