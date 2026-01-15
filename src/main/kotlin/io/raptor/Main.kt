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

    val routesFile = File("raptor_data/routes.bin")
    val routes = if (routesFile.exists()) {
        println("Loading routes...")
        NetworkLoader.loadRoutes(routesFile)
    } else {
        null
    }
    if (routes != null) {
        println("${routes.size} routes loaded.")
        println("\nAll network routes:")
        routes.map { it.name }.distinct().sorted().forEach { name ->
            println("- $name")
        }
    }

    val targetStopId = 9790
    val stop = stops.find { it.name == "Marcy l'Etoile" }

    if (stop != null) {
        println("\nLines passing through stop $targetStopId (${stop.name}):")
        if (stop.routeIds.isEmpty()) {
            println("No lines found for this stop.")
        } else {
            stop.routeIds.forEach { routeId ->
                val route = routes?.find { it.id == routeId }
                if (route != null) {
                    println("- Route ID: $routeId | Name: ${route.name}")
                } else {
                    println("- Route ID: $routeId")
                }
            }
        }
    } else {
        println("\nStop with ID $targetStopId not found.")
    }
}
