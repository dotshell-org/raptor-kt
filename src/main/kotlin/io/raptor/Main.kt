package io.raptor

import io.raptor.data.NetworkLoader
import java.io.File

fun main() {
    val stopsFile = File("raptor_data/stops.bin")
    if (!stopsFile.exists()) {
        println("Error : The file ${stopsFile.absolutePath} doesn't exist.")
        return
    }

    println("Loading stops...")
    val stops = NetworkLoader.loadStops(stopsFile)
    println("${stops.size} stops loaded.")

    println("\nStops list:")
    stops.forEach { stop ->
        println("ID: ${stop.id} | Name: ${stop.name} | Lat: ${stop.lat}, Lon: ${stop.lon}")
    }
}
