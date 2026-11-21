package id.nine.assistant

import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import id.nine.assistant.core.SideQuests
import id.nine.assistant.databinding.ActivityNineBinding

/**
 * Layar "Nine": status dan kemajuan kedekatan, kejutan harian (tanpa membocorkan isinya), dan tempat kustomisasi
 * (kepribadian, pakaian, nada suara) yang disiapkan untuk nanti.
 */
class NineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNineBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupInsets()
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val nine = application as NineApp
        val lang = nine.prefs.lang
        val st = nine.affinity.state()
        binding.txtLevel.text = getString(R.string.nine_level, st.level.number, st.level.name(lang))
        binding.progress.setProgressCompat((st.progress * 1000).toInt().coerceIn(0, 1000), false)
        binding.txtNext.text = st.next?.let { getString(R.string.nine_next, it.name(lang)) } ?: getString(R.string.nine_top)
        binding.txtStats.text = getString(R.string.nine_stats, st.streak, st.days)

        val found = nine.prefs.questsFound.count { SideQuests.byId(it) != null }
        val total = SideQuests.ALL.size
        val today = java.time.LocalDate.now().toString()
        val doneToday = nine.prefs.questDay == today && nine.prefs.questDone
        binding.txtSurprise.setText(
            when {
                found >= total -> R.string.nine_surprise_none
                doneToday -> R.string.nine_surprise_done
                else -> R.string.nine_surprise_open
            }
        )
        binding.txtFound.text = getString(R.string.nine_found, found, total)
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
