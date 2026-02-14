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
    val stopCount: Int = network.stopCount

    // Flat bestArrival: bestArrival[round * stopCount + stopIndex]
    // Int.MAX_VALUE represents infinity (unreachable)
    val bestArrival: IntArray = IntArray((maxRounds + 1) * stopCount).also { it.fill(Int.MAX_VALUE) }

    // Parent tracking: flat interleaved IntArray
    // Layout: parentData[(round * stopCount + stopIndex) * 7 + field]
    // 7 fields per entry, all in one contiguous array for cache locality
    val parentData: IntArray = IntArray((maxRounds + 1) * stopCount * PARENT_STRIDE).also { it.fill(-1) }

    companion object {
        const val P_STOP = 0
        const val P_ROUND = 1
        const val P_ROUTE = 2
        const val P_DEP_TIME = 3
        const val P_TRIP = 4
        const val P_BOARD_POS = 5
        const val P_ALIGHT_POS = 6
        const val PARENT_STRIDE = 7
    }

    // Boolean arrays for O(1) mark checks
    private val markedStops = BooleanArray(network.stopCount)
    private val markedStopsPrevious = BooleanArray(network.stopCount)

    // Incremental tracking of marked stop indices (IntArray-backed, no boxing)
    private var markedArray = IntArray(256)
    private var markedSize = 0
    private var markedArrayPrev = IntArray(256)
    private var markedSizePrev = 0

    // Track max round used for lazy reset of bestArrival and parentData
    private var lastMaxRound = maxRounds // first reset fills everything

    fun reset() {
        bestArrival.fill(Int.MAX_VALUE)
        // Only reset parent data for rounds actually used in previous query
        val parentResetEnd = (lastMaxRound + 1) * stopCount * PARENT_STRIDE
        java.util.Arrays.fill(parentData, 0, parentResetEnd, -1)
        lastMaxRound = 0
        markedStops.fill(false)
        markedStopsPrevious.fill(false)
        markedSize = 0
        markedSizePrev = 0
    }

    fun markStop(stopIndex: Int) {
        if (!markedStops[stopIndex]) {
            markedStops[stopIndex] = true
            if (markedSize == markedArray.size) {
                markedArray = markedArray.copyOf(markedArray.size * 2)
            }
            markedArray[markedSize++] = stopIndex
        }
    }

    fun isMarkedInPreviousRound(stopIndex: Int): Boolean = markedStopsPrevious[stopIndex]

    fun clearMarks() {
        System.arraycopy(markedStops, 0, markedStopsPrevious, 0, markedStops.size)
        markedStops.fill(false)

        // Swap arrays
        val tmpArr = markedArrayPrev; markedArrayPrev = markedArray; markedArray = tmpArr
        markedSizePrev = markedSize
        markedSize = 0
    }

    fun getMarkedCount(): Int = markedSize
    fun getMarkedAt(i: Int): Int = markedArray[i]

    fun getMarkedPrevArray(): IntArray = markedArrayPrev
    fun getMarkedPrevSize(): Int = markedSizePrev

    /**
     * Propagates best arrival times from round k-1 to round k.
     */
    fun copyArrivalTimesToNextRound(round: Int) {
        if (round !in 1..maxRounds) return
        System.arraycopy(bestArrival, (round - 1) * stopCount, bestArrival, round * stopCount, stopCount)
    }

    fun getBestArrival(stopIndex: Int): Int {
        var minArrival = Int.MAX_VALUE
        for (k in 0..maxRounds) {
            val v = bestArrival[k * stopCount + stopIndex]
            if (v < minArrival) {
                minArrival = v
            }
        }
        return minArrival
    }

    fun getBestRound(stopIndex: Int): Int {
        var minArrival = Int.MAX_VALUE
        var bestRound = -1
        for (k in 0..maxRounds) {
            val v = bestArrival[k * stopCount + stopIndex]
            if (v < minArrival) {
                minArrival = v
                bestRound = k
            }
        }
        return bestRound
    }

    /**
     * Sets parent info for a stop in a given round (interleaved flat array write).
     * All 7 fields are written to contiguous memory for cache locality.
     */
    fun setParent(
        round: Int, stopIndex: Int,
        pStopIndex: Int, pRound: Int, routeIdx: Int,
        depTime: Int, tripIdx: Int, boardingPos: Int, alightingPos: Int
    ) {
        if (round > lastMaxRound) lastMaxRound = round
        val base = (round * stopCount + stopIndex) * PARENT_STRIDE
        parentData[base + P_STOP] = pStopIndex
        parentData[base + P_ROUND] = pRound
        parentData[base + P_ROUTE] = routeIdx
        parentData[base + P_DEP_TIME] = depTime
        parentData[base + P_TRIP] = tripIdx
        parentData[base + P_BOARD_POS] = boardingPos
        parentData[base + P_ALIGHT_POS] = alightingPos
    }

    fun reconstructJourney(destinationIndex: Int, round: Int? = null): List<JourneyLeg> {
        val legs = mutableListOf<JourneyLeg>()
        var currentRound = round ?: getBestRound(destinationIndex)
        var currentStop = destinationIndex

        if (currentRound == -1 || bestArrival[currentRound * stopCount + destinationIndex] == Int.MAX_VALUE) {
            return emptyList()
        }

        // Reconstruct backwards from destination
        while (currentRound > 0 && currentStop >= 0) {
            val base = (currentRound * stopCount + currentStop) * PARENT_STRIDE
            val pStop = parentData[base + P_STOP]
            if (pStop == -1) break

            val pRound = parentData[base + P_ROUND]
            val pRouteInternalIdx = parentData[base + P_ROUTE]
            val pDepTime = parentData[base + P_DEP_TIME]
            val pTripIdx = parentData[base + P_TRIP]
            val pBoardingPos = parentData[base + P_BOARD_POS]
            val pAlightingPos = parentData[base + P_ALIGHT_POS]

            val intermediateStopIndices = mutableListOf<Int>()
            val intermediateArrivalTimes = mutableListOf<Int>()

            var routeName: String? = null
            var direction: String? = null

            if (pRouteInternalIdx != -1) {
                val route = network.routeList[pRouteInternalIdx]
                routeName = route.name
                val flat = route.flatStopTimes
                val stride = route.stopCountInRoute

                // Direction: last stop of the route
                val lastStopId = route.stopIds.last()
                val lastStopIndex = network.getStopIndex(lastStopId)
                if (lastStopIndex != -1) {
                    direction = network.stops[lastStopIndex].name
                }

                // Intermediate stops between boarding and alighting
                val tripOffset = pTripIdx * stride
                for (i in pBoardingPos + 1 until pAlightingPos) {
                    val stopId = route.stopIds[i]
                    intermediateStopIndices.add(network.getStopIndex(stopId))
                    intermediateArrivalTimes.add(flat[tripOffset + i])
                }
            }

            legs.add(
                JourneyLeg(
                    fromStopIndex = pStop,
                    toStopIndex = currentStop,
                    departureTime = pDepTime,
                    arrivalTime = bestArrival[currentRound * stopCount + currentStop],
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
