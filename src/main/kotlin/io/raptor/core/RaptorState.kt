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

data class Tuple5<out A, out B, out C, out D, out E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
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

    // Parent tracking: [round][stopIndex] -> (parentStopIndex, parentRound, routeId or null if transfer, departureTime, tripId)
    val parent: Array<Array<Tuple5<Int, Int, Int?, Int, Int>?>> = Array(maxRounds + 1) {
        Array(network.stopCount) { null }
    }

    // A boolean array to track which stops were improved in the current round
    private val markedStops = BooleanArray(network.stopCount)
    private val markedStopsPrevious = BooleanArray(network.stopCount)

    fun markStop(stopIndex: Int) {
        markedStops[stopIndex] = true
    }

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
            val parentInfo = parent[currentRound][currentStop] ?: // No more parents, we reached the origin
            break

            val (parentStop, parentRound, routeId, departureTime, tripId) = parentInfo

            val intermediateStopIndices = mutableListOf<Int>()
            val intermediateArrivalTimes = mutableListOf<Int>()

            var routeName: String? = null
            var direction: String? = null

            if (routeId != null && tripId != -1) {
                val routes = network.getRoutesServingStops(listOf(parentStop)).filter { it.id == routeId }
                
                for (route in routes) {
                    val trip = route.trips.find { it.id == tripId }
                    if (trip != null) {
                        val startIndex = route.stopIds.indexOfFirst { network.getStopIndex(it) == parentStop }
                        val endIndex = route.stopIds.indexOfFirst { network.getStopIndex(it) == currentStop }
                        
                        if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
                            routeName = route.name
                            val lastStopId = route.stopIds.last()
                            val lastStopIndex = network.getStopIndex(lastStopId)
                            if (lastStopIndex != -1) {
                                direction = network.stops[lastStopIndex].name
                            }
                            for (i in startIndex + 1 until endIndex) {
                                val stopId = route.stopIds[i]
                                val stopIndex = network.getStopIndex(stopId)
                                intermediateStopIndices.add(stopIndex)
                                intermediateArrivalTimes.add(trip.stopTimes[i])
                            }
                            break // Found the correct variant
                        }
                    }
                }
            }

            legs.add(
                JourneyLeg(
                    fromStopIndex = parentStop,
                    toStopIndex = currentStop,
                    departureTime = departureTime,
                    arrivalTime = bestArrival[currentRound][currentStop],
                    routeName = routeName,
                    isTransfer = routeName == null,
                    intermediateStopIndices = intermediateStopIndices,
                    intermediateArrivalTimes = intermediateArrivalTimes,
                    direction = direction
                )
            )

            // Move to the parent stop and round
            currentStop = parentStop
            currentRound = parentRound
        }

        return legs.reversed()
    }
}