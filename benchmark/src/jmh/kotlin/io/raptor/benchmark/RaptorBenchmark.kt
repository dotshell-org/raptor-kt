package io.raptor.benchmark

import io.raptor.RaptorLibrary
import io.raptor.data.NetworkLoader
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Main RAPTOR routing benchmark.
 *
 * Measures forward and arrive-by routing performance across multiple datasets
 * with 1000 seeded random query pairs per dataset for statistical rigor.
 *
 * RaptorAlgorithm is NOT thread-safe (reuses mutable state), so all benchmarks
 * run single-threaded with Scope.Benchmark.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(3)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 1, timeUnit = TimeUnit.SECONDS)
open class RaptorBenchmark {

    @Param("PARIS", "FINLAND", "RTM")
    lateinit var dataset: String

    private lateinit var library: RaptorLibrary
    private lateinit var queries: List<QueryPair>
    private var queryIndex = 0

    @Setup(Level.Trial)
    fun setup() {
        val config = DatasetConfig.valueOf(dataset)
        require(config.isAvailable()) {
            "Dataset $dataset not available at ${config.stopsPath()} / ${config.routesPath()}"
        }

        // Load library
        library = RaptorLibrary(
            stopsInputStream = FileInputStream(config.stopsPath()),
            routesInputStream = FileInputStream(config.routesPath())
        )

        // Load stops separately to enumerate all IDs (getCurrentNetwork is private)
        val stops = NetworkLoader.loadStops(FileInputStream(config.stopsPath()))
        val allStopIds = stops.map { it.id }

        // Generate 1000 random queries with fixed seed for reproducibility
        val generator = RandomQueryGenerator(allStopIds, seed = 12345L)
        queries = generator.generate(1000)

        // Prime the RaptorAlgorithm cache (first call creates + caches the algorithm instance)
        library.getOptimizedPaths(queries[0].originIds, queries[0].destIds, queries[0].departureTime)

        queryIndex = 0
    }

    /**
     * Forward routing: measures single-query latency with rotating random pairs.
     * This is the primary benchmark — the hot path in production usage.
     */
    @Benchmark
    fun forwardRouting(bh: Blackhole) {
        val q = queries[queryIndex % queries.size]
        queryIndex++
        val result = library.getOptimizedPaths(q.originIds, q.destIds, q.departureTime)
        bh.consume(result)
    }

    /**
     * Arrive-by routing: uses binary search internally (~7x slower than forward).
     * Arrival time = departure time + 1 hour, with default 120min search window.
     */
    @Benchmark
    fun arriveByRouting(bh: Blackhole) {
        val q = queries[queryIndex % queries.size]
        queryIndex++
        val result = library.getOptimizedPathsArriveBy(
            q.originIds, q.destIds, q.departureTime + 3600
        )
        bh.consume(result)
    }
}
