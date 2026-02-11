package io.raptor

import io.raptor.core.JourneyLeg
import io.raptor.core.RaptorAlgorithm
import io.raptor.data.NetworkLoader
import io.raptor.model.Network
import io.raptor.model.Stop
import java.io.InputStream

/**
 * Data class representing a pair of input streams for a specific time period
 * @param periodId Identifier for this time period (e.g., "winter2024", "summer2024")
 * @param stopsInputStream Stream for the stops binary file
 * @param routesInputStream Stream for the routes binary file
 */
data class PeriodData(
    val periodId: String,
    val stopsInputStream: InputStream,
    val routesInputStream: InputStream
)

/**
 * RAPTOR library for routing search with support for multiple time periods.
 * Use with Android: Pass a list of PeriodData objects from assets.
 * Example: 
 * ```
 * RaptorLibrary(listOf(
 *     PeriodData("winter", context.assets.open("stops_winter.bin"), context.assets.open("routes_winter.bin")),
 *     PeriodData("summer", context.assets.open("stops_summer.bin"), context.assets.open("routes_summer.bin"))
 * ))
 * ```
 */
class RaptorLibrary(periodDataList: List<PeriodData>) {
    private val networks: Map<String, Network>
    private var currentPeriodId: String

    init {
        require(periodDataList.isNotEmpty()) { "At least one period data must be provided" }
        
        networks = periodDataList.associate { periodData ->
            val stops = NetworkLoader.loadStops(periodData.stopsInputStream)
            val routes = NetworkLoader.loadRoutes(periodData.routesInputStream)
            periodData.periodId to Network(stops, routes)
        }
        
        // Par défaut, utilise la première période
        currentPeriodId = periodDataList.first().periodId
    }
    
    /**
     * Alternative constructor for single period (backward compatibility)
     */
    constructor(stopsInputStream: InputStream, routesInputStream: InputStream) : this(
        listOf(PeriodData("default", stopsInputStream, routesInputStream))
    )
    
    /**
     * Sets the active period for route calculations
     * @param periodId The identifier of the period to use
     * @return true if the period was found and set, false otherwise
     */
    fun setPeriod(periodId: String): Boolean {
        return if (networks.containsKey(periodId)) {
            currentPeriodId = periodId
            true
        } else {
            false
        }
    }
    
    /**
     * Gets the currently active period identifier
     */
    fun getCurrentPeriod(): String = currentPeriodId
    
    /**
     * Gets all available period identifiers
     */
    fun getAvailablePeriods(): Set<String> = networks.keys
    
    /**
     * Gets the network for the current period
     */
    private fun getCurrentNetwork(): Network = networks[currentPeriodId]!!

    /**
     * Searches for optimized paths between stop identifiers.
     * Returns a list of journeys (Pareto-optimal with respect to the number of transfers).
     * Uses the currently active period.
     * 
     * @param originStopIds List of origin stop identifiers
     * @param destinationStopIds List of destination stop identifiers
     * @param departureTime Departure time in seconds from midnight
     * @param maxRounds Maximum number of transfers + 1
     */
    fun getOptimizedPaths(
        originStopIds: List<Int>,
        destinationStopIds: List<Int>,
        departureTime: Int,
        maxRounds: Int = 5,
        allowedRouteIds: Set<Int>? = null,
        allowedRouteNames: Set<String>? = null,
        blockedRouteIds: Set<Int> = emptySet(),
        blockedRouteNames: Set<String> = emptySet()
    ): List<List<JourneyLeg>> {
        val network = getCurrentNetwork()
        val originIndices = originStopIds.map { network.getStopIndex(it) }.filter { it != -1 }
        val destinationIndices = destinationStopIds.map { network.getStopIndex(it) }.filter { it != -1 }

        if (originIndices.isEmpty() || destinationIndices.isEmpty()) {
            return emptyList()
        }

        val algorithm = RaptorAlgorithm(network, debug = false)
        val routeFilter = buildRouteFilter(allowedRouteIds, allowedRouteNames, blockedRouteIds, blockedRouteNames)
        val bestArrivalAtAnyRound = algorithm.route(originIndices, destinationIndices, departureTime, routeFilter)

        if (bestArrivalAtAnyRound == Int.MAX_VALUE) {
            return emptyList()
        }

        val paretoJourneys = mutableListOf<List<JourneyLeg>>()
        var lastBestArrival = Int.MAX_VALUE

        for (k in 1..maxRounds) {
            // Find best destination with manual loop (avoids iterator + lambda allocation)
            var bestDestIndex = -1
            var bestTime = Int.MAX_VALUE
            for (idx in destinationIndices) {
                val t = algorithm.getArrivalTime(idx, k)
                if (t < bestTime) { bestTime = t; bestDestIndex = idx }
            }

            if (bestDestIndex != -1 && bestTime < lastBestArrival) {
                val journey = algorithm.getJourney(bestDestIndex, k)
                if (!journey.isNullOrEmpty()) {
                    paretoJourneys.add(journey)
                    lastBestArrival = bestTime
                }
            }
        }

        return paretoJourneys
    }

