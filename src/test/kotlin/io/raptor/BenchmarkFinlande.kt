package io.raptor

import java.io.File
import java.io.FileInputStream

object BenchmarkFinlande {
    // Extended maxRounds for long-distance intercity queries (Utsjoki needs many transfers)
    private const val LONG_DISTANCE_MAX_ROUNDS = 15
    // 24h search window for arrive-by on long-distance queries
    private const val LONG_DISTANCE_SEARCH_WINDOW_MIN = 1440

    @JvmStatic
    fun main(args: Array<String>) {
        val baseDir = args.getOrNull(0) ?: "raptor_data_finlande"
        val dir = File(baseDir)
        if (!dir.exists() || !dir.isDirectory) {
            println("[Benchmark Finlande] Directory not found: $baseDir")
            return
        }

        var stopsFile = File(dir, "stops.bin")
        var routesFile = File(dir, "routes.bin")
        if (!stopsFile.exists() || !routesFile.exists()) {
            stopsFile = dir.listFiles()?.firstOrNull { it.name.startsWith("stops") && it.name.endsWith(".bin") } ?: stopsFile
            routesFile = dir.listFiles()?.firstOrNull { it.name.startsWith("routes") && it.name.endsWith(".bin") } ?: routesFile
        }
        if (!stopsFile.exists() || !routesFile.exists()) {
            println("[Benchmark Finlande] No stops/routes .bin files found in: $baseDir")
            return
        }

        println("=== RAPTOR-KT Performance Benchmark — Finland ===")
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

        // Finland test queries — mix of Helsinki urban, suburban, and intercity
        // Long-distance queries (marked true) use extended maxRounds and search window
        data class Query(val origin: String, val dest: String, val longDistance: Boolean = false)

        val queries = listOf(
            Query("Rautatientori", "Mellunm"),                   // Helsinki metro east
            Query("Kamppi", "Tapiola"),                           // Helsinki -> Espoo metro west
            Query("Pasila", "Tikkurila"),                         // Helsinki -> Vantaa rail
            Query("Herttoniemi", "Kontula"),                      // Helsinki metro suburban
            Query("Kalasatama", "Matinkyl"),                      // Helsinki east -> Espoo west metro
            Query("Helsinki", "Tampere"),                         // intercity rail ~180km
            Query("Helsinki", "Turku"),                           // intercity rail ~170km
            Query("Rovaniemi", "Helsinki", longDistance = true),  // Lapland -> Helsinki ~820km
            Query("Nuorgam", "Helsinki", longDistance = true),    // Northernmost EU village -> Helsinki ~1300km
            Query("Utsjoki", "Helsinki", longDistance = true),    // Isolated stop (no transfers in GTFS) -> 0 journeys expected
        )

        // Resolve stop IDs
        data class ResolvedQuery(
            val origin: String,
            val dest: String,
            val originIds: List<Int>,
            val destIds: List<Int>,
            val longDistance: Boolean
        )

        val resolvedQueries = queries.mapNotNull { q ->
            val originIds = raptor.searchStopsByName(q.origin).map { it.id }
            val destIds = raptor.searchStopsByName(q.dest).map { it.id }
            if (originIds.isEmpty() || destIds.isEmpty()) {
                println("SKIP: ${q.origin} -> ${q.dest} (stops not found)")
                null
            } else {
                println("OK: ${q.origin} (${originIds.size} stops) -> ${q.dest} (${destIds.size} stops)" +
                    if (q.longDistance) " [LONG DISTANCE: maxRounds=$LONG_DISTANCE_MAX_ROUNDS, window=${LONG_DISTANCE_SEARCH_WINDOW_MIN}min]" else "")
                ResolvedQuery(q.origin, q.dest, originIds, destIds, q.longDistance)
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
        repeat(3) {
            for (rq in resolvedQueries) {
                val mr = if (rq.longDistance) LONG_DISTANCE_MAX_ROUNDS else 5
                raptor.getOptimizedPaths(rq.originIds, rq.destIds, 8 * 3600, maxRounds = mr)
            }
        }

        // Benchmark forward routing
        val iterations = 50
        val longDistanceIterations = 10  // fewer iterations for long-distance (much slower)
        println()
        println("--- Forward Routing (getOptimizedPaths, dep 08:00) ---")
        println("($iterations iterations per query, $longDistanceIterations for long-distance)")
        println()

        val forwardHashes = mutableListOf<String>()
        for (rq in resolvedQueries) {
            val mr = if (rq.longDistance) LONG_DISTANCE_MAX_ROUNDS else 5
            val iters = if (rq.longDistance) longDistanceIterations else iterations
            val startNano = System.nanoTime()
            var lastResult: List<List<io.raptor.core.JourneyLeg>> = emptyList()
            repeat(iters) {
                lastResult = raptor.getOptimizedPaths(rq.originIds, rq.destIds, 8 * 3600, maxRounds = mr)
            }
            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0
            val hash = journeyHash(lastResult)
            forwardHashes.add(hash)
            val suffix = if (rq.longDistance) " [maxR=$mr]" else ""
            println("%-55s  %.2f ms avg  (%.0f ms total)  journeys=%d  hash=%s".format(
                "${rq.origin} -> ${rq.dest}$suffix", elapsedMs / iters, elapsedMs, lastResult.size, hash
            ))
        }

        // Benchmark arrive-by routing
        val arriveByIterations = 5
        val longDistanceArriveByIterations = 2  // very few for long-distance arrive-by
        println()
        println("--- Arrive-By Routing (getOptimizedPathsArriveBy) ---")
        println("($arriveByIterations iterations per query, $longDistanceArriveByIterations for long-distance)")
        println("Standard: arr 09:00, Long-distance: arr 23:59 window=${LONG_DISTANCE_SEARCH_WINDOW_MIN}min")
        println()

        val arriveByHashes = mutableListOf<String>()
        for (rq in resolvedQueries) {
            val mr = if (rq.longDistance) LONG_DISTANCE_MAX_ROUNDS else 5
            val iters = if (rq.longDistance) longDistanceArriveByIterations else arriveByIterations
            // Long-distance: arrive by 23:59 with 24h window; standard: arrive by 09:00
            val arrTime = if (rq.longDistance) 23 * 3600 + 59 * 60 else 9 * 3600
            val windowMin = if (rq.longDistance) LONG_DISTANCE_SEARCH_WINDOW_MIN else 120
            val startNano = System.nanoTime()
            var lastResult: List<List<io.raptor.core.JourneyLeg>> = emptyList()
            repeat(iters) {
                lastResult = raptor.getOptimizedPathsArriveBy(
                    rq.originIds, rq.destIds, arrTime,
                    maxRounds = mr, searchWindowMinutes = windowMin
                )
            }
            val elapsedMs = (System.nanoTime() - startNano) / 1_000_000.0
            val hash = journeyHash(lastResult)
            arriveByHashes.add(hash)
            val suffix = if (rq.longDistance) " [maxR=$mr, win=${windowMin}m]" else ""
            println("%-55s  %.2f ms avg  (%.0f ms total)  journeys=%d  hash=%s".format(
                "${rq.origin} -> ${rq.dest} (arrive-by)$suffix", elapsedMs / iters, elapsedMs, lastResult.size, hash
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
        for (rq in resolvedQueries) {
            val mr = if (rq.longDistance) LONG_DISTANCE_MAX_ROUNDS else 5
            val journeys = raptor.getOptimizedPaths(rq.originIds, rq.destIds, 8 * 3600, maxRounds = mr)
            println("${rq.origin} -> ${rq.dest}: ${journeys.size} Pareto-optimal journey(s)" +
                if (rq.longDistance) " [maxRounds=$mr]" else "")
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
