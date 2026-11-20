package id.nine.assistant.wake

import android.content.Context
import java.io.File

/**
 * Model Vosk kecil (bahasa Inggris, ±40 MB) dibundel di assets/vosk-en saat build.
 * Disalin sekali ke penyimpanan aplikasi karena Vosk membaca model dari path file.
 */
object VoskAssets {
    private const val ASSET_DIR = "vosk-en"
    private const val VERSION = "small-en-us-0.15"

    fun hasBundledModel(context: Context): Boolean = try {
        !context.assets.list(ASSET_DIR).isNullOrEmpty()
    } catch (e: Exception) {
        false
    }

    /** Mengembalikan folder model yang siap dipakai, atau null bila model tidak ada di APK. */
    fun ensure(context: Context): File? {
        val target = File(context.filesDir, ASSET_DIR)
        val marker = File(target, ".nine-version")
        if (marker.isFile && marker.readText() == VERSION) return target
        if (!hasBundledModel(context)) return null
        target.deleteRecursively()
        copy(context, ASSET_DIR, target)
        marker.writeText(VERSION)
        return target
    }

    private fun copy(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (c in children) copy(context, "$assetPath/$c", File(dest, c))
    }
}
