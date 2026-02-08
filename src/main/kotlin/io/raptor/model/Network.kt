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
    private val routesById = routes.groupBy { it.id }
    private val routeByIdDirect: Map<Int, Route> = routes.associateBy { it.id }

    // Index stops by name for implicit transfers
    val stopsByName: Map<String, List<Int>> = stops.indices.groupBy { stops[it].name }

    fun getStopIndex(id: Int): Int = stopIdToIndex[id] ?: -1

    fun getRouteById(routeId: Int): Route? = routeByIdDirect[routeId]

    /**
     * Identifies all routes that serve any of the given stops.
     */
    fun getRoutesServingStops(stopIndices: Collection<Int>): Set<Route> {
        val routesToExplore = mutableSetOf<Route>()
        for (stopIdx in stopIndices) {
            val stop = stops[stopIdx]
            for (routeId in stop.routeIds) {
                routesById[routeId]?.let { routesToExplore.addAll(it) }
            }
        }
        return routesToExplore
    }
}