package io.raptor.core

import io.raptor.model.Network
import io.raptor.model.Trip

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
            exploreRoutes(state, k)

            // Phase 2: Explore Transfers
            // (Apply walking distances from stops improved in current round k)
        }
    }

    private fun exploreRoutes(state: RaptorState, round: Int) {
        // 1. Identify routes serving stops marked in the previous round
        val markedFromPrevious = state.getMarkedIndices()
        val routesToExplore = network.getRoutesServingStops(markedFromPrevious)

        for (route in routesToExplore) {
            var currentTrip: Trip? = null
            var boardingIndex = -1

            // 2. Traverse each stop in the route in order
            for (i in 0 until route.stopIds.size) {
                val stopIndex = route.stopIds[i]

                // Can we improve our current trip by boarding at this stop?
                if (state.isMarkedInPreviousRound(stopIndex)) {
                    val arrivalAtStop = state.bestArrival[round - 1][stopIndex]
                    val earliestTrip = findEarliestTrip(route, i, arrivalAtStop)

                    // If this trip is better than the one we are currently on
                    if (earliestTrip != null && (currentTrip == null || earliestTrip.isEarlierThan(currentTrip))) {
                        currentTrip = earliestTrip
                        boardingIndex = i
                    }
                }

                // 3. If we are on a trip, try to update the arrival time at the current stop
                if (currentTrip != null) {
                    val arrivalTime = currentTrip.stopTimes[i]
                    if (arrivalTime < state.bestArrival[round][stopIndex]) {
                        state.bestArrival[round][stopIndex] = arrivalTime
                        state.markStop(stopIndex) // Mark for next round or transfers
                    }
                }
            }
        }
    }

    /**
     * Finds the earliest trip in a route that can be boarded at a given stop index,
     * given an arrival time at that stop.
     */
    private fun findEarliestTrip(route: io.raptor.model.Route, stopIndex: Int, arrivalTime: Int): Trip? {
        // Optimization: we want the trip that departs from 'stopIndex' as early as possible,
        // but no earlier than 'arrivalTime'
        var earliestTrip: Trip? = null

        for (trip in route.trips) {
            val departureFromStop = trip.stopTimes[stopIndex]
            if (departureFromStop >= arrivalTime) {
                if (earliestTrip == null || departureFromStop < earliestTrip.stopTimes[stopIndex]) {
                    earliestTrip = trip
                }
            }
        }

        return earliestTrip
    }
}