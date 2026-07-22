package io.raptor.benchmark

import io.raptor.RaptorLibrary
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Measures cold-load time: binary deserialization + Network construction.
 * Uses SingleShotTime since loading is a one-time operation per app launch.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(5)
@Warmup(iterations = 3)
@Measurement(iterations = 10)
open class LoadBenchmark {

    @Param("LYON")
    lateinit var dataset: String

    @Benchmark
    fun loadNetwork(bh: Blackhole) {
        val config = DatasetConfig.valueOf(dataset)
        val lib = RaptorLibrary(
            stopsBytes = config.stopsPath().readBytes(),
            routesBytes = config.routesPath().readBytes()
        )
        // Networks are now built lazily, so force the active period's build here; otherwise this
        // would only time the (cheap) constructor instead of deserialization + Network construction.
        bh.consume(lib.getStops(lib.getCurrentPeriod()))
        bh.consume(lib)
    }
}
