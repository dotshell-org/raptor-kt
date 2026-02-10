package io.raptor.core

import io.raptor.model.Network

data class JourneyLeg(
    val fromStopIndex: Int,
    val toStopIndex: Int,
    val departureTime: Int,
    val arrivalTime: Int,
    val routeName: String?,
    val isTransfer: Boolean,
    val intermediateStopIndices: List<Int> = emptyList(),
    val intermediateArrivalTimes: List<Int> = emptyList(),
    val direction: String? = null
)

class ParentInfo(
    val parentStopIndex: Int,
    val parentRound: Int,
    val routeId: Int,              // -1 for transfers
    val departureTime: Int,
    val tripIndex: Int,            // index into route.trips, -1 for transfers
    val boardingStopInRoute: Int,  // position in route.stopIds, -1 for transfers
    val alightingStopInRoute: Int  // position in route.stopIds, -1 for transfers
)

/**
 * Tracks the state of a single routing request across rounds.
 */
class RaptorState(val network: Network, val maxRounds: Int) {
    // bestArrival[round][stopIndex] stores the earliest arrival time
    // Int.MAX_VALUE represents infinity (unreachable)
    val bestArrival: Array<IntArray> = Array(maxRounds + 1) {
        IntArray(network.stopCount) { Int.MAX_VALUE }
    }

    // Parent tracking: [round][stopIndex] -> ParentInfo
    val parent: Array<Array<ParentInfo?>> = Array(maxRounds + 1) {
        Array(network.stopCount) { null }
    }

    // Boolean arrays for O(1) mark checks
    private val markedStops = BooleanArray(network.stopCount)
    private val markedStopsPrevious = BooleanArray(network.stopCount)

    // Incremental tracking of marked stop indices (avoids scanning full array)
    private var markedList = ArrayList<Int>(256)
    private var markedListPrevious = ArrayList<Int>(256)

    fun markStop(stopIndex: Int) {
        if (!markedStops[stopIndex]) {
            markedStops[stopIndex] = true
            markedList.add(stopIndex)
        }
    }

    fun isMarkedInPreviousRound(stopIndex: Int): Boolean = markedStopsPrevious[stopIndex]

    fun clearMarks() {
        System.arraycopy(markedStops, 0, markedStopsPrevious, 0, markedStops.size)
        markedStops.fill(false)

        // Swap lists
        val temp = markedListPrevious
        markedListPrevious = markedList
        markedList = temp
        markedList.clear()
    }

    fun getMarkedIndices(): List<Int> = ArrayList(markedList)

    fun getMarkedInPreviousRound(): List<Int> = markedListPrevious

    /**
     * Propagates best arrival times from round k-1 to round k.
     */
    fun copyArrivalTimesToNextRound(round: Int) {
        if (round !in 1..maxRounds) return
        System.arraycopy(bestArrival[round - 1], 0, bestArrival[round], 0, network.stopCount)
    }

    fun getBestArrival(stopIndex: Int): Int {
        var minArrival = Int.MAX_VALUE
        for (k in 0..maxRounds) {
            if (bestArrival[k][stopIndex] < minArrival) {
                minArrival = bestArrival[k][stopIndex]
            }
        }
        return minArrival
    }

    fun getBestRound(stopIndex: Int): Int {
        var minArrival = Int.MAX_VALUE
        var bestRound = -1
        for (k in 0..maxRounds) {
            if (bestArrival[k][stopIndex] < minArrival) {
                minArrival = bestArrival[k][stopIndex]
                bestRound = k
            }
        }
        return bestRound
    }

    fun reconstructJourney(destinationIndex: Int, round: Int? = null): List<JourneyLeg> {
        val legs = mutableListOf<JourneyLeg>()
        var currentRound = round ?: getBestRound(destinationIndex)
        var currentStop = destinationIndex

        if (currentRound == -1 || bestArrival[currentRound][destinationIndex] == Int.MAX_VALUE) {
            return emptyList()
        }

        // Reconstruct backwards from destination
        while (currentRound > 0 && currentStop >= 0) {
            val info = parent[currentRound][currentStop] ?: break

            val intermediateStopIndices = mutableListOf<Int>()
            val intermediateArrivalTimes = mutableListOf<Int>()

            var routeName: String? = null
            var direction: String? = null

            if (info.routeId != -1) {
                val route = network.getRouteById(info.routeId)
                if (route != null) {
                    routeName = route.name
                    val trip = route.trips[info.tripIndex]

                    // Direction: last stop of the route
                    val lastStopId = route.stopIds.last()
                    val lastStopIndex = network.getStopIndex(lastStopId)
                    if (lastStopIndex != -1) {
                        direction = network.stops[lastStopIndex].name
                    }

                    // Intermediate stops between boarding and alighting
                    for (i in info.boardingStopInRoute + 1 until info.alightingStopInRoute) {
                        val stopId = route.stopIds[i]
                        intermediateStopIndices.add(network.getStopIndex(stopId))
                        intermediateArrivalTimes.add(trip.stopTimes[i])
                    }
                }
            }

            legs.add(
                JourneyLeg(
                    fromStopIndex = info.parentStopIndex,
                    toStopIndex = currentStop,
                    departureTime = info.departureTime,
                    arrivalTime = bestArrival[currentRound][currentStop],
                    routeName = routeName,
                    isTransfer = routeName == null,
                    intermediateStopIndices = intermediateStopIndices,
                    intermediateArrivalTimes = intermediateArrivalTimes,
                    direction = direction
                )
            )

            // Move to the parent stop and round
            currentStop = info.parentStopIndex
            currentRound = info.parentRound
        }

        return legs.reversed()
    }
}
