package io.raptor.data

import io.raptor.model.*
import java.io.InputStream

object NetworkLoader {

    fun loadStops(inputStream: InputStream): List<Stop> {
        val reader = BinaryReader(inputStream)
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

    fun loadRoutes(inputStream: InputStream): List<Route> {
        val reader = BinaryReader(inputStream)
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

            val trips = List(tripCount) {
                val tripId = reader.readUInt32()
                val arrivalTimes = IntArray(stopCount)
                var currentTime = 0
                // Delta encoding decoding
                for (s in 0 until stopCount) {
                    currentTime += reader.readInt32()
                    arrivalTimes[s] = currentTime
                }
                Trip(tripId, arrivalTimes)
            }
            Route(routeId, name, stopIds, trips)
        }
    }
}