package io.raptor.benchmark

import io.raptor.RaptorLibrary
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.io.FileInputStream
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

    @Param("PARIS", "FINLAND", "RTM")
    lateinit var dataset: String

    @Benchmark
    fun loadNetwork(bh: Blackhole) {
        val config = DatasetConfig.valueOf(dataset)
        val lib = RaptorLibrary(
            stopsInputStream = FileInputStream(config.stopsPath()),
            routesInputStream = FileInputStream(config.routesPath())
        )
        bh.consume(lib)
    }
}
