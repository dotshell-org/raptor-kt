package io.raptor.benchmark

import io.raptor.Location
import io.raptor.RaptorLibrary
import io.raptor.WalkingParams
import io.raptor.benchmark.SyntheticNetworkBuilder.StopDef
import io.raptor.core.LegType
import io.raptor.core.RaptorAlgorithm
import io.raptor.geo.Geo
import io.raptor.model.Network
import io.raptor.model.Transfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the walking access/egress support in the backward (arrive-by) RAPTOR search:
 * egress-shifted round-0 seeding, access-adjusted D*, the forward/backward mirror, and the
 * facade arrive-by overload. Fully synthetic — no .bin dataset. Times in seconds from midnight.
 */
class WalkingArriveByTest {

    private val earliest = 27000 // search-window floor used by core-level tests

    // ── Core: classic equivalence ────────────────────────────────────────────────

    /** Same shape as WalkCoreForwardTest.transferNetwork: X(1->3), Y(3->4), walk 3->4. */
    private fun transferNetwork(): Network = SyntheticNetworkBuilder.network(
        stops = listOf(
            StopDef(1, "Alpha"),
            StopDef(3, "Gamma", transfers = listOf(Transfer(targetStopId = 4, walkTime = 120))),
            StopDef(4, "Delta")
        ),
        routes = listOf(
            SyntheticNetworkBuilder.route(100, "X", intArrayOf(1, 3), listOf(
                intArrayOf(28900, 29400),
                intArrayOf(29200, 29700)
            )),
            SyntheticNetworkBuilder.route(101, "Y", intArrayOf(3, 4), listOf(
                intArrayOf(29460, 29500)
            ))
        )
    )

    @Test
    fun classicEquivalenceAcrossArities() {
        val network = transferNetwork()
        val origins = listOf(network.getStopIndex(1))
        val dests = listOf(network.getStopIndex(4))

        for (arrivalTime in listOf(29500, 29520, 30000, 29499)) {
            val old = RaptorAlgorithm(network)
                .routeBackward(origins, dests, arrivalTime, earliest, null, 5)
            val nulls = RaptorAlgorithm(network).routeBackward(
                origins, dests, arrivalTime, earliest, null, 5,
                accessSeconds = null, egressSeconds = null, initialBestDeparture = Int.MIN_VALUE
            )
            val zeros = RaptorAlgorithm(network).routeBackward(
                origins, dests, arrivalTime, earliest, null, 5,
                accessSeconds = IntArray(origins.size), egressSeconds = IntArray(dests.size)
            )
            assertEquals("arrivalTime $arrivalTime (null arrays)", old, nulls)
            assertEquals("arrivalTime $arrivalTime (zero arrays)", old, zeros)
        }
    }

    // ── Core: egress shifts which trip is catchable ─────────────────────────────

    @Test
    fun egressShiftsCatchableTrip() {
        // X: departs 29100, reaches DestA at 29400; egress walk from DestA is 600 s.
        val network = SyntheticNetworkBuilder.network(
            stops = listOf(StopDef(1, "Origin"), StopDef(3, "DestA")),
            routes = listOf(
                SyntheticNetworkBuilder.route(100, "X", intArrayOf(1, 3), listOf(intArrayOf(29100, 29400)))
            )
        )
        val algo = RaptorAlgorithm(network)
        val origins = listOf(network.getStopIndex(1))
        val dests = listOf(network.getStopIndex(3))

        // Arrive by 30000: the stop must be reached by 29400 — exactly catchable
        assertEquals(29100, algo.routeBackward(
            origins, dests, 30000, earliest, null, 5, egressSeconds = intArrayOf(600)
        ))
        // Arrive by 29999: limit is 29399, the 29400 arrival no longer makes it
        assertEquals(Int.MIN_VALUE, algo.routeBackward(
            origins, dests, 29999, earliest, null, 5, egressSeconds = intArrayOf(600)
        ))
    }

    // ── Core: access walk adjusts D* across origin stops ────────────────────────

