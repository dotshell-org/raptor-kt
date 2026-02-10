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
    val trips: Array<Trip> // Scheduled trips for this route, sorted by departure time
)

/**
 * Represents a specific trip with its associated stop times.
 */
data class Trip(
    val id: Int,
    val stopTimes: IntArray // Arrival times at each stop (seconds since midnight)
)