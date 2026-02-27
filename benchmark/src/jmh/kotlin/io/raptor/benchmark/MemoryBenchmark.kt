package io.raptor.benchmark

import io.raptor.RaptorLibrary
import io.raptor.data.NetworkLoader
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Measures per-query GC allocation using the JMH GC profiler.
 *
 * Uses SingleShotTime mode with many iterations to get per-invocation allocation data.
 * The GC profiler (-prof gc) reports gc.alloc.rate.norm (bytes per operation).
 *
 * This is critical for Android where GC pressure directly impacts UI smoothness.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(value = 5, jvmArgs = ["-Xmx1g"])
@Warmup(iterations = 100)
@Measurement(iterations = 500)
open class MemoryBenchmark {

    @Param("PARIS", "RTM")
    lateinit var dataset: String

    private lateinit var library: RaptorLibrary
    private lateinit var queries: List<QueryPair>
    private var queryIndex = 0

    @Setup(Level.Trial)
    fun setup() {
        val config = DatasetConfig.valueOf(dataset)
        library = RaptorLibrary(
            stopsInputStream = FileInputStream(config.stopsPath()),
            routesInputStream = FileInputStream(config.routesPath())
        )

        val stops = NetworkLoader.loadStops(FileInputStream(config.stopsPath()))
        val generator = RandomQueryGenerator(stops.map { it.id }, seed = 99L)
        queries = generator.generate(500)

        // Prime the algorithm cache
        library.getOptimizedPaths(queries[0].originIds, queries[0].destIds, queries[0].departureTime)
        queryIndex = 0
    }

    @Benchmark
    fun forwardRoutingAllocation(bh: Blackhole) {
        val q = queries[queryIndex % queries.size]
        queryIndex++
        bh.consume(library.getOptimizedPaths(q.originIds, q.destIds, q.departureTime))
    }
}
