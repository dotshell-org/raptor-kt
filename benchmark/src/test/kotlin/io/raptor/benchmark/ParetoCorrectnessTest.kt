package io.raptor.benchmark

import io.raptor.RaptorLibrary
import io.raptor.data.NetworkLoader
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.FileInputStream

/**
 * Validates RAPTOR algorithm correctness on random queries:
 * - Pareto optimality (no dominated journeys)
 * - Temporal consistency (legs properly chained)
 * - Arrive-by constraint (actual arrival <= requested arrival)
 */
class ParetoCorrectnessTest {

    companion object {
        private lateinit var library: RaptorLibrary
        private lateinit var stopIds: List<Int>

        @BeforeClass
        @JvmStatic
        fun setup() {
            val config = DatasetConfig.RTM // Smallest dataset = fastest tests
            require(config.isAvailable()) { "RTM data not available at ${config.stopsPath()}" }

            library = RaptorLibrary(
                FileInputStream(config.stopsPath()),
                FileInputStream(config.routesPath())
            )
            val stops = NetworkLoader.loadStops(FileInputStream(config.stopsPath()))
            stopIds = stops.map { it.id }
        }
    }

    /**
     * For any two journeys in a result set, neither should dominate the other.
     * A journey j1 dominates j2 if j1 has equal-or-better arrival time AND
     * equal-or-fewer transfers, with at least one strict improvement.
     */
    @Test
    fun testParetoOptimality() {
        val generator = RandomQueryGenerator(stopIds, seed = 777L)
        val queries = generator.generate(200)
        var checked = 0

        for (q in queries) {
            val journeys = library.getOptimizedPaths(q.originIds, q.destIds, q.departureTime)
            if (journeys.size < 2) continue

            for (i in journeys.indices) {
                for (j in i + 1 until journeys.size) {
                    val j1 = journeys[i]
                    val j2 = journeys[j]
                    val arr1 = j1.last().arrivalTime
                    val arr2 = j2.last().arrivalTime
                    val transfers1 = j1.count { !it.isTransfer } - 1
                    val transfers2 = j2.count { !it.isTransfer } - 1

                    val j1DominatesJ2 = arr1 <= arr2 && transfers1 <= transfers2 &&
                        (arr1 < arr2 || transfers1 < transfers2)
                    val j2DominatesJ1 = arr2 <= arr1 && transfers2 <= transfers1 &&
                        (arr2 < arr1 || transfers2 < transfers1)

                    assertFalse(
                        "Journey $i dominates journey $j: arr=$arr1 t=$transfers1 vs arr=$arr2 t=$transfers2",
                        j1DominatesJ2
                    )
                    assertFalse(
                        "Journey $j dominates journey $i: arr=$arr2 t=$transfers2 vs arr=$arr1 t=$transfers1",
                        j2DominatesJ1
                    )
                }
            }
            checked++
        }
        assertTrue("Should check at least 10 multi-journey queries, got $checked", checked >= 10)
    }

    /**
     * Verifies temporal consistency of each journey:
     * - departure <= arrival for each leg
     * - previous leg arrival <= next leg departure
     * - first leg departs at or after query departure time
     */
    @Test
    fun testJourneyTemporalConsistency() {
        val generator = RandomQueryGenerator(stopIds, seed = 888L)
        val queries = generator.generate(200)

        for (q in queries) {
            val journeys = library.getOptimizedPaths(q.originIds, q.destIds, q.departureTime)
            for ((jIdx, journey) in journeys.withIndex()) {
                if (journey.isEmpty()) continue

                assertTrue(
                    "Journey $jIdx first leg departs before query time: " +
                        "${journey.first().departureTime} < ${q.departureTime}",
                    journey.first().departureTime >= q.departureTime
                )

                for ((lIdx, leg) in journey.withIndex()) {
                    assertTrue(
                        "Journey $jIdx leg $lIdx: dep ${leg.departureTime} > arr ${leg.arrivalTime}",
                        leg.departureTime <= leg.arrivalTime
                    )
                    if (lIdx > 0) {
                        val prev = journey[lIdx - 1]
                        assertTrue(
                            "Journey $jIdx leg $lIdx: prev arr ${prev.arrivalTime} > dep ${leg.departureTime}",
                            prev.arrivalTime <= leg.departureTime
                        )
                    }
                }
            }
        }
    }

    /**
     * Verifies arrive-by results: actual arrival time must not exceed the requested arrival time.
     */
    @Test
    fun testArriveByConstraint() {
        val generator = RandomQueryGenerator(stopIds, seed = 999L)
        val queries = generator.generate(100)

        for (q in queries) {
            val arrivalTime = q.departureTime + 3600
            val journeys = library.getOptimizedPathsArriveBy(q.originIds, q.destIds, arrivalTime)
            for ((jIdx, journey) in journeys.withIndex()) {
                if (journey.isEmpty()) continue
                val actualArrival = journey.last().arrivalTime
                assertTrue(
                    "Arrive-by journey $jIdx arrives at $actualArrival, after requested $arrivalTime",
                    actualArrival <= arrivalTime
                )
            }
        }
    }
}
