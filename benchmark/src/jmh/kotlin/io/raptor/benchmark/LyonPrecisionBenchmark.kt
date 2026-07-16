package io.raptor.benchmark

import io.raptor.RaptorLibrary
import io.raptor.data.NetworkLoader
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * High-precision LYON benchmarks for refreshing the README performance tables.
 *
 * Same methodology as [NamedRoutesBenchmark] (named O-D pairs, name-resolved multi-stop sets,
 * forward 08:00 / arrive-by 09:00) and [RaptorBenchmark] (1000 seeded random pairs), but with
 * much heavier JMH statistics: 5 forks (fork-to-fork JIT/thermal variance dominates the error on
 * desktop machines), 10 warmup + 20 measurement iterations per fork. Expect ~30 min for the
 * named routes + ~5 min for the aggregate.
 *
 * Protocol for trustworthy numbers: idle machine (no builds/browser), mains power, and compare
 * the per-fork means in the JSON output — early forks of a cold machine run slow; if the fork
 * spread looks bimodal, re-run rather than averaging the artifact in.
 *
 * NOT part of the default `:benchmark:jmh` run. Execute with:
 *
 *   ./gradlew jmhPrecisionLyon
 *
 * Results land in benchmark/build/reports/jmh/jmhPrecisionLyon.json.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(5)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 1, timeUnit = TimeUnit.SECONDS)
open class LyonPrecisionNamedRoutesBenchmark {

    @Param(
        "perrache_soie", "bellecour_partdieu", "vaise_oullins",
        "perrache_cuire", "bonnevay_gorge", "partdieu_bellecour"
    )
    lateinit var routeKey: String

    private lateinit var library: RaptorLibrary
    private lateinit var originIds: List<Int>
    private lateinit var destIds: List<Int>

    @Setup(Level.Trial)
    fun setup() {
        val config = DatasetConfig.LYON
        require(config.isAvailable()) {
            "Dataset LYON not available at ${config.stopsPath()} / ${config.routesPath()}"
        }
        library = RaptorLibrary(
            stopsBytes = config.stopsPath().readBytes(),
            routesBytes = config.routesPath().readBytes()
        )

        val (originName, destName) = NamedRoutesBenchmark.ROUTES.getValue(routeKey)
        originIds = library.searchStopsByName(originName).map { it.id }
        destIds = library.searchStopsByName(destName).map { it.id }
        require(originIds.isNotEmpty() && destIds.isNotEmpty()) {
            "Stops not found for $routeKey ($originName -> $destName)"
        }

        // Prime the algorithm cache
        library.getOptimizedPaths(originIds, destIds, 8 * 3600)
    }

    @Benchmark
    fun forward(bh: Blackhole) {
        bh.consume(library.getOptimizedPaths(originIds, destIds, 8 * 3600))
    }

    @Benchmark
    fun arriveBy(bh: Blackhole) {
        bh.consume(library.getOptimizedPathsArriveBy(originIds, destIds, 9 * 3600))
    }
}

/**
 * High-precision LYON aggregate (the README "1000 random O-D pairs" line). Same rotating
 * seeded queries as [RaptorBenchmark], heavier statistics (see file kdoc).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(5)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 1, timeUnit = TimeUnit.SECONDS)
open class LyonPrecisionAggregateBenchmark {

    private lateinit var library: RaptorLibrary
    private lateinit var queries: List<QueryPair>
    private var queryIndex = 0

    @Setup(Level.Trial)
    fun setup() {
        val config = DatasetConfig.LYON
        require(config.isAvailable()) {
            "Dataset LYON not available at ${config.stopsPath()} / ${config.routesPath()}"
        }
        library = RaptorLibrary(
            stopsBytes = config.stopsPath().readBytes(),
            routesBytes = config.routesPath().readBytes()
        )

        val stops = NetworkLoader.loadStops(config.stopsPath().readBytes())
        val generator = RandomQueryGenerator(stops.map { it.id }, seed = 12345L)
        queries = generator.generate(1000)

        library.getOptimizedPaths(queries[0].originIds, queries[0].destIds, queries[0].departureTime)
        queryIndex = 0
    }

    @Benchmark
    fun forward(bh: Blackhole) {
        val q = queries[queryIndex % queries.size]
        queryIndex++
        bh.consume(library.getOptimizedPaths(q.originIds, q.destIds, q.departureTime))
    }

    @Benchmark
    fun arriveBy(bh: Blackhole) {
        val q = queries[queryIndex % queries.size]
        queryIndex++
        bh.consume(library.getOptimizedPathsArriveBy(q.originIds, q.destIds, q.departureTime + 3600))
    }
}
