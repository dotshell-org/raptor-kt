package io.raptor.core

import io.raptor.model.Network

data class JourneyLeg(
    val fromStopIndex: Int,
    val toStopIndex: Int,
    val departureTime: Int,
    val arrivalTime: Int,
    val routeName: String?,
    val isTransfer: Boolean
)

data class Tuple4<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

/**
 * Tracks the state of a single routing request across rounds.
 */
class RaptorState(val network: Network, val maxRounds: Int, val debug: Boolean = false) {
    // bestArrival[round][stopIndex] stores the earliest arrival time
    // Int.MAX_VALUE represents infinity (unreachable)
    val bestArrival: Array<IntArray> = Array(maxRounds + 1) {
        IntArray(network.stopCount) { Int.MAX_VALUE }
    }

    // Parent tracking: [round][stopIndex] -> (parentStopIndex, parentRound, routeName or null if transfer, departureTime)
    val parent: Array<Array<Tuple4<Int, Int, String?, Int>?>> = Array(maxRounds + 1) {
        Array(network.stopCount) { null }
    }

    // A boolean array to track which stops were improved in the current round
    private val markedStops = BooleanArray(network.stopCount)
    private val markedStopsPrevious = BooleanArray(network.stopCount)

    fun markStop(stopIndex: Int) {
        markedStops[stopIndex] = true
    }

    fun isMarked(stopIndex: Int): Boolean = markedStops[stopIndex]

    fun isMarkedInPreviousRound(stopIndex: Int): Boolean = markedStopsPrevious[stopIndex]

    fun clearMarks() {
        System.arraycopy(markedStops, 0, markedStopsPrevious, 0, markedStops.size)
        markedStops.fill(false)
    }

    fun getMarkedIndices(): List<Int> {
        return markedStops.indices.filter { markedStops[it] }
    }

    fun getMarkedInPreviousRound(): List<Int> {
        return markedStopsPrevious.indices.filter { markedStopsPrevious[it] }
    }

    /**
     * Propagates best arrival times from round k-1 to round k.
     */
    fun copyArrivalTimesToNextRound(round: Int) {
        if (round <= 0 || round > maxRounds) return
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

    fun reconstructJourney(destinationIndex: Int): List<JourneyLeg> {
        val legs = mutableListOf<JourneyLeg>()
        var currentRound = getBestRound(destinationIndex)
        var currentStop = destinationIndex

        // Reconstruct backwards from destination
        while (currentRound > 0 && currentStop >= 0) {
            val parentInfo = parent[currentRound][currentStop]
            if (parentInfo == null) {
                // No more parents, we reached the origin
                break
            }

            val (parentStop, parentRound, routeName, departureTime) = parentInfo
            legs.add(
                JourneyLeg(
                    fromStopIndex = parentStop,
                    toStopIndex = currentStop,
                    departureTime = departureTime,
                    arrivalTime = bestArrival[currentRound][currentStop],
                    routeName = routeName,
                    isTransfer = routeName == null
                )
            )

            // Move to the parent stop and round
            currentStop = parentStop
            currentRound = parentRound
        }

        return legs.reversed()
    }
}