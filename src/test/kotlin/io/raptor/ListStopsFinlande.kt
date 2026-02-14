package io.raptor

import java.io.File
import java.io.FileInputStream

object ListStopsFinlande {
    @JvmStatic
    fun main(args: Array<String>) {
        val baseDir = args.getOrNull(0) ?: "raptor_data_finlande"
        val stopsFile = File(baseDir, "stops.bin")
        if (!stopsFile.exists()) {
            println("[ListStopsFinlande] stops.bin not found in: $baseDir")
            return
        }

        val stops = io.raptor.data.NetworkLoader.loadStops(FileInputStream(stopsFile))
        val distinctNames = stops.map { it.name }.distinct().sorted()

        println("=== Finlande — Distinct Stop Names ===")
        println("Total stops: ${stops.size}, distinct names: ${distinctNames.size}")
        println()
        for (name in distinctNames) {
            println(name)
        }
    }
}