    @Test
    fun accessWalkAdjustsBestDeparture() {
        // Two origin stops reaching the same destination in time:
        // P departs FarStop at 29100 (600 s access) -> coordinate departure 28500
        // Q departs NearStop at 28800 (60 s access) -> coordinate departure 28740 (later => wins)
        val network = SyntheticNetworkBuilder.network(
            stops = listOf(StopDef(1, "FarStop"), StopDef(5, "NearStop"), StopDef(6, "Dest")),
            routes = listOf(
                SyntheticNetworkBuilder.route(110, "P", intArrayOf(1, 6), listOf(intArrayOf(29100, 29500))),
                SyntheticNetworkBuilder.route(111, "Q", intArrayOf(5, 6), listOf(intArrayOf(28800, 29500)))
            )
        )
        val algo = RaptorAlgorithm(network)
        val best = algo.routeBackward(
            listOf(network.getStopIndex(1), network.getStopIndex(5)),
            listOf(network.getStopIndex(6)),
            29600, earliest, null, 5,
            accessSeconds = intArrayOf(600, 60)
        )
        assertEquals(28740, best)
    }

    // ── Core: mirror property — forward at D* arrives on time ───────────────────

    @Test
    fun forwardRunAtBackwardOptimumArrivesOnTime() {
        val network = SyntheticNetworkBuilder.network(
            stops = listOf(StopDef(1, "Origin"), StopDef(3, "DestA"), StopDef(4, "DestB")),
            routes = listOf(
                SyntheticNetworkBuilder.route(100, "X", intArrayOf(1, 3), listOf(intArrayOf(29100, 29400))),
                SyntheticNetworkBuilder.route(101, "Y", intArrayOf(3, 4), listOf(intArrayOf(29520, 29940)))
            )
        )
        val algo = RaptorAlgorithm(network)
        val origins = listOf(network.getStopIndex(1))
        val dests = listOf(network.getStopIndex(3), network.getStopIndex(4))
        val access = intArrayOf(300)
        val egress = intArrayOf(600, 10)
        val arrivalTime = 30100

        val dStar = algo.routeBackward(origins, dests, arrivalTime, earliest, null, 5,
            accessSeconds = access, egressSeconds = egress)
        assertEquals(28800, dStar) // X departs 29100, minus 300 s access

        val forwardBest = algo.route(origins, dests, dStar, null, 5,
            accessSeconds = access, egressSeconds = egress)
        assertTrue("forward re-run at D* must arrive by $arrivalTime, got $forwardBest",
            forwardBest <= arrivalTime)
    }

    // ── Facade: geographic fixtures (same layout as WalkingQueryTest) ───────────

    private val metersPerDegreeLat = Math.PI / 180.0 * Geo.EARTH_RADIUS_METERS
    private val walking = WalkingParams.DEFAULT

    private val oLat = 45.7500
    private val oLon = 4.8500
    private val dLat = 45.7500
    private val dLon = eastOf(45.7500, 4.8500, 3000.0)
    private val s1Lat = northOf(oLat, 200.0)
    private val s2Lat = northOf(dLat, 150.0)

    private fun northOf(lat: Double, meters: Double): Double = lat + meters / metersPerDegreeLat

    private fun eastOf(lat: Double, lon: Double, meters: Double): Double =
        lon + meters / (metersPerDegreeLat * kotlin.math.cos(lat * Math.PI / 180.0))

