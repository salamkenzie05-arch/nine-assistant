package id.nine.assistant.skills

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import id.nine.assistant.core.AppLang
import id.nine.assistant.nlu.InfoTopic
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Menjawab pertanyaan tentang HP langsung dari sistem Android. Sengaja TIDAK membaca data pengenal
 * (IMEI, nomor seri, alamat MAC, nomor telepon, nama jaringan Wi-Fi): hanya hal yang aman diucapkan.
 */
class PhoneInfoSkill(private val ctx: Context) {

    private val gb = 1_073_741_824.0

    private fun f(lang: AppLang, v: Double, digits: Int = 1): String = String.format(lang.locale, "%.${digits}f", v)

    fun answer(topic: InfoTopic, lang: AppLang): String = try {
        when (topic) {
            InfoTopic.STORAGE -> storage(lang)
            InfoTopic.RAM -> ram(lang)
            InfoTopic.MODEL -> model(lang)
            InfoTopic.ANDROID -> android(lang)
            InfoTopic.NETWORK -> network(lang)
            InfoTopic.BATTERY_HEALTH -> batteryDetail(lang)
            InfoTopic.SCREEN -> screen(lang)
            InfoTopic.UPTIME -> uptime(lang)
            InfoTopic.WIFI -> wifi(lang)
            InfoTopic.BLUETOOTH -> bluetooth(lang)
            InfoTopic.VOLUME -> volume(lang)
            InfoTopic.CPU -> cpu(lang)
        }
    } catch (e: Exception) {
        lang.t("Maaf, informasi itu tidak bisa saya baca dari sistem.", "Sorry, I couldn't read that from the system.")
    }

