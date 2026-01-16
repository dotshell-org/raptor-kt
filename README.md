# Raptor-KT

[![Maven Central](https://img.shields.io/maven-central/v/eu.dotshell/raptor-kt)](https://central.sonatype.com/artifact/eu.dotshell/raptor-kt)

RAPTOR (Round-Based Public Transit Optimized Router) implementation in Kotlin for Android.

## Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("eu.dotshell:raptor-kt:1.0.0")
}
```

## Usage (Android)

```kotlin
// Place your stops.bin and routes.bin files in assets folder
val raptor = RaptorLibrary(
    stopsInputStream = assets.open("stops.bin"),
    routesInputStream = assets.open("routes.bin")
)

// Search for stops
val originStops = raptor.searchStopsByName("Perrache")
val destStops = raptor.searchStopsByName("Cuire")

// Get optimized paths
val departureTime = 8 * 3600 // 08:00:00 in seconds
val journeys = raptor.getOptimizedPaths(
    originStopIds = originStops.map { it.id },
    destinationStopIds = destStops.map { it.id },
    departureTime = departureTime
)

// Display results
for (journey in journeys) {
    raptor.displayJourney(journey)
}
```