    /**
     * Searches for optimized paths that arrive before a specified time.
     * Uses binary search to find the latest possible departure that arrives on time.
     * Returns a list of journeys (Pareto-optimal with respect to the number of transfers).
     * 
     * @param originStopIds List of origin stop identifiers
     * @param destinationStopIds List of destination stop identifiers
     * @param arrivalTime Desired arrival time in seconds from midnight
     * @param maxRounds Maximum number of transfers + 1
     * @param searchWindowMinutes How far back to search for departures (default: 120 minutes)
     */
    fun getOptimizedPathsArriveBy(
        originStopIds: List<Int>,
        destinationStopIds: List<Int>,
        arrivalTime: Int,
        maxRounds: Int = 5,
        searchWindowMinutes: Int = 120,
        allowedRouteIds: Set<Int>? = null,
        allowedRouteNames: Set<String>? = null,
        blockedRouteIds: Set<Int> = emptySet(),
        blockedRouteNames: Set<String> = emptySet()
    ): List<List<JourneyLeg>> {
        val network = getCurrentNetwork()
        val originIndices = originStopIds.map { network.getStopIndex(it) }.filter { it != -1 }
        val destinationIndices = destinationStopIds.map { network.getStopIndex(it) }.filter { it != -1 }

        if (originIndices.isEmpty() || destinationIndices.isEmpty()) {
            return emptyList()
        }

        val searchWindowSeconds = searchWindowMinutes * 60
        val earliestDeparture = maxOf(0, arrivalTime - searchWindowSeconds)
        val routeFilter = buildRouteFilter(allowedRouteIds, allowedRouteNames, blockedRouteIds, blockedRouteNames)
        
        // Binary search to find the latest departure that arrives on time
        var low = earliestDeparture
        var high = arrivalTime
        var bestJourneys: List<List<JourneyLeg>> = emptyList()
        var bestDepartureTime = -1
        val algorithm = RaptorAlgorithm(network, debug = false)

        while (low <= high) {
            val mid = (low + high) / 2
            val bestArrival = algorithm.route(originIndices, destinationIndices, mid, routeFilter)

            if (bestArrival <= arrivalTime) {
                // This departure works, try a later one
                val journeys = extractParetoJourneys(algorithm, destinationIndices, maxRounds, arrivalTime)
                if (journeys.isNotEmpty()) {
                    val latestDeparture = journeys.maxOf { it.first().departureTime }
                    if (latestDeparture > bestDepartureTime) {
                        bestDepartureTime = latestDeparture
                        bestJourneys = journeys
                    }
                }
                low = mid + 60 // Move forward by 1 minute
            } else {
                // Arrives too late, try an earlier departure
                high = mid - 60
            }
        }

        return bestJourneys
    }

    /**
     * Helper function to extract Pareto-optimal journeys that arrive before a given time.
     */
    private fun extractParetoJourneys(
        algorithm: RaptorAlgorithm,
        destinationIndices: List<Int>,
        maxRounds: Int,
        maxArrivalTime: Int
    ): List<List<JourneyLeg>> {
        val paretoJourneys = mutableListOf<List<JourneyLeg>>()
        var lastBestArrival = Int.MAX_VALUE

        for (k in 1..maxRounds) {
            var bestDestIndex = -1
            var bestTime = Int.MAX_VALUE
            for (idx in destinationIndices) {
                val t = algorithm.getArrivalTime(idx, k)
                if (t < bestTime) { bestTime = t; bestDestIndex = idx }
            }

            if (bestDestIndex != -1 && bestTime <= maxArrivalTime && bestTime < lastBestArrival) {
                val journey = algorithm.getJourney(bestDestIndex, k)
                if (!journey.isNullOrEmpty()) {
                    paretoJourneys.add(journey)
                    lastBestArrival = bestTime
                }
            }
        }

        return paretoJourneys
    }

    private fun buildRouteFilter(
        allowedRouteIds: Set<Int>?,
        allowedRouteNames: Set<String>?,
        blockedRouteIds: Set<Int>,
        blockedRouteNames: Set<String>
    ): io.raptor.core.RouteFilter? {
        if (allowedRouteIds == null &&
            allowedRouteNames == null &&
            blockedRouteIds.isEmpty() &&
            blockedRouteNames.isEmpty()
        ) {
            return null
        }
        return io.raptor.core.RouteFilter(
            allowedRouteIds = allowedRouteIds,
            allowedRouteNames = allowedRouteNames,
            blockedRouteIds = blockedRouteIds,
            blockedRouteNames = blockedRouteNames
        )
    }

    /**
     * Searches for stops by their name in the current period.
     */
    fun searchStopsByName(name: String): List<Stop> {
        val network = getCurrentNetwork()
        return network.stops.filter { it.name.contains(name, ignoreCase = true) }
    }

    /**
     * Searches and displays optimized routes between two stops (by name).
     */
    fun searchAndDisplayRoute(
        originName: String,
        destinationName: String,
        departureTime: Int,
        showIntermediateStops: Boolean = false,
        allowedRouteIds: Set<Int>? = null,
        allowedRouteNames: Set<String>? = null,
        blockedRouteIds: Set<Int> = emptySet(),
        blockedRouteNames: Set<String> = emptySet()
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

            val paretoJourneys = getOptimizedPaths(
                originStopIds = originIds,
                destinationStopIds = destinationIds,
                departureTime = departureTime,
                allowedRouteIds = allowedRouteIds,
                allowedRouteNames = allowedRouteNames,
                blockedRouteIds = blockedRouteIds,
                blockedRouteNames = blockedRouteNames
            )

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
        val network = getCurrentNetwork()
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
        return if (h >= 24) "%02d:%02d:%02d(+1)".format(h - 24, m, s)
        else "%02d:%02d:%02d".format(h, m, s)
    }
}
