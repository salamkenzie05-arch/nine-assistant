package id.nine.assistant

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import id.nine.assistant.core.Affinity
import id.nine.assistant.core.AffinityRules
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.Mood
import id.nine.assistant.core.SideQuests
import id.nine.assistant.databinding.ActivityNineBinding
import java.time.LocalDate

/**
 * Layar "Nine" (gaya halaman streak): hari beruntun, level, Nine dengan tangan dan kaki berupa bola melayang,
 * bar XP, cara menumbuhkan kedekatan, dan tempat kustomisasi (sifat, pakaian, suara) yang disiapkan untuk nanti.
 */
class NineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNineBinding
    private var greeted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupInsets()
        binding.btnBack.setOnClickListener { finish() }
        val soon = View.OnClickListener { Toast.makeText(this, R.string.nine_soon, Toast.LENGTH_SHORT).show() }
        binding.chipPersonality.setOnClickListener(soon)
        binding.chipOutfit.setOnClickListener(soon)
        binding.chipVoice.setOnClickListener(soon)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (!greeted) {
            greeted = true
            binding.buddy.react(Mood.HAPPY, 1800)
        }
    }

    private fun render() {
        val nine = application as NineApp
        val prefs = nine.prefs
        val lang = prefs.lang
        val st = nine.affinity.state()
        val today = LocalDate.now()

        binding.txtTitlePill.text = st.level.name(lang)
        binding.txtLevelNum.text = st.level.number.toString()
        binding.txtStreak.text = st.streak.toString()
        binding.txtSay.text = sayLine(lang, st, today.toEpochDay().toInt() + st.level.number)

        val next = st.next
        if (next == null) {
            binding.txtXp.setText(R.string.nine_top)
        } else {
            val need = (next.minXp - st.level.minXp).coerceAtLeast(1)
            binding.txtXp.text = getString(R.string.nine_xp_fmt, (st.xp - st.level.minXp).coerceIn(0, need), need)
        }
        binding.progress.setProgressCompat((st.progress * 1000).toInt().coerceIn(0, 1000), false)
        // XP sudah cukup tetapi level berikutnya masih menunggu hari pemakaian.
        val lockedByDays = next != null && st.xp >= next.minXp
        binding.txtHint.text = if (lockedByDays) {
            getString(R.string.nine_hint_days, (AffinityRules.MIN_DAYS[st.level.number] - st.days).coerceAtLeast(1))
        } else getString(R.string.nine_hint_streak)

        val found = prefs.questsFound.count { SideQuests.byId(it) != null }
        val total = SideQuests.ALL.size
        val surpriseDone = (prefs.questDay == today.toString() && prefs.questDone) || found >= total
        binding.growList.removeAllViews()
        addGrow(
            st.activeToday,
            getString(R.string.nine_grow_talk),
            getString(R.string.nine_grow_talk_sub)
        )
        addGrow(
            st.todayCount >= 10,
            getString(R.string.nine_grow_more),
            getString(R.string.nine_grow_more_sub)
        )
        addGrow(
            surpriseDone,
            getString(R.string.nine_grow_surprise),
            if (found >= total) getString(R.string.nine_grow_surprise_all)
            else getString(R.string.nine_grow_surprise_sub, SideQuests.REWARD, found, total)
        )
    }

    private fun addGrow(done: Boolean, title: String, sub: String) {
        val row = layoutInflater.inflate(R.layout.item_grow, binding.growList, false)
        row.findViewById<TextView>(R.id.growTitle).text = title
        row.findViewById<TextView>(R.id.growSub).text = sub
        if (done) {
            row.findViewById<View>(R.id.growBadge).setBackgroundResource(R.drawable.bg_check_done)
            row.findViewById<View>(R.id.growCheck).visibility = View.VISIBLE
        }
        binding.growList.addView(row)
    }

    /** Ucapan Nine di gelembung: berganti tiap hari, menyesuaikan sudah/belum ngobrol dan panjang streak. */
    private fun sayLine(lang: AppLang, st: Affinity.State, seed: Int): String {
        val isId = lang == AppLang.ID
        val pool: List<String> = when {
            !st.activeToday -> if (isId) {
                listOf("Ayo ngobrol, aku menunggu.", "Hari ini belum kita mulai, lho.", "Aku kangen. Sapa aku, dong.")
            } else {
                listOf("Come chat, I'm waiting.", "We haven't started today yet.", "I missed you. Say hello?")
            }
            st.streak >= 7 -> if (isId) {
                listOf("${st.streak} hari berturut-turut. Aku senang.", "Kita rajin ya, ${st.streak} hari!")
            } else {
                listOf("${st.streak} days in a row. I'm happy.", "We're on a roll, ${st.streak} days!")
            }
            else -> if (isId) {
                listOf("Senang kamu di sini.", "Ada yang bisa kubantu?", "Aku selalu di sini.")
            } else {
                listOf("Glad you're here.", "Anything I can do?", "I'm always here.")
            }
        }
        return pool[Math.floorMod(seed, pool.size)]
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.nineRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
