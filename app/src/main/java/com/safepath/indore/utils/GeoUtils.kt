package com.safepath.indore.utils

import com.google.android.gms.maps.model.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Haversine distance in metres. */
    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2).let { it * it } +
                cos(lat1) * cos(lat2) * sin(dLng / 2).let { it * it }
        val c = 2 * atan2(sqrt(h), sqrt(1 - h))
        return EARTH_RADIUS_M * c
    }

    /** Total length of a polyline in metres. */
    fun polylineLengthMeters(points: List<LatLng>): Double {
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += distanceMeters(points[i - 1], points[i])
        }
        return sum
    }

    /** Linear interpolation between two coordinates. t in [0, 1]. */
    fun interpolate(a: LatLng, b: LatLng, t: Double): LatLng =
        LatLng(a.latitude + (b.latitude - a.latitude) * t,
               a.longitude + (b.longitude - a.longitude) * t)

    /**
     * Returns a point offset perpendicular to the AB line by [meters] metres.
     * Positive offset = left of A->B direction, negative = right.
     */
    fun perpendicularOffset(a: LatLng, b: LatLng, t: Double, meters: Double): LatLng {
        val mid = interpolate(a, b, t)
        val dLat = b.latitude - a.latitude
        val dLng = b.longitude - a.longitude
        val len = sqrt(dLat * dLat + dLng * dLng)
        if (len == 0.0) return mid
        // Perpendicular unit vector (rotate 90°): (-dLng, dLat)
        val pLat = -dLng / len
        val pLng = dLat / len
        // Convert metres to degrees roughly (1 deg lat ≈ 111_000 m).
        val degLat = meters / 111_000.0
        val degLng = meters / (111_000.0 * cos(Math.toRadians(mid.latitude)))
        return LatLng(mid.latitude + pLat * degLat, mid.longitude + pLng * degLng)
    }

    /** Sample a polyline at fixed metre intervals. */
    fun samplePolyline(points: List<LatLng>, intervalMeters: Double): List<LatLng> {
        if (points.size < 2) return points
        val out = mutableListOf<LatLng>()
        out += points.first()
        var carry = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val seg = distanceMeters(a, b)
            var d = intervalMeters - carry
            while (d <= seg) {
                val t = d / seg
                out += interpolate(a, b, t)
                d += intervalMeters
            }
            carry = (seg - (d - intervalMeters))
        }
        out += points.last()
        return out
    }
}
