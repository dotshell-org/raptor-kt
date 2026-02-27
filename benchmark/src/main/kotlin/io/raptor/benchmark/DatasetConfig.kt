package io.raptor.benchmark

import java.io.File

/**
 * Available benchmark datasets with their file paths and metadata.
 * Data files are located at the project root (resolved via `raptor.dataRoot` system property).
 */
enum class DatasetConfig(
    val dirName: String,
    val stopsFile: String,
    val routesFile: String,
    val description: String
) {
    PARIS(
        "raptor_data_paris", "stops.bin", "routes.bin",
        "Paris IDFM"
    ),
    FINLAND(
        "raptor_data_finlande", "stops.bin", "routes.bin",
        "Finland national"
    ),
    RTM(
        "raptor_data_rtm", "stops.bin", "routes.bin",
        "Marseille RTM"
    ),
    LYON(
        "raptor_data", "stops_school_on_weekdays.bin", "routes_school_on_weekdays.bin",
        "Lyon TCL weekday"
    );

    private fun rootDir(): File {
        val root = System.getProperty("raptor.dataRoot", ".")
        return File(root)
    }

    fun stopsPath(): File = File(rootDir(), "$dirName/$stopsFile")
    fun routesPath(): File = File(rootDir(), "$dirName/$routesFile")

    fun isAvailable(): Boolean = stopsPath().exists() && routesPath().exists()
}
