package io.raptor.core

import io.raptor.model.Network

/**
 * Tracks the state of a single routing request across rounds.
 */
class RaptorState(val network: Network, val maxRounds: Int) {
    // bestArrival[round][stopIndex] stores the earliest arrival time
    // Int.MAX_VALUE represents infinity (unreachable)
    val bestArrival: Array<IntArray> = Array(maxRounds + 1) {
        IntArray(network.stopCount) { Int.MAX_VALUE }
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
}