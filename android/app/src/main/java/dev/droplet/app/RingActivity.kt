package dev.droplet.app

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dev.droplet.app.databinding.ActivityRingBinding

/** Full screen over the lock screen while the phone rings: one big Stop button. */
class RingActivity : AppCompatActivity() {
    private lateinit var b: ActivityRingBinding
    private var pulse: ObjectAnimator? = null
    private val onChange: () -> Unit = { if (Ringer.ringing == null) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // the ring screen is always dark, whatever the system theme
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityRingBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars()
        b.stop.setOnClickListener {
            Ringer.stop(this, tellHub = true)
            finish()
        }
        pulse = ObjectAnimator.ofPropertyValuesHolder(b.drop,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.15f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.15f)).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    override fun onStart() {
        super.onStart()
        val ring = Ringer.ringing
        if (ring == null) { finish(); return }
        b.from.text = getString(R.string.ring_title, ring.from)
        Ringer.addListener(onChange)
    }

    override fun onStop() {
        Ringer.removeListener(onChange)
        super.onStop()
    }

    override fun onDestroy() {
        pulse?.cancel()
        super.onDestroy()
    }
}
