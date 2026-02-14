# Raptor-KT

[![Maven Central](https://img.shields.io/maven-central/v/eu.dotshell/raptor-kt)](https://central.sonatype.com/artifact/eu.dotshell/raptor-kt)

RAPTOR (Round-Based Public Transit Optimized Router) implementation in Kotlin for Android.

## Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("eu.dotshell:raptor-kt:1.1.0")
}
```

## Usage (Android)

### Simple Usage (Single Period)

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

### Multi-Period Support

If you have multiple sets of transit data for different time periods (e.g., winter/summer schedules), you can load them all at once:

```kotlin
// Load multiple periods
val raptor = RaptorLibrary(listOf(
    PeriodData(
        periodId = "winter",
        stopsInputStream = assets.open("stops_winter.bin"),
        routesInputStream = assets.open("routes_winter.bin")
    ),
    PeriodData(
        periodId = "summer",
        stopsInputStream = assets.open("stops_summer.bin"),
        routesInputStream = assets.open("routes_summer.bin")
    )
))

// Check available periods
val periods = raptor.getAvailablePeriods() // Returns: ["winter", "summer"]

// Switch to a specific period
raptor.setPeriod("summer")

// All subsequent queries will use the summer schedule
val journeys = raptor.getOptimizedPaths(
    originStopIds = originStops.map { it.id },
    destinationStopIds = destStops.map { it.id },
    departureTime = departureTime
)

// Get current active period
val currentPeriod = raptor.getCurrentPeriod() // Returns: "summer"
```

### Arrive-By Search

You can also search for routes that arrive before a specific time (useful for "I need to be there by 9am" scenarios):

```kotlin
// Find the best routes to arrive by 09:00
val arrivalTime = 9 * 3600 // 09:00:00 in seconds
val journeys = raptor.getOptimizedPathsArriveBy(
    originStopIds = originStops.map { it.id },
    destinationStopIds = destStops.map { it.id },
    arrivalTime = arrivalTime,
    searchWindowMinutes = 120 // Search departures up to 2 hours before arrival time
)

// The returned journeys will arrive at or before 09:00
// with the latest possible departure time
for (journey in journeys) {
    raptor.displayJourney(journey)
}
```

### Route Filtering (Whitelist/Blacklist)

You can restrict which lines are eligible during routing using route names or ids. This is useful to keep a journey within the same fare system or to exclude specific lines.

```kotlin
// Allow only specific lines by name
val journeys = raptor.getOptimizedPaths(
    originStopIds = originStops.map { it.id },
    destinationStopIds = destStops.map { it.id },
    departureTime = departureTime,
    allowedRouteNames = setOf("JD2", "JD3", "RX")
)

// Exclude specific lines by id
val journeysArriveBy = raptor.getOptimizedPathsArriveBy(
    originStopIds = originStops.map { it.id },
    destinationStopIds = destStops.map { it.id },
    arrivalTime = arrivalTime,
    blockedRouteIds = setOf(12, 27)
)

// Works with searchAndDisplayRoute too
raptor.searchAndDisplayRoute(
    originName = "Perrache",
    destinationName = "Cuire",
    departureTime = departureTime,
    allowedRouteNames = setOf("JD2", "JD3", "RX")
)
```

## Performance

Results after JVM warmup.

### TCL Lyon — 14 386 stops, 331 routes, 19 523 trips (~14 MB)

| Route | Forward | Arrive-By |
|:------|--------:|----------:|
| Perrache → Vaulx-en-Velin La Soie | 0.36 ms | 1.48 ms |
| Bellecour → Part-Dieu | 0.20 ms | 0.90 ms |
| Gare de Vaise → Oullins Centre | 0.28 ms | 1.60 ms |
| Perrache → Cuire | 0.33 ms | 2.34 ms |
| Laurent Bonnevay → Gorge de Loup | 0.28 ms | 2.10 ms |
| Part-Dieu → Bellecour | 0.18 ms | 0.97 ms |

100 iterations (forward), 10 iterations (arrive-by).

### IDFM Paris — 53 944 stops, 3 744 routes, 377 225 trips (~142 MB)

| Route | Forward | Arrive-By |
|:------|--------:|----------:|
| Gare de Lyon → Gare du Nord | 2.38 ms | 19.89 ms |
| Gare Saint-Lazare → Montparnasse Bienvenue | 3.01 ms | 20.35 ms |
| Charles de Gaulle - Étoile → Nation | 1.17 ms | 8.33 ms |
| République → Bastille | 0.86 ms | 4.22 ms |
| Gare du Nord → Gare Montparnasse | 6.35 ms | 42.98 ms |
| Bastille → Gare Saint-Lazare | 2.95 ms | 29.73 ms |
| Glacière → Bonne Nouvelle | 7.19 ms | 51.55 ms |

50 iterations (forward), 5 iterations (arrive-by).
