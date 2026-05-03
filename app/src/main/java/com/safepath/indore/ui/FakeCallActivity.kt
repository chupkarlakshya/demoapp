package com.safepath.indore.ui

import android.media.AudioAttributes
import android.media.RingtoneManager
import android.media.Ringtone
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.safepath.indore.databinding.ActivityFakeCallBinding

/**
 * Simulated incoming-call screen. Plays the system ringtone and
 * shows accept / decline UI. Tapping accept switches to a fake
 * "in-call" timer; tapping decline closes the screen.
 */
class FakeCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFakeCallBinding
    private var ringtone: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())
    private var elapsedSec = 0
    private var inCall = false

    private val timerTick = object : Runnable {
        override fun run() {
            elapsedSec += 1
            val mm = elapsedSec / 60
            val ss = elapsedSec % 60
            binding.callTimer.text = "%02d:%02d".format(mm, ss)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFakeCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(applicationContext, uri).apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            try { play() } catch (_: Exception) { /* ringtone optional */ }
        }

        binding.declineButton.setOnClickListener { finish() }
        binding.acceptButton.setOnClickListener {
            inCall = true
            ringtone?.stop()
            binding.callerName.text = "Mom"
            binding.callTimer.text = "00:00"
            handler.postDelayed(timerTick, 1000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ringtone?.stop()
        handler.removeCallbacks(timerTick)
    }
}
