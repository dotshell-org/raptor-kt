package io.raptor.core

import io.raptor.model.Route

data class RouteFilter(
    val allowedRouteIds: Set<Int>? = null,
    val allowedRouteNames: Set<String>? = null,
    val blockedRouteIds: Set<Int> = emptySet(),
    val blockedRouteNames: Set<String> = emptySet()
) {
    fun allows(route: Route): Boolean {
        if (allowedRouteIds != null && route.id !in allowedRouteIds) return false
        if (allowedRouteNames != null && route.name !in allowedRouteNames) return false
        if (route.id in blockedRouteIds) return false
        if (route.name in blockedRouteNames) return false
        return true
    }
}
