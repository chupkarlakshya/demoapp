package com.safepath.indore.routing

import com.google.android.gms.maps.model.LatLng

enum class RouteType { FASTEST, BALANCED, SAFEST }

data class Route(
    val type: RouteType,
    val points: List<LatLng>,
    val distanceMeters: Double,
    val risk: Double,
    val roadPenalty: Double,
    val cost: Double
) {
    /** Used in the bottom panel chips. */
    fun shortLabel(): String =
        "%.1f km · risk %.0f".format(distanceMeters / 1000.0, risk)
}
