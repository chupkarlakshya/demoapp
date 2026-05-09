package com.safepath.indore.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.safepath.indore.databinding.ActivityLiveTrackBinding

/**
 * Mock live-location share screen. Polls the fused location provider
 * every few seconds and updates the on-screen coordinate so the demo
 * looks alive.
 */
class LiveTrackActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveTrackBinding
    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0

    private val tick = object : Runnable {
        override fun run() {
            ticks += 1
            binding.liveTimer.text = "Last update: ${ticks * 5}s ago"
            updateLocation()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveTrackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.stopTrackingButton.setOnClickListener { finish() }
        updateLocation()
        handler.postDelayed(tick, 5000)
    }

    @SuppressLint("MissingPermission")
    private fun updateLocation() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            binding.liveLocation.text = "📍 22.7196, 75.8577 (sample)"
            return
        }
        try {
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        binding.liveLocation.text = "📍 %.5f, %.5f".format(loc.latitude, loc.longitude)
                    } else {
                        binding.liveLocation.text = "📍 Waiting for GPS…"
                    }
                }
        } catch (_: SecurityException) {
            binding.liveLocation.text = "📍 22.7196, 75.8577 (sample)"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tick)
    }
}
