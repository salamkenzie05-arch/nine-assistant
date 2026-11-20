package id.nine.assistant

import android.app.DatePickerDialog
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import id.nine.assistant.core.Greeter
import id.nine.assistant.core.Prefs
import id.nine.assistant.databinding.ActivityProfileBinding
import java.time.LocalDate

/**
 * Profil pengguna: nama, panggilan, tanggal lahir (untuk ucapan ulang tahun sekali setahun), dan pasangan (opsional).
 * Muncul otomatis saat pertama kali Nine dibuka; bisa dibuka lagi dari Pengaturan. Semua tersimpan hanya di HP ini.
 */
class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var prefs: Prefs
    private var firstRun = false
    private var birth: LocalDate? = null

    /** Panggilan umum: (Indonesia, Inggris). Memilih salah satu mengisi kedua bahasa. */
    private val calls = listOf(
        "Tuan" to "sir", "Nyonya" to "madam", "Nona" to "miss", "Bos" to "boss", "Kak" to "Kak", "Pak" to "sir", "Bu" to "madam"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = (application as NineApp).prefs
        firstRun = intent.getBooleanExtra(EXTRA_FIRST_RUN, false)
        setupInsets()

        binding.editName.setText(prefs.userName)
        binding.editCall.setText(prefs.honorificId)
        binding.editPartner.setText(prefs.partnerName)
        birth = prefs.birthDateOrNull()
        renderBirth()

        for ((id, en) in calls) {
            val chip = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = id
                isAllCaps = false
                setOnClickListener { binding.editCall.setText(id) }
                tag = en
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
            binding.callChips.addView(chip, lp)
        }

        binding.editBirth.setOnClickListener { pickDate() }
        binding.btnClearBirth.setOnClickListener {
            birth = null
            renderBirth()
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSaveProfile.setOnClickListener { save() }
        binding.btnLater.visibility = if (firstRun) View.VISIBLE else View.GONE
        binding.btnLater.setOnClickListener { finish() }
        if (!firstRun) binding.txtProfileIntro.setText(R.string.profile_intro_edit)
    }

    private fun renderBirth() {
        val b = birth
        val lang = prefs.lang
        binding.editBirth.setText(b?.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", lang.locale)) ?: "")
        val age = b?.let { Greeter.ageOn(it, LocalDate.now()) }
        binding.txtAge.text = if (age != null) lang.t("Umur: $age tahun", "Age: $age") else ""
        binding.btnClearBirth.visibility = if (b != null) View.VISIBLE else View.GONE
    }

    private fun pickDate() {
        val start = birth ?: LocalDate.now().minusYears(25)
        val dlg = DatePickerDialog(
            this,
            { _, y, m, d ->
                val picked = try {
                    LocalDate.of(y, m + 1, d)
                } catch (e: Exception) {
                    null
                }
                if (picked != null && !picked.isAfter(LocalDate.now())) {
                    birth = picked
                    renderBirth()
                }
            },
            start.year, start.monthValue - 1, start.dayOfMonth
        )
        dlg.datePicker.maxDate = System.currentTimeMillis()
        dlg.show()
    }

    private fun save() {
        val lang = prefs.lang
        val name = Prefs.cleanName(binding.editName.text?.toString().orEmpty())
        if (name.isBlank()) {
            binding.editName.error = lang.t("Isi nama dulu", "Please enter your name")
            return
        }
        val call = Prefs.cleanName(binding.editCall.text?.toString().orEmpty())
        val en = calls.firstOrNull { it.first.equals(call, ignoreCase = true) }?.second ?: call
        val partner = Prefs.cleanName(binding.editPartner.text?.toString().orEmpty())
        val changedCall = call != prefs.honorificId || en != prefs.honorificEn
        prefs.userName = name
        prefs.honorificId = call
        prefs.honorificEn = en
        prefs.partnerName = partner
        prefs.setBirthDate(birth)
        val wasDone = prefs.profileDone
        prefs.profileDone = true
        if (!wasDone) prefs.introPending = true
        if (changedCall) (application as NineApp).brain.reset() // instruksi sistem AI memakai panggilan ini
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.profileRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    companion object {
        const val EXTRA_FIRST_RUN = "first_run"
    }
}
