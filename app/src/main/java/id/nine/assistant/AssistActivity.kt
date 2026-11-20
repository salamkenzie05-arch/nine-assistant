package id.nine.assistant

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import id.nine.assistant.core.AssistantController
import id.nine.assistant.service.AssistSession
import java.lang.ref.WeakReference

/**
 * Layar asisten: dibuka oleh tombol power / "hey nine" saat Nine menjadi asisten digital default.
 * Latarnya transparan dengan vinyet lembut (seperti Google) sehingga aplikasi di belakang tetap terlihat.
 * Menutup diri sendiri (dan sesi asistennya) begitu Nine membuka aplikasi lain, supaya layar tidak nyangkut.
 */
class AssistActivity : MainActivity() {

    private var closing = false
    private var launched = false
    private var sawBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = WeakReference(this)

        binding.root.setBackgroundResource(R.drawable.bg_assist_vignette)
        binding.content.setBackgroundResource(R.drawable.bg_assist_scrim)
        binding.content.gravity = Gravity.BOTTOM
        binding.topBar.visibility = View.GONE
        binding.modelCard.visibility = View.GONE

        // Panel riwayat dibatasi agar ada ruang kosong di atas: ketuk di sana untuk menutup.
        val h = resources.displayMetrics.heightPixels
        (binding.listFrame.layoutParams as LinearLayout.LayoutParams).apply {
            height = (h * 0.36f).toInt()
            weight = 0f
            binding.listFrame.layoutParams = this
        }
        binding.root.setOnClickListener { closeOverlay() }
    }

    override fun onLaunch(intent: Intent, fallback: Intent?): Boolean {
        val ok = super.onLaunch(intent, fallback)
        if (ok && !launched) {
            // Aplikasi lain sudah dibuka. Layar asisten ditutup setelah Nine selesai mengucapkan jawabannya
            // (menutupnya terlalu cepat akan memotong suara). Dua pengaman waktu mencegah layar menggantung.
            launched = true
            sawBusy = controller.state != AssistantController.State.IDLE
            binding.root.postDelayed({ if (!sawBusy) closeOverlay() }, NO_SPEECH_CLOSE_MS)
            binding.root.postDelayed({ closeOverlay() }, MAX_CLOSE_MS)
        }
        return ok
    }

    override fun onState(state: AssistantController.State) {
        super.onState(state)
        if (!launched) return
        if (state != AssistantController.State.IDLE) {
            sawBusy = true
        } else if (sawBusy) {
            binding.root.postDelayed({ closeOverlay() }, 350)
        }
    }

    private fun closeOverlay() {
        if (closing) return
        closing = true
        finish()
    }

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        super.onDestroy()
        AssistSession.dismiss()
    }

    companion object {
        private const val NO_SPEECH_CLOSE_MS = 2500L
        private const val MAX_CLOSE_MS = 25_000L
        private var instance: WeakReference<AssistActivity>? = null

        /** Dipanggil oleh sesi asisten saat sistem menyembunyikannya. */
        fun closeIfOpen() {
            instance?.get()?.let { a ->
                a.runOnUiThread { if (!a.isFinishing) a.closeOverlay() }
            }
        }
    }
}
