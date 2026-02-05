package io.raptor

import java.io.File
import java.io.FileInputStream

object RouteFilterDemo {
    @JvmStatic
    fun main(args: Array<String>) {
        val baseDir = args.getOrNull(0)
            ?: "/home/tristan/PycharmProjects/raptor-gtfs-pipeline/raptor_data/school_on_weekdays"
        val stopsFile = File(baseDir, "stops.bin")
        val routesFile = File(baseDir, "routes.bin")
        if (!stopsFile.exists() || !routesFile.exists()) {
            println("[RouteFilterDemo] Missing stops.bin or routes.bin in: $baseDir")
            return
        }

        val raptor = RaptorLibrary(
            stopsInputStream = FileInputStream(stopsFile),
            routesInputStream = FileInputStream(routesFile)
        )

        val departureTime = 10 * 3600
        raptor.searchAndDisplayRoute(
            originName = "Campus Région Numérique",
            destinationName = "Meyzieu Z.I",
            departureTime = departureTime,
            // blockedRouteNames = (2..999).map { "JD$it" }.toSet()
            blockedRouteNames = setOf("RX", "D")
        )
    }

}
