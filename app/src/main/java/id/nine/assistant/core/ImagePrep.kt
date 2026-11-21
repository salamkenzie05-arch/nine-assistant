package id.nine.assistant.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import kotlin.math.max

/**
 * Menyiapkan gambar pilihan pengguna untuk dibaca otak AI: dikecilkan (maks. [MAX_SIDE] px), diputar sesuai EXIF,
 * lalu disimpan ulang sebagai JPEG baru di cache pribadi aplikasi. Menyimpan ulang membuang metadata (termasuk lokasi GPS).
 * Berkas hanya hidup sebentar dan dihapus setelah dijawab.
 */
object ImagePrep {
    const val MAX_SIDE = 1024
    /** Sisi terpanjang untuk membaca teks (lembar InBody): lebih besar agar angka kecil tetap terbaca. */
    const val OCR_SIDE = 2000
    private const val MAX_AGE_MS = 60L * 60_000L

    /** Hasil berupa berkas JPEG baru, atau null bila gambar tidak bisa dibaca. Jangan panggil di thread utama. */
    fun prepare(ctx: Context, uri: Uri, maxSide: Int = MAX_SIDE): File? {
        return try {
            val dir = File(ctx.cacheDir, "pick")
            dir.mkdirs()
            purge(dir)
            val cr = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            val orientation = try {
                cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                    ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            }
            val longest = max(decoded.width, decoded.height)
            if (longest > maxSide) {
                val k = maxSide.toFloat() / longest
                m.postScale(k, k)
            }
            val out = if (m.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (out !== decoded) decoded.recycle()
            val file = File(dir, "pick_${System.nanoTime()}.jpg")
            file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            out.recycle()
            file
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun purge(dir: File) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.delete() }
    }
}
