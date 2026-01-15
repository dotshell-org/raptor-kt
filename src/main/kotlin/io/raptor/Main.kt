package io.raptor

import io.raptor.data.NetworkLoader
import java.io.File

fun main() {
    val stopsFile = File("raptor_data/stops.bin")
    val routesFile = File("raptor_data/routes.bin")

    if (!stopsFile.exists() || !routesFile.exists()) {
        println("Error: Required data files missing.")
        return
    }

    println("Loading network...")
    val stops = NetworkLoader.loadStops(stopsFile)
    val routes = NetworkLoader.loadRoutes(routesFile)
    val network = io.raptor.model.Network(stops, routes)
    println("${stops.size} stops and ${routes.size} routes loaded.")

    val originName = "Le Méridien"
    val destinationName = "Hôtel Région Montrochet"
    val departureTime = 8 * 3600 + 0 * 60 // 08:00:00

    val origin = stops.find { it.name == originName }
    val destination = stops.find { it.name == destinationName }

    if (origin != null && destination != null) {
        // Collect all indices for origin and destination
        val originIndices = stops.indices.filter { stops[it].name == originName }
        val destinationIndices = stops.indices.filter { stops[it].name == destinationName }

        val algorithm = io.raptor.core.RaptorAlgorithm(network, debug = false)
        val bestArrivalAtAnyRound = algorithm.route(originIndices, destinationIndices, departureTime)

        if (bestArrivalAtAnyRound == Int.MAX_VALUE) {
            println("\nNo route found at 08:00:00. Trying at 00:00:00...")
            algorithm.route(originIndices, destinationIndices, 0)
        } else {
            val paretoJourneys = mutableListOf<List<io.raptor.core.JourneyLeg>>()
            var lastBestArrival = Int.MAX_VALUE
            
            // Collect best journey for each number of transfers (round)
            for (k in 1..5) {
                val bestDestIndex = destinationIndices.minByOrNull { idx -> 
                    val journey = algorithm.getJourney(idx, k)
                    journey?.lastOrNull()?.arrivalTime ?: Int.MAX_VALUE
                }
                
                if (bestDestIndex != null) {
                    val journey = algorithm.getJourney(bestDestIndex, k)
                    if (!journey.isNullOrEmpty()) {
                        val arrivalTime = journey.last().arrivalTime
                        if (arrivalTime < lastBestArrival) {
                            paretoJourneys.add(journey)
                            lastBestArrival = arrivalTime
                        }
                    }
                }
            }

            println("\n=== ROUTES FOUND (Pareto Optimal) ===")
            for ((idx, journey) in paretoJourneys.withIndex()) {
                val arrival = journey.last().arrivalTime
                val transfers = journey.count { !it.isTransfer } - 1
                println("\nOption ${idx + 1}: Arrival ${formatTime(arrival)} | $transfers transfers")
                displayJourney(journey, stops)
            }
        }
    } else {
        if (origin == null) println("Origin stop not found: $originName")
        if (destination == null) println("Destination stop not found: $destinationName")
    }
}

fun displayJourney(journey: List<io.raptor.core.JourneyLeg>, stops: List<io.raptor.model.Stop>) {
    for ((index, leg) in journey.withIndex()) {
        val fromStop = stops[leg.fromStopIndex]
        val toStop = stops[leg.toStopIndex]
        val depTime = formatTime(leg.departureTime)
        val arrTime = formatTime(leg.arrivalTime)
        
        if (leg.isTransfer) {
            println("${index + 1}. 🚶 Transfer: ${fromStop.name} → ${toStop.name}")
            println("   Departure: $depTime | Arrival: $arrTime (${(leg.arrivalTime - leg.departureTime) / 60} min)")
        } else {
            println("${index + 1}. 🚍 Line ${leg.routeName} : ${fromStop.name} → ${toStop.name}")
            println("   Departure: $depTime | Arrival: $arrTime (${(leg.arrivalTime - leg.departureTime) / 60} min)")
        }
    }
}

fun formatTime(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
