package io.raptor.benchmark

import io.raptor.WalkingParams
import io.raptor.geo.Geo
import io.raptor.model.Network
import io.raptor.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for the walking geo primitives (haversine, WalkingParams, findNearbyStops).
 * Fully synthetic — requires no .bin dataset.
 */
class GeoTest {

    // On a sphere of radius 6,371 km, one degree of latitude spans ~111,194.9 m.
    private val metersPerDegreeLat = Math.PI / 180.0 * Geo.EARTH_RADIUS_METERS

    @Test
    fun haversineZeroDistance() {
        assertEquals(0.0, Geo.distanceMeters(45.7578, 4.8320, 45.7578, 4.8320), 1e-9)
    }

    @Test
    fun haversineOneDegreeLatitude() {
        val d = Geo.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(metersPerDegreeLat, d, 50.0)
    }

    @Test
    fun haversineOneDegreeLongitudeAtEquator() {
        val d = Geo.distanceMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(metersPerDegreeLat, d, 50.0)
    }

    @Test
    fun haversineOneDegreeLongitudeAt60North() {
        // Longitude degrees shrink with cos(latitude): ~55,597 m at 60°N
        val d = Geo.distanceMeters(60.0, 0.0, 60.0, 1.0)
        assertEquals(metersPerDegreeLat * 0.5, d, 60.0)
    }

    @Test
    fun haversineParisLyon() {
        // Paris Notre-Dame -> Lyon Bellecour, ~391.5 km great-circle
        val d = Geo.distanceMeters(48.8566, 2.3522, 45.7640, 4.8357)
        assertEquals(391_500.0, d, 2_000.0)
    }

    @Test
    fun haversineIsSymmetric() {
        val a = Geo.distanceMeters(48.8566, 2.3522, 45.7640, 4.8357)
        val b = Geo.distanceMeters(45.7640, 4.8357, 48.8566, 2.3522)
        assertEquals(a, b, 1e-6)
    }

    @Test
    fun walkSecondsSimpleSpeeds() {
        val unit = WalkingParams(speedMetersPerSecond = 1.0, detourFactor = 1.0)
        assertEquals(0, unit.walkSeconds(0.0))
        assertEquals(100, unit.walkSeconds(100.0))

        val detour = WalkingParams(speedMetersPerSecond = 1.0, detourFactor = 1.5)
        assertEquals(150, detour.walkSeconds(100.0))
    }

    @Test
    fun walkSecondsRoundsUp() {
        val params = WalkingParams(speedMetersPerSecond = 2.0, detourFactor = 1.0)
        assertEquals(51, params.walkSeconds(101.0)) // 50.5 s -> 51
    }

    @Test
    fun walkSecondsDefaults() {
        // 4.8 km/h with detour 1.3: 500 m -> 487.5 s, 1000 m -> 975 s (±1 for fp noise)
        val sec500 = WalkingParams.DEFAULT.walkSeconds(500.0)
        assertTrue("500 m should take ~488 s, got $sec500", sec500 in 487..488)
        val sec1000 = WalkingParams.DEFAULT.walkSeconds(1000.0)
        assertTrue("1000 m should take ~975 s, got $sec1000", sec1000 in 975..976)
    }

    @Test
    fun walkingParamsValidation() {
        assertThrows(IllegalArgumentException::class.java) {
            WalkingParams(speedMetersPerSecond = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalkingParams(detourFactor = 0.9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalkingParams(maxAccessEgressDistanceMeters = -1.0)
        }
    }

    // --- findNearbyStops ---

    private val centerLat = 45.7578
    private val centerLon = 4.8320

    private fun stopAt(id: Int, name: String, lat: Double, lon: Double) =
        Stop(id, name, lat, lon, IntArray(0), emptyList())

    /** Places a stop [meters] due north of the center. */
    private fun northOffset(meters: Double): Double = centerLat + meters / metersPerDegreeLat

    /** Places a stop [meters] due east of the center. */
    private fun eastOffset(meters: Double): Double =
        centerLon + meters / (metersPerDegreeLat * kotlin.math.cos(centerLat * Math.PI / 180.0))

    private fun buildNetwork(): Network = Network(
        stops = listOf(
            stopAt(1, "Center", centerLat, centerLon),                 // 0 m
            stopAt(2, "North300", northOffset(300.0), centerLon),      // ~300 m
            stopAt(3, "East400", centerLat, eastOffset(400.0)),        // ~400 m
            stopAt(4, "Boundary499", northOffset(499.0), centerLon),   // ~499 m, prefilter edge
            stopAt(5, "Outside600", northOffset(600.0), centerLon),    // ~600 m
            stopAt(6, "Far", 45.80, 4.90)                              // several km
        ),
        routes = emptyList()
    )

    @Test
    fun findNearbyStopsFiltersByRadius() {
        val network = buildNetwork()
        val nearby = network.findNearbyStops(centerLat, centerLon, 500.0)

        val byIndex = nearby.associateBy { it.stopIndex }
        assertEquals(setOf(0, 1, 2, 3), byIndex.keys)

        assertEquals(0.0, byIndex[0]!!.distanceMeters, 1e-6)
        assertEquals(300.0, byIndex[1]!!.distanceMeters, 5.0)
        assertEquals(400.0, byIndex[2]!!.distanceMeters, 5.0)
        assertEquals(499.0, byIndex[3]!!.distanceMeters, 5.0)
    }

    @Test
    fun findNearbyStopsZeroRadius() {
        val network = buildNetwork()
        val nearby = network.findNearbyStops(centerLat, centerLon, 0.0)
        assertEquals(1, nearby.size)
        assertEquals(0, nearby[0].stopIndex)
    }

    @Test
    fun findNearbyStopsEmptyWhenNothingInRange() {
        val network = buildNetwork()
        val nearby = network.findNearbyStops(46.5, 5.5, 500.0)
        assertTrue(nearby.isEmpty())
    }

    @Test
    fun findNearbyStopsMatchesBruteForceHaversine() {
        // The bounding-box prefilter must never reject a stop the exact haversine would keep
        val network = buildNetwork()
        for (radius in listOf(100.0, 300.0, 499.0, 501.0, 2000.0, 10_000.0)) {
            val expected = network.stops.indices.filter {
                Geo.distanceMeters(centerLat, centerLon, network.stops[it].lat, network.stops[it].lon) <= radius
            }.toSet()
            val actual = network.findNearbyStops(centerLat, centerLon, radius).map { it.stopIndex }.toSet()
            assertEquals("radius $radius", expected, actual)
        }
    }
}
