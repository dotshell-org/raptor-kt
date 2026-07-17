package io.raptor.benchmark

import io.raptor.data.NetworkLoader
import io.raptor.model.Stop
import io.raptor.model.Transfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the RST3 stops format (per-stop fare zone) and its backward compatibility:
 * the same two stops encode identically under RST2 and RST3 except for the zone field, and
 * NetworkLoader picks the right layout off the magic. An empty zone decodes to null so an
 * unzoned RST3 stop is indistinguishable from an RST2 stop.
 */
class NetworkLoaderV3Test {

    private val stopA = Stop(
        id = 1, name = "Bellecour", lat = 45.7578, lon = 4.8320,
        routeIds = intArrayOf(10), transfers = listOf(Transfer(targetStopId = 2, walkTime = 90)),
        zone = "1"
    )
    private val stopB = Stop(
        id = 2, name = "Rural", lat = 46.10, lon = 4.20,
        routeIds = intArrayOf(), transfers = emptyList(), zone = null
    )

    @Test
    fun rst3ParsesZonesAndPreservesTheRestOfTheRecord() {
        val stops = NetworkLoader.loadStops(encodeStops("RST3", version = 3, hasZone = true, listOf(stopA, stopB)))

        assertEquals(2, stops.size)
        val a = stops[0]
        assertEquals(1, a.id)
        assertEquals("Bellecour", a.name)
        assertEquals("1", a.zone)
        assertEquals(listOf(10), a.routeIds.toList())
        assertEquals(listOf(Transfer(2, 90)), a.transfers)

        // Empty zone string round-trips to null, not "".
        assertNull(stops[1].zone)
    }

    @Test
    fun rst2StillLoadsWithNullZone() {
        val stops = NetworkLoader.loadStops(encodeStops("RST2", version = 2, hasZone = false, listOf(stopA, stopB)))

        assertEquals(2, stops.size)
        assertNull(stops[0].zone)
        assertNull(stops[1].zone)
        // The rest of the record is unaffected by the missing zone field.
        assertEquals("Bellecour", stops[0].name)
        assertEquals(listOf(Transfer(2, 90)), stops[0].transfers)
    }

    // ── Minimal little-endian encoder mirroring NetworkLoader.loadStopsFlat ──

    private fun encodeStops(magic: String, version: Int, hasZone: Boolean, stops: List<Stop>): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add((v and 0xFF).toByte()); out.add(((v shr 8) and 0xFF).toByte()) }
        fun i32(v: Int) { for (i in 0 until 4) out.add(((v shr (8 * i)) and 0xFF).toByte()) }
        fun f64(v: Double) { val b = v.toRawBits(); for (i in 0 until 8) out.add(((b shr (8 * i)) and 0xFF).toByte()) }
        fun utf8(s: String) { val e = s.encodeToByteArray(); u16(e.size); e.forEach { out.add(it) } }

        for (c in magic) out.add(c.code.toByte())
        u16(version)
        i32(stops.size)
        for (s in stops) {
            i32(s.id)
            utf8(s.name)
            f64(s.lat)
            f64(s.lon)
            if (hasZone) utf8(s.zone ?: "")
            i32(s.routeIds.size)
            s.routeIds.forEach { i32(it) }
            i32(s.transfers.size)
            for (t in s.transfers) { i32(t.targetStopId); i32(t.walkTime) }
        }
        return out.toByteArray()
    }
}
