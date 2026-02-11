package io.raptor.core

import io.raptor.model.Network

class RaptorAlgorithm(private val network: Network, private val debug: Boolean = false) {
    private var lastState: RaptorState? = null

    // Reusable buffers for route collection (avoid allocations per round)
    private val routeSeenBuffer = BooleanArray(network.routeCount)
    private val routeResultBuffer = IntArray(network.routeCount)
    // Reusable buffer for destination O(1) check (avoids HashSet allocation per query)
    private val destinationBuffer = BooleanArray(network.stopCount)

    fun route(
        originIndices: List<Int>,
        destinationIndices: List<Int>,
        departureTime: Int,
        routeFilter: RouteFilter? = null
    ): Int {
        val state = lastState?.also { it.reset() } ?: RaptorState(network, maxRounds = 5)
        lastState = state

        if (debug) {
            println("Route search: ${network.stops[originIndices[0]].name} -> ${network.stops[destinationIndices[0]].name}")
            println("Departure at ${formatTime(departureTime)}")
        }

        // Mark destinations in reusable BooleanArray for O(1) contains checks
        val destBuf = destinationBuffer
        for (idx in destinationIndices) {
            destBuf[idx] = true
        }

        // Target pruning value
        var bestArrivalAtDestination = Int.MAX_VALUE

        // Step 1: Initialization (Round 0)
        for (idx in originIndices) {
            state.bestArrival[0][idx] = departureTime
            state.markStop(idx)

        }

        // Step 2: Main loop through rounds
        for (k in 1..state.maxRounds) {
            if (debug) {
                println("[ROUND $k] Exploring routes...")
            }
            state.clearMarks() // Move current marks to previous, then clear current

            // Copy best arrivals from k-1 to k to ensure we don't lose improvements
            state.copyArrivalTimesToNextRound(k)

            // Phase 1: Explore Routes
            exploreRoutes(state, k, destBuf, bestArrivalAtDestination, routeFilter)

            // Phase 2: Explore Transfers
            exploreTransfers(state, k)

            // Update best arrival at destination for pruning in next round
            for (destIdx in destinationIndices) {
                if (state.bestArrival[k][destIdx] < bestArrivalAtDestination) {
                    bestArrivalAtDestination = state.bestArrival[k][destIdx]
                }
            }

            if (state.getMarkedCount() == 0) {
                break // Stopping criteria
            }
        }

        // Clean up destination buffer
        for (idx in destinationIndices) {
            destBuf[idx] = false
        }

        var finalBest = Int.MAX_VALUE
        for (idx in destinationIndices) {
            val arrival = state.getBestArrival(idx)
            if (arrival < finalBest) finalBest = arrival
        }

        if (debug) {
            if (finalBest == Int.MAX_VALUE) println("Destination not reached.")
            else println("Best arrival: ${formatTime(finalBest)}")
        }
        return finalBest
    }

