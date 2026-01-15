package io.raptor.model

/**
 * A container holding all network data for the router.
 */
class Network(
    val stops: List<Stop>,
    val routes: List<Route>
) {
    // Total number of stops in the network
    val stopCount: Int = stops.size

    // Quick access to a stop's index if needed
    private val stopIndexMap = stops.associateBy { it.id }

    fun getStop(id: Int): Stop? = stopIndexMap[id]

    /**
     * Identifies all routes that serve any of the given stops.
     */
    fun getRoutesServingStops(stopIndices: Collection<Int>): Set<Route> {
        val routeIds = mutableSetOf<Int>()
        for (stopIdx in stopIndices) {
            val stop = stops[stopIdx]
            for (routeId in stop.routeIds) {
                routeIds.add(routeId)
            }
        }
        // Map route IDs to actual route objects
        return routeIds.mapNotNull { id -> routes.find { it.id == id } }.toSet()
    }
}