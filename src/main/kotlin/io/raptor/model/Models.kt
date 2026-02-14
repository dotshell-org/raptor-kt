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
 * Uses @JvmField for hot-path fields to eliminate property accessor overhead.
 */
class Route(
    @JvmField val id: Int,
    @JvmField val name: String,
    @JvmField val stopIds: IntArray,         // Ordered list of stop IDs for this route
    @JvmField val tripCount: Int,            // Number of trips
    @JvmField val stopCountInRoute: Int,     // Number of stops in this route
    @JvmField val flatStopTimes: IntArray,   // Row-major: [trip0_stop0, trip0_stop1, ..., trip1_stop0, ...] sorted by first stop time
    @JvmField val tripIds: IntArray,         // Trip IDs in sorted order (parallel to flatStopTimes rows)
    @JvmField val hasOvernightTrips: Boolean // true if any trip has a time decrease (midnight wrap)
)