    /** Ringkasan untuk "info HP". */
    fun summary(lang: AppLang): String = try {
        val st = StatFs(Environment.getDataDirectory().path)
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        lang.t(
            "${deviceName()}, Android ${Build.VERSION.RELEASE}. Penyimpanan ${f(lang, st.availableBytes / gb)} dari ${f(lang, st.totalBytes / gb, 0)} GB kosong. " +
                "RAM ${f(lang, mi.totalMem / gb)} GB. Baterai $pct%.",
            "${deviceName()}, Android ${Build.VERSION.RELEASE}. ${f(lang, st.availableBytes / gb)} of ${f(lang, st.totalBytes / gb, 0)} GB storage free. " +
                "${f(lang, mi.totalMem / gb)} GB of RAM. Battery $pct%."
        )
    } catch (e: Exception) {
        lang.t("Maaf, info HP tidak bisa dibaca.", "Sorry, I couldn't read the phone info.")
    }

    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
    }

    private fun storage(lang: AppLang): String {
        val st = StatFs(Environment.getDataDirectory().path)
        val free = st.availableBytes
        val total = st.totalBytes.coerceAtLeast(1L)
        val pct = (free * 100.0 / total).roundToInt()
        return lang.t(
            "Penyimpanan kosong ${f(lang, free / gb)} dari ${f(lang, total / gb, 0)} GB ($pct% kosong).",
            "${f(lang, free / gb)} of ${f(lang, total / gb, 0)} GB of storage is free ($pct% free)."
        )
    }

    private fun ram(lang: AppLang): String {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val low = if (mi.lowMemory) lang.t(" Memori sedang menipis.", " Memory is running low.") else ""
        return lang.t(
            "RAM total ${f(lang, mi.totalMem / gb)} GB, sekarang ${f(lang, mi.availMem / gb)} GB tersedia.$low",
            "Total RAM is ${f(lang, mi.totalMem / gb)} GB, with ${f(lang, mi.availMem / gb)} GB available now.$low"
        )
    }

    private fun model(lang: AppLang): String {
        val name = try {
            Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME).orEmpty().trim()
        } catch (e: Exception) {
            ""
        }
        val tech = deviceName()
        return if (name.isNotEmpty() && !name.equals(tech, ignoreCase = true)) {
            lang.t("HP ini bernama $name ($tech).", "This phone is named $name ($tech).")
        } else {
            lang.t("HP ini $tech.", "This phone is a $tech.")
        }
    }

    private fun android(lang: AppLang): String {
        val patch = try {
            Build.VERSION.SECURITY_PATCH.orEmpty()
        } catch (e: Exception) {
            ""
        }
        val base = lang.t(
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        )
        return if (patch.isNotBlank()) lang.t("$base, patch keamanan $patch.", "$base, security patch $patch.") else "$base."
    }

    private fun network(lang: AppLang): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?: return lang.t("Tidak ada koneksi internet saat ini.", "There is no internet connection right now.")
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> lang.t("data seluler", "mobile data")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> lang.t("tethering Bluetooth", "Bluetooth tethering")
            else -> lang.t("jaringan lain", "another network")
        }
        val works = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (works) {
            lang.t("Terhubung lewat $kind, internet berfungsi.", "Connected over $kind, and the internet works.")
        } else {
            lang.t("Terhubung lewat $kind, tetapi internet belum bisa diakses.", "Connected over $kind, but the internet isn't reachable yet.")
        }
    }

    private fun batteryDetail(lang: AppLang): String {
        val b: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return lang.t("Maaf, data baterai tidak tersedia.", "Sorry, battery data isn't available.")
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level >= 0) (level * 100f / scale).roundToInt() else -1
        val temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val health = when (b.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> lang.t("kondisi baik", "health is good")
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> lang.t("terlalu panas", "overheating")
            BatteryManager.BATTERY_HEALTH_COLD -> lang.t("terlalu dingin", "too cold")
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> lang.t("tegangan berlebih", "over-voltage")
            BatteryManager.BATTERY_HEALTH_DEAD -> lang.t("rusak", "dead")
            else -> ""
        }
        val source = when {
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> lang.t("nirkabel", "wireless")
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC"
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
            else -> ""
        }
        val parts = ArrayList<String>()
        if (pct >= 0) parts += lang.t("Baterai $pct%", "Battery $pct%")
        parts += when {
            charging && source.isNotEmpty() -> lang.t("sedang diisi ($source)", "charging ($source)")
            charging -> lang.t("sedang diisi", "charging")
            else -> lang.t("tidak diisi", "not charging")
        }
        if (temp != Int.MIN_VALUE) parts += lang.t("suhu ", "temperature ") + f(lang, temp / 10.0) + "°C"
        if (health.isNotEmpty()) parts += health
        val hot = if (temp >= 420) lang.t(" Cukup panas; istirahatkan HP sebentar.", " That's rather warm; let the phone rest a moment.") else ""
        return parts.joinToString(", ") + "." + hot
    }

    @Suppress("DEPRECATION")
    private fun screen(lang: AppLang): String {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return lang.t("Maaf, data layar tidak tersedia.", "Sorry, display data isn't available.")
        val m = DisplayMetrics()
        d.getRealMetrics(m)
        val inches = if (m.xdpi > 0f && m.ydpi > 0f) {
            val w = m.widthPixels / m.xdpi
            val h = m.heightPixels / m.ydpi
            sqrt((w * w + h * h).toDouble())
        } else 0.0
        val hz = d.refreshRate.roundToInt()
        val size = if (inches in 3.0..20.0) lang.t(", sekitar ${f(lang, inches)} inci", ", about ${f(lang, inches)} inches") else ""
        return lang.t(
            "Layar ${m.widthPixels} × ${m.heightPixels} piksel$size, ${m.densityDpi} dpi, saat ini $hz Hz.",
            "Display ${m.widthPixels} × ${m.heightPixels} pixels$size, ${m.densityDpi} dpi, currently $hz Hz."
        )
    }

    private fun uptime(lang: AppLang): String {
        val total = SystemClock.elapsedRealtime() / 60_000L
        val days = total / (60 * 24)
        val hours = (total / 60) % 24
        val mins = total % 60
        val idText = buildString {
            if (days > 0) append("$days hari ")
            if (hours > 0 || days > 0) append("$hours jam ")
            if (days == 0L) append("$mins menit")
        }.trim()
        val enText = buildString {
            if (days > 0) append("$days day${if (days == 1L) "" else "s"} ")
            if (hours > 0 || days > 0) append("$hours hour${if (hours == 1L) "" else "s"} ")
            if (days == 0L) append("$mins minute${if (mins == 1L) "" else "s"}")
        }.trim()
        return lang.t("HP ini menyala $idText sejak restart terakhir.", "This phone has been up for $enText since its last restart.")
    }

    @Suppress("DEPRECATION")
    private fun wifi(lang: AppLang): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val connected = cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val setting = Settings.Global.getInt(ctx.contentResolver, "wifi_on", -1)
        return when {
            connected -> lang.t("Wi-Fi menyala dan terhubung.", "Wi-Fi is on and connected.")
            setting == 1 -> lang.t("Wi-Fi menyala, tetapi belum terhubung ke jaringan.", "Wi-Fi is on, but not connected to a network.")
            setting == 0 -> lang.t("Wi-Fi mati.", "Wi-Fi is off.")
            else -> lang.t("Saya tidak bisa memastikan status Wi-Fi di HP ini.", "I can't tell the Wi-Fi state on this phone.")
        }
    }

    private fun bluetooth(lang: AppLang): String = when (Settings.Global.getInt(ctx.contentResolver, "bluetooth_on", -1)) {
        1 -> lang.t("Bluetooth menyala.", "Bluetooth is on.")
        0 -> lang.t("Bluetooth mati.", "Bluetooth is off.")
        else -> lang.t("Saya tidak bisa memastikan status Bluetooth di HP ini.", "I can't tell the Bluetooth state on this phone.")
    }

    private fun volume(lang: AppLang): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val pct = (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
        val ring = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> lang.t("senyap", "silent")
            AudioManager.RINGER_MODE_VIBRATE -> lang.t("getar", "vibrate")
            else -> lang.t("bersuara", "normal")
        }
        return lang.t("Volume media $pct%. Mode dering: $ring.", "Media volume is $pct%. Ringer mode: $ring.")
    }

    private fun cpu(lang: AppLang): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ")
        } else ""
        val name = soc.ifBlank { Build.HARDWARE }
        val cores = Runtime.getRuntime().availableProcessors()
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return lang.t(
            "Prosesor $name, $cores inti, arsitektur $abi.",
            "Processor $name, $cores cores, $abi architecture."
        )
    }
}
