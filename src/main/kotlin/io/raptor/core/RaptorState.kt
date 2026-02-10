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

/**
 * Tracks the state of a single routing request across rounds.
 */
class RaptorState(val network: Network, val maxRounds: Int) {
    // bestArrival[round][stopIndex] stores the earliest arrival time
    // Int.MAX_VALUE represents infinity (unreachable)
    val bestArrival: Array<IntArray> = Array(maxRounds + 1) {
        IntArray(network.stopCount).also { it.fill(Int.MAX_VALUE) }
    }

    // Parent tracking as struct-of-arrays (avoids per-improvement object allocation)
    // Sentinel value -1 means "no parent"
    val parentStopIndex: Array<IntArray> = Array(maxRounds + 1) { IntArray(network.stopCount) { -1 } }
    val parentRound: Array<IntArray> = Array(maxRounds + 1) { IntArray(network.stopCount) { -1 } }
    val parentRouteIdx: Array<IntArray> = Array(maxRounds + 1) { IntArray(network.stopCount) { -1 } }
    val parentDepartureTime: Array<IntArray> = Array(maxRounds + 1) { IntArray(network.stopCount) { -1 } }
    val parentTripIndex: Array<IntArray> = Array(maxRounds + 1) { IntArray(network.stopCount) { -1 } }
    val parentBoardingPos: Array<IntArray> = Array(maxRounds + 1) { IntArray(network.stopCount) { -1 } }
    val parentAlightingPos: Array<IntArray> = Array(maxRounds + 1) { IntArray(network.stopCount) { -1 } }

    // Boolean arrays for O(1) mark checks
    private val markedStops = BooleanArray(network.stopCount)
    private val markedStopsPrevious = BooleanArray(network.stopCount)

    // Incremental tracking of marked stop indices (avoids scanning full array)
    private var markedList = ArrayList<Int>(256)
    private var markedListPrevious = ArrayList<Int>(256)

    fun reset() {
        for (k in 0..maxRounds) {
            bestArrival[k].fill(Int.MAX_VALUE)
            parentStopIndex[k].fill(-1)
            parentRound[k].fill(-1)
            parentRouteIdx[k].fill(-1)
            parentDepartureTime[k].fill(-1)
            parentTripIndex[k].fill(-1)
            parentBoardingPos[k].fill(-1)
            parentAlightingPos[k].fill(-1)
        }
        markedStops.fill(false)
        markedStopsPrevious.fill(false)
        markedList.clear()
        markedListPrevious.clear()
    }

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

    fun getMarkedCount(): Int = markedList.size
    fun getMarkedAt(i: Int): Int = markedList[i]

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

    /**
     * Sets parent info for a stop in a given round (struct-of-arrays write).
     */
    fun setParent(
        round: Int, stopIndex: Int,
        pStopIndex: Int, pRound: Int, routeIdx: Int,
        depTime: Int, tripIdx: Int, boardingPos: Int, alightingPos: Int
    ) {
        parentStopIndex[round][stopIndex] = pStopIndex
        parentRound[round][stopIndex] = pRound
        parentRouteIdx[round][stopIndex] = routeIdx
        parentDepartureTime[round][stopIndex] = depTime
        parentTripIndex[round][stopIndex] = tripIdx
        parentBoardingPos[round][stopIndex] = boardingPos
        parentAlightingPos[round][stopIndex] = alightingPos
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
            val pStop = parentStopIndex[currentRound][currentStop]
            if (pStop == -1) break

            val pRound = parentRound[currentRound][currentStop]
            val pRouteInternalIdx = parentRouteIdx[currentRound][currentStop]
            val pDepTime = parentDepartureTime[currentRound][currentStop]
            val pTripIdx = parentTripIndex[currentRound][currentStop]
            val pBoardingPos = parentBoardingPos[currentRound][currentStop]
            val pAlightingPos = parentAlightingPos[currentRound][currentStop]

            val intermediateStopIndices = mutableListOf<Int>()
            val intermediateArrivalTimes = mutableListOf<Int>()

            var routeName: String? = null
            var direction: String? = null

            if (pRouteInternalIdx != -1) {
                val route = network.routeList[pRouteInternalIdx]
                routeName = route.name
                val trip = route.trips[pTripIdx]

                // Direction: last stop of the route
                val lastStopId = route.stopIds.last()
                val lastStopIndex = network.getStopIndex(lastStopId)
                if (lastStopIndex != -1) {
                    direction = network.stops[lastStopIndex].name
                }

                // Intermediate stops between boarding and alighting
                for (i in pBoardingPos + 1 until pAlightingPos) {
                    val stopId = route.stopIds[i]
                    intermediateStopIndices.add(network.getStopIndex(stopId))
                    intermediateArrivalTimes.add(trip.stopTimes[i])
                }
            }

            legs.add(
                JourneyLeg(
                    fromStopIndex = pStop,
                    toStopIndex = currentStop,
                    departureTime = pDepTime,
                    arrivalTime = bestArrival[currentRound][currentStop],
                    routeName = routeName,
                    isTransfer = routeName == null,
                    intermediateStopIndices = intermediateStopIndices,
                    intermediateArrivalTimes = intermediateArrivalTimes,
                    direction = direction
                )
            )

            // Move to the parent stop and round
            currentStop = pStop
            currentRound = pRound
        }

        return legs.reversed()
    }
}