    private fun walkSecondsBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int =
        walking.walkSeconds(Geo.distanceMeters(lat1, lon1, lat2, lon2))

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
    fun arriveByCoordinateToCoordinateEndToEnd() {
        val library = simpleLibrary()
        val arrivalTime = 30000
        val journeys = library.getOptimizedPathsArriveBy(
            Location.Point(oLat, oLon), Location.Point(dLat, dLon), arrivalTime
        )

        assertEquals(1, journeys.size)
        val legs = journeys[0]
        assertEquals(3, legs.size)
        val (access, ride, egress) = Triple(legs[0], legs[1], legs[2])

        val expectedAccess = walkSecondsBetween(oLat, oLon, s1Lat, oLon)
        val expectedEgress = walkSecondsBetween(dLat, dLon, s2Lat, dLon)

        // D* = ride departure minus the access walk: leaving home later misses the ride
        assertEquals(LegType.WALK_ACCESS, access.legType)
        assertEquals(29100 - expectedAccess, access.departureTime)
        assertEquals(29100, access.arrivalTime)

        assertEquals(LegType.TRANSIT, ride.legType)
        assertEquals("L1", ride.routeName)
        assertEquals(29100, ride.departureTime)
        assertEquals(29800, ride.arrivalTime)

        assertEquals(LegType.WALK_EGRESS, egress.legType)
        assertEquals(29800, egress.departureTime)
        assertEquals(29800 + expectedEgress, egress.arrivalTime)
        assertTrue("must arrive by $arrivalTime", egress.arrivalTime <= arrivalTime)
    }

    @Test
    fun arriveByTooEarlyForTransitGivesNoJourney() {
        val library = simpleLibrary()
        // Ride reaches the stop at 29800 + egress > 29800: impossible to arrive by 29800,
        // and the 3 km separation is out of direct-walk range
        val journeys = library.getOptimizedPathsArriveBy(
            Location.Point(oLat, oLon), Location.Point(dLat, dLon), 29800
        )
        assertTrue(journeys.isEmpty())
    }

    @Test
    fun arriveByNearbyPointsWalkDepartsAsLateAsPossible() {
        val library = simpleLibrary()
        val arrivalTime = 30000
        val d2Lon = eastOf(oLat, oLon, 300.0)
        val journeys = library.getOptimizedPathsArriveBy(
            Location.Point(oLat, oLon), Location.Point(oLat, d2Lon), arrivalTime
        )

        assertEquals(1, journeys.size)
        val walk = journeys[0].single()
        assertEquals(LegType.WALK_DIRECT, walk.legType)
        assertEquals(arrivalTime, walk.arrivalTime)
        assertEquals(arrivalTime - walkSecondsBetween(oLat, oLon, oLat, d2Lon), walk.departureTime)
    }

    @Test
    fun arriveByWalkDominatesEarlierTransit() {
        // A transit option exists (early ride W to a stop near the destination) but the direct
        // walk departs much later: only the walk must be returned.
        val d3Lon = eastOf(oLat, oLon, 300.0)
        val s4Lat = northOf(oLat, 50.0)
        val (stopsBytes, routesBytes) = SyntheticNetworkBuilder.encodeBinary(
            stops = listOf(
                StopDef(1, "StopNearOrigin", s1Lat, oLon),
                StopDef(7, "StopNearDest3", s4Lat, d3Lon)
            ),
            routes = listOf(
                SyntheticNetworkBuilder.route(102, "W", intArrayOf(1, 7), listOf(
                    intArrayOf(27000, 27300)
                ))
            )
        )
        val library = RaptorLibrary(stopsBytes, routesBytes)

        val arrivalTime = 30000
        val journeys = library.getOptimizedPathsArriveBy(
            Location.Point(oLat, oLon), Location.Point(oLat, d3Lon), arrivalTime
        )

        assertEquals(1, journeys.size)
        val walk = journeys[0].single()
        assertEquals(LegType.WALK_DIRECT, walk.legType)
        assertEquals(arrivalTime, walk.arrivalTime)
    }

    @Test
    fun arriveByStopIdsLocationDelegatesToClassicOverload() {
        val library = simpleLibrary()
        val legacy = library.getOptimizedPathsArriveBy(listOf(1), listOf(2), 29900)
        val viaLocation = library.getOptimizedPathsArriveBy(
            Location.StopIds(listOf(1)), Location.StopIds(listOf(2)), 29900
        )
        assertTrue(legacy.isNotEmpty())
        assertEquals(legacy, viaLocation)
    }
}
