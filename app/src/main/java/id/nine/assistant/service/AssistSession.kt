package id.nine.assistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import id.nine.assistant.MainActivity

/** Saat asisten dipanggil, buka layar asisten dan langsung mulai mendengarkan. */
class AssistSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_AUTO_LISTEN, true)
            putExtra(MainActivity.EXTRA_TOKEN, (context.applicationContext as id.nine.assistant.NineApp).prefs.launchToken)
        }
        try {
            startAssistantActivity(intent)
        } catch (e: Exception) {
            try {
                context.startActivity(intent)
            } catch (e2: Exception) {
                // tidak ada yang bisa dilakukan; sesi ditutup di bawah
                hide()
            }
        }
    }
}
