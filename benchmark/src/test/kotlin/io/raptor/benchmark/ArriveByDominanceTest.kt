package io.raptor.benchmark

import io.raptor.RaptorLibrary
import io.raptor.core.JourneyLeg
import io.raptor.data.NetworkLoader
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Dominance guard for getOptimizedPathsArriveBy.
 *
 * Reimplements the historical binary-search strategy (60s grid, 120min window) in test code,
 * using only the public forward API, as a REFERENCE. Whatever the production implementation is
 * (binary search today, backward RAPTOR tomorrow), it must never do worse than this reference:
 *   (a) feasibility is never lost (reference finds a journey => production finds one),
 *   (b) the achieved departure time is never earlier than the reference's (exact search may be later),
 *   (c) every returned journey arrives at or before the requested arrival time.
 *
 * Unlike RegressionTest (exact output hash), these are inequalities: they stay valid when the
 * implementation legitimately improves (e.g. dropping the 60s grid quantization).
 */
class ArriveByDominanceTest {

    companion object {
        private const val QUERY_COUNT = 150
        private const val SEARCH_WINDOW_SECONDS = 120 * 60
        private const val STEP_SECONDS = 60

        private lateinit var library: RaptorLibrary
        private lateinit var stopIds: List<Int>

        @BeforeClass
        @JvmStatic
        fun setup() {
            val config = DatasetConfig.LYON
            require(config.isAvailable()) { "LYON data not available at ${config.stopsPath()}" }

            library = RaptorLibrary(
                config.stopsPath().readBytes(),
                config.routesPath().readBytes()
            )
            stopIds = NetworkLoader.loadStops(config.stopsPath().readBytes()).map { it.id }
        }

        private fun bestArrival(journeys: List<List<JourneyLeg>>): Int =
            journeys.minOfOrNull { it.last().arrivalTime } ?: Int.MAX_VALUE

        private fun bestDeparture(journeys: List<List<JourneyLeg>>): Int =
            journeys.maxOfOrNull { it.first().departureTime } ?: Int.MIN_VALUE

        /**
         * Reference arrive-by: binary search over departure times on the public forward API,
         * mirroring the historical getOptimizedPathsArriveBy semantics (60s steps, 120min window).
         */
        private fun bisectReference(
            originIds: List<Int>,
            destIds: List<Int>,
            arrivalTime: Int
        ): List<List<JourneyLeg>> {
            var low = maxOf(0, arrivalTime - SEARCH_WINDOW_SECONDS)
            var high = arrivalTime
            var bestMid = -1

            while (low <= high) {
                val mid = (low + high) / 2
                val journeys = library.getOptimizedPaths(originIds, destIds, mid)
                if (bestArrival(journeys) <= arrivalTime) {
                    bestMid = mid
                    low = mid + STEP_SECONDS
                } else {
                    high = mid - STEP_SECONDS
                }
            }

            if (bestMid == -1) return emptyList()
            return library.getOptimizedPaths(originIds, destIds, bestMid)
                .filter { it.last().arrivalTime <= arrivalTime }
        }
    }

    @Test
    fun arriveByNeverWorseThanBisectReference() {
        val queries = RandomQueryGenerator(stopIds, seed = 2026L).generate(QUERY_COUNT)
        var comparable = 0

        for ((qIdx, q) in queries.withIndex()) {
            val arrivalTime = q.departureTime + 3600

            val reference = bisectReference(q.originIds, q.destIds, arrivalTime)
            val actual = library.getOptimizedPathsArriveBy(q.originIds, q.destIds, arrivalTime)

            // (c) hard constraint: every journey arrives on time
            for ((jIdx, journey) in actual.withIndex()) {
                if (journey.isEmpty()) continue
                assertTrue(
                    "Query $qIdx journey $jIdx arrives at ${journey.last().arrivalTime}, after requested $arrivalTime",
                    journey.last().arrivalTime <= arrivalTime
                )
            }

            if (reference.isEmpty()) continue

            // (a) feasibility never lost
            assertTrue(
                "Query $qIdx: reference (bisect) found a journey but production returned none " +
                    "(origins=${q.originIds} dests=${q.destIds} arrivalTime=$arrivalTime)",
                actual.isNotEmpty()
            )

            // (b) departure never earlier than the reference's
            val refDep = bestDeparture(reference)
            val actDep = bestDeparture(actual)
            assertTrue(
                "Query $qIdx: production departs at $actDep, earlier than bisect reference $refDep",
                actDep >= refDep
            )
            comparable++
        }

        assertTrue("Expected at least 20 comparable (feasible) queries, got $comparable", comparable >= 20)
    }
}
