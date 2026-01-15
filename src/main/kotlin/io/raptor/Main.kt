package io.raptor

fun main() {
    val raptor = RaptorLibrary("raptor_data")

    println("Network loaded.")

    val originName = "Parc Tête d'Or Churchill"
    val destinationName = "Campus Région Numérique"
    val departureTime = 8 * 3600 + 0 * 60 // 08:00:00

    raptor.searchAndDisplayRoute(originName, destinationName, departureTime, showIntermediateStops = true)
}
