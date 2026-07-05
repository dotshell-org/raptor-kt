package io.raptor.benchmark

import io.raptor.RaptorLibrary
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Per-route latency benchmark on the named RTM Marseille O-D pairs from the README table.
 * Same methodology as [NamedRoutesBenchmark] (LYON); see its KDoc for how to run — this class is
 * excluded from the default `:benchmark:jmh` filter.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
open class NamedRoutesRtmBenchmark {

    companion object {
        val ROUTES = mapOf(
            "vieuxport_larose" to ("Vieux-Port" to "La Rose"),
            "castellane_bougainville" to ("Castellane" to "Bougainville"),
            "stcharles_prado" to ("Gare St Charles" to "Rond-Point du Prado"),
            "timone_joliette" to ("La Timone" to "Joliette"),
            "larose_castellane" to ("La Rose" to "Castellane"),
            "noailles_dromel" to ("Noailles" to "Sainte-Marguerite Dromel"),
            "bougainville_fourragere" to ("Bougainville" to "La Fourragère")
        )
    }

    @Param(
        "vieuxport_larose", "castellane_bougainville", "stcharles_prado", "timone_joliette",
        "larose_castellane", "noailles_dromel", "bougainville_fourragere"
    )
    lateinit var routeKey: String

    private lateinit var library: RaptorLibrary
    private lateinit var originIds: List<Int>
    private lateinit var destIds: List<Int>

    @Setup(Level.Trial)
    fun setup() {
        val config = DatasetConfig.RTM
        require(config.isAvailable()) {
            "Dataset RTM not available at ${config.stopsPath()} / ${config.routesPath()}"
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
