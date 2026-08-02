package io.raptor.benchmark

import io.raptor.RaptorLibrary
import io.raptor.benchmark.SyntheticNetworkBuilder.StopDef
import io.raptor.core.RaptorAlgorithm
import io.raptor.core.StopFilter
import io.raptor.model.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-stop blocking and time penalties — the two levers a live disruption feed needs and that
 * route-level filtering cannot express.
 *
 * The distinction the tests pin down is the one that matters operationally: a blocked stop is not
 * a cut line. The vehicle still runs past it, so a journey that merely *passes through* is
 * untouched while boarding and alighting there become impossible. Times are seconds from midnight.
 */
class StopFilterTest {

    /** X: Alpha(1) → Beta(2) → Gamma(3), one trip at 30000 / 30300 / 30600. */
    private fun lineNetwork(): Network = SyntheticNetworkBuilder.network(
        stops = listOf(StopDef(1, "Alpha"), StopDef(2, "Beta"), StopDef(3, "Gamma")),
        routes = listOf(
            SyntheticNetworkBuilder.route(
                100, "X", intArrayOf(1, 2, 3),
                listOf(intArrayOf(30000, 30300, 30600))
            )
        )
    )

    /** X: Alpha(1) → Beta(2), then Y: Beta(2) → Gamma(3) with a tight and a loose connection. */
    private fun transferNetwork(): Network = SyntheticNetworkBuilder.network(
        stops = listOf(StopDef(1, "Alpha"), StopDef(2, "Beta"), StopDef(3, "Gamma")),
        routes = listOf(
            SyntheticNetworkBuilder.route(
                100, "X", intArrayOf(1, 2),
                listOf(intArrayOf(30000, 30300))
            ),
            SyntheticNetworkBuilder.route(
                101, "Y", intArrayOf(2, 3),
                listOf(intArrayOf(30360, 30500), intArrayOf(30800, 30900))
            )
        )
    )

    private fun Network.index(id: Int) = listOf(getStopIndex(id))

    // ── Blocking ────────────────────────────────────────────────────────────────

    @Test
    fun blockedStopIsStillPassedThrough() {
        val network = lineNetwork()
        val filter = StopFilter(blockedStopIndices = setOf(network.getStopIndex(2)))

        val plain = RaptorAlgorithm(network)
            .route(network.index(1), network.index(3), 29000)
        val blocked = RaptorAlgorithm(network)
            .route(network.index(1), network.index(3), 29000, stopFilter = filter)

        assertEquals("Alpha to Gamma is unaffected by closing Beta", 30600, plain)
        assertEquals("the vehicle runs past a closed stop, it does not vanish", plain, blocked)
    }

    @Test
    fun blockedStopCannotBeAlightedAt() {
        val network = lineNetwork()
        val filter = StopFilter(blockedStopIndices = setOf(network.getStopIndex(2)))

        val arrival = RaptorAlgorithm(network)
            .route(network.index(1), network.index(2), 29000, stopFilter = filter)

        assertEquals("Beta is closed, nobody gets off there", Int.MAX_VALUE, arrival)
    }

    @Test
    fun blockedStopCannotBeBoardedAt() {
        val network = lineNetwork()
        val filter = StopFilter(blockedStopIndices = setOf(network.getStopIndex(2)))

        val arrival = RaptorAlgorithm(network)
            .route(network.index(2), network.index(3), 29000, stopFilter = filter)

        assertEquals("Beta is closed, nobody gets on there", Int.MAX_VALUE, arrival)
    }

    // ── Penalties ───────────────────────────────────────────────────────────────

    @Test
    fun penaltyIsChargedOnArrivalAtTheStop() {
        val network = lineNetwork()
        val filter = StopFilter(penaltySecondsByStopIndex = mapOf(network.getStopIndex(2) to 120))

        val arrival = RaptorAlgorithm(network)
            .route(network.index(1), network.index(2), 29000, stopFilter = filter)

        assertEquals("scheduled 30300 plus the 120 s the stop is costing", 30420, arrival)
    }

    @Test
    fun penaltyDoesNotSlowDownARideThatOnlyPassesThrough() {
        val network = lineNetwork()
        val filter = StopFilter(penaltySecondsByStopIndex = mapOf(network.getStopIndex(2) to 120))

        val arrival = RaptorAlgorithm(network)
            .route(network.index(1), network.index(3), 29000, stopFilter = filter)

        assertEquals("a crowded platform costs nothing if you stay on the vehicle", 30600, arrival)
    }

