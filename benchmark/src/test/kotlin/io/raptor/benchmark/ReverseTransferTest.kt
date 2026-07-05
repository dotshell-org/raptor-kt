package io.raptor.benchmark

import io.raptor.data.NetworkLoader
import io.raptor.model.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Verifies that Network.reverseTransferData is an exact transpose of transferData:
 * every explicit arc (source -> target, walkTime) has exactly one mirror (target <- source, walkTime),
 * and arc counts match. The backward arrive-by search relies on this adjacency.
 */
class ReverseTransferTest {

    companion object {
        private lateinit var network: Network

        @BeforeClass
        @JvmStatic
        fun setup() {
            val config = DatasetConfig.LYON
            require(config.isAvailable()) { "LYON data not available at ${config.stopsPath()}" }
            network = Network(
                NetworkLoader.loadStops(config.stopsPath().readBytes()),
                NetworkLoader.loadRoutes(config.routesPath().readBytes())
            )
        }
    }

    @Test
    fun reverseIsExactTranspose() {
        var forwardArcs = 0
        for (s in 0 until network.stopCount) {
            val arr = network.transferData[s]
            var t = 0
            while (t < arr.size) {
                val target = arr[t]
                val walk = arr[t + 1]
                t += 2
                if (target == -1) continue
                forwardArcs++

                val rev = network.reverseTransferData[target]
                var found = false
                var r = 0
                while (r < rev.size) {
                    if (rev[r] == s && rev[r + 1] == walk) {
                        found = true
                        break
                    }
                    r += 2
                }
                assertTrue("Arc $s -> $target (walk=$walk) missing from reverseTransferData[$target]", found)
            }
        }

        var reverseArcs = 0
        for (t in 0 until network.stopCount) {
            reverseArcs += network.reverseTransferData[t].size / 2
        }
        assertEquals("Reverse adjacency must hold exactly the forward arc count", forwardArcs, reverseArcs)

        println("=== Explicit transfer arcs on LYON: $forwardArcs (reverse: $reverseArcs) ===")
    }
}
