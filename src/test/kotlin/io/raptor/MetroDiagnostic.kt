package io.raptor

import io.raptor.data.NetworkLoader
import io.raptor.model.Network
import java.io.File
import java.io.FileInputStream

/**
 * Diagnostic tool to investigate why metro lines are never used in routing.
 * Iterates all period files in raptor_data (stops_*.bin / routes_*.bin).
 */
object MetroDiagnostic {
    @JvmStatic
    fun main(args: Array<String>) {
        val baseDir = args.getOrNull(0) ?: "raptor_data"
        val dir = File(baseDir)

        // Discover period files: stops_<period>.bin + routes_<period>.bin
        val stopsFiles = dir.listFiles()?.filter { it.name.startsWith("stops") && it.name.endsWith(".bin") }?.sortedBy { it.name } ?: emptyList()
        val periods = stopsFiles.mapNotNull { sf ->
            val period = sf.name.removePrefix("stops_").removePrefix("stops").removeSuffix(".bin")
            val rf = File(dir, "routes${if (period.isNotEmpty()) "_$period" else ""}.bin")
            if (rf.exists()) Triple(period.ifEmpty { "default" }, sf, rf) else null
        }

        if (periods.isEmpty()) {
            println("No period files found in $baseDir")
            return
        }

        println("=== METRO DIAGNOSTIC — ALL PERIODS ===")
        println("Found ${periods.size} period(s): ${periods.map { it.first }}")
        println()

        for ((period, stopsFile, routesFile) in periods) {
            println("================================================================")
            println("  PERIOD: $period")
            println("  Files: ${stopsFile.name} (${stopsFile.length() / 1024}KB), ${routesFile.name} (${routesFile.length() / 1024}KB)")
            println("================================================================")

            val stops = NetworkLoader.loadStops(FileInputStream(stopsFile))
            val routes = NetworkLoader.loadRoutes(FileInputStream(routesFile))
            val network = Network(stops, routes)

            println("${network.stopCount} stops, ${network.routeCount} routes")
            println()

            diagnosePeriod(network, period, stopsFile, routesFile)
            println()
        }
    }

