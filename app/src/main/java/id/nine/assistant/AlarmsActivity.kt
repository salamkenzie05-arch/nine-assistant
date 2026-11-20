package id.nine.assistant

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import id.nine.assistant.alarm.AlarmMatch
import id.nine.assistant.alarm.AlarmRingService
import id.nine.assistant.alarm.AlarmScheduler
import id.nine.assistant.alarm.AlarmStore
import id.nine.assistant.alarm.NineAlarm
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Fmt
import id.nine.assistant.databinding.ActivityAlarmsBinding
import id.nine.assistant.nlu.RepeatDays
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle

/** Layar alarm Nine: lihat, nyalakan/matikan, hapus, dan tambah alarm (juga bisa lewat suara). */
class AlarmsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmsBinding
    private lateinit var store: AlarmStore
    private var items: List<NineAlarm> = emptyList()
    private val lang: AppLang get() = (application as NineApp).prefs.lang

    private inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.alarmTime)
        val detail: TextView = v.findViewById(R.id.alarmDetail)
        val toggle: MaterialSwitch = v.findViewById(R.id.alarmSwitch)
        val delete: ImageButton = v.findViewById(R.id.alarmDelete)
    }

    private fun daysText(days: Set<DayOfWeek>): String = when {
        days.isEmpty() -> lang.t("Sekali", "Once")
        days == RepeatDays.ALL -> lang.t("Setiap hari", "Every day")
        days == RepeatDays.WEEKDAYS -> lang.t("Hari kerja", "Weekdays")
        days == RepeatDays.WEEKEND -> lang.t("Akhir pekan", "Weekends")
        else -> days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, lang.locale) }
    }

    private val adapter = object : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_alarm, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val a = items[position]
            val now = LocalDateTime.now()
            holder.time.text = a.clock
            val parts = arrayListOf(daysText(a.days))
            a.label?.let { parts.add(0, it) }
            if (a.enabled) parts += Fmt.at(a.nextTrigger(now), lang, now)
            holder.detail.text = parts.joinToString(" · ")
            val color = ContextCompat.getColor(this@AlarmsActivity, if (a.enabled) R.color.nine_on_bg else R.color.nine_muted)
            holder.time.setTextColor(color)
            holder.toggle.setOnCheckedChangeListener(null)
            holder.toggle.isChecked = a.enabled
            holder.toggle.setOnCheckedChangeListener { _, checked ->
                val n = LocalDateTime.now()
                val upd = if (checked) a.copy(enabled = true, date = a.date?.takeIf { it.atTime(a.hour, a.minute).isAfter(n) }) else a.copy(enabled = false)
                store.update(upd)
                AlarmScheduler.schedule(this@AlarmsActivity, upd, n)
                if (!checked && AlarmRingService.ringingId == a.id) AlarmRingService.stop(this@AlarmsActivity)
                binding.alarmList.post { reload() }
            }
            holder.delete.setOnClickListener {
                AlarmScheduler.cancel(this@AlarmsActivity, a.id)
                store.remove(a.id)
                if (AlarmRingService.ringingId == a.id) AlarmRingService.stop(this@AlarmsActivity)
                reload()
            }
            holder.itemView.setOnClickListener { pickTime(a) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = AlarmStore(this)
        setupInsets()
        binding.alarmList.layoutManager = LinearLayoutManager(this)
        binding.alarmList.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAddAlarm.setOnClickListener { pickTime(null) }
        binding.btnClockApp.setOnClickListener {
            try {
                startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            } catch (e: Exception) {
                // tidak ada aplikasi Jam
            }
        }
        binding.btnExact.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    // abaikan
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun pickTime(existing: NineAlarm?) {
        val now = LocalDateTime.now()
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(existing?.hour ?: now.plusHours(1).hour)
            .setMinute(existing?.minute ?: 0)
            .setTitleText(if (existing == null) R.string.alarm_add else R.string.alarm_edit)
            .build()
        picker.addOnPositiveButtonClickListener {
            val n = LocalDateTime.now()
            val a = if (existing == null) {
                store.add(picker.hour, picker.minute, null, emptySet(), null)
            } else {
                existing.copy(hour = picker.hour, minute = picker.minute, enabled = true, date = null).also { store.update(it) }
            }
            AlarmScheduler.schedule(this, a, n)
            reload()
        }
        picker.show(supportFragmentManager, "time")
    }

    @Suppress("NotifyDataSetChanged")
    private fun reload() {
        items = AlarmMatch.sorted(store.all())
        adapter.notifyDataSetChanged()
        binding.txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.btnExact.visibility = if (AlarmScheduler.canExact(this)) View.GONE else View.VISIBLE
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.alarmsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
