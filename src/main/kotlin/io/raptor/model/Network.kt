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
}