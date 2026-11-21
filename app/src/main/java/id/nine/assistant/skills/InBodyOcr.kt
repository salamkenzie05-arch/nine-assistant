package id.nine.assistant.skills

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import id.nine.assistant.fit.InBodyParser
import java.io.File

/**
 * Membaca teks dari foto lembar InBody dengan ML Kit (model dibundel di aplikasi; gambar diproses di perangkat dan tidak
 * dikirim ke mana pun). Hanya mengembalikan baris teks beserta kotaknya; penguraian angka ada di [InBodyParser].
 */
object InBodyOcr {
    private val main = Handler(Looper.getMainLooper())

    /** [cb] selalu dipanggil di thread utama; null = gagal membaca. */
    fun read(context: Context, path: String, cb: (List<InBodyParser.OcrLine>?) -> Unit) {
        val recognizer = try {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } catch (e: Throwable) {
            main.post { cb(null) }
            return
        }
        fun finish(lines: List<InBodyParser.OcrLine>?) {
            try { recognizer.close() } catch (e: Throwable) { /* abaikan */ }
            main.post { cb(lines) }
        }
        try {
            val image = InputImage.fromFilePath(context.applicationContext, Uri.fromFile(File(path)))
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    val out = ArrayList<InBodyParser.OcrLine>()
                    for (block in text.textBlocks) for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        out.add(InBodyParser.OcrLine(line.text, box.left, box.top, box.right, box.bottom))
                    }
                    finish(out)
                }
                .addOnFailureListener { finish(null) }
        } catch (e: Throwable) {
            finish(null)
        }
    }
}
