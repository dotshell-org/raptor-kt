package io.raptor.benchmark

import io.raptor.RaptorLibrary
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Per-route latency benchmark on the named LYON O-D pairs shown in the README performance table.
 * Mirrors the historical methodology (name-resolved multi-stop origin/destination sets,
 * forward departure 08:00, arrive-by 09:00) with proper JMH statistics.
 *
 * NOT part of the default `:benchmark:jmh` run (the gradle `includes` filter only selects
 * RaptorBenchmark, keeping the routine benchmark under its time budget). Run it via the JMH jar:
 *
 *   ./gradlew :benchmark:jmhJar
 *   java -Draptor.dataRoot=<project root> -jar benchmark/build/libs/benchmark-jmh.jar \
 *        "io.raptor.benchmark.NamedRoutesBenchmark.*" -rf json -rff named-results.json
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
open class NamedRoutesBenchmark {

    companion object {
        val ROUTES = mapOf(
            "perrache_soie" to ("Perrache" to "Vaulx-en-Velin La Soie"),
            "bellecour_partdieu" to ("Bellecour" to "Part-Dieu"),
            "vaise_oullins" to ("Gare de Vaise" to "Oullins Centre"),
            "perrache_cuire" to ("Perrache" to "Cuire"),
            "bonnevay_gorge" to ("Laurent Bonnevay" to "Gorge de Loup"),
            "partdieu_bellecour" to ("Part-Dieu" to "Bellecour")
        )
    }

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

        val (originName, destName) = ROUTES.getValue(routeKey)
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
