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

        // --- Memory before load ---
        System.gc()
        Thread.sleep(100)
        val memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        // --- Load network with split timing ---
        val loadStart = System.nanoTime()
        val stops = io.raptor.data.NetworkLoader.loadStops(FileInputStream(stopsFile))
        val routes = io.raptor.data.NetworkLoader.loadRoutes(FileInputStream(routesFile))
        val deserMs = (System.nanoTime() - loadStart) / 1_000_000.0

        val networkStart = System.nanoTime()
        val network = io.raptor.model.Network(stops, routes)
        val networkMs = (System.nanoTime() - networkStart) / 1_000_000.0

        // Build RaptorLibrary using the single-period constructor
        val raptor = RaptorLibrary(
            stopsInputStream = FileInputStream(stopsFile),
            routesInputStream = FileInputStream(routesFile)
        )
        val totalLoadMs = deserMs + networkMs

        // --- Memory after load ---
        System.gc()
        Thread.sleep(100)
        val memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        println("Network: ${stops.size} stops, ${routes.size} routes, ${routes.sumOf { it.tripCount }} trips")
        println("Load time: %.1f ms total (deserialization: %.1f ms, network build: %.1f ms)".format(totalLoadMs, deserMs, networkMs))
        println("Memory delta: %.2f MB".format((memAfter - memBefore) / (1024.0 * 1024.0)))
        println()

        // Define test queries
        val queries = listOf(
            "Perrache" to "Vaulx-en-Velin La Soie",
            "Bellecour" to "Part-Dieu",
            "Gare de Vaise" to "Oullins Centre",
            "Perrache" to "Cuire",
            "Laurent Bonnevay" to "Gorge de Loup",  // peripherie-peripherie
            "Part-Dieu" to "Bellecour", // large origin stop set (many stops match)
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

        val forwardHashes = mutableListOf<String>()
        for ((names, originIds, destIds) in resolvedQueries) {
            val (origin, dest) = names
            val startNano = System.nanoTime()
            var lastResult: List<List<io.raptor.core.JourneyLeg>> = emptyList()
            repeat(iterations) {
                lastResult = raptor.getOptimizedPaths(originIds, destIds, 8 * 3600)
            }
            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0
            val hash = journeyHash(lastResult)
            forwardHashes.add(hash)
            println("%-50s  %.2f ms avg  (%.0f ms total)  hash=%s".format(
                "$origin -> $dest", elapsedMs / iterations, elapsedMs, hash
            ))
        }

        // Benchmark arrive-by routing
        val arriveByIterations = 10
        println()
        println("--- Arrive-By Routing (getOptimizedPathsArriveBy) ---")
        println("($arriveByIterations iterations per query)")
        println()

        val arriveByHashes = mutableListOf<String>()
        for ((names, originIds, destIds) in resolvedQueries) {
            val (origin, dest) = names
            val startNano = System.nanoTime()
            var lastResult: List<List<io.raptor.core.JourneyLeg>> = emptyList()
            repeat(arriveByIterations) {
                lastResult = raptor.getOptimizedPathsArriveBy(originIds, destIds, 9 * 3600)
            }
            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0
            val hash = journeyHash(lastResult)
            arriveByHashes.add(hash)
            println("%-50s  %.2f ms avg  (%.0f ms total)  hash=%s".format(
                "$origin -> $dest (arrive-by)", elapsedMs / arriveByIterations, elapsedMs, hash
            ))
        }

        // Correctness hashes summary
        println()
        println("--- Correctness Hashes ---")
        println("Forward:   ${forwardHashes.joinToString(" ")}")
        println("Arrive-by: ${arriveByHashes.joinToString(" ")}")

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

    /**
     * Compute a deterministic hash of journey results for regression detection.
     * Any change in routing output will change this hash.
     */
    private fun journeyHash(journeys: List<List<io.raptor.core.JourneyLeg>>): String {
        var h = 17L
        for (journey in journeys) {
            h = h * 31 + journey.size
            for (leg in journey) {
                h = h * 31 + leg.fromStopIndex
                h = h * 31 + leg.toStopIndex
                h = h * 31 + leg.departureTime
                h = h * 31 + leg.arrivalTime
                h = h * 31 + (if (leg.isTransfer) 1 else 0)
                h = h * 31 + (leg.routeName?.hashCode()?.toLong() ?: 0L)
            }
        }
        // Return as compact hex string
        return "%08x".format(h.toInt())
    }

    private fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
