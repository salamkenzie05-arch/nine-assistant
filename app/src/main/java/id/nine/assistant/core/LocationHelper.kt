package id.nine.assistant.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lokasi perangkat untuk akurasi (cuaca, "terdekat", waktu setempat) tanpa Google Play Services.
 * Hanya dipakai bila pengguna mengaktifkan "Gunakan lokasi" di Pengaturan dan memberi izin.
 * Nama tempat disimpan sementara di memori; koordinat tidak disimpan ke disk.
 */
class LocationHelper(context: Context, private val prefs: Prefs) {

    data class Place(val lat: Double, val lon: Double, val accuracyM: Float, val label: String?, val time: Long, val countryCode: String? = null)

    private val app = context.applicationContext
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var last: Place? = null
        private set

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun enabled(): Boolean = prefs.useLocation && hasPermission()

    /** Ringkasan untuk AI, mis. "Bandung, Jawa Barat, Indonesia (±50 m)". Null bila tidak tersedia. */
    fun describe(): String? {
        val p = last ?: return null
        val coords = String.format(Locale.US, "%.4f, %.4f", p.lat, p.lon)
        return (p.label?.let { "$it ($coords)" } ?: coords)
    }

    /** Perbarui lokasi di latar belakang; [done] dipanggil di main thread (boleh null). */
    @SuppressLint("MissingPermission")
    fun refresh(done: ((Place?) -> Unit)? = null) {
        if (!enabled()) {
            done?.let { main.post { it(null) } }
            return
        }
        val cached = bestLastKnown()
        if (cached != null && System.currentTimeMillis() - cached.time < 10 * 60 * 1000L) {
            resolve(cached, done)
            return
        }
        val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
        if (provider == null || Build.VERSION.SDK_INT < 30) {
            resolve(cached, done)
            return
        }
        try {
            lm.getCurrentLocation(provider, null, io) { loc -> resolve(loc ?: cached, done) }
        } catch (e: Exception) {
            resolve(cached, done)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(): Location? {
        if (!hasPermission()) return null
        var best: Location? = null
        for (p in runCatching { lm.getProviders(true) }.getOrDefault(emptyList())) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best.time) best = l
        }
        return best
    }

    private fun resolve(loc: Location?, done: ((Place?) -> Unit)?) {
        if (loc == null) {
            done?.let { main.post { it(last) } }
            return
        }
        io.execute {
            var country: String? = null
            val label = try {
                if (Geocoder.isPresent()) {
                    @Suppress("DEPRECATION")
                    val a = Geocoder(app, Locale("id", "ID")).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                    country = a?.countryCode
                    listOfNotNull(a?.subLocality, a?.locality ?: a?.subAdminArea, a?.adminArea, a?.countryName)
                        .distinct().joinToString(", ").ifBlank { null }
                } else null
            } catch (e: Exception) {
                null
            }
            val p = Place(loc.latitude, loc.longitude, loc.accuracy, label ?: last?.label, loc.time, country)
            last = p
            done?.let { main.post { it(p) } }
        }
    }
}
