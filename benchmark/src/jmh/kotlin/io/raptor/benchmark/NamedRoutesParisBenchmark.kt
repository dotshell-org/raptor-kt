package io.raptor.benchmark

import io.raptor.RaptorLibrary
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Per-route latency benchmark on the named IDFM Paris O-D pairs from the README table.
 * Same methodology as [NamedRoutesBenchmark] (LYON); see its KDoc for how to run — this class is
 * excluded from the default `:benchmark:jmh` filter. Launch the JVM with extra heap (-Xmx3g):
 * the Paris network is ~20x larger than Lyon's.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
open class NamedRoutesParisBenchmark {

    companion object {
        val ROUTES = mapOf(
            "garelyon_garenord" to ("Gare de Lyon" to "Gare du Nord"),
            "stlazare_montparnasse" to ("Gare Saint-Lazare" to "Montparnasse Bienvenue"),
            "etoile_nation" to ("Charles de Gaulle - Étoile" to "Nation"),
            "republique_bastille" to ("République" to "Bastille"),
            "garenord_montparnasse" to ("Gare du Nord" to "Gare Montparnasse"),
            "bastille_stlazare" to ("Bastille" to "Gare Saint-Lazare"),
            "glaciere_bonnenouvelle" to ("Glacière" to "Bonne Nouvelle")
        )
    }

    @Param(
        "garelyon_garenord", "stlazare_montparnasse", "etoile_nation", "republique_bastille",
        "garenord_montparnasse", "bastille_stlazare", "glaciere_bonnenouvelle"
    )
    lateinit var routeKey: String

    private lateinit var library: RaptorLibrary
    private lateinit var originIds: List<Int>
    private lateinit var destIds: List<Int>

    @Setup(Level.Trial)
    fun setup() {
        val config = DatasetConfig.PARIS
        require(config.isAvailable()) {
            "Dataset PARIS not available at ${config.stopsPath()} / ${config.routesPath()}"
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
