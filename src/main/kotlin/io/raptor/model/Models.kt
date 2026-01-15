package io.raptor.model

/**
 * Represents a transit stop.
 */
data class Stop(
    val id: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val routeIds: IntArray,      // IDs of routes serving this stop
    val transfers: List<Transfer> // List of possible walking transfers
)

/**
 * Represents a walking transfer between two stops.
 */
data class Transfer(
    val targetStopId: Int, // Destination stop ID
    val walkTime: Int      // Walking time in seconds
)

/**
 * Represents a transit route (a line with a specific sequence of stops).
 */
data class Route(
    val id: Int,
    val name: String,
    val stopIds: IntArray, // Ordered list of stop IDs for this route
    val trips: List<Trip>  // List of scheduled trips for this route
)

/**
 * Represents a specific trip with its associated stop times.
 */
data class Trip(
    val id: Int,
    val stopTimes: IntArray // Arrival times at each stop (seconds since midnight)
) {
    /**
     * Checks if this trip is "earlier" than another trip.
     * In the context of RAPTOR, this usually means it departs earlier from a common reference point.
     * Here we just compare the first stop's time for simplicity, but in `exploreRoutes` 
     * it's used to compare trips already found.
     */
    fun isEarlierThan(other: Trip): Boolean {
        if (this.stopTimes.isEmpty() || other.stopTimes.isEmpty()) return false
        return this.stopTimes[0] < other.stopTimes[0]
    }
}