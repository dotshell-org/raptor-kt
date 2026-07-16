package io.raptor.benchmark

import io.raptor.Location
import io.raptor.RaptorLibrary
import io.raptor.WalkingParams
import io.raptor.benchmark.SyntheticNetworkBuilder.StopDef
import io.raptor.core.LegType
import io.raptor.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests of the coordinate-based walking API at the RaptorLibrary level, going through
 * the real binary loading path (synthetic v1 payloads — no .bin dataset required).
 *
 * Geography: origin point O, destination point D ~3 km east (out of direct-walk range),
 * stops placed a few hundred meters from each point. Times in seconds from midnight.
 */
class WalkingQueryTest {

    private val metersPerDegreeLat = Math.PI / 180.0 * Geo.EARTH_RADIUS_METERS
    private val dep0800 = 28800
    private val walking = WalkingParams.DEFAULT

    private val oLat = 45.7500
    private val oLon = 4.8500
    private val dLat = 45.7500
    private val dLon = eastOf(45.7500, 4.8500, 3000.0)

    private fun northOf(lat: Double, meters: Double): Double = lat + meters / metersPerDegreeLat

    private fun eastOf(lat: Double, lon: Double, meters: Double): Double =
        lon + meters / (metersPerDegreeLat * kotlin.math.cos(lat * Math.PI / 180.0))

    private fun walkSecondsBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int =
        walking.walkSeconds(Geo.distanceMeters(lat1, lon1, lat2, lon2))

    // ── Simple network: S1 near O, S2 near D, one line between them ──────────────

    private val s1Lat = northOf(oLat, 200.0)
    private val s2Lat = northOf(dLat, 150.0)

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
    fun coordinateToCoordinateEndToEnd() {
        val library = simpleLibrary()
        val journeys = library.getOptimizedPaths(
            Location.Point(oLat, oLon), Location.Point(dLat, dLon), dep0800
        )

        assertEquals(1, journeys.size)
        val legs = journeys[0]
        assertEquals(3, legs.size)
        val (access, ride, egress) = Triple(legs[0], legs[1], legs[2])

        val expectedAccess = walkSecondsBetween(oLat, oLon, s1Lat, oLon)
        assertEquals(LegType.WALK_ACCESS, access.legType)
        assertTrue(access.isTransfer)
        assertEquals(-1, access.fromStopIndex)
        assertEquals(0, access.toStopIndex)
        // Anchored to the boarding: leave as late as possible, arrive exactly at departure
        assertEquals(29100 - expectedAccess, access.departureTime)
        assertEquals(29100, access.arrivalTime)
        assertEquals(oLat, access.fromLat!!, 1e-9)
        assertEquals(oLon, access.fromLon!!, 1e-9)
        assertEquals(s1Lat, access.toLat!!, 1e-9)
        assertEquals("the walk ends exactly at boarding", ride.departureTime, access.arrivalTime)

        assertEquals(LegType.TRANSIT, ride.legType)
        assertEquals("L1", ride.routeName)
        assertEquals(29100, ride.departureTime)
        assertEquals(29800, ride.arrivalTime)

        val expectedEgress = walkSecondsBetween(dLat, dLon, s2Lat, dLon)
        assertEquals(LegType.WALK_EGRESS, egress.legType)
        assertTrue(egress.isTransfer)
        assertEquals(1, egress.fromStopIndex)
        assertEquals(-1, egress.toStopIndex)
        assertEquals(29800, egress.departureTime)
        assertEquals(29800 + expectedEgress, egress.arrivalTime)
        assertEquals(dLat, egress.toLat!!, 1e-9)
        assertEquals(dLon, egress.toLon!!, 1e-9)
    }

    @Test
    fun stopToCoordinateHasNoAccessLeg() {
        val library = simpleLibrary()
        val journeys = library.getOptimizedPaths(
            Location.StopIds(listOf(1)), Location.Point(dLat, dLon), dep0800
        )

        assertEquals(1, journeys.size)
        val legs = journeys[0]
        assertEquals(2, legs.size)
        assertEquals(LegType.TRANSIT, legs[0].legType)
        assertEquals(LegType.WALK_EGRESS, legs[1].legType)
    }

    @Test
    fun coordinateToStopHasNoEgressLeg() {
        val library = simpleLibrary()
        val journeys = library.getOptimizedPaths(
            Location.Point(oLat, oLon), Location.StopIds(listOf(2)), dep0800
        )

        assertEquals(1, journeys.size)
        val legs = journeys[0]
        assertEquals(2, legs.size)
        assertEquals(LegType.WALK_ACCESS, legs[0].legType)
        assertEquals(LegType.TRANSIT, legs[1].legType)
    }

    @Test
    fun pointExactlyOnStopNeedsNoAccessLeg() {
        val library = simpleLibrary()
        val journeys = library.getOptimizedPaths(
            Location.Point(s1Lat, oLon), Location.Point(dLat, dLon), dep0800
        )

        assertEquals(1, journeys.size)
        val legs = journeys[0]
        assertEquals(2, legs.size)
        assertEquals(LegType.TRANSIT, legs[0].legType)
        assertEquals(LegType.WALK_EGRESS, legs[1].legType)
    }

