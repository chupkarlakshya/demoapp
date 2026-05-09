package com.safepath.indore.data

import android.os.Handler
import android.os.Looper
import com.google.android.gms.maps.model.LatLng
import com.safepath.indore.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar

/** Cell predicted by the backend ML model. */
data class RiskCell(val location: LatLng, val score: Double)

/** Pulls grid risk predictions from the backend ML model (`/api/risk-grid`). */
object RiskApiRepository {
    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun api(path: String): String =
        BuildConfig.SAFEPATH_API_URL.trimEnd('/') + path

    fun fetchGrid(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        steps: Int = 20,
        hour: Int? = null,
        day: Int? = null,
        callback: (List<RiskCell>) -> Unit
    ) {
        val cal = Calendar.getInstance()
        val h = hour ?: cal.get(Calendar.HOUR_OF_DAY)
        // Calendar.DAY_OF_WEEK: SUN=1..SAT=7. Convert to Mon=0..Sun=6 to match
        // the model's training (Python's weekday()).
        val pyDay = day ?: ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7)

        val url = api("/api/risk-grid") +
            "?minLat=$minLat&maxLat=$maxLat&minLng=$minLng&maxLng=$maxLng" +
            "&steps=$steps&hour=$h&day=$pyDay"
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("RiskApiRepository", "grid fetch failed: ${e.message}")
                mainHandler.post { callback(emptyList()) }
            }

            override fun onResponse(call: Call, response: Response) {
                val cells = response.use { res ->
                    if (!res.isSuccessful) {
                        android.util.Log.e("RiskApiRepository", "grid HTTP ${res.code}")
                        emptyList()
                    } else parseGrid(res.body?.string().orEmpty())
                }
                mainHandler.post { callback(cells) }
            }
        })
    }

    private fun parseGrid(raw: String): List<RiskCell> {
        if (raw.isBlank()) return emptyList()
        val obj = JSONObject(raw)
        val arr = obj.optJSONArray("cells") ?: return emptyList()
        val out = ArrayList<RiskCell>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            out += RiskCell(
                location = LatLng(c.getDouble("lat"), c.getDouble("lng")),
                score = c.getDouble("score")
            )
        }
        return out
    }
}
