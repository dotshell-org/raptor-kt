package io.raptor

import io.raptor.core.JourneyLeg
import io.raptor.core.RaptorAlgorithm
import io.raptor.data.NetworkLoader
import io.raptor.model.Network
import io.raptor.model.Stop

/**
 * Binary data for a specific time period — the full `.bin` contents read into memory.
 * @param periodId Identifier for this time period (e.g., "winter2024", "summer2024")
 * @param stopsBytes Raw bytes of the stops binary file
 * @param routesBytes Raw bytes of the routes binary file
 */
data class PeriodData(
    val periodId: String,
    val stopsBytes: ByteArray,
    val routesBytes: ByteArray
)

/**
 * RAPTOR library for routing search with support for multiple time periods.
 * Cross-platform: pass the raw bytes of each period's `.bin` files.
 * Example:
 * ```
 * RaptorLibrary(listOf(
 *     PeriodData("winter", stopsWinterBytes, routesWinterBytes),
 *     PeriodData("summer", stopsSummerBytes, routesSummerBytes)
 * ))
 * ```
 */
class RaptorLibrary(periodDataList: List<PeriodData>) {
    private val networks: Map<String, Network>
    private val algorithmCache = mutableMapOf<String, RaptorAlgorithm>()
    private var currentPeriodId: String

    init {
        require(periodDataList.isNotEmpty()) { "At least one period data must be provided" }
        
        networks = periodDataList.associate { periodData ->
            val stops = NetworkLoader.loadStops(periodData.stopsBytes)
            val routes = NetworkLoader.loadRoutes(periodData.routesBytes)
            periodData.periodId to Network(stops, routes)
        }
        
        // Par défaut, utilise la première période
        currentPeriodId = periodDataList.first().periodId
    }
    
    /**
     * Alternative constructor for single period (backward compatibility)
     */
    constructor(stopsBytes: ByteArray, routesBytes: ByteArray) : this(
        listOf(PeriodData("default", stopsBytes, routesBytes))
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
        val originIndices = network.mapStopIdsToIndices(originStopIds)
        val destinationIndices = network.mapStopIdsToIndices(destinationStopIds)

        if (originIndices.isEmpty() || destinationIndices.isEmpty()) {
            return emptyList()
        }

        val algorithm = algorithmCache.getOrPut(currentPeriodId) { RaptorAlgorithm(network, debug = false) }
        val routeFilter = buildRouteFilter(allowedRouteIds, allowedRouteNames, blockedRouteIds, blockedRouteNames)
        val bestArrivalAtAnyRound = algorithm.route(originIndices, destinationIndices, departureTime, routeFilter, maxRounds)

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
        val originIndices = network.mapStopIdsToIndices(originStopIds)
        val destinationIndices = network.mapStopIdsToIndices(destinationStopIds)

        if (originIndices.isEmpty() || destinationIndices.isEmpty()) {
            return emptyList()
        }

        val searchWindowSeconds = searchWindowMinutes * 60
        val earliestDeparture = maxOf(0, arrivalTime - searchWindowSeconds)
        val routeFilter = buildRouteFilter(allowedRouteIds, allowedRouteNames, blockedRouteIds, blockedRouteNames)
        val algorithm = algorithmCache.getOrPut(currentPeriodId) { RaptorAlgorithm(network, debug = false) }

        // Binary search to find the latest departure that arrives on time.
        // Probes only need arrival times, not journeys, so skip parent tracking (trackParents = false).
        var low = earliestDeparture
        var high = arrivalTime
        var bestMid = -1

        while (low <= high) {
            val mid = (low + high) / 2
            val bestArrival = algorithm.route(originIndices, destinationIndices, mid, routeFilter, maxRounds, trackParents = false)

            if (bestArrival <= arrivalTime) {
                bestMid = mid
                low = mid + 60
            } else {
                high = mid - 60
            }
        }

        if (bestMid == -1) return emptyList()
        // Final tracked run at the best departure so journeys can be reconstructed.
        algorithm.route(originIndices, destinationIndices, bestMid, routeFilter, maxRounds)
        return extractParetoJourneys(algorithm, destinationIndices, maxRounds, arrivalTime)
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

    /**
     * Maps stop IDs to internal indices in a single pass (drops unknown ids), allocating one list
     * instead of the two produced by map { }.filter { } plus its lambdas.
     */
    private fun Network.mapStopIdsToIndices(ids: List<Int>): List<Int> {
        val out = ArrayList<Int>(ids.size)
        for (i in ids.indices) {
            val ix = getStopIndex(ids[i])
            if (ix != -1) out.add(ix)
        }
        return out
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
        fun p(v: Int): String = v.toString().padStart(2, '0')
        return if (h >= 24) "${p(h - 24)}:${p(m)}:${p(s)}(+1)"
        else "${p(h)}:${p(m)}:${p(s)}"
    }
}
