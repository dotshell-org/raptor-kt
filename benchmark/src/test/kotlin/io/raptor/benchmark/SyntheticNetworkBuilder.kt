package io.raptor.benchmark

import io.raptor.model.Network
import io.raptor.model.Route
import io.raptor.model.Stop
import io.raptor.model.Transfer

/**
 * Builds small hand-crafted networks for unit tests — no .bin dataset required.
 */
object SyntheticNetworkBuilder {

    class StopDef(
        val id: Int,
        val name: String,
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val transfers: List<Transfer> = emptyList()
    )

    /**
     * @param trips one IntArray of stop times per trip, parallel to [stopIds]. Trips must be
     *        sorted by time at every stop position (FIFO), as the loader guarantees for real data.
     */
    fun route(id: Int, name: String, stopIds: IntArray, trips: List<IntArray>): Route {
        val stopCount = stopIds.size
        val flat = IntArray(trips.size * stopCount)
        for (t in trips.indices) {
            require(trips[t].size == stopCount) { "trip $t has ${trips[t].size} times for $stopCount stops" }
            trips[t].copyInto(flat, t * stopCount)
        }
        var overnight = false
        for (row in trips) {
            for (i in 1 until row.size) {
                if (row[i] < row[i - 1]) overnight = true
            }
        }
        return Route(id, name, stopIds, trips.size, stopCount, flat, IntArray(trips.size) { it }, overnight)
    }

    /**
     * Builds a Network, computing each stop's routeIds from the routes that serve it
     * (routeIndicesForStop is empty otherwise and the stop can never board anything).
     */
    fun network(stops: List<StopDef>, routes: List<Route>): Network {
        val fullStops = stops.map { def ->
            val serving = routes.filter { r -> r.stopIds.any { it == def.id } }.map { it.id }.distinct()
            Stop(def.id, def.name, def.lat, def.lon, serving.toIntArray(), def.transfers)
        }
        return Network(fullStops, routes)
    }
}
