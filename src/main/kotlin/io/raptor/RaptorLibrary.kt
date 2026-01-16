package io.raptor

import io.raptor.core.JourneyLeg
import io.raptor.core.RaptorAlgorithm
import io.raptor.data.NetworkLoader
import io.raptor.model.Network
import io.raptor.model.Stop
import java.io.InputStream

/**
 * RAPTOR library for routing search.
 * Use with Android: Pass InputStream objects from assets.
 * Example: RaptorLibrary(context.assets.open("stops.bin"), context.assets.open("routes.bin"))
 */
class RaptorLibrary(stopsInputStream: InputStream, routesInputStream: InputStream) {
    private val network: Network

    init {
        val stops = NetworkLoader.loadStops(stopsInputStream)
        val routes = NetworkLoader.loadRoutes(routesInputStream)
        network = Network(stops, routes)
    }

    /**
     * Searches for optimized paths between stop identifiers.
     * Returns a list of journeys (Pareto-optimal with respect to the number of transfers).
     */
    fun getOptimizedPaths(
        originStopIds: List<Int>,
        destinationStopIds: List<Int>,
        departureTime: Int,
        maxRounds: Int = 5
    ): List<List<JourneyLeg>> {
        val originIndices = originStopIds.map { network.getStopIndex(it) }.filter { it != -1 }
        val destinationIndices = destinationStopIds.map { network.getStopIndex(it) }.filter { it != -1 }

        if (originIndices.isEmpty() || destinationIndices.isEmpty()) {
            return emptyList()
        }

        val algorithm = RaptorAlgorithm(network, debug = false)
        val bestArrivalAtAnyRound = algorithm.route(originIndices, destinationIndices, departureTime)

        if (bestArrivalAtAnyRound == Int.MAX_VALUE) {
            return emptyList()
        }

        val paretoJourneys = mutableListOf<List<JourneyLeg>>()
        var lastBestArrival = Int.MAX_VALUE

        for (k in 1..maxRounds) {
            val bestDestIndex = destinationIndices.minByOrNull { idx ->
                val journey = algorithm.getJourney(idx, k)
                journey?.lastOrNull()?.arrivalTime ?: Int.MAX_VALUE
            }

            if (bestDestIndex != null) {
                val journey = algorithm.getJourney(bestDestIndex, k)
                if (!journey.isNullOrEmpty()) {
                    val arrivalTime = journey.last().arrivalTime
                    if (arrivalTime < lastBestArrival) {
                        paretoJourneys.add(journey)
                        lastBestArrival = arrivalTime
                    }
                }
            }
        }

        return paretoJourneys
    }

    /**
     * Searches for stops by their name.
     */
    fun searchStopsByName(name: String): List<Stop> {
        return network.stops.filter { it.name.contains(name, ignoreCase = true) }
    }

    /**
     * Searches and displays optimized routes between two stops (by name).
     */
    fun searchAndDisplayRoute(
        originName: String,
        destinationName: String,
        departureTime: Int,
        showIntermediateStops: Boolean = false
    ) {
        val originStops = searchStopsByName(originName)
        val destinationStops = searchStopsByName(destinationName)

        if (originStops.isEmpty()) {
            println("Origin stop not found: $originName")
        }
        if (destinationStops.isEmpty()) {
            println("Destination stop not found: $destinationName")
        }

        if (originStops.isNotEmpty() && destinationStops.isNotEmpty()) {
            val originIds = originStops.map { it.id }
            val destinationIds = destinationStops.map { it.id }

            val paretoJourneys = getOptimizedPaths(originIds, destinationIds, departureTime)

            if (paretoJourneys.isEmpty()) {
                println("\nNo route found at ${formatTime(departureTime)}.")
            } else {
                println("\n=== ROUTES FOUND (Pareto Optimal) ===")
                for ((idx, journey) in paretoJourneys.withIndex()) {
                    val arrival = journey.last().arrivalTime
                    val transfers = journey.count { !it.isTransfer } - 1
                    println("\nOption ${idx + 1}: Arrival at ${formatTime(arrival)} | $transfers transfers")
                    displayJourney(journey, showIntermediateStops)
                }
            }
        }
    }

    /**
     * Displays a journey in a readable format.
     */
    fun displayJourney(journey: List<JourneyLeg>, showIntermediateStops: Boolean = false) {
        val stops = network.stops
        for ((index, leg) in journey.withIndex()) {
            val fromStop = stops[leg.fromStopIndex]
            val toStop = stops[leg.toStopIndex]
            val depTime = formatTime(leg.departureTime)
            val arrTime = formatTime(leg.arrivalTime)

            if (leg.isTransfer) {
                println("${index + 1}. 🚶 Transfer: ${fromStop.name} → ${toStop.name}")
                println("   Departure: $depTime | Arrival: $arrTime (${(leg.arrivalTime - leg.departureTime) / 60} min)")
            } else {
                val directionInfo = if (leg.direction != null) " to ${leg.direction}" else ""
                println("${index + 1}. 🚍 Line ${leg.routeName}$directionInfo: ${fromStop.name} → ${toStop.name}")
                if (showIntermediateStops && leg.intermediateStopIndices.isNotEmpty()) {
                    println("   Departure: $depTime from ${fromStop.name}")
                    for (i in leg.intermediateStopIndices.indices) {
                        val intermediateStop = stops[leg.intermediateStopIndices[i]]
                        val intermediateTime = formatTime(leg.intermediateArrivalTimes[i])
                        println("     - $intermediateTime: ${intermediateStop.name}")
                    }
                    println("   Arrival: $arrTime at ${toStop.name} (${(leg.arrivalTime - leg.departureTime) / 60} min)")
                } else {
                    println("   Departure: $depTime | Arrival: $arrTime (${(leg.arrivalTime - leg.departureTime) / 60} min)")
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
