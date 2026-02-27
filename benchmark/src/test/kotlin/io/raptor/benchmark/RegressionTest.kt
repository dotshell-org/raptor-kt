package io.raptor.benchmark

import io.raptor.RaptorLibrary
import io.raptor.core.JourneyLeg
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.FileInputStream

/**
 * Regression test: computes deterministic hashes for known query results.
 * If the routing algorithm changes output, these hashes change.
 *
 * Workflow:
 * 1. First run: prints reference hashes to stdout
 * 2. Hard-code the hashes as expected values
 * 3. Subsequent runs: assert hashes match (any algorithm change = test failure)
 */
class RegressionTest {

    companion object {
        private lateinit var library: RaptorLibrary

        @BeforeClass
        @JvmStatic
        fun setup() {
            val config = DatasetConfig.RTM
            require(config.isAvailable()) { "RTM data not available at ${config.stopsPath()}" }

            library = RaptorLibrary(
                FileInputStream(config.stopsPath()),
                FileInputStream(config.routesPath())
            )
        }

        /**
         * Deterministic hash of journey structure, reusing the pattern from existing benchmarks.
         */
        fun journeyHash(journeys: List<List<JourneyLeg>>): String {
            var h = 17L
            for (journey in journeys) {
                h = h * 31 + journey.size
                for (leg in journey) {
                    h = h * 31 + leg.fromStopIndex
                    h = h * 31 + leg.toStopIndex
                    h = h * 31 + leg.departureTime
                    h = h * 31 + leg.arrivalTime
                    h = h * 31 + (if (leg.isTransfer) 1 else 0)
                    h = h * 31 + (leg.routeName?.hashCode()?.toLong() ?: 0L)
                }
            }
            return "%08x".format(h.toInt())
        }
    }

    /**
     * Known O-D pairs on RTM Marseille.
     * Departure at 08:00 (28800s), default maxRounds=5.
     *
     * On first run, hashes are printed. Once confirmed stable, uncomment
     * the assertEquals lines to lock in reference values.
     */
    @Test
    fun testReferenceQueriesStable() {
        val queries = listOf(
            "Vieux-Port" to "La Rose",
            "Castellane" to "Bougainville",
            "Gare St Charles" to "Rond-Point du Prado"
        )

        val hashes = queries.map { (origin, dest) ->
            val originIds = library.searchStopsByName(origin).map { it.id }
            val destIds = library.searchStopsByName(dest).map { it.id }
            val result = library.getOptimizedPaths(originIds, destIds, 8 * 3600)
            journeyHash(result)
        }

        println("=== Regression Reference Hashes (RTM, dep 08:00) ===")
        for ((i, pair) in queries.withIndex()) {
            println("  ${pair.first} -> ${pair.second}: ${hashes[i]}")
        }

        // TODO: Uncomment after first run to lock in reference values:
        // assertEquals("Vieux-Port -> La Rose", "<hash>", hashes[0])
        // assertEquals("Castellane -> Bougainville", "<hash>", hashes[1])
        // assertEquals("Gare St Charles -> Rond-Point du Prado", "<hash>", hashes[2])
    }

    /**
     * Arrive-by regression test.
     * Arrival at 09:00 (32400s), default searchWindow=120min.
     */
    @Test
    fun testArriveByReferenceQueriesStable() {
        val queries = listOf(
            "Vieux-Port" to "La Rose",
            "Castellane" to "Bougainville"
        )

        val hashes = queries.map { (origin, dest) ->
            val originIds = library.searchStopsByName(origin).map { it.id }
            val destIds = library.searchStopsByName(dest).map { it.id }
            val result = library.getOptimizedPathsArriveBy(originIds, destIds, 9 * 3600)
            journeyHash(result)
        }

        println("=== Arrive-By Regression Reference Hashes (RTM, arr 09:00) ===")
        for ((i, pair) in queries.withIndex()) {
            println("  ${pair.first} -> ${pair.second}: ${hashes[i]}")
        }

        // TODO: Uncomment after first run:
        // assertEquals("Arrive-by Vieux-Port -> La Rose", "<hash>", hashes[0])
        // assertEquals("Arrive-by Castellane -> Bougainville", "<hash>", hashes[1])
    }
}
