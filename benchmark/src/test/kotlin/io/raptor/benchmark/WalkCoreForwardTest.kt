package io.raptor.benchmark

import io.raptor.benchmark.SyntheticNetworkBuilder.StopDef
import io.raptor.core.RaptorAlgorithm
import io.raptor.model.Network
import io.raptor.model.Transfer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the walking access/egress support in the forward RAPTOR core:
 * round-0 access seeding, egress-adjusted target pruning, initial bound, and strict
 * equivalence with the classic (no-walk) behavior. Fully synthetic — no .bin dataset.
 *
 * Times are seconds from midnight (08:00:00 = 28800).
 */
class WalkCoreForwardTest {

    private val dep0800 = 28800

    /**
     * S1 --X--> S3 --Y--> S4, plus an explicit 120 s walk transfer S3 -> S4.
     * X: trip1 [28900 -> 29400], trip2 [29200 -> 29700]
     * Y: trip  [29460 -> 29500]
     * From S1 at 08:00, S4 is reachable at 29520 (X + walk, round 1) or 29500 (X + Y, round 2).
     */
    private fun transferNetwork(): Network = SyntheticNetworkBuilder.network(
        stops = listOf(
            StopDef(1, "Alpha"),
            StopDef(2, "Beta"),
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

        // Old arity, null walk arrays, and explicit zero walk arrays must behave identically
        val algoOld = RaptorAlgorithm(network)
        val resultOld = algoOld.route(origins, dests, dep0800, null, 5)

        val algoNull = RaptorAlgorithm(network)
        val resultNull = algoNull.route(
            origins, dests, dep0800, null, 5,
            accessSeconds = null, egressSeconds = null, initialBestArrival = Int.MAX_VALUE
        )

        val algoZero = RaptorAlgorithm(network)
        val resultZero = algoZero.route(
            origins, dests, dep0800, null, 5,
            accessSeconds = IntArray(origins.size),
            egressSeconds = IntArray(dests.size)
        )

        assertEquals(29500, resultOld)
        assertEquals(resultOld, resultNull)
        assertEquals(resultOld, resultZero)

        // Every per-round label must match too
        for (stop in 0 until network.stopCount) {
            for (round in 0..5) {
                val old = algoOld.getArrivalTime(stop, round)
                assertEquals("stop $stop round $round (null arrays)", old, algoNull.getArrivalTime(stop, round))
                assertEquals("stop $stop round $round (zero arrays)", old, algoZero.getArrivalTime(stop, round))
            }
        }

        // Sanity on the Pareto shape: round 1 = X + walk, round 2 = X + Y
        val s4 = network.getStopIndex(4)
        assertEquals(29520, algoOld.getArrivalTime(s4, 1))
        assertEquals(29500, algoOld.getArrivalTime(s4, 2))
    }

    @Test
    fun accessOffsetShiftsBoarding() {
        val network = transferNetwork()
        val origins = listOf(network.getStopIndex(1))
        val dests = listOf(network.getStopIndex(3))
        val algo = RaptorAlgorithm(network)

        // Without access walk: catches X trip1 (dep 28900) -> 29400
        assertEquals(29400, algo.route(origins, dests, dep0800))

        // 300 s access walk: seeded at 29100, misses trip1, catches trip2 (dep 29200) -> 29700
        assertEquals(29700, algo.route(origins, dests, dep0800, accessSeconds = intArrayOf(300)))
    }

    /**
     * The user scenario: "transit X + longer egress walk" must beat "transit X + transit Y"
     * when Y arrives too late — and lose when Y is early enough.
     * A (egress 600 s) and B (egress 10 s) are both destination stops.
     */
    private fun egressNetwork(yTrip: IntArray): Network = SyntheticNetworkBuilder.network(
        stops = listOf(
            StopDef(1, "Origin"),
            StopDef(3, "DestA"),
            StopDef(4, "DestB")
        ),
        routes = listOf(
            SyntheticNetworkBuilder.route(100, "X", intArrayOf(1, 3), listOf(intArrayOf(28860, 29400))),
            SyntheticNetworkBuilder.route(101, "Y", intArrayOf(3, 4), listOf(yTrip))
        )
    )

    @Test
    fun egressCompetitionLateSecondRideLoses() {
        // X reaches A at 29400, virtual destination via A = 30000.
        // Y arrives at B at 30600 -> 30610 with egress: worse, must not improve the result
        // (and its label write is target-pruned).
        val network = egressNetwork(yTrip = intArrayOf(30300, 30600))
        val algo = RaptorAlgorithm(network)
        val a = network.getStopIndex(3)
        val b = network.getStopIndex(4)

        val best = algo.route(
            listOf(network.getStopIndex(1)), listOf(a, b), dep0800,
            egressSeconds = intArrayOf(600, 10)
        )
        assertEquals(30000, best)
        assertEquals("pruned label must not be written", Int.MAX_VALUE, algo.getArrivalTime(b, 2))
    }

    @Test
    fun egressCompetitionEarlySecondRideWins() {
        // Y arrives at B at 29940 -> 29950 with egress: beats 30000 via A
        val network = egressNetwork(yTrip = intArrayOf(29520, 29940))
        val algo = RaptorAlgorithm(network)

        val best = algo.route(
            listOf(network.getStopIndex(1)), listOf(network.getStopIndex(3), network.getStopIndex(4)), dep0800,
            egressSeconds = intArrayOf(600, 10)
        )
        assertEquals(29950, best)
    }

    @Test
    fun transferWrittenDestinationLabelGetsEgress() {
        // In transferNetwork, S4 is reached in round 1 by a walk transfer (29520) and in round 2
        // by ride Y (29500). With a 100 s egress on S4 both are adjusted: best = 29600.
        val network = transferNetwork()
        val algo = RaptorAlgorithm(network)

        val best = algo.route(
            listOf(network.getStopIndex(1)), listOf(network.getStopIndex(4)), dep0800,
            egressSeconds = intArrayOf(100)
        )
        assertEquals(29600, best)
    }

    @Test
    fun initialBestArrivalPrunesSlowerTransit() {
        val network = egressNetwork(yTrip = intArrayOf(30300, 30600))
        val origins = listOf(network.getStopIndex(1))
        val dests = listOf(network.getStopIndex(3))
        val algo = RaptorAlgorithm(network)

        // Transit arrives at 29400: a 29000 bound (e.g. a faster direct walk) prunes everything
        assertEquals(Int.MAX_VALUE, algo.route(origins, dests, dep0800, initialBestArrival = 29000))
        // A 29500 bound lets it through
        assertEquals(29400, algo.route(origins, dests, dep0800, initialBestArrival = 29500))
    }

    @Test
    fun unreachableDestinationWithEgressStaysUnreachable() {
        // Isolated destination: MAX_VALUE label + egress must not overflow into a bogus best
        val network = SyntheticNetworkBuilder.network(
            stops = listOf(StopDef(1, "Origin"), StopDef(9, "Island")),
            routes = listOf(
                SyntheticNetworkBuilder.route(100, "X", intArrayOf(1), listOf(intArrayOf(28900)))
            )
        )
        val algo = RaptorAlgorithm(network)
        val best = algo.route(
            listOf(network.getStopIndex(1)), listOf(network.getStopIndex(9)), dep0800,
            egressSeconds = intArrayOf(600)
        )
        assertEquals(Int.MAX_VALUE, best)
    }

    @Test
    fun walkQueryLeavesNoStateBehind() {
        // A walking query followed by a classic query on the SAME algorithm instance must give
        // the same result as a fresh instance (egressAtStop must be cleared after each query).
        val network = egressNetwork(yTrip = intArrayOf(29520, 29940))
        val origins = listOf(network.getStopIndex(1))
        val dests = listOf(network.getStopIndex(3), network.getStopIndex(4))

        val reused = RaptorAlgorithm(network)
        reused.route(origins, dests, dep0800, egressSeconds = intArrayOf(600, 10))
        val afterWalkQuery = reused.route(origins, dests, dep0800)

        val fresh = RaptorAlgorithm(network)
        assertEquals(fresh.route(origins, dests, dep0800), afterWalkQuery)
    }
}
