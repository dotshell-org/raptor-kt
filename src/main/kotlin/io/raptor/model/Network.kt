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
    private val routeMap = routes.associateBy { it.id }
    
    // Index stops by name for implicit transfers
    val stopsByName: Map<String, List<Int>> = stops.indices.groupBy { stops[it].name }

    fun getStop(id: Int): Stop? = stopIndexMap[id]

    fun getStopIndex(id: Int): Int {
        val stop = stopIndexMap[id]
        return if (stop != null) stops.indexOf(stop) else -1
    }

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
        return routeIds.mapNotNull { id -> routeMap[id] }.toSet()
    }
}