    @Test
    fun penaltyCanMakeAConnectionUncatchable() {
        val network = transferNetwork()
        val tight = RaptorAlgorithm(network)
            .route(network.index(1), network.index(3), 29000)
        assertEquals("without a penalty the 30360 connection is caught", 30500, tight)

        val filter = StopFilter(penaltySecondsByStopIndex = mapOf(network.getStopIndex(2) to 120))
        val delayed = RaptorAlgorithm(network)
            .route(network.index(1), network.index(3), 29000, stopFilter = filter)

        assertEquals("arriving at 30420 misses Y at 30360, so the 30800 trip it is", 30900, delayed)
    }

    @Test
    fun penaltyLeavesAnItineraryStandingWhenNoAlternativeExists() {
        val network = lineNetwork()
        val filter = StopFilter(penaltySecondsByStopIndex = mapOf(network.getStopIndex(2) to 3600))

        val arrival = RaptorAlgorithm(network)
            .route(network.index(1), network.index(2), 29000, stopFilter = filter)

        // The whole point of WARNING versus BLOCKING: the journey gets worse, not impossible.
        assertNotEquals("a penalty must never make a stop unreachable", Int.MAX_VALUE, arrival)
        assertEquals(30300 + 3600, arrival)
    }

    // ── Buffer hygiene ──────────────────────────────────────────────────────────

    @Test
    fun filtersDoNotLeakIntoTheNextQueryOnTheSameInstance() {
        val network = transferNetwork()
        // Instances are cached per period and reused across queries, so a scatter that is not
        // fully cleared would silently disrupt every later itinerary.
        val algorithm = RaptorAlgorithm(network)
        val filter = StopFilter(
            blockedStopIndices = setOf(network.getStopIndex(3)),
            penaltySecondsByStopIndex = mapOf(network.getStopIndex(2) to 120)
        )

        assertEquals(
            Int.MAX_VALUE,
            algorithm.route(network.index(1), network.index(3), 29000, stopFilter = filter)
        )
        assertEquals(
            "the next query must see an undisrupted network",
            30500,
            algorithm.route(network.index(1), network.index(3), 29000)
        )
    }

    // ── Backward (arrive-by) mirror ─────────────────────────────────────────────

    @Test
    fun backwardSearchHonoursBlockedStops() {
        val network = lineNetwork()
        val filter = StopFilter(blockedStopIndices = setOf(network.getStopIndex(2)))

        val departure = RaptorAlgorithm(network).routeBackward(
            network.index(1), network.index(2), 31000, 29000, null, 5, stopFilter = filter
        )

        assertEquals("no departure reaches a stop nobody may alight at", Int.MIN_VALUE, departure)
    }

    @Test
    fun backwardPenaltyMirrorsTheForwardOne() {
        val network = transferNetwork()
        val filter = StopFilter(penaltySecondsByStopIndex = mapOf(network.getStopIndex(2) to 120))

        // Forward, the penalty pushes the arrival to 30900; asking to arrive by 30800 is therefore
        // infeasible while the unpenalised search still makes it at 30500.
        val plain = RaptorAlgorithm(network).routeBackward(
            network.index(1), network.index(3), 30800, 29000, null, 5
        )
        val penalised = RaptorAlgorithm(network).routeBackward(
            network.index(1), network.index(3), 30800, 29000, null, 5, stopFilter = filter
        )

        assertNotEquals("the unpenalised journey arrives by 30800", Int.MIN_VALUE, plain)
        assertEquals("the penalised one cannot", Int.MIN_VALUE, penalised)
    }

    // ── Public facade ───────────────────────────────────────────────────────────

    @Test
    fun facadeMapsStopIdsAndDropsUnknownOnes() {
        val stops = listOf(StopDef(1, "Alpha"), StopDef(2, "Beta"), StopDef(3, "Gamma"))
        val routes = listOf(
            SyntheticNetworkBuilder.route(
                100, "X", intArrayOf(1, 2),
                listOf(intArrayOf(30000, 30300))
            ),
            SyntheticNetworkBuilder.route(
                101, "Y", intArrayOf(2, 3),
                listOf(intArrayOf(30360, 30500), intArrayOf(30800, 30900))
            )
        )
        val (stopBytes, routeBytes) = SyntheticNetworkBuilder.encodeBinary(stops, routes)
        val library = RaptorLibrary(stopBytes, routeBytes)

        val plain = library.getOptimizedPaths(listOf(1), listOf(3), 29000)
        assertEquals(30500, plain.first().last().arrivalTime)

        // 999 does not exist in this timetable: it must be ignored, not break the query.
        val penalised = library.getOptimizedPaths(
            listOf(1), listOf(3), 29000,
            stopPenaltySeconds = mapOf(2 to 120, 999 to 600)
        )
        assertEquals(30900, penalised.first().last().arrivalTime)

        val blocked = library.getOptimizedPaths(listOf(1), listOf(2), 29000, blockedStopIds = setOf(2))
        assertTrue("Beta is closed, so no journey ends there", blocked.isEmpty())
    }
}
