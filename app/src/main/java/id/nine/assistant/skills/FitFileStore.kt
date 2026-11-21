package id.nine.assistant.skills

import android.content.Context
import id.nine.assistant.fit.FitCodec
import id.nine.assistant.fit.FitData
import id.nine.assistant.fit.FitStore
import java.io.File

/**
 * Data pelatih fitness + gizi: satu berkas JSON di penyimpanan pribadi aplikasi (tidak ikut cadangan, tidak dikirim ke mana pun).
 * Ditulis lewat berkas sementara lalu di-rename supaya tidak setengah tertulis bila aplikasi dimatikan saat menyimpan.
 */
class FitFileStore(context: Context) : FitStore {
    private val dir = context.applicationContext.filesDir
    private val file = File(dir, "fit_data.json")
    private val tmp = File(dir, "fit_data.json.tmp")
    private val bad = File(dir, "fit_data.bad.json")

    @Synchronized
    override fun load(): FitData {
        return try {
            if (!file.exists()) return FitData()
            val raw = file.readText(Charsets.UTF_8)
            val data = FitCodec.decode(raw)
            // Berkas besar tapi hasilnya kosong = rusak: simpan salinannya agar tidak tertimpa diam-diam.
            val empty = data.workouts.isEmpty() && data.meals.isEmpty() && data.weights.isEmpty() && data.inbody.isEmpty()
            if (empty && raw.length > 80) runCatching { file.copyTo(bad, overwrite = true) }
            data
        } catch (e: Exception) {
            FitData()
        }
    }

    @Synchronized
    override fun save(data: FitData) {
        val raw = FitCodec.encode(data)
        tmp.writeText(raw, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(raw, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
