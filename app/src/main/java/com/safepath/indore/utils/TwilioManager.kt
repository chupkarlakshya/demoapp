package com.safepath.indore.utils

import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Handles sending SMS via Twilio REST API.
 * 
 * IMPORTANT: In a production app, these credentials should be stored on a 
 * secure backend server, not in the Android app.
 */
object TwilioManager {
    private const val TAG = "TwilioManager"

    // Replace these with your actual Twilio credentials
    private const val ACCOUNT_SID = "AC96e95497dbb108271dde8043575268c1"
    private const val AUTH_TOKEN = "b6b2c393639290ac52f1994ae061809a"
    private const val FROM_NUMBER = "+18777804236" // Your Twilio phone number

    private val client = OkHttpClient()

    fun sendSosSms(toNumber: String, message: String, callback: (Boolean) -> Unit) {
        if (ACCOUNT_SID.startsWith("YOUR_") || toNumber.isEmpty()) {
            Log.e(TAG, "Twilio credentials or recipient number missing")
            callback(false)
            return
        }

        val url = "https://api.twilio.com/2010-04-01/Accounts/$ACCOUNT_SID/Messages.json"
        
        val formBody = FormBody.Builder()
            .add("To", toNumber)
            .add("From", FROM_NUMBER)
            .add("Body", message)
            .build()

        val auth = "Basic " + Base64.encodeToString(
            "$ACCOUNT_SID:$AUTH_TOKEN".toByteArray(),
            Base64.NO_WRAP
        )

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", auth)
            .post(formBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to send SMS", e)
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                if (!success) {
                    Log.e(TAG, "Twilio error: ${response.body?.string()}")
                }
                callback(success)
                response.close()
            }
        })
    }
}
