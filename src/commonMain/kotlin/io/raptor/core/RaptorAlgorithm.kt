package io.raptor.core

import io.raptor.model.Network

class RaptorAlgorithm(private val network: Network, private val debug: Boolean = false) {
    private var lastState: RaptorState? = null
    private var lastBackwardState: BackwardRaptorState? = null

    // Reusable buffers for route collection (avoid allocations per round)
    private val routeSeenBuffer = BooleanArray(network.routeCount)
    private val routeResultBuffer = IntArray(network.routeCount)
    // Reusable buffer for destination O(1) check (avoids HashSet allocation per query)
    private val destinationBuffer = BooleanArray(network.stopCount)
    // Reusable buffer for route filter bitmask (true = allowed)
    private val routeFilterBuffer = BooleanArray(network.routeCount)
    // Per-stop walk times for coordinate queries, scattered per query and cleared after.
    // Kept zero-filled so the hot-loop arithmetic (arrival + egressAtStop[stop]) is a no-op
    // for classic stop-to-stop queries — no null branch in exploreRoutes.
    private val egressAtStop = IntArray(network.stopCount)
    private val accessAtStop = IntArray(network.stopCount)

    /**
     * @param accessSeconds Walk time to reach each origin stop, parallel to [originIndices]
     *        (which must then hold unique indices); null = board at [departureTime] directly.
     * @param egressSeconds Walk time from each destination stop to the final point, parallel to
     *        [destinationIndices] (unique indices); null = arrival at the stop is the arrival.
     * @param initialBestArrival Admissible upper bound used for target pruning, e.g. the arrival
     *        time of a direct origin-to-destination walk. Never returned as a result.
     * @return best arrival at the "virtual destination" (stop arrival + its egress walk), or
     *         Int.MAX_VALUE when no transit journey beats [initialBestArrival].
     */
    fun route(
        originIndices: List<Int>,
        destinationIndices: List<Int>,
        departureTime: Int,
        routeFilter: RouteFilter? = null,
        maxRounds: Int = 5,
        accessSeconds: IntArray? = null,
        egressSeconds: IntArray? = null,
        initialBestArrival: Int = Int.MAX_VALUE
    ): Int {
        val existing = lastState
        val state = if (existing != null && existing.maxRounds >= maxRounds) {
            existing.also { it.reset() }
        } else {
            RaptorState(network, maxRounds = maxRounds)
        }
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
        // Scatter egress walk times (buffer is all-zero when egressSeconds is null)
        if (egressSeconds != null) {
            for (d in destinationIndices.indices) {
                egressAtStop[destinationIndices[d]] = egressSeconds[d]
            }
        }

        // Pre-compute route filter bitmask (true = allowed)
        val filterBuf: BooleanArray? = if (routeFilter != null) {
            val buf = routeFilterBuffer
            for (i in 0 until network.routeCount) {
                buf[i] = routeFilter.allows(network.routeList[i])
            }
            buf
        } else null

        // Target pruning value
        var bestArrivalAtDestination = initialBestArrival

        // Step 1: Initialization (Round 0)
        val sc = state.stopCount
        for (i in originIndices.indices) {
            val idx = originIndices[i]
            // round 0 offset is the access walk (0 for classic queries)
            val seed = departureTime + (accessSeconds?.get(i) ?: 0)
            if (seed < state.bestArrival[idx]) {
                state.bestArrival[idx] = seed
                state.markStop(idx)
            }
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
            exploreRoutes(state, k, destBuf, bestArrivalAtDestination, filterBuf)

            // Phase 2: Explore Transfers
            exploreTransfers(state, k, bestArrivalAtDestination)

            // Update best arrival at destination for pruning in next round
            // (destination = stop arrival + its egress walk; MAX guard avoids overflow)
            val kOff = k * sc
            for (destIdx in destinationIndices) {
                val arrival = state.bestArrival[kOff + destIdx]
                if (arrival == Int.MAX_VALUE) continue
                val adjusted = arrival + egressAtStop[destIdx]
                if (adjusted < bestArrivalAtDestination) {
                    bestArrivalAtDestination = adjusted
                }
            }

            if (state.getMarkedCount() == 0) {
                break // Stopping criteria
            }
        }

        var finalBest = Int.MAX_VALUE
        for (idx in destinationIndices) {
            val arrival = state.getBestArrival(idx)
            if (arrival == Int.MAX_VALUE) continue
            val adjusted = arrival + egressAtStop[idx]
            if (adjusted < finalBest) finalBest = adjusted
        }

        // Clean up destination buffer and scattered egress walk times
        for (idx in destinationIndices) {
            destBuf[idx] = false
            egressAtStop[idx] = 0
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
        filterMask: BooleanArray?
    ) {
        var currentBestAtDestination = bestArrivalAtDestination
        val markedPrevArr = state.getMarkedPrevArray()
        val markedPrevSize = state.getMarkedPrevSize()
        val routeCount = network.collectRouteIndices(markedPrevArr, markedPrevSize, routeSeenBuffer, routeResultBuffer)

        if (debug) {
            println("  Marked stops from previous: $markedPrevSize, routes to explore: $routeCount")
        }

        val ba = state.bestArrival
        val sc = state.stopCount
        val roundOff = round * sc
        val prevRoundOff = (round - 1) * sc

        for (ri in 0 until routeCount) {
            val routeIdx = routeResultBuffer[ri]
            if (filterMask != null && !filterMask[routeIdx]) continue
            val route = network.routeList[routeIdx]

            val flat = route.flatStopTimes
            val stride = route.stopCountInRoute
            val overnight = route.hasOvernightTrips
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

                    // Overnight wrap protection (only for routes with midnight-crossing trips)
                    if (overnight && arrivalTime < flat[tripOffset + boardingIndex]) continue

                    // Target pruning: can we even improve the best arrival at destination?
                    if (arrivalTime < ba[roundOff + stopIndex] && arrivalTime < currentBestAtDestination) {
                        if (debug) {
                            println("    -> ${network.stops[stopIndex].name} at ${formatTime(arrivalTime)}")
                        }
                        ba[roundOff + stopIndex] = arrivalTime
                        state.setParent(round, stopIndex,
                            boardingStopIndex, round - 1, routeIdx,
                            flat[tripOffset + boardingIndex], currentTripIndex,
                            boardingIndex, i
                        )
                        state.markStop(stopIndex)

                        // If this stop is one of our destinations, update currentBestAtDestination
                        // (adjusted by its egress walk — 0 for classic queries, where the guard is
                        // always true since the write condition ensured arrivalTime < current bound)
                        if (destinationBuf[stopIndex]) {
                            val adjusted = arrivalTime + egressAtStop[stopIndex]
                            if (adjusted < currentBestAtDestination) {
                                currentBestAtDestination = adjusted
                            }
                        }
                    }
                }

                // 2. Can we improve our current trip by boarding at this stop?
                if (state.isMarkedInPreviousRound(stopIndex)) {
                    val arrivalAtStop = ba[prevRoundOff + stopIndex]
                    // Quick-reject: if we arrive after current trip's departure here,
                    // no earlier trip is catchable (FIFO: earlier trips depart even sooner)
                    if (currentTripIndex != -1 && arrivalAtStop >= flat[tripOffset + i]) continue

                    // Bounded search: only search trips [0, currentTripIndex) since
                    // FIFO guarantees only earlier trips can improve
                    val searchBound = if (currentTripIndex == -1) route.tripCount else currentTripIndex
                    val earliestTripIdx = findEarliestTripIndex(flat, searchBound, stride, i, arrivalAtStop)

                    if (earliestTripIdx != -1) {
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
            if (flatStopTimes[mid * stride + stopIndexInRoute] >= arrivalTime) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    /**
     * Backward RAPTOR: computes the LATEST departure time from any origin that still reaches one
     * of the destinations by [arrivalTime], never departing before [earliestDeparture] (search
     * window floor). Exact mirror of [route]: routes are scanned in reverse stop order, labels are
     * relaxed by max (sentinel Int.MIN_VALUE = unreachable), and trips are caught by the latest one
     * arriving at or before the label. No parent tracking — callers reconstruct journeys with a
     * forward [route] run at the returned departure time.
     *
     * Kept as a fully separate code path (not a direction flag on route()): threading a parameter
     * through the forward hot loop measurably regressed its JIT compilation.
     *
     * @return the latest feasible departure time, or Int.MIN_VALUE if no journey exists.
     */
    fun routeBackward(
        originIndices: List<Int>,
        destinationIndices: List<Int>,
        arrivalTime: Int,
        earliestDeparture: Int,
        routeFilter: RouteFilter? = null,
        maxRounds: Int = 5
    ): Int {
        val existing = lastBackwardState
        val state = if (existing != null && existing.maxRounds >= maxRounds) {
            existing.also { it.reset() }
        } else {
            BackwardRaptorState(network, maxRounds)
        }
        lastBackwardState = state

        // Mark origins (the targets of the backward search) for O(1) pruning checks.
        // Reuses the same scratch buffer as route()'s destinations — calls are sequential.
        val origBuf = destinationBuffer
        for (idx in originIndices) {
            origBuf[idx] = true
        }

        val filterBuf: BooleanArray? = if (routeFilter != null) {
            val buf = routeFilterBuffer
            for (i in 0 until network.routeCount) {
                buf[i] = routeFilter.allows(network.routeList[i])
            }
            buf
        } else null

        var bestDepartureAtOrigin = Int.MIN_VALUE

        // Round 0: we can be at a destination at arrivalTime itself
        val ld = state.latestDeparture
        for (idx in destinationIndices) {
            ld[idx] = arrivalTime
            state.markStop(idx)
        }
        // Trailing-walk mirror: forward journeys may END with a single transfer into a destination
        // (exploreTransfers runs after each ride round). Seed round-0 labels at walk-adjacent stops
        // so the round-1 backward ride can alight there. Such a walk is always adjacent to the
        // journey's LAST ride, which is backward round 1, so seeding round 0 covers every case.
        for (idx in destinationIndices) {
            val transfers = network.reverseTransferData[idx]
            var t = 0
            while (t < transfers.size) {
                val sourceStopIndex = transfers[t]
                val walkTime = transfers[t + 1]
                t += 2
                if (sourceStopIndex == idx) continue
                val dep = arrivalTime - walkTime
                if (dep < earliestDeparture) continue
                if (dep > ld[sourceStopIndex]) {
                    ld[sourceStopIndex] = dep
                    state.markStop(sourceStopIndex)
                }
            }
            for (sourceStopIndex in network.implicitTransferData[idx]) {
                val dep = arrivalTime - 120
                if (dep < earliestDeparture) continue
                if (dep > ld[sourceStopIndex]) {
                    ld[sourceStopIndex] = dep
                    state.markStop(sourceStopIndex)
                }
            }
        }

        for (k in 1..state.maxRounds) {
            state.clearMarks()
            state.copyToNextRound(k)

            // Forward journeys always START with a ride (transfers are only relaxed after riding),
            // so only RIDE-written origin labels are valid journey endpoints for the mirror:
            // exploreRoutesBackward returns the best origin departure it reached, and
            // walk-written origin labels (exploreTransfersBackward) never feed D*.
            bestDepartureAtOrigin = exploreRoutesBackward(
                state, k, origBuf, bestDepartureAtOrigin, earliestDeparture, filterBuf
            )
            exploreTransfersBackward(state, k, bestDepartureAtOrigin, earliestDeparture)

            if (state.getMarkedCount() == 0) {
                break
            }
        }

        for (idx in originIndices) {
            origBuf[idx] = false
        }

        return bestDepartureAtOrigin
    }

    /** @return the best (latest) RIDE-written origin departure seen so far, for D* and pruning. */
    private fun exploreRoutesBackward(
        state: BackwardRaptorState,
        round: Int,
        originBuf: BooleanArray,
        bestDepartureAtOrigin: Int,
        earliestDeparture: Int,
        filterMask: BooleanArray?
    ): Int {
        var currentBestAtOrigin = bestDepartureAtOrigin
        val markedPrevArr = state.getMarkedPrevArray()
        val markedPrevSize = state.getMarkedPrevSize()
        val routeCount = network.collectRouteIndices(markedPrevArr, markedPrevSize, routeSeenBuffer, routeResultBuffer)

        val ld = state.latestDeparture
        val sc = state.stopCount
        val roundOff = round * sc
        val prevRoundOff = (round - 1) * sc

        for (ri in 0 until routeCount) {
            val routeIdx = routeResultBuffer[ri]
            if (filterMask != null && !filterMask[routeIdx]) continue
            val route = network.routeList[routeIdx]

            val flat = route.flatStopTimes
            val stride = route.stopCountInRoute
            val overnight = route.hasOvernightTrips
            var currentTripIndex = -1
            var tripOffset = 0  // currentTripIndex * stride, cached
            var alightIndex = -1  // position where we leave the current trip (mirror of boardingIndex)

            val stopIndicesArray = network.routeStopIndices[routeIdx]

            // Scan positions in REVERSE: upstream stops come after the alighting stop
            for (i in stopIndicesArray.size - 1 downTo 0) {
                val stopIndex = stopIndicesArray[i]
                if (stopIndex == -1) continue

                // 1. If we are on a trip (alighting later at alightIndex), we can depart from this
                //    earlier stop at the trip's time here.
                if (currentTripIndex != -1) {
                    val departureTime = flat[tripOffset + i]

                    // Overnight wrap protection: times must decrease scanning backward
                    if (overnight && departureTime > flat[tripOffset + alightIndex]) continue

                    // Target + window pruning
                    if (departureTime > currentBestAtOrigin && departureTime >= earliestDeparture) {
                        if (departureTime > ld[roundOff + stopIndex]) {
                            ld[roundOff + stopIndex] = departureTime
                            state.markStop(stopIndex)
                        }
                        // A ride reaching an origin is a complete journey endpoint: it must feed D*
                        // even when the cell already holds a LATER walk-written label (walk-first
                        // journeys are not valid endpoints, but their labels can mask this cell).
                        if (originBuf[stopIndex]) {
                            currentBestAtOrigin = departureTime
                        }
                    }
                }

                // 2. Can we improve the current trip by alighting at this stop?
                if (state.isMarkedInPreviousRound(stopIndex)) {
                    val latestAtStop = ld[prevRoundOff + stopIndex]
                    // Quick-reject: if the current trip already arrives here no earlier than
                    // latestAtStop, no LATER trip is catchable (FIFO: later trips arrive even later)
                    if (currentTripIndex != -1 && latestAtStop <= flat[tripOffset + i]) continue

                    // Bounded search: only trips (currentTripIndex, tripCount) can improve
                    val searchFrom = if (currentTripIndex == -1) 0 else currentTripIndex + 1
                    val latestTripIdx = findLatestTripIndex(flat, searchFrom, route.tripCount, stride, i, latestAtStop)

                    if (latestTripIdx != -1) {
                        currentTripIndex = latestTripIdx
                        tripOffset = latestTripIdx * stride
                        alightIndex = i
                    }
                }
            }
        }
        return currentBestAtOrigin
    }

    /**
     * Binary search for the latest trip arriving at or before latestTime at the given stop,
     * within trip indices [searchFrom, tripCount). Mirror of [findEarliestTripIndex]; relies on
     * the same FIFO column ordering. Returns the trip index, or -1 if no trip qualifies.
     */
    private fun findLatestTripIndex(
        flatStopTimes: IntArray, searchFrom: Int, tripCount: Int, stride: Int,
        stopIndexInRoute: Int, latestTime: Int
    ): Int {
        var low = searchFrom
        var high = tripCount - 1
        var result = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (flatStopTimes[mid * stride + stopIndexInRoute] <= latestTime) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun exploreTransfersBackward(
        state: BackwardRaptorState,
        round: Int,
        bestAtOrigin: Int,
        earliestDeparture: Int
    ) {
        // Capture count before iteration — markStop() only appends (mirror of exploreTransfers)
        val markedCount = state.getMarkedCount()
        val ld = state.latestDeparture
        val roundOff = round * state.stopCount

        for (mi in 0 until markedCount) {
            val stopIndex = state.getMarkedAt(mi)
            val departureTime = ld[roundOff + stopIndex]

            // Explicit INCOMING transfers: to walk source -> stopIndex and leave stopIndex at
            // departureTime, we must leave the source by departureTime - walkTime.
            val transfers = network.reverseTransferData[stopIndex]
            var t = 0
            while (t < transfers.size) {
                val sourceStopIndex = transfers[t]
                val walkTime = transfers[t + 1]
                t += 2
                if (sourceStopIndex == stopIndex) continue
                val departureAtSource = departureTime - walkTime
                if (departureAtSource <= bestAtOrigin || departureAtSource < earliestDeparture) continue

                if (departureAtSource > ld[roundOff + sourceStopIndex]) {
                    ld[roundOff + sourceStopIndex] = departureAtSource
                    state.markStop(sourceStopIndex)
                }
            }

            // Implicit transfers: same-name stops, symmetric adjacency, 120s default transfer time
            val implicitSources = network.implicitTransferData[stopIndex]
            for (otherStopIndex in implicitSources) {
                val departureAtSource = departureTime - 120
                if (departureAtSource <= bestAtOrigin || departureAtSource < earliestDeparture) continue

                if (departureAtSource > ld[roundOff + otherStopIndex]) {
                    ld[roundOff + otherStopIndex] = departureAtSource
                    state.markStop(otherStopIndex)
                }
            }
        }
    }

    private fun exploreTransfers(state: RaptorState, round: Int, bestAtDest: Int) {
        // Capture count before iteration — safe because markStop() only appends
        val markedCount = state.getMarkedCount()
        val ba = state.bestArrival
        val roundOff = round * state.stopCount

        for (mi in 0 until markedCount) {
            val stopIndex = state.getMarkedAt(mi)
            val arrivalTime = ba[roundOff + stopIndex]

            // Explicit transfers (pre-computed: [targetIdx, walkTime, targetIdx, walkTime, ...])
            val transfers = network.transferData[stopIndex]
            var t = 0
            while (t < transfers.size) {
                val targetStopIndex = transfers[t]
                val walkTime = transfers[t + 1]
                t += 2
                if (targetStopIndex == -1 || targetStopIndex == stopIndex) continue
                val arrivalAtTarget = arrivalTime + walkTime
                if (arrivalAtTarget >= bestAtDest) continue

                if (arrivalAtTarget < ba[roundOff + targetStopIndex]) {
                    if (debug) {
                        println("  Walk (explicit) transfer: ${network.stops[stopIndex].name} -> ${network.stops[targetStopIndex].name} (${formatTime(arrivalAtTarget)})")
                    }
                    ba[roundOff + targetStopIndex] = arrivalAtTarget
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
                if (arrivalAtTarget >= bestAtDest) continue

                if (arrivalAtTarget < ba[roundOff + otherStopIndex]) {
                    if (debug) {
                        println("  Implicit transfer: ${network.stops[stopIndex].name} -> ${network.stops[otherStopIndex].name} (${formatTime(arrivalAtTarget)})")
                    }
                    ba[roundOff + otherStopIndex] = arrivalAtTarget
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
        fun p(v: Int): String = v.toString().padStart(2, '0')
        return if (h >= 24) "${p(h - 24)}:${p(m)}:${p(s)}(+1)"
        else "${p(h)}:${p(m)}:${p(s)}"
    }

    fun getArrivalTime(stopIndex: Int, round: Int): Int {
        val s = lastState ?: return Int.MAX_VALUE
        val idx = round * s.stopCount + stopIndex
        return if (idx >= 0 && idx < s.bestArrival.size) s.bestArrival[idx] else Int.MAX_VALUE
    }

    fun getJourney(destinationIndex: Int, round: Int? = null): List<JourneyLeg>? {
        return lastState?.reconstructJourney(destinationIndex, round)
    }
}
