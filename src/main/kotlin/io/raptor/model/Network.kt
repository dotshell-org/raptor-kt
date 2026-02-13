package io.raptor.model

/**
 * A container holding all network data for the router.
 */
class Network(
    val stops: List<Stop>,
    routes: List<Route>
) {
    // Total number of stops in the network
    val stopCount: Int = stops.size

    // Quick access: stopId -> index in stops list (O(1) lookup)
    private val stopIdToIndex: HashMap<Int, Int> = HashMap<Int, Int>(stops.size * 2).also { map ->
        for (i in stops.indices) {
            map[stops[i].id] = i
        }
    }
    // Routes indexed by internal position (0..routeCount-1)
    val routeList: Array<Route> = routes.toTypedArray()
    val routeCount: Int = routeList.size

    // routeId -> internal indices in routeList (multiple routes can share the same id, e.g. directions)
    private val routeInternalIndices: HashMap<Int, IntArray> = HashMap<Int, IntArray>(routes.size * 2).also { map ->
        // First pass: count occurrences per routeId
        val counts = HashMap<Int, Int>(routes.size * 2)
        for (r in routes) {
            counts[r.id] = (counts[r.id] ?: 0) + 1
        }
        // Allocate arrays and fill
        val offsets = HashMap<Int, Int>(routes.size * 2)
        for ((id, count) in counts) {
            map[id] = IntArray(count)
            offsets[id] = 0
        }
        for (i in routes.indices) {
            val id = routes[i].id
            val arr = map[id]!!
            arr[offsets[id]!!] = i
            offsets[id] = offsets[id]!! + 1
        }
    }

    // Pre-computed: routeStopIndices[routeInternalIdx][posInRoute] = global stopIndex
    val routeStopIndices: Array<IntArray> = Array(routeList.size) { r ->
        val stopIds = routeList[r].stopIds
        IntArray(stopIds.size) { i -> stopIdToIndex[stopIds[i]] ?: -1 }
    }

    // Pre-computed transfer data: transferData[stopIndex] = [targetIdx0, walkTime0, targetIdx1, walkTime1, ...]
    val transferData: Array<IntArray> = Array(stops.size) { si ->
        val transfers = stops[si].transfers
        val arr = IntArray(transfers.size * 2)
        for (t in transfers.indices) {
            arr[t * 2] = stopIdToIndex[transfers[t].targetStopId] ?: -1
            arr[t * 2 + 1] = transfers[t].walkTime
        }
        arr
    }

    // Pre-computed: routeIndicesForStop[stopIndex] = array of internal route indices
    val routeIndicesForStop: Array<IntArray> = Array(stops.size) { si ->
        val routeIds = stops[si].routeIds
        // Count total indices first (a single routeId may map to multiple internal routes)
        var total = 0
        for (rid in routeIds) {
            total += routeInternalIndices[rid]?.size ?: 0
        }
        val indices = IntArray(total)
        var count = 0
        for (rid in routeIds) {
            val arr = routeInternalIndices[rid] ?: continue
            for (idx in arr) {
                indices[count++] = idx
            }
        }
        indices
    }

    // Index stops by name for implicit transfers
    val stopsByName: Map<String, List<Int>> = stops.indices.groupBy { stops[it].name }

    // Pre-computed implicit transfer data: implicitTransferData[stopIndex] = IntArray of other stop indices with same name
    val implicitTransferData: Array<IntArray> = Array(stops.size) { si ->
        val sameNameStops = stopsByName[stops[si].name] ?: emptyList()
        val filtered = IntArray(sameNameStops.size - 1)
        var count = 0
        for (idx in sameNameStops) {
            if (idx != si) filtered[count++] = idx
        }
        if (count == filtered.size) filtered else filtered.copyOf(count)
    }

    fun getStopIndex(id: Int): Int = stopIdToIndex[id] ?: -1

    /**
     * Collects route internal indices serving any of the given stops.
     * Uses a reusable BooleanArray for deduplication (no HashSet allocation).
     * Returns count of results written to resultBuffer.
     */
    fun collectRouteIndices(stopIndices: IntArray, stopCount: Int, seenBuffer: BooleanArray, resultBuffer: IntArray): Int {
        var count = 0
        for (idx in 0 until stopCount) {
            val si = stopIndices[idx]
            val routeIndices = routeIndicesForStop[si]
            for (routeIdx in routeIndices) {
                if (!seenBuffer[routeIdx]) {
                    seenBuffer[routeIdx] = true
                    resultBuffer[count++] = routeIdx
                }
            }
        }
        // Reset seen entries
        for (i in 0 until count) {
            seenBuffer[resultBuffer[i]] = false
        }
        return count
    }
}