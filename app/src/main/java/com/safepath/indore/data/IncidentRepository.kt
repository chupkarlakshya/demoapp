package com.safepath.indore.data

import android.os.Handler
import android.os.Looper
import com.google.android.gms.maps.model.LatLng
import com.safepath.indore.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Repository for managing crowd-sourced incident reports.
 * 
 * --- DOCUMENTATION FOR BACKEND DEVELOPERS ---
 * 
 * 1. ENDPOINT: POST /v1/incidents
 *    Payload: {
 *      "type": "HARASSMENT" | "UNSAFE_SPOT" | "POOR_LIGHTING" | "SUSPICIOUS_ACTIVITY" | "OTHER",
 *      "lat": Double,
 *      "lng": Double,
 *      "description": String,
 *      "timestamp": Long
 *    }
 *    Goal: Store the incident and broadcast it to nearby users if possible.
 * 
 * 2. ENDPOINT: GET /v1/incidents?lat=...&lng=...&radius=...
 *    Goal: Return a list of active incidents within a certain radius.
 *    Logic: The backend should filter out expired incidents (e.g., older than 24h).
 * 
 * 3. SECURITY:
 *    To prevent spam, consider rate-limiting reports by IP or Device ID.
 *    In the future, a trust-score system could be added for users.
 */
object IncidentRepository {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun api(path: String): String = BuildConfig.SAFEPATH_API_URL.trimEnd('/') + path

    private fun postToMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    /**
     * Sends a new report to the backend.
     */
    fun submitReport(report: IncidentReport, onComplete: (Boolean) -> Unit) {
        val body = JSONObject()
            .put("type", report.type.name)
            .put("description", report.description)
            .put("latitude", report.location.latitude)
            .put("longitude", report.location.longitude)
            .put("severity", report.severity)
            .put("reportedBy", report.reportedBy)
            .toString()
            .toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(api("/api/incidents"))
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("IncidentRepository", "POST Failure: ${e.message}", e)
                postToMain { onComplete(false) }
            }
            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                if (!success) {
                    android.util.Log.e("IncidentRepository", "POST Error: ${response.code} ${response.message}")
                }
                response.close()
                postToMain { onComplete(success) }
            }
        })
    }

    /**
     * Fetches incidents from the backend.
     */
    fun getActiveIncidents(callback: (List<IncidentReport>) -> Unit) {
        val request = Request.Builder()
            .url(api("/api/incidents?status=verified"))
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("IncidentRepository", "GET Failure: ${e.message}", e)
                postToMain { callback(emptyList()) }
            }
            override fun onResponse(call: Call, response: Response) {
                val reports = response.use { res ->
                    if (!res.isSuccessful) {
                        android.util.Log.e("IncidentRepository", "GET Error: ${res.code}")
                        emptyList()
                    } else parseReports(res.body?.string().orEmpty())
                }
                postToMain { callback(reports) }
            }
        })
    }

    fun submitSos(
        latitude: Double,
        longitude: Double,
        contact: String,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val body = JSONObject()
            .put("userId", "android-demo-user")
            .put("contact", contact)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .toString()
            .toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(api("/api/sos"))
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("IncidentRepository", "POST Failure: ${e.message}", e)
                postToMain { onComplete(false) }
            }
            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                if (!success) {
                    android.util.Log.e("IncidentRepository", "POST Error: ${response.code} ${response.message}")
                }
                response.close()
                postToMain { onComplete(success) }
            }
        })
    }

    private fun parseReports(raw: String): List<IncidentReport> {
        val array = JSONArray(raw)
        val reports = ArrayList<IncidentReport>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val type = runCatching {
                IncidentType.valueOf(item.optString("type", "OTHER"))
            }.getOrDefault(IncidentType.OTHER)
            reports += IncidentReport(
                id = item.optString("id"),
                type = type,
                location = LatLng(item.optDouble("latitude"), item.optDouble("longitude")),
                description = item.optString("description"),
                severity = item.optInt("severity", 3),
                status = item.optString("status", "verified"),
                timestamp = System.currentTimeMillis(),
                reportedBy = item.optString("reportedBy", "anonymous")
            )
        }
        return reports
    }
}
