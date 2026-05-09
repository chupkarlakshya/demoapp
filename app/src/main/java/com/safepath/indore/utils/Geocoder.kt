package com.safepath.indore.utils

import android.content.Context
import android.location.Geocoder as AndroidGeocoder
import com.google.android.gms.maps.model.LatLng
import java.util.Locale

/**
 * Hybrid Geocoder:
 * 1. Checks hardcoded "landmarks" first for instant demo response.
 * 2. Falls back to Android System Geocoder for any address in Indore.
 */
object Geocoder {

    private val landmarks = linkedMapOf(
        "rajwada" to LatLng(22.7196, 75.8577),
        "vijay nagar" to LatLng(22.7530, 75.8930),
        "palasia" to LatLng(22.7430, 75.8850),
        "geeta bhavan" to LatLng(22.7330, 75.8740),
        "bhawarkuan" to LatLng(22.7050, 75.8700),
        "sapna sangeeta" to LatLng(22.7280, 75.8700),
        "indore airport" to LatLng(22.7218, 75.8011),
        "iim indore" to LatLng(22.6898, 75.8525),
        "iit indore" to LatLng(22.5210, 75.9215),
        "saket" to LatLng(22.7260, 75.8950),
        "lig square" to LatLng(22.7500, 75.8750),
        "rau" to LatLng(22.6450, 75.8120),
        "navlakha" to LatLng(22.7080, 75.8780),
        "scheme 78" to LatLng(22.7700, 75.8930),
        "bombay hospital" to LatLng(22.7610, 75.8820),
        "khajrana" to LatLng(22.7460, 75.9030)
    )

    fun knownDestinations(): List<String> = landmarks.keys.toList()

    /**
     * Attempts to find coordinates for a query.
     * Note: Android's Geocoder.getFromLocationName is a blocking network call.
     * In a production app, this should be wrapped in a Coroutine.
     */
    fun geocode(context: Context, query: String): LatLng? {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return null
        
        // 1. Check Landmark Cache (Instant)
        landmarks[key]?.let { return it }
        val looseMatch = landmarks.entries.firstOrNull { (k, _) -> k.contains(key) || key.contains(k) }
        if (looseMatch != null) return looseMatch.value

        // 2. Fallback to System Geocoder (Indore biased)
        return try {
            val geocoder = AndroidGeocoder(context, Locale.getDefault())
            // Appending "Indore" to ensure local results
            val addresses = geocoder.getFromLocationName("$query, Indore, Madhya Pradesh", 1)
            if (!addresses.isNullOrEmpty()) {
                LatLng(addresses[0].latitude, addresses[0].longitude)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
