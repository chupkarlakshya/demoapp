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
import com.safepath.indore.data.IncidentRepository
import com.safepath.indore.databinding.ActivitySosBinding
import android.widget.Toast
import android.content.Intent
import android.net.Uri

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
        
        binding.callPoliceButton.setOnClickListener {
            dialNumber("100")
        }

        binding.callAmbulanceButton.setOnClickListener {
            dialNumber("108")
        }

        // SMS dispatch is handled by the backend (POST /api/sos). The server
        // fans out to the user's saved contact + any SOS_FANOUT_NUMBERS, and
        // suppresses real sends when DEMO_MODE is on.
        IncidentRepository.submitSos(lat, lng, contact.orEmpty()) { success ->
            val msg = if (success) "SOS dispatched. Help is on the way."
                      else "Could not reach SafePath server. Try again."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        vibrate()
        handler.post(tick)
    }

    private fun dialNumber(number: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL)
            intent.data = Uri.parse("tel:$number")
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open dialer.", Toast.LENGTH_SHORT).show()
        }
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
