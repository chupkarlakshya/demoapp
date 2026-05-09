package com.safepath.indore.data

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads `crime_data.csv` from assets/ and parses it into [CrimePoint]s.
 * The CSV is small (~2k rows) so we keep everything in memory.
 *
 * Expected header:
 *   timestamp,act379,act13,act279,act323,act363,act302,latitude,longitude
 */
object CrimeDataLoader {

    private const val TAG = "CrimeDataLoader"
    private const val ASSET_NAME = "crime_data.csv"

    @Volatile
    private var cache: List<CrimePoint>? = null

    fun load(context: Context): List<CrimePoint> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val out = parse(context)
            cache = out
            return out
        }
    }

    private fun parse(context: Context): List<CrimePoint> {
        val results = ArrayList<CrimePoint>(2200)
        try {
            context.assets.open(ASSET_NAME).use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    val header = reader.readLine() ?: return emptyList()
                    val cols = header.split(",").map { it.trim() }
                    val idxTs = cols.indexOf("timestamp")
                    val idxAct379 = cols.indexOf("act379")
                    val idxAct323 = cols.indexOf("act323")
                    val idxAct363 = cols.indexOf("act363")
                    val idxAct302 = cols.indexOf("act302")
                    val idxLat = cols.indexOf("latitude")
                    val idxLng = cols.indexOf("longitude")

                    var line = reader.readLine()
                    while (line != null) {
                        val parts = line.split(",")
                        if (parts.size >= cols.size) {
                            val lat = parts[idxLat].toDoubleOrNull()
                            val lng = parts[idxLng].toDoubleOrNull()
                            if (lat != null && lng != null) {
                                val ts = parts[idxTs]
                                val hour = parseHour(ts)
                                results += CrimePoint(
                                    latitude = lat,
                                    longitude = lng,
                                    hour = hour,
                                    act302 = parts[idxAct302].toIntOrNull() ?: 0,
                                    act363 = parts[idxAct363].toIntOrNull() ?: 0,
                                    act323 = parts[idxAct323].toIntOrNull() ?: 0,
                                    act379 = parts[idxAct379].toIntOrNull() ?: 0
                                )
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $ASSET_NAME", e)
        }
        Log.i(TAG, "Loaded ${results.size} crime points")
        return results
    }

    /** Pulls the hour from "DD-MM-YYYY HH:MM" — falls back to 12 if it can't. */
    private fun parseHour(timestamp: String): Int {
        val space = timestamp.indexOf(' ')
        if (space < 0) return 12
        val time = timestamp.substring(space + 1)
        val colon = time.indexOf(':')
        if (colon < 0) return 12
        return time.substring(0, colon).trim().toIntOrNull() ?: 12
    }
}