    @Test
    fun nearbyPointsGetPureWalkOnly() {
        val library = simpleLibrary()
        // 300 m apart: direct walk in range, no useful transit (L1 goes the other way)
        val d2Lon = eastOf(oLat, oLon, 300.0)
        val journeys = library.getOptimizedPaths(
            Location.Point(oLat, oLon), Location.Point(oLat, d2Lon), dep0800
        )

        assertEquals(1, journeys.size)
        val legs = journeys[0]
        assertEquals(1, legs.size)
        val walk = legs[0]
        assertEquals(LegType.WALK_DIRECT, walk.legType)
        assertTrue(walk.isTransfer)
        assertEquals(-1, walk.fromStopIndex)
        assertEquals(-1, walk.toStopIndex)
        assertEquals(dep0800, walk.departureTime)
        assertEquals(dep0800 + walkSecondsBetween(oLat, oLon, oLat, d2Lon), walk.arrivalTime)
    }

    @Test
    fun noStopsInRangeAndTooFarToWalkGivesNoJourney() {
        val library = simpleLibrary()
        val journeys = library.getOptimizedPaths(
            Location.Point(46.5, 5.5), Location.Point(46.6, 5.6), dep0800
        )
        assertTrue(journeys.isEmpty())
    }

    @Test
    fun stopIdsLocationDelegatesToClassicOverload() {
        val library = simpleLibrary()
        val legacy = library.getOptimizedPaths(listOf(1), listOf(2), dep0800)
        val viaLocation = library.getOptimizedPaths(
            Location.StopIds(listOf(1)), Location.StopIds(listOf(2)), dep0800
        )
        assertTrue(legacy.isNotEmpty())
        assertEquals(legacy, viaLocation)
    }

    // ── The user scenario: ride X + longer egress walk vs ride X + ride Y ────────

    private val aLat = northOf(dLat, 400.0) // DestA: 400 m from D -> long egress
    private val bLat = northOf(dLat, 50.0)  // DestB: 50 m from D -> short egress

    private fun competitionLibrary(yTrip: IntArray): RaptorLibrary {
        val (stopsBytes, routesBytes) = SyntheticNetworkBuilder.encodeBinary(
            stops = listOf(
                StopDef(1, "StopNearOrigin", s1Lat, oLon),
                StopDef(3, "DestA", aLat, dLon),
                StopDef(4, "DestB", bLat, dLon)
            ),
            routes = listOf(
                SyntheticNetworkBuilder.route(100, "X", intArrayOf(1, 3), listOf(
                    intArrayOf(29100, 29400)
                )),
                SyntheticNetworkBuilder.route(101, "Y", intArrayOf(3, 4), listOf(yTrip))
            )
        )
        return RaptorLibrary(stopsBytes, routesBytes)
    }

    @Test
    fun ridePlusWalkBeatsWaitingForLateSecondRide() {
        // Y leaves DestA only at 30300: X + 400 m walk (arrives ~29790) must win and be the
        // ONLY journey — the X+Y option (arrives ~30649) is dominated and pruned.
        val library = competitionLibrary(yTrip = intArrayOf(30300, 30600))
        val journeys = library.getOptimizedPaths(
            Location.Point(oLat, oLon), Location.Point(dLat, dLon), dep0800
        )

        assertEquals(1, journeys.size)
        val legs = journeys[0]
        assertEquals(3, legs.size)
        assertEquals(LegType.WALK_ACCESS, legs[0].legType)
        assertEquals("X", legs[1].routeName)
        assertEquals(LegType.WALK_EGRESS, legs[2].legType)
        val expectedEgressA = walkSecondsBetween(dLat, dLon, aLat, dLon)
        assertEquals(29400 + expectedEgressA, legs[2].arrivalTime)
    }

    @Test
    fun earlySecondRideStillWinsWhenFaster() {
        // Y leaves DestA at 29520 and reaches DestB at 29700: X+Y+short walk (~29749) beats
        // X + long walk (~29790); both are Pareto-optimal (1 ride vs 2 rides).
        val library = competitionLibrary(yTrip = intArrayOf(29520, 29700))
        val journeys = library.getOptimizedPaths(
            Location.Point(oLat, oLon), Location.Point(dLat, dLon), dep0800
        )

        assertEquals(2, journeys.size)

        val oneRide = journeys[0]
        assertEquals(3, oneRide.size)
        assertEquals("X", oneRide[1].routeName)
        val expectedEgressA = walkSecondsBetween(dLat, dLon, aLat, dLon)
        assertEquals(29400 + expectedEgressA, oneRide.last().arrivalTime)

        val twoRides = journeys[1]
        assertEquals(4, twoRides.size)
        assertEquals(LegType.WALK_ACCESS, twoRides[0].legType)
        assertEquals("X", twoRides[1].routeName)
        assertEquals("Y", twoRides[2].routeName)
        assertEquals(LegType.WALK_EGRESS, twoRides[3].legType)
        val expectedEgressB = walkSecondsBetween(dLat, dLon, bLat, dLon)
        assertEquals(29700 + expectedEgressB, twoRides.last().arrivalTime)

        assertTrue(
            "the 2-ride journey must arrive strictly earlier",
            twoRides.last().arrivalTime < oneRide.last().arrivalTime
        )
    }
}
