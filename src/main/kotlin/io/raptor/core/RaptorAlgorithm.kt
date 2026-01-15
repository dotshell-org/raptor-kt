package io.raptor.core

import io.raptor.model.Network
import io.raptor.model.Trip

class RaptorAlgorithm(private val network: Network, private val debug: Boolean = false) {
    private var lastState: RaptorState? = null

    fun route(originIndices: List<Int>, destinationIndices: List<Int>, departureTime: Int): Int {
        val state = RaptorState(network, maxRounds = 5)
        lastState = state

        if (debug) {
            println("Route search: ${network.stops[originIndices[0]].name} -> ${network.stops[destinationIndices[0]].name}")
            println("Departure at ${formatTime(departureTime)}")
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
            exploreRoutes(state, k, destinationIndices, bestArrivalAtDestination)

            // Phase 2: Explore Transfers
            exploreTransfers(state, k)

            // Update best arrival at destination for pruning in next round
            for (destIdx in destinationIndices) {
                if (state.bestArrival[k][destIdx] < bestArrivalAtDestination) {
                    bestArrivalAtDestination = state.bestArrival[k][destIdx]
                }
            }

            val markedNow = state.getMarkedIndices()
            if (markedNow.isEmpty()) {
                break // Stopping criteria
            }
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

    private fun exploreRoutes(state: RaptorState, round: Int, destinationIndices: List<Int>, bestArrivalAtDestination: Int) {
        var currentBestAtDestination = bestArrivalAtDestination
        val markedFromPrevious = state.getMarkedInPreviousRound()
        val routesToExplore = network.getRoutesServingStops(markedFromPrevious)

        for (route in routesToExplore) {
            var currentTrip: Trip? = null
            var boardingIndex = -1

            var routeLogged = false

            for (i in 0 until route.stopIds.size) {
                val stopId = route.stopIds[i]
                val stopIndex = network.getStopIndex(stopId)
                if (stopIndex == -1) continue

                // 1. If we are on a trip, try to update the arrival time at the current stop
                if (currentTrip != null) {
                    val arrivalTime = currentTrip.stopTimes[i]
                    
                    // Target pruning: can we even improve the best arrival at destination?
                    if (arrivalTime < state.bestArrival[round][stopIndex] && arrivalTime < currentBestAtDestination) {
                        if (debug) {
                            println("    -> ${network.stops[stopIndex].name} à ${formatTime(arrivalTime)}")
                        }
                        state.bestArrival[round][stopIndex] = arrivalTime
                        state.parent[round][stopIndex] = Tuple4(
                            route.stopIds[boardingIndex].let { network.getStopIndex(it) },
                            round - 1,
                            route.name,
                            currentTrip.stopTimes[boardingIndex]
                        )
                        state.markStop(stopIndex)
                        
                        // If this stop is one of our destinations, update currentBestAtDestination
                        if (destinationIndices.contains(stopIndex)) {
                            currentBestAtDestination = arrivalTime
                        }
                    }
                }

                // 2. Can we improve our current trip by boarding at this stop?
                if (state.isMarkedInPreviousRound(stopIndex)) {
                    val arrivalAtStop = state.bestArrival[round - 1][stopIndex]
                    val earliestTrip = findEarliestTrip(route, i, arrivalAtStop)

                    if (earliestTrip != null && (currentTrip == null || earliestTrip.isEarlierThan(currentTrip))) {
                        currentTrip = earliestTrip
                        boardingIndex = i
                        if (debug && !routeLogged) {
                            println("  Line ${route.name}")
                            routeLogged = true
                        }
                    }
                }
            }
        }
    }

    private fun findEarliestTrip(route: io.raptor.model.Route, stopIndexInRoute: Int, arrivalTime: Int): Trip? {
        var earliestTrip: Trip? = null

        for (trip in route.trips) {
            val departureFromStop = trip.stopTimes[stopIndexInRoute]
            if (departureFromStop >= arrivalTime) {
                if (earliestTrip == null || departureFromStop < earliestTrip.stopTimes[stopIndexInRoute]) {
                    earliestTrip = trip
                }
            }
        }

        return earliestTrip
    }

    private fun exploreTransfers(state: RaptorState, round: Int) {
        val markedStops = state.getMarkedIndices()

        for (stopIndex in markedStops) {
            val arrivalTime = state.bestArrival[round][stopIndex]
            if (arrivalTime == Int.MAX_VALUE) continue

            val stop = network.stops[stopIndex]

            // Explicit transfers
            for (transfer in stop.transfers) {
                val targetStopIndex = network.getStopIndex(transfer.targetStopId)
                if (targetStopIndex == -1 || targetStopIndex == stopIndex) continue // Skip invalid or self-transfers
                val arrivalAtTarget = arrivalTime + transfer.walkTime

                if (arrivalAtTarget < state.bestArrival[round][targetStopIndex]) {
                    if (debug) {
                        println("  Walk (explicit) transfer: ${stop.name} -> ${network.stops[targetStopIndex].name} (${formatTime(arrivalAtTarget)})")
                    }
                    state.bestArrival[round][targetStopIndex] = arrivalAtTarget
                    state.parent[round][targetStopIndex] = Tuple4(stopIndex, round, null, arrivalTime)
                    state.markStop(targetStopIndex)
                }
            }
            
            // Implicit transfers: stops with the same name (default 120s transfer time)
            val stopsWithSameName = network.stopsByName[stop.name] ?: emptyList()
            for (otherStopIndex in stopsWithSameName) {
                if (otherStopIndex == stopIndex) continue
                
                val arrivalAtTarget = arrivalTime + 120 // 2 minutes default transfer time
                
                if (arrivalAtTarget < state.bestArrival[round][otherStopIndex]) {
                    if (debug) {
                        println("  Implicit transfer : ${stop.name} -> ${network.stops[otherStopIndex].name} (${formatTime(arrivalAtTarget)})")
                    }
                    state.bestArrival[round][otherStopIndex] = arrivalAtTarget
                    state.parent[round][otherStopIndex] = Tuple4(stopIndex, round, null, arrivalTime)
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

    fun getJourney(destinationIndex: Int, round: Int? = null): List<JourneyLeg>? {
        return lastState?.reconstructJourney(destinationIndex, round)
    }
}