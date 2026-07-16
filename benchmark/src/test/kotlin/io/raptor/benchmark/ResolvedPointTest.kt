package io.raptor.benchmark

import io.raptor.Location
import io.raptor.RaptorLibrary
import io.raptor.StopWalk
import io.raptor.WalkingParams
import io.raptor.benchmark.SyntheticNetworkBuilder.StopDef
import io.raptor.core.LegType
import io.raptor.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for Location.ResolvedPoint (caller-provided walk times, e.g. from a street-network
 * router) and the direct-walk duration override. Fully synthetic — no .bin dataset.
 */
class ResolvedPointTest {

    private val metersPerDegreeLat = Math.PI / 180.0 * Geo.EARTH_RADIUS_METERS
    private val dep0800 = 28800

    private val oLat = 45.7500
    private val oLon = 4.8500
    private val dLat = 45.7500
    private val dLon = eastOf(45.7500, 4.8500, 3000.0)
    private val s1Lat = northOf(oLat, 200.0)
    private val s2Lat = northOf(dLat, 150.0)

    private fun northOf(lat: Double, meters: Double): Double = lat + meters / metersPerDegreeLat

    private fun eastOf(lat: Double, lon: Double, meters: Double): Double =
        lon + meters / (metersPerDegreeLat * kotlin.math.cos(lat * Math.PI / 180.0))

    private fun simpleLibrary(): RaptorLibrary {
        val (stopsBytes, routesBytes) = SyntheticNetworkBuilder.encodeBinary(
            stops = listOf(
                StopDef(1, "StopNearOrigin", s1Lat, oLon),
                StopDef(2, "StopNearDest", s2Lat, dLon)
            ),
            routes = listOf(
                SyntheticNetworkBuilder.route(100, "L1", intArrayOf(1, 2), listOf(
                    intArrayOf(29100, 29800)
                ))
            )
        )
        return RaptorLibrary(stopsBytes, routesBytes)
    }

    @Test
    fun resolvedPointUsesProvidedAccessSeconds() {
        val library = simpleLibrary()
        val journeys = library.getOptimizedPaths(
            Location.ResolvedPoint(oLat, oLon, listOf(StopWalk(stopId = 1, walkSeconds = 250))),
            Location.Point(dLat, dLon),
            dep0800
        )

        assertEquals(1, journeys.size)
        val access = journeys[0].first()
        assertEquals(LegType.WALK_ACCESS, access.legType)
        // Provided seconds (not the ~195 s great-circle estimate), anchored to the 29100 boarding
        assertEquals(29100 - 250, access.departureTime)
        assertEquals(29100, access.arrivalTime)
    }

    @Test
    fun resolvedPointUsesProvidedEgressSeconds() {
        val library = simpleLibrary()
        val journeys = library.getOptimizedPaths(
            Location.Point(oLat, oLon),
            Location.ResolvedPoint(dLat, dLon, listOf(StopWalk(stopId = 2, walkSeconds = 500))),
            dep0800
        )

        assertEquals(1, journeys.size)
        val egress = journeys[0].last()
        assertEquals(LegType.WALK_EGRESS, egress.legType)
        assertEquals(29800 + 500, egress.arrivalTime)
    }

    @Test
    fun resolvedPointMatchesPointWhenSecondsMatch() {
        val library = simpleLibrary()
        // Same walk seconds as the internal Point resolution would compute
        val accessSeconds = WalkingParams.DEFAULT.walkSeconds(Geo.distanceMeters(oLat, oLon, s1Lat, oLon))

        val viaPoint = library.getOptimizedPaths(
            Location.Point(oLat, oLon), Location.Point(dLat, dLon), dep0800
        )
        val viaResolved = library.getOptimizedPaths(
            Location.ResolvedPoint(oLat, oLon, listOf(StopWalk(1, accessSeconds))),
            Location.Point(dLat, dLon),
            dep0800
        )

        assertTrue(viaPoint.isNotEmpty())
        assertEquals(viaPoint, viaResolved)
    }

    @Test
    fun unknownStopIdsAreIgnoredAndDuplicatesKeepTheSmallestWalk() {
        val library = simpleLibrary()
        val journeys = library.getOptimizedPaths(
            Location.ResolvedPoint(
                oLat, oLon,
                listOf(
                    StopWalk(stopId = 999, walkSeconds = 10), // unknown id: ignored
                    StopWalk(stopId = 1, walkSeconds = 400),
                    StopWalk(stopId = 1, walkSeconds = 250)   // duplicate: min wins
                )
            ),
            Location.Point(dLat, dLon),
            dep0800
        )

        assertEquals(1, journeys.size)
        // The kept (smallest) 250 s walk shows in the boarding-anchored departure
        assertEquals(29100 - 250, journeys[0].first().departureTime)
    }

    @Test
    fun directWalkOverrideReplacesDuration() {
        val library = simpleLibrary()
        val d2Lon = eastOf(oLat, oLon, 300.0) // walk-only pair (no useful transit)

        val journeys = library.getOptimizedPaths(
            Location.Point(oLat, oLon), Location.Point(oLat, d2Lon), dep0800,
            directWalkSecondsOverride = 777
        )
        assertEquals(1, journeys.size)
        val walk = journeys[0].single()
        assertEquals(LegType.WALK_DIRECT, walk.legType)
        assertEquals(dep0800 + 777, walk.arrivalTime)

        val arriveBy = library.getOptimizedPathsArriveBy(
            Location.Point(oLat, oLon), Location.Point(oLat, d2Lon), 30000,
            directWalkSecondsOverride = 777
        )
        assertEquals(1, arriveBy.size)
        val lateWalk = arriveBy[0].single()
        assertEquals(30000, lateWalk.arrivalTime)
        assertEquals(30000 - 777, lateWalk.departureTime)
    }
}
