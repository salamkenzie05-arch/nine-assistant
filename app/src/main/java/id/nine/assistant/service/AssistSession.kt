package id.nine.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import id.nine.assistant.AssistActivity
import id.nine.assistant.MainActivity
import id.nine.assistant.NineApp

/**
 * Saat asisten dipanggil (tombol power/samping, geser dari sudut, atau "hey nine"), buka layar asisten
 * transparan [AssistActivity] dan langsung mulai mendengarkan.
 *
 * PENTING: jendela bawaan sesi dimatikan ([setUiEnabled]). Jendela bawaan itu layar penuh dan tembus pandang
 * tetapi tetap menangkap sentuhan, sehingga setelah Nine membuka aplikasi lain layar terasa "nyangkut"
 * dan baru bisa disentuh lagi setelah menekan Back.
 */
class AssistSession(context: Context) : VoiceInteractionSession(context) {

    override fun onCreate() {
        super.onCreate()
        try {
            setUiEnabled(false)
        } catch (e: Exception) {
            // diulang di onPrepareShow
        }
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        try {
            setUiEnabled(false)
        } catch (e: Exception) {
            // sesi tetap berfungsi; hanya jendela bawaan yang mungkin tampil
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        current = this
        try {
            setUiEnabled(false)
        } catch (e: Exception) {
            // abaikan
        }
        val intent = Intent(context, AssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_AUTO_LISTEN, true)
            putExtra(MainActivity.EXTRA_TOKEN, (context.applicationContext as NineApp).prefs.launchToken)
        }
        try {
            startAssistantActivity(intent)
        } catch (e: Exception) {
            try {
                context.startActivity(intent)
            } catch (e2: Exception) {
                // tidak ada yang bisa dilakukan; sesi ditutup
                hide()
            }
        }
    }

    override fun onHide() {
        super.onHide()
        AssistActivity.closeIfOpen()
        if (current === this) current = null
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var current: AssistSession? = null

        /** Tutup sesi asisten yang sedang tampil (dipanggil saat layar asisten selesai atau membuka aplikasi lain). */
        fun dismiss() {
            val s = current ?: return
            current = null
            try {
                s.hide()
            } catch (e: Exception) {
                // sesi sudah tertutup
            }
        }
    }
}
