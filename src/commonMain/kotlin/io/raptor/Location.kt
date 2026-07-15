package io.raptor

import kotlin.math.ceil

/**
 * An itinerary endpoint: either a set of stop ids (classic search) or an arbitrary WGS84 point
 * (e.g. a geocoded address or POI), reached on foot through nearby stops.
 */
sealed class Location {
    data class StopIds(val ids: List<Int>) : Location()
    data class Point(val lat: Double, val lon: Double) : Location()
}

/**
 * Walking model used to turn great-circle distances into walk durations.
 *
 * @param speedMetersPerSecond Average walking speed (default 4.8 km/h)
 * @param detourFactor Multiplier applied to the great-circle distance to approximate the real
 *        street-network path (default 1.3)
 * @param maxAccessEgressDistanceMeters Radius around a [Location.Point] within which stops are
 *        considered reachable on foot at the start/end of a journey
 * @param maxDirectWalkDistanceMeters Maximum distance for proposing a pure-walk journey
 */
data class WalkingParams(
    val speedMetersPerSecond: Double = 4.8 / 3.6,
    val detourFactor: Double = 1.3,
    val maxAccessEgressDistanceMeters: Double = 500.0,
    val maxDirectWalkDistanceMeters: Double = 1000.0
) {
    init {
        require(speedMetersPerSecond > 0) { "speedMetersPerSecond must be positive" }
        require(detourFactor >= 1.0) { "detourFactor must be >= 1.0" }
        require(maxAccessEgressDistanceMeters >= 0) { "maxAccessEgressDistanceMeters must be >= 0" }
        require(maxDirectWalkDistanceMeters >= 0) { "maxDirectWalkDistanceMeters must be >= 0" }
    }

    /**
     * Walk duration in seconds for a great-circle distance, detour factor applied.
     */
    fun walkSeconds(distanceMeters: Double): Int =
        ceil(distanceMeters * detourFactor / speedMetersPerSecond).toInt()

    companion object {
        val DEFAULT = WalkingParams()
    }
}
