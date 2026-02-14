package io.raptor

import java.io.File
import java.io.FileInputStream

object BenchmarkRTM {
    @JvmStatic
    fun main(args: Array<String>) {
        val baseDir = args.getOrNull(0) ?: "raptor_data_rtm"
        val dir = File(baseDir)
        if (!dir.exists() || !dir.isDirectory) {
            println("[Benchmark RTM] Directory not found: $baseDir")
            return
        }

        var stopsFile = File(dir, "stops.bin")
        var routesFile = File(dir, "routes.bin")
        if (!stopsFile.exists() || !routesFile.exists()) {
            stopsFile = dir.listFiles()?.firstOrNull { it.name.startsWith("stops") && it.name.endsWith(".bin") } ?: stopsFile
            routesFile = dir.listFiles()?.firstOrNull { it.name.startsWith("routes") && it.name.endsWith(".bin") } ?: routesFile
        }
        if (!stopsFile.exists() || !routesFile.exists()) {
            println("[Benchmark RTM] No stops/routes .bin files found in: $baseDir")
            return
        }

        println("=== RAPTOR-KT Performance Benchmark — Marseille (RTM) ===")
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

        // Marseille-specific test queries
        val queries = listOf(
            "Vieux-Port" to "La Rose",                      // M1 end-to-end
            "Castellane" to "Bougainville",                  // M2 end-to-end
            "Gare St Charles" to "Rond-Point du Prado",      // centre -> sud
            "La Timone" to "Joliette",                       // inter-lignes
            "La Rose" to "Castellane",                       // peripherie -> centre
            "Noailles" to "Sainte-Marguerite Dromel",        // traversee sud
            "Bougainville" to "La Fourrag",                  // nord -> est (partial match)
        )

        // Resolve stop IDs
        val resolvedQueries = queries.mapNotNull { (origin, dest) ->
            val originIds = raptor.searchStopsByName(origin).map { it.id }
            val destIds = raptor.searchStopsByName(dest).map { it.id }
            if (originIds.isEmpty() || destIds.isEmpty()) {
                println("SKIP: $origin -> $dest (stops not found)")
                null
            } else {
                println("OK: $origin (${originIds.size} stops) -> $dest (${destIds.size} stops)")
                Triple(origin to dest, originIds, destIds)
            }
        }

        if (resolvedQueries.isEmpty()) {
            println("No valid queries found. Listing some stop names for reference:")
            val sampleNames = network.stops.asSequence()
                .map { it.name }
                .distinct()
                .sorted()
                .take(50)
                .toList()
            println(sampleNames.joinToString("\n") { "  $it" })
            return
        }

        println()

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
        println("--- Forward Routing (getOptimizedPaths, dep 08:00) ---")
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
            println("%-55s  %.2f ms avg  (%.0f ms total)  journeys=%d  hash=%s".format(
                "$origin -> $dest", elapsedMs / iterations, elapsedMs, lastResult.size, hash
            ))
        }

        // Benchmark arrive-by routing
        val arriveByIterations = 10
        println()
        println("--- Arrive-By Routing (getOptimizedPathsArriveBy, arr 09:00) ---")
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
            println("%-55s  %.2f ms avg  (%.0f ms total)  journeys=%d  hash=%s".format(
                "$origin -> $dest (arrive-by)", elapsedMs / arriveByIterations, elapsedMs, lastResult.size, hash
            ))
        }

        // Correctness hashes summary
        println()
        println("--- Correctness Hashes ---")
        println("Forward:   ${forwardHashes.joinToString(" ")}")
        println("Arrive-by: ${arriveByHashes.joinToString(" ")}")

        // Display journeys for correctness check
        println()
        println("--- Correctness Check ---")
        for ((names, originIds, destIds) in resolvedQueries) {
            val (origin, dest) = names
            val journeys = raptor.getOptimizedPaths(originIds, destIds, 8 * 3600)
            println("$origin -> $dest: ${journeys.size} Pareto-optimal journey(s)")
            for ((idx, journey) in journeys.withIndex()) {
                val dep = journey.first().departureTime
                val arr = journey.last().arrivalTime
                val transitLegs = journey.count { !it.isTransfer }
                println("  Option ${idx + 1}: dep=${formatTime(dep)} arr=${formatTime(arr)} transfers=${transitLegs - 1}")
                for (leg in journey) {
                    val fromName = network.stops.getOrNull(leg.fromStopIndex)?.name ?: "stop${leg.fromStopIndex}"
                    val toName = network.stops.getOrNull(leg.toStopIndex)?.name ?: "stop${leg.toStopIndex}"
                    if (leg.isTransfer) {
                        println("    TRANSFER: $fromName -> $toName (${leg.arrivalTime - leg.departureTime}s)")
                    } else {
                        println("    ${leg.routeName}: $fromName -> $toName dep=${formatTime(leg.departureTime)} arr=${formatTime(leg.arrivalTime)}")
                    }
                }
            }
            println()
        }
    }

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
        return "%08x".format(h.toInt())
    }

    private fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
