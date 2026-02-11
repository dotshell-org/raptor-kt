package io.raptor.data

import io.raptor.model.*
import java.io.InputStream

object NetworkLoader {

    fun loadStops(inputStream: InputStream): List<Stop> {
        val reader = BinaryReader(inputStream)
        val magic = reader.peekMagic()
        return when (magic) {
            "RSTS" -> loadStopsV1(reader)
            "RST2" -> loadStopsV2(reader)
            else -> throw IllegalStateException("Unknown stops format: '$magic'. Expected RSTS (v1) or RST2 (v2).")
        }
    }

    fun loadRoutes(inputStream: InputStream): List<Route> {
        val reader = BinaryReader(inputStream)
        val magic = reader.peekMagic()
        return when (magic) {
            "RRTS" -> loadRoutesV1(reader)
            "RRT2" -> loadRoutesV2(reader)
            else -> throw IllegalStateException("Unknown routes format: '$magic'. Expected RRTS (v1) or RRT2 (v2).")
        }
    }

    // ── V1 format (original) ──────────────────────────────────────────

    private fun loadStopsV1(reader: BinaryReader): List<Stop> {
        reader.readMagic("RSTS")
        reader.readUInt16()
        val count = reader.readUInt32()

        return List(count) {
            val id = reader.readUInt32()
            val nameLen = reader.readUInt16()
            val name = reader.readUTF8(nameLen)
            val lat = reader.readFloat64()
            val lon = reader.readFloat64()

            val routeRefCount = reader.readUInt32()
            val routeIds = IntArray(routeRefCount) { reader.readUInt32() }

            val transferCount = reader.readUInt32()
            val transfers = List(transferCount) {
                Transfer(targetStopId = reader.readUInt32(), walkTime = reader.readInt32())
            }

            Stop(id, name, lat, lon, routeIds, transfers)
        }
    }

    private fun loadRoutesV1(reader: BinaryReader): List<Route> {
        reader.readMagic("RRTS")
        reader.readUInt16()
        val count = reader.readUInt32()

        return List(count) {
            val routeId = reader.readUInt32()
            val nameLen = reader.readUInt16()
            val name = reader.readUTF8(nameLen)
            val stopCount = reader.readUInt32()
            val tripCount = reader.readUInt32()
            val stopIds = IntArray(stopCount) { reader.readUInt32() }

            // Read all trip data into flat arrays
            val tripIds = IntArray(tripCount)
            val flatStopTimes = IntArray(tripCount * stopCount)
            for (t in 0 until tripCount) {
                tripIds[t] = reader.readUInt32()
                var currentTime = 0
                for (s in 0 until stopCount) {
                    currentTime += reader.readInt32()
                    flatStopTimes[t * stopCount + s] = currentTime
                }
            }

            // Sort trips by first stop departure time using index sort
            val sortedIndices = (0 until tripCount).sortedBy { flatStopTimes[it * stopCount] }
            val sortedTripIds = IntArray(tripCount) { tripIds[sortedIndices[it]] }
            val sortedFlat = IntArray(tripCount * stopCount)
            for (i in 0 until tripCount) {
                System.arraycopy(flatStopTimes, sortedIndices[i] * stopCount, sortedFlat, i * stopCount, stopCount)
            }

            Route(routeId, name, stopIds, tripCount, stopCount, sortedFlat, sortedTripIds)
        }
    }

    // ── V2 format (pre-sorted, flat layout) ───────────────────────────

    private fun loadStopsV2(reader: BinaryReader): List<Stop> {
        reader.readMagic("RST2")
        reader.readUInt16() // version
        val count = reader.readUInt32()

        return List(count) {
            val id = reader.readUInt32()
            val nameLen = reader.readUInt16()
            val name = reader.readUTF8(nameLen)
            val lat = reader.readFloat64()
            val lon = reader.readFloat64()

            val routeRefCount = reader.readUInt32()
            val routeIds = IntArray(routeRefCount) { reader.readUInt32() }

            val transferCount = reader.readUInt32()
            val transfers = List(transferCount) {
                Transfer(targetStopId = reader.readUInt32(), walkTime = reader.readInt32())
            }

            Stop(id, name, lat, lon, routeIds, transfers)
        }
    }

    private fun loadRoutesV2(reader: BinaryReader): List<Route> {
        reader.readMagic("RRT2")
        reader.readUInt16() // version
        val count = reader.readUInt32()

        return List(count) {
            val routeId = reader.readUInt32()
            val nameLen = reader.readUInt16()
            val name = reader.readUTF8(nameLen)
            val stopCount = reader.readUInt32()
            val tripCount = reader.readUInt32()
            val stopIds = IntArray(stopCount) { reader.readUInt32() }

            // V2: trip IDs are stored separately, pre-sorted
            val tripIds = IntArray(tripCount) { reader.readUInt32() }

            // V2: flat stop times are pre-sorted, delta-encoded per row
            val flatStopTimes = IntArray(tripCount * stopCount)
            for (t in 0 until tripCount) {
                var currentTime = 0
                for (s in 0 until stopCount) {
                    currentTime += reader.readInt32()
                    flatStopTimes[t * stopCount + s] = currentTime
                }
            }
            // No sorting needed — pre-sorted in binary file

            Route(routeId, name, stopIds, tripCount, stopCount, flatStopTimes, tripIds)
        }
    }
}
