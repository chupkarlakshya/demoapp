package com.safepath.indore.data

import com.google.android.gms.maps.model.LatLng
import com.safepath.indore.utils.GeoUtils

/**
 * Computes risk scores by summing the time-weighted risks of crime points
 * inside a fixed radius around a given location.
 *
 * No ML, no clustering — just radius lookups, as per the spec.
 */
class RiskCalculator(private val crimes: List<CrimePoint>) {

    /** Threshold above which a 250 m disc is considered HIGH-RISK. */
    private val highRiskThreshold = 12.0

    private val radiusMeters = 250.0

    /** Sum of time-weighted risk within [radiusMeters] of [location]. */
    fun riskAt(location: LatLng, radius: Double = radiusMeters): Double {
        var sum = 0.0
        // Bounding box pre-filter: ~radius / 111_000 in degrees.
        val degLat = radius / 111_000.0
        val degLng = radius / (111_000.0 * Math.cos(Math.toRadians(location.latitude)))
        val minLat = location.latitude - degLat
        val maxLat = location.latitude + degLat
        val minLng = location.longitude - degLng
        val maxLng = location.longitude + degLng
        for (c in crimes) {
            if (c.latitude < minLat || c.latitude > maxLat ||
                c.longitude < minLng || c.longitude > maxLng) continue
            if (c.timeWeightedRisk == 0.0) continue
            val d = GeoUtils.distanceMeters(location, c.latLng)
            if (d <= radius) sum += c.timeWeightedRisk
        }
        return sum
    }

    fun isHighRisk(location: LatLng): Boolean = riskAt(location) >= highRiskThreshold

    /**
     * Total risk along a polyline by sampling at fixed intervals and
     * summing per-sample risk.
     */
    fun routeRisk(points: List<LatLng>, sampleEveryMeters: Double = 100.0): Double {
        val samples = GeoUtils.samplePolyline(points, sampleEveryMeters)
        var total = 0.0
        for (p in samples) total += riskAt(p, radiusMeters)
        return total
    }

    /** Returns the most-risk-loaded crime points for the heatmap. */
    fun riskyPoints(limit: Int = 800): List<Pair<LatLng, Double>> =
        crimes.asSequence()
            .filter { it.timeWeightedRisk > 0 }
            .map { it.latLng to it.timeWeightedRisk }
            .sortedByDescending { it.second }
            .take(limit)
            .toList()

    fun allWeightedPoints(): List<Pair<LatLng, Double>> =
        crimes.asSequence()
            .filter { it.timeWeightedRisk > 0 }
            .map { it.latLng to it.timeWeightedRisk }
            .toList()
}