    private fun diagnosePeriod(network: Network, period: String, stopsFile: File, routesFile: File) {
        // 1. Find metro routes (A, B, C, D in Lyon)
        val metroNames = setOf("A", "B", "C", "D")
        println("--- Metro routes ---")
        var metroRouteCount = 0
        for (i in network.routeList.indices) {
            val r = network.routeList[i]
            if (r.name in metroNames) {
                metroRouteCount++
                val firstStopIdx = network.getStopIndex(r.stopIds[0])
                val lastStopIdx = network.getStopIndex(r.stopIds.last())
                val firstName = if (firstStopIdx >= 0) network.stops[firstStopIdx].name else "???"
                val lastName = if (lastStopIdx >= 0) network.stops[lastStopIdx].name else "???"
                println("  Route idx=$i id=${r.id} name='${r.name}' stops=${r.stopCountInRoute} trips=${r.tripCount} ($firstName -> $lastName)")
            }
        }
        if (metroRouteCount == 0) {
            println("  *** NO METRO ROUTES FOUND! ***")
            val allNames = network.routeList.map { it.name }.toSortedSet()
            println("  All unique route names (${allNames.size}): ${allNames.take(60)}")
        }
        println()

        // 2. Check Bellecour stop connectivity
        val testStopName = "Bellecour"
        println("--- Stops matching '$testStopName' ---")
        val matchingStops = network.stops.indices.filter { network.stops[it].name.contains(testStopName, ignoreCase = true) }
        if (matchingStops.isEmpty()) {
            println("  No stops matching '$testStopName' found!")
        }
        for (si in matchingStops) {
            val stop = network.stops[si]
            println("  stopIndex=$si id=${stop.id} name='${stop.name}'")

            val routeIndices = network.routeIndicesForStop[si]
            val routeNames = routeIndices.map { network.routeList[it].name }.sorted()
            println("    Routes serving this stop (${routeIndices.size}): $routeNames")

            val transfers = network.transferData[si]
            val transferCount = transfers.size / 2
            println("    Explicit transfers ($transferCount):")
            var t = 0
            while (t < transfers.size) {
                val targetIdx = transfers[t]
                val walkTime = transfers[t + 1]
                t += 2
                if (targetIdx >= 0) {
                    val targetStop = network.stops[targetIdx]
                    val targetRoutes = network.routeIndicesForStop[targetIdx].map { network.routeList[it].name }.sorted()
                    println("      -> '${targetStop.name}' (${walkTime}s walk) routes=$targetRoutes")
                }
            }

            val implicitTargets = network.implicitTransferData[si]
            println("    Implicit transfers (same name, ${implicitTargets.size}):")
            for (targetIdx in implicitTargets) {
                val targetStop = network.stops[targetIdx]
                val targetRoutes = network.routeIndicesForStop[targetIdx].map { network.routeList[it].name }.sorted()
                println("      -> '${targetStop.name}' (120s) stopIndex=$targetIdx routes=$targetRoutes")
            }
        }
        println()

        // 3. Routing test: Perrache -> Part-Dieu (should use metro A or B)
        println("--- Routing: Perrache -> Part-Dieu (dep 08:00) ---")
        val raptor = RaptorLibrary(
            stopsInputStream = FileInputStream(stopsFile),
            routesInputStream = FileInputStream(routesFile)
        )
        val originStops = raptor.searchStopsByName("Perrache")
        val destStops = raptor.searchStopsByName("Part-Dieu")
        val originIds = originStops.map { it.id }
        val destIds = destStops.map { it.id }
        println("  Origins (${originIds.size}): ${originStops.map { "'${it.name}'" }.take(5)}")
        println("  Destinations (${destIds.size}): ${destStops.map { "'${it.name}'" }.take(5)}")

        if (originIds.isNotEmpty() && destIds.isNotEmpty()) {
            val journeys = raptor.getOptimizedPaths(originIds, destIds, 8 * 3600)
            println("  Result: ${journeys.size} journey(s)")
            for ((idx, journey) in journeys.withIndex()) {
                println("  Journey ${idx + 1}:")
                for (leg in journey) {
                    if (leg.isTransfer) {
                        val fromName = network.stops.getOrNull(leg.fromStopIndex)?.name ?: "stop${leg.fromStopIndex}"
                        val toName = network.stops.getOrNull(leg.toStopIndex)?.name ?: "stop${leg.toStopIndex}"
                        println("    TRANSFER: $fromName -> $toName (${leg.arrivalTime - leg.departureTime}s)")
                    } else {
                        val fromName = network.stops.getOrNull(leg.fromStopIndex)?.name ?: "stop${leg.fromStopIndex}"
                        val toName = network.stops.getOrNull(leg.toStopIndex)?.name ?: "stop${leg.toStopIndex}"
                        println("    LINE ${leg.routeName}: $fromName -> $toName dep=${formatTime(leg.departureTime)} arr=${formatTime(leg.arrivalTime)}")
                    }
                }
            }
        } else {
            println("  SKIPPED: origin or destination not found")
        }
        println()

        // 4. Stats
        val stopsWithNoRoutes = network.stops.indices.count { network.routeIndicesForStop[it].isEmpty() }
        val totalRouteRefs = network.stops.sumOf { it.routeIds.size }
        var unmappedCount = 0
        for (si in network.stops.indices) {
            if (network.stops[si].routeIds.isNotEmpty() && network.routeIndicesForStop[si].isEmpty()) {
                unmappedCount++
            }
        }
        println("--- Stats ---")
        println("  Stops with NO routes: $stopsWithNoRoutes / ${network.stopCount} (${stopsWithNoRoutes * 100 / network.stopCount}%)")
        println("  Route refs in stops: $totalRouteRefs, stops with refs but no mapping: $unmappedCount")
    }

    private fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
