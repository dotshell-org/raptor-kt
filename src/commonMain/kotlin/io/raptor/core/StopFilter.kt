package io.raptor.core

/**
 * Per-stop disruptions applied to a single query, expressed in internal stop *indices*.
 *
 * [RouteFilter] can only take a whole line out of the network, which is far too blunt for a live
 * disruption feed: a lift out of order at one station would ban every journey on that metro line,
 * and a two-minute delay has no way to be expressed at all. This is the finer instrument.
 *
 * - [blockedStopIndices]: the vehicle no longer serves the stop. Nobody boards or alights there,
 *   but a trip already running still passes through — which is what "station closed" actually
 *   means, as opposed to the line being cut.
 * - [penaltySecondsByStopIndex]: extra seconds charged for *arriving at* or *transferring at* the
 *   stop. Riding through costs nothing, since the timetable still governs the vehicle. The point
 *   is to make journeys touching a troubled stop lose on their own merits, so an alternative wins
 *   when one exists and the itinerary survives when none does.
 */
class StopFilter(
    val blockedStopIndices: Set<Int> = emptySet(),
    val penaltySecondsByStopIndex: Map<Int, Int> = emptyMap()
) {
    val isEmpty: Boolean
        get() = blockedStopIndices.isEmpty() && penaltySecondsByStopIndex.isEmpty()

    companion object {
        val NONE = StopFilter()
    }
}
