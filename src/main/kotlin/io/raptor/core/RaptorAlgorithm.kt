package io.raptor.core

import io.raptor.model.Network

class RaptorAlgorithm(private val network: Network) {
    fun route(originIndex: Int, destinationIndex: Int, departureTime: Int) {
        val state = RaptorState(network, maxRounds = 10)

        // Step 1: Initialization (Round 0)
        state.bestArrival[0][originIndex] = departureTime
        state.markStop(originIndex)

        // Step 2: Main loop through rounds
        for (k in 1..state.maxRounds) {
            val markedInPreviousRound = state.getMarkedIndices()
            if (markedInPreviousRound.isEmpty()) break // Stopping criteria

            state.clearMarks()

            // Phase 1: Explore Routes
            // (Find trips that can be boarded from stops marked in k-1)

            // Phase 2: Explore Transfers
            // (Apply walking distances from stops improved in current round k)
        }
    }
}