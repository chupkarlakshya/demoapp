package com.safepath.indore.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.safepath.indore.databinding.ActivityFakeCallBinding

/**
 * Simulated incoming-call screen. Plays the system ringtone and
 * shows accept / decline UI. Tapping accept switches to a fake
 * "in-call" timer; tapping decline closes the screen.
 */
class FakeCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFakeCallBinding
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
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

        startRinging()

        binding.declineButton.setOnClickListener {
            stopRinging()
            finish()
        }

        binding.acceptButton.setOnClickListener {
            if (!inCall) {
                inCall = true
                stopRinging()
                binding.acceptButton.visibility = View.GONE
                binding.declineButton.text = "Hang Up"
                binding.callerName.text = "Mom"
                binding.callTimer.text = "00:00"
                handler.postDelayed(timerTick, 1000)
            }
        }
    }

    private fun startRinging() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }

            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 1000, 1000) // Vibrate 1s, Pause 1s
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0 means repeat

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRinging() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        vibrator?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRinging()
        handler.removeCallbacks(timerTick)
    }
}
