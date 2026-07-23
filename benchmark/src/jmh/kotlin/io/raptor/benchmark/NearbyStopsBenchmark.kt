package io.raptor.benchmark

import io.raptor.data.NetworkLoader
import io.raptor.geo.Geo
import io.raptor.model.NearbyStop
import io.raptor.model.Network
import io.raptor.model.Stop
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random

/**
 * Isolates the gain of the spatial index in [Network.findNearbyStops] on the IDFM (Paris) network.
 *
 * Both code paths run in the SAME JVM, over the SAME query points and network, so the comparison is
 * free of the run-to-run noise of two separate JMH launches:
 *  - [grid] calls the current implementation (uniform spatial grid).
 *  - [bruteForce] reproduces the exact pre-change scan (linear over all stops with a bounding-box
 *    prefilter) — the "before".
 *
 * Query points sit a few hundred metres from real stops (a plausible address/geocode), so at the
 * 500 m access radius each query returns a handful of stops. A [Setup] guard asserts the grid and
 * the brute-force scan agree on every point, so a "fast but wrong" grid can't post a false win.
 *
 * Routes are irrelevant to findNearbyStops, so the network is built with an empty route list to keep
 * setup cheap; the stop set (count + spatial distribution) is the full IDFM weekday one.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(2)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
open class NearbyStopsBenchmark {

    @Param("500", "1500")
    var radiusMeters: Int = 500

    private lateinit var network: Network
    private lateinit var latQ: DoubleArray
    private lateinit var lonQ: DoubleArray
    private var idx = 0
    private val n = 1000

    @Setup(Level.Trial)
    fun setup() {
        val stops = NetworkLoader.loadStops(DatasetConfig.PARIS.stopsPath().readBytes())
        network = Network(stops, emptyList())

        // Query points: a real stop jittered by up to ~300 m (a plausible nearby address).
        val rnd = Random(42)
        val jitterLat = 300.0 / 111_000.0
        latQ = DoubleArray(n)
        lonQ = DoubleArray(n)
        for (i in 0 until n) {
            val s = stops[rnd.nextInt(stops.size)]
            val jitterLon = 300.0 / (111_000.0 * cos(s.lat * PI / 180.0))
            latQ[i] = s.lat + (rnd.nextDouble() - 0.5) * 2 * jitterLat
            lonQ[i] = s.lon + (rnd.nextDouble() - 0.5) * 2 * jitterLon
        }

        // Correctness guard: the grid must return exactly what the brute-force scan returns.
        val radius = radiusMeters.toDouble()
        for (i in 0 until n) {
            val g = network.findNearbyStops(latQ[i], lonQ[i], radius).map { it.stopIndex }.toSet()
            val b = bruteForce(network.stops, latQ[i], lonQ[i], radius).map { it.stopIndex }.toSet()
            check(g == b) { "grid/brute mismatch at point $i (radius $radiusMeters)" }
        }
        println("[NearbyStopsBenchmark] IDFM stops=${stops.size}, points=$n, radius=${radiusMeters}m — grid == brute verified")
    }

    @Benchmark
    fun grid(bh: Blackhole) {
        val i = idx; idx = if (i + 1 == n) 0 else i + 1
        bh.consume(network.findNearbyStops(latQ[i], lonQ[i], radiusMeters.toDouble()))
    }

    @Benchmark
    fun bruteForce(bh: Blackhole) {
        val i = idx; idx = if (i + 1 == n) 0 else i + 1
        bh.consume(bruteForce(network.stops, latQ[i], lonQ[i], radiusMeters.toDouble()))
    }

    private companion object {
        /** The exact pre-change [Network.findNearbyStops] body (111 000 m/deg over-covering box). */
        fun bruteForce(stops: List<Stop>, lat: Double, lon: Double, maxDistanceMeters: Double): List<NearbyStop> {
            val dLatMax = maxDistanceMeters / 111_000.0
            val cosLat = cos(lat * PI / 180.0)
            val dLonMax = if (cosLat > 1e-6) dLatMax / cosLat else Double.MAX_VALUE
            val result = ArrayList<NearbyStop>()
            for (i in stops.indices) {
                val stop = stops[i]
                if (abs(stop.lat - lat) > dLatMax || abs(stop.lon - lon) > dLonMax) continue
                val distance = Geo.distanceMeters(lat, lon, stop.lat, stop.lon)
                if (distance <= maxDistanceMeters) result.add(NearbyStop(i, distance))
            }
            return result
        }
    }
}