    private fun exploreRoutes(
        state: RaptorState,
        round: Int,
        destinationBuf: BooleanArray,
        bestArrivalAtDestination: Int,
        routeFilter: RouteFilter?
    ) {
        var currentBestAtDestination = bestArrivalAtDestination
        val markedFromPrevious = state.getMarkedInPreviousRound()
        val routeCount = network.collectRouteIndices(markedFromPrevious, routeSeenBuffer, routeResultBuffer)

        if (debug) {
            println("  Marked stops from previous: ${markedFromPrevious.size}, routes to explore: $routeCount")
        }

        for (ri in 0 until routeCount) {
            val routeIdx = routeResultBuffer[ri]
            val route = network.routeList[routeIdx]
            if (routeFilter != null && !routeFilter.allows(route)) continue

            val flat = route.flatStopTimes
            val stride = route.stopCountInRoute
            var currentTripIndex = -1
            var tripOffset = 0  // currentTripIndex * stride, cached
            var boardingIndex = -1
            var boardingStopIndex = -1

            var routeLogged = false

            // Use pre-computed stop indices to avoid HashMap lookups
            val stopIndicesArray = network.routeStopIndices[routeIdx]

            for (i in stopIndicesArray.indices) {
                val stopIndex = stopIndicesArray[i]
                if (stopIndex == -1) continue

                // 1. If we are on a trip, try to update the arrival time at the current stop
                if (currentTripIndex != -1) {
                    val arrivalTime = flat[tripOffset + i]

                    // Target pruning: can we even improve the best arrival at destination?
                    if (arrivalTime < state.bestArrival[round][stopIndex] && arrivalTime < currentBestAtDestination) {
                        if (debug) {
                            println("    -> ${network.stops[stopIndex].name} at ${formatTime(arrivalTime)}")
                        }
                        state.bestArrival[round][stopIndex] = arrivalTime
                        state.setParent(round, stopIndex,
                            boardingStopIndex, round - 1, routeIdx,
                            flat[tripOffset + boardingIndex], currentTripIndex,
                            boardingIndex, i
                        )
                        state.markStop(stopIndex)


                        // If this stop is one of our destinations, update currentBestAtDestination
                        if (destinationBuf[stopIndex]) {
                            currentBestAtDestination = arrivalTime
                        }
                    }
                }

                // 2. Can we improve our current trip by boarding at this stop?
                if (state.isMarkedInPreviousRound(stopIndex)) {
                    val arrivalAtStop = state.bestArrival[round - 1][stopIndex]
                    val earliestTripIdx = findEarliestTripIndex(flat, route.tripCount, stride, i, arrivalAtStop)

                    if (earliestTripIdx != -1) {
                        val departureFromStop = flat[earliestTripIdx * stride + i]
                        if (currentTripIndex == -1 || departureFromStop < flat[tripOffset + i]) {
                            currentTripIndex = earliestTripIdx
                            tripOffset = earliestTripIdx * stride
                            boardingIndex = i
                            boardingStopIndex = stopIndex
                            if (debug && !routeLogged) {
                                println("  Line ${route.name}")
                                routeLogged = true
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Binary search for the earliest trip departing at or after arrivalTime from the given stop.
     * Requires trips to be sorted by departure time (ensured at load time).
     * Returns the trip index, or -1 if no trip found.
     */
    private fun findEarliestTripIndex(
        flatStopTimes: IntArray, tripCount: Int, stride: Int,
        stopIndexInRoute: Int, arrivalTime: Int
    ): Int {
        var low = 0
        var high = tripCount - 1
        var result = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val departureFromStop = flatStopTimes[mid * stride + stopIndexInRoute]
            if (departureFromStop >= arrivalTime) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    private fun exploreTransfers(state: RaptorState, round: Int) {
        // Capture count before iteration — safe because markStop() only appends
        val markedCount = state.getMarkedCount()

        for (mi in 0 until markedCount) {
            val stopIndex = state.getMarkedAt(mi)
            val arrivalTime = state.bestArrival[round][stopIndex]
            if (arrivalTime == Int.MAX_VALUE) continue

            // Explicit transfers (pre-computed: [targetIdx, walkTime, targetIdx, walkTime, ...])
            val transfers = network.transferData[stopIndex]
            var t = 0
            while (t < transfers.size) {
                val targetStopIndex = transfers[t]
                val walkTime = transfers[t + 1]
                t += 2
                if (targetStopIndex == -1 || targetStopIndex == stopIndex) continue
                val arrivalAtTarget = arrivalTime + walkTime

                if (arrivalAtTarget < state.bestArrival[round][targetStopIndex]) {
                    if (debug) {
                        println("  Walk (explicit) transfer: ${network.stops[stopIndex].name} -> ${network.stops[targetStopIndex].name} (${formatTime(arrivalAtTarget)})")
                    }
                    state.bestArrival[round][targetStopIndex] = arrivalAtTarget
                    state.setParent(round, targetStopIndex,
                        stopIndex, round, -1, arrivalTime, -1, -1, -1
                    )
                    state.markStop(targetStopIndex)

                }
            }

            // Implicit transfers: pre-computed same-name stops (default 120s transfer time)
            val implicitTargets = network.implicitTransferData[stopIndex]
            for (otherStopIndex in implicitTargets) {
                val arrivalAtTarget = arrivalTime + 120 // 2 minutes default transfer time

                if (arrivalAtTarget < state.bestArrival[round][otherStopIndex]) {
                    if (debug) {
                        println("  Implicit transfer: ${network.stops[stopIndex].name} -> ${network.stops[otherStopIndex].name} (${formatTime(arrivalAtTarget)})")
                    }
                    state.bestArrival[round][otherStopIndex] = arrivalAtTarget
                    state.setParent(round, otherStopIndex,
                        stopIndex, round, -1, arrivalTime, -1, -1, -1
                    )
                    state.markStop(otherStopIndex)

                }
            }
        }
    }

    private fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    fun getArrivalTime(stopIndex: Int, round: Int): Int {
        return lastState?.bestArrival?.getOrNull(round)?.getOrNull(stopIndex) ?: Int.MAX_VALUE
    }

    fun getJourney(destinationIndex: Int, round: Int? = null): List<JourneyLeg>? {
        return lastState?.reconstructJourney(destinationIndex, round)
    }
}
