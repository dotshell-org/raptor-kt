package io.raptor.benchmark

import java.util.Random

/**
 * Generates reproducible random query pairs for benchmarking.
 * Fixed seed ensures the same queries are used across runs for comparability.
 */
data class QueryPair(
    val originIds: List<Int>,
    val destIds: List<Int>,
    val departureTime: Int
)

class RandomQueryGenerator(
    private val stopIds: List<Int>,
    private val seed: Long = 42L
) {
    private val rng = Random(seed)

    /**
     * Generate [count] random query pairs.
     * - Single stop ID per origin/destination for consistent benchmark semantics.
     * - Departure times uniformly between 6:00 and 22:00.
     * - Origin != destination guaranteed.
     */
    fun generate(count: Int): List<QueryPair> {
        val pairs = mutableListOf<QueryPair>()
        while (pairs.size < count) {
            val originIdx = rng.nextInt(stopIds.size)
            var destIdx = rng.nextInt(stopIds.size)
            if (destIdx == originIdx) destIdx = (destIdx + 1) % stopIds.size

            val depHour = 6 + rng.nextInt(16) // 6:00 to 21:xx
            val depMinute = rng.nextInt(60)
            val departureTime = depHour * 3600 + depMinute * 60

            pairs.add(
                QueryPair(
                    originIds = listOf(stopIds[originIdx]),
                    destIds = listOf(stopIds[destIdx]),
                    departureTime = departureTime
                )
            )
        }
        return pairs
    }
}
