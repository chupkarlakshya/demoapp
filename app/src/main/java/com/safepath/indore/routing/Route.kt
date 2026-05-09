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
    fun shortLabel(): String {
        val km = distanceMeters / 1000.0
        // Normalize risk to "risk per km" to allow fair comparison between long and short routes.
        val normalizedRisk = if (distanceMeters > 0) (risk / (distanceMeters / 1000.0)) else 0.0
        return "%.1f km · risk %.0f".format(km, normalizedRisk)
    }
}
