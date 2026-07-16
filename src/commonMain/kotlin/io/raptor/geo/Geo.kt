package io.raptor.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle distance helpers for walking access/egress computations.
 */
object Geo {
    const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Haversine distance in meters between two WGS84 coordinates.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val a = sinLat * sinLat + cos(lat1.toRadians()) * cos(lat2.toRadians()) * sinLon * sinLon
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
}
