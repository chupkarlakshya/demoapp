package com.safepath.indore.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.appcompat.app.AppCompatActivity
import com.safepath.indore.databinding.ActivitySosBinding
import com.safepath.indore.utils.TwilioManager
import android.widget.Toast

/**
 * Simulates an SOS being triggered: shows the location that "would" be
 * shared, vibrates briefly, and starts a counter showing seconds since
 * SOS activation.
 */
class SosActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
    }

    private lateinit var binding: ActivitySosBinding
    private val handler = Handler(Looper.getMainLooper())
    private var seconds = 0

    private val tick = object : Runnable {
        override fun run() {
            seconds += 1
            binding.sosTimer.text = "Active for ${seconds}s · Help is on the way"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val lat = intent.getDoubleExtra(EXTRA_LAT, 22.7196)
        val lng = intent.getDoubleExtra(EXTRA_LNG, 75.8577)
        binding.sosLocation.text = "📍 %.5f, %.5f".format(lat, lng)

        val prefs = getSharedPreferences("SafePath", MODE_PRIVATE)
        val contact = prefs.getString("emergency_contact", "")
        if (!contact.isNullOrEmpty()) {
            binding.sosTimer.text = "Alerting $contact... · Help is on the way"
        }

        binding.cancelSosButton.setOnClickListener { finish() }
        
        // Trigger Real SOS via Twilio
        if (!contact.isNullOrEmpty()) {
            val mapsLink = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"
            val message = "EMERGENCY SOS from SafePath Indore! I need help. My current location: $mapsLink"
            
            TwilioManager.sendSosSms(contact, message) { success ->
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "Emergency SMS sent to $contact", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Failed to send SMS. Check Twilio config.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        vibrate()
        handler.post(tick)
    }

    private fun vibrate() {
        try {
            val pattern = longArrayOf(0, 200, 100, 200, 100, 200)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        } catch (_: Exception) { /* vibrator optional */ }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tick)
    }
}
