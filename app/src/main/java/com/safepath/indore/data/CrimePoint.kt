package com.safepath.indore.data

import com.google.android.gms.maps.model.LatLng

/**
 * Represents a single crime data record.
 *
 * Risk score uses the formula from the spec:
 *   risk = (5×act302) + (4×act363) + (3×act323) + (2×act379)
 *   if hour >= 20: risk *= 1.5
 */
data class CrimePoint(
    val latitude: Double,
    val longitude: Double,
    val hour: Int,
    val act302: Int,
    val act363: Int,
    val act323: Int,
    val act379: Int
) {
    val latLng: LatLng by lazy { LatLng(latitude, longitude) }

    val baseRisk: Double =
        5.0 * act302 + 4.0 * act363 + 3.0 * act323 + 2.0 * act379

    val timeWeightedRisk: Double =
        if (hour >= 20) baseRisk * 1.5 else baseRisk
}
