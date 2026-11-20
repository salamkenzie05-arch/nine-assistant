package id.nine.assistant

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import id.nine.assistant.alarm.AlarmRingService
import id.nine.assistant.alarm.AlarmScheduler
import id.nine.assistant.alarm.AlarmStore
import id.nine.assistant.core.Mood
import id.nine.assistant.databinding.ActivityAlarmRingBinding
import id.nine.assistant.ui.NineFaceView

/** Layar alarm berbunyi: tampil di atas layar kunci, wajah Nine ikut "membangunkan". */
class AlarmRingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmRingBinding
    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { if (!AlarmRingService.ringing) finish() }

    private val wiggle = object : Runnable {
        var i = 0
        override fun run() {
            val moods = arrayOf(Mood.SURPRISED, Mood.HAPPY, Mood.CURIOUS, Mood.HAPPY)
            binding.ringFace.react(moods[i++ % moods.size], 1400)
            main.postDelayed(this, 1700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityAlarmRingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupInsets()

        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, AlarmRingService.ringingId)
        val a = AlarmStore(this).get(id)
        binding.ringTime.text = a?.clock ?: ""
        binding.ringLabel.text = a?.label ?: getString(R.string.alarm_default_label)
        binding.ringFace.interactive = false
        binding.ringFace.mode = NineFaceView.Mode.SPEAKING
        binding.btnStop.setOnClickListener {
            AlarmRingService.stop(this)
            finish()
        }
        binding.btnSnooze.text = getString(R.string.alarm_snooze_fmt, AlarmRingService.SNOOZE_MINUTES)
        binding.btnSnooze.setOnClickListener {
            AlarmRingService.snooze(this)
            finish()
        }
        // Tombol kembali tidak mematikan alarm secara tidak sengaja.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        AlarmRingService.listeners.add(listener)
        if (!AlarmRingService.ringing) {
            finish()
            return
        }
        main.post(wiggle)
    }

    override fun onStop() {
        super.onStop()
        AlarmRingService.listeners.remove(listener)
        main.removeCallbacks(wiggle)
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.ringRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (24 * resources.displayMetrics.density).toInt()
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            WindowInsetsCompat.CONSUMED
        }
    }
}
