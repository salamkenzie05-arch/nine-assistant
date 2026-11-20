package id.nine.assistant.a11y

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import id.nine.assistant.NineApp
import id.nine.assistant.nlu.SettingsTarget

/**
 * Layanan aksesibilitas Nine. Android tidak mengizinkan aplikasi biasa menyalakan Wi-Fi/Bluetooth/mode pesawat/hotspot
 * atau menekan tombol di aplikasi lain; layanan ini melakukannya seperti jari pengguna: membuka Panel Cepat dan
 * mengetuk tombolnya, memilih lagu pertama di YouTube Music, serta menampilkan tombol melayang (seperti AssistiveTouch).
 *
 * Layanan hanya bekerja saat dipanggil perintah (atau untuk menggambar tombol melayang). Ia tidak membaca layar
 * di luar tugas itu dan tidak mengirim apa pun ke internet.
 */
class NineAccessibilityService : AccessibilityService() {

    enum class Outcome { DONE, ALREADY, CLICKED, NOT_FOUND, BUSY, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var touch: AssistiveTouch? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshTouch()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Tidak memakai peristiwa: semua tugas berjalan lewat polling singkat saat diperintah.
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        touch?.reposition()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        touch?.hide()
        touch = null
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        touch?.hide()
        touch = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Tampilkan atau sembunyikan tombol melayang sesuai pengaturan. */
    fun refreshTouch() {
        val want = (application as NineApp).prefs.assistiveTouch
        if (want) {
            if (touch == null) touch = AssistiveTouch(this)
            touch?.show()
        } else {
            touch?.hide()
            touch = null
        }
    }

    // ------------------------------------------------------------------ simpul layar

    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            rootInActiveWindow?.let { out.add(it) }
        } catch (e: Exception) {
            // abaikan
        }
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (out.none { it == r }) out.add(r)
            }
        } catch (e: Exception) {
            // abaikan
        }
        return out
    }

    private fun systemUiRoots(): List<AccessibilityNodeInfo> {
        val all = roots()
        val sys = all.filter { (it.packageName?.toString() ?: "").contains("systemui") }
        return sys.ifEmpty { all }
    }

    private fun rootOf(pkg: String): AccessibilityNodeInfo? = roots().firstOrNull { it.packageName?.toString() == pkg }

    /** Telusuri [n] (kedalaman terbatas); berhenti dan kembalikan node pertama yang [pick]-nya true. */
    private fun find(n: AccessibilityNodeInfo?, depth: Int = 0, pick: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null || depth > 45) return null
        if (pick(n)) return n
        for (i in 0 until n.childCount) {
            val c = try {
                n.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            find(c, depth + 1, pick)?.let { return it }
        }
        return null
    }

    private fun texts(n: AccessibilityNodeInfo?, depth: Int, out: MutableList<String>) {
        if (n == null || depth < 0) return
        n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until n.childCount) {
            val c = try {
                n.getChild(i)
            } catch (e: Exception) {
                null
            }
            texts(c, depth - 1, out)
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            hops++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun screenSize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        return Pair(m.widthPixels, m.heightPixels)
    }

    // ------------------------------------------------------------------ tombol Panel Cepat

    private val offRe = Regex("""\b(off|mati|nonaktif|non-aktif|tidak aktif|dimatikan|disabled)\b""")
    private val onRe = Regex("""\b(on|aktif|menyala|nyala|hidup|dinyalakan|enabled)\b""")

    private fun parseOnOff(raw: String): Boolean? {
        val s = norm(raw).lowercase().trim()
        if (s.isEmpty()) return null
        if (offRe.containsMatchIn(s)) return false
        if (onRe.containsMatchIn(s)) return true
        return null
    }

    private fun labelRegex(t: SettingsTarget): Regex = when (t) {
        SettingsTarget.WIFI -> Regex("""^(wi-?fi|internet)\b""")
        SettingsTarget.BLUETOOTH -> Regex("""^bluetooth\b""")
        SettingsTarget.AIRPLANE -> Regex("""airplane|pesawat|flight|penerbangan""")
        SettingsTarget.HOTSPOT -> Regex("""hotspot|tethering|modem""")
        SettingsTarget.MOTION -> Regex("""motion|gerak""")
        SettingsTarget.LOCATION -> Regex("""^(location|lokasi)\b""")
        SettingsTarget.MOBILE_DATA -> Regex("""mobile data|data seluler|data ponsel|cellular|^data\b|^internet\b""")
        SettingsTarget.DND -> Regex("""do not disturb|jangan ganggu|\bdnd\b""")
        SettingsTarget.BATTERY_SAVER -> Regex("""battery saver|hemat baterai|penghemat baterai|power saving|penghemat daya|hemat daya""")
        else -> Regex("""^\b$""")
    }

    /** Samakan tanda hubung/spasi khusus (mis. "Wi‑Fi" di One UI memakai tanda hubung tak terputus) agar cocok dengan regex. */
    private fun norm(s: String): String = s.replace(Regex("[\\u2010-\\u2015\\u2212]"), "-").replace('\u00A0', ' ').replace('\u202F', ' ')

    private fun labelOf(n: AccessibilityNodeInfo): String {
        val cd = n.contentDescription?.toString().orEmpty()
        val t = n.text?.toString().orEmpty()
        var base = if (cd.isNotBlank()) cd else t
        if (base.isBlank()) {
            val sub = ArrayList<String>()
            texts(n, 3, sub)
            base = sub.firstOrNull().orEmpty()
        }
        return norm(base).split(',', '\n').firstOrNull().orEmpty().trim().lowercase()
    }

    private fun stateOf(n: AccessibilityNodeInfo): Boolean? {
        if (n.isCheckable) return n.isChecked
        if (Build.VERSION.SDK_INT >= 30) {
            parseOnOff(n.stateDescription?.toString().orEmpty())?.let { return it }
        }
        val cd = n.contentDescription?.toString().orEmpty()
        for (p in cd.split(',', '\n').drop(1)) parseOnOff(p)?.let { return it }
        val sub = ArrayList<String>()
        texts(n, 3, sub)
        for (t in sub.drop(1)) parseOnOff(t)?.let { return it }
        return null
    }

    private fun findTile(re: Regex): AccessibilityNodeInfo? {
        val (sw, sh) = screenSize()
        for (r in systemUiRoots()) {
            val hit = find(r) { n ->
                if (!(n.isClickable || n.isLongClickable) || !n.isVisibleToUser) return@find false
                val b = Rect()
                n.getBoundsInScreen(b)
                if (b.width() <= 0 || b.height() <= 0 || b.width() > sw * 0.62 || b.height() > sh * 0.4) return@find false
                re.containsMatchIn(labelOf(n))
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun scrollShade() {
        for (r in systemUiRoots()) {
            val s = find(r) { it.isScrollable && it.isVisibleToUser }
            if (s != null && s.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return
        }
    }

    private fun systemUiVisible(): Boolean = roots().any { (it.packageName?.toString() ?: "").contains("systemui") }

    private fun closeShade() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else if (systemUiVisible()) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        } catch (e: Exception) {
            // abaikan
        }
    }

    /** Switch di dialog "Internet" (Android 12+) yang labelnya cocok dengan [re]. */
    private fun findDialogSwitch(re: Regex): AccessibilityNodeInfo? {
        for (r in systemUiRoots()) {
            val hit = find(r) { n ->
                val isSwitch = n.isCheckable || (n.className?.toString() ?: "").contains("Switch")
                if (!isSwitch || !n.isVisibleToUser) return@find false
                val sub = ArrayList<String>()
                texts(n, 0, sub)
                n.parent?.let { texts(it, 2, sub) }
                sub.any { re.containsMatchIn(norm(it).lowercase()) }
            }
            if (hit != null) return hit
        }
        return null
    }

    /**
     * Nyalakan/matikan [target] lewat Panel Cepat. [done] dipanggil di main thread. Bila tombolnya tidak ada di
     * Panel Cepat, Wi-Fi/Bluetooth/mode pesawat dicoba lewat halaman pengaturannya.
     */
    fun toggle(target: SettingsTarget, on: Boolean, done: (Outcome) -> Unit) {
        if (busy) {
            done(Outcome.BUSY)
            return
        }
        busy = true
        val finish: (Outcome) -> Unit = { o ->
            busy = false
            done(o)
        }
        val opened = try {
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        } catch (e: Exception) {
            false
        }
        if (!opened) {
            finish(Outcome.FAILED)
            return
        }
        TileTask(target, on) { outcome ->
            if (outcome == Outcome.NOT_FOUND && pageIntent(target) != null) {
                closeShade()
                main.postDelayed({ PageTask(target, on, finish).begin() }, 500)
            } else {
                closeShade()
                finish(outcome)
            }
        }.begin()
    }

    private inner class TileTask(val target: SettingsTarget, val on: Boolean, val done: (Outcome) -> Unit) {
        private val re = labelRegex(target)
        private var tries = 0
        private var scrolls = 0
        private var verifyTries = 0
        private var clicked = false

        fun begin() {
            main.postDelayed({ seek() }, 800)
        }

        private fun seek() {
            tries++
            val tile = findTile(re)
            if (tile == null) {
                if (tries > MAX_FIND) {
                    done(Outcome.NOT_FOUND)
                    return
                }
                if (tries % 4 == 0 && scrolls < 4) {
                    scrolls++
                    scrollShade()
                }
                main.postDelayed({ seek() }, 300)
                return
            }
            val st = stateOf(tile)
            if (st == on) {
                done(Outcome.ALREADY)
                return
            }
            clicked = clickNode(tile)
            if (!clicked) {
                done(Outcome.FAILED)
                return
            }
            main.postDelayed({ verify() }, 650)
        }

        private fun verify() {
            verifyTries++
            // Android 12+: tombol "Internet" membuka dialog; saklar Wi-Fi/data seluler ada di dalamnya.
            if (target == SettingsTarget.WIFI || target == SettingsTarget.MOBILE_DATA) {
                val re2 = if (target == SettingsTarget.WIFI) Regex("""wi-?fi""") else Regex("""mobile data|data seluler|data ponsel|cellular""")
                val sw = findDialogSwitch(re2)
                if (sw != null) {
                    if (sw.isChecked == on) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        main.postDelayed({ done(Outcome.ALREADY) }, 250)
                    } else {
                        clickNode(sw)
                        main.postDelayed({
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            done(Outcome.DONE)
                        }, 700)
                    }
                    return
                }
            }
            val tile = findTile(re)
            val st = tile?.let { stateOf(it) }
            if (st == on) {
                done(Outcome.DONE)
                return
            }
            if (verifyTries >= MAX_VERIFY) {
                done(Outcome.CLICKED)
                return
            }
            main.postDelayed({ verify() }, 350)
        }
    }

    private fun pageIntent(t: SettingsTarget): Intent? = when (t) {
        SettingsTarget.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        SettingsTarget.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        SettingsTarget.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        else -> null
    }

    /** Cadangan: buka halaman pengaturan lalu ketuk saklar utamanya (saklar pertama di halaman). */
    private inner class PageTask(val target: SettingsTarget, val on: Boolean, val done: (Outcome) -> Unit) {
        private var tries = 0

        fun begin() {
            val i = pageIntent(target)
            if (i == null) {
                done(Outcome.NOT_FOUND)
                return
            }
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                done(Outcome.FAILED)
                return
            }
            main.postDelayed({ look() }, 900)
        }

        private fun look() {
            tries++
            val sw = roots().firstNotNullOfOrNull { r ->
                find(r) { n ->
                    val cls = n.className?.toString() ?: ""
                    (cls.contains("Switch") || n.isCheckable) && n.isVisibleToUser &&
                        (n.packageName?.toString() ?: "").let { !it.contains("systemui") }
                }
            }
            if (sw == null) {
                if (tries > 14) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    done(Outcome.NOT_FOUND)
                } else {
                    main.postDelayed({ look() }, 300)
                }
                return
            }
            if (sw.isChecked == on) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                done(Outcome.ALREADY)
                return
            }
            clickNode(sw)
            main.postDelayed({
                performGlobalAction(GLOBAL_ACTION_BACK)
                done(Outcome.DONE)
            }, 800)
        }
    }

    // ------------------------------------------------------------------ YouTube Music

    /**
     * Dipanggil sesaat setelah hasil pencarian YouTube Music dibuka: pilih tab "Lagu" lalu ketuk lagu pertama.
     * Berjalan sendiri di latar; bila layarnya tidak dikenali, hasil pencarian tetap terbuka untuk dipilih manual.
     */
    fun playFirstSong() {
        val ytm = "com.google.android.apps.youtube.music"
        val names = setOf("songs", "lagu")
        val runner = object : Runnable {
            var tries = 0
            var chipDone = false
            var chipAt = 0L

            override fun run() {
                tries++
                val root = rootOf(ytm)
                if (root == null) {
                    if (tries < 45) main.postDelayed(this, 350)
                    return
                }
                if (!chipDone) {
                    val chip = find(root) { n -> (n.text?.toString()?.trim()?.lowercase() ?: "") in names && n.isVisibleToUser }
                    if (chip != null) {
                        clickNode(chip)
                        chipDone = true
                        chipAt = System.currentTimeMillis()
                        main.postDelayed(this, 1400)
                    } else if (tries < 16) {
                        main.postDelayed(this, 350)
                    } else {
                        // tidak ada tab "Lagu": langsung ke hasil pertama
                        chipDone = true
                        chipAt = 0L
                        main.postDelayed(this, 100)
                    }
                    return
                }
                if (System.currentTimeMillis() - chipAt < 1000) {
                    main.postDelayed(this, 300)
                    return
                }
                val item = firstSongRow(root)
                if (item != null) {
                    clickNode(item)
                } else if (tries < 70) {
                    main.postDelayed(this, 350)
                }
            }
        }
        main.postDelayed(runner, 1300)
    }

    /** Baris hasil pertama pada daftar terbesar di layar (daftar hasil pencarian). */
    private fun firstSongRow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestH = 0
        fun scan(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30) return
            val cls = n.className?.toString() ?: ""
            if ((cls.contains("RecyclerView") || cls.contains("ListView")) && n.isVisibleToUser) {
                val b = Rect()
                n.getBoundsInScreen(b)
                if (b.height() > bestH && n.childCount > 0) {
                    bestH = b.height()
                    best = n
                }
            }
            for (i in 0 until n.childCount) {
                val c = try {
                    n.getChild(i)
                } catch (e: Exception) {
                    null
                }
                scan(c, depth + 1)
            }
        }
        scan(root, 0)
        val list = best ?: return null
        val minH = (resources.displayMetrics.density * 48).toInt()
        for (i in 0 until list.childCount) {
            val c = try {
                list.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            val b = Rect()
            c.getBoundsInScreen(b)
            if (b.height() < minH || !c.isVisibleToUser) continue
            val sub = ArrayList<String>()
            texts(c, 4, sub)
            if (sub.isEmpty()) continue
            if (c.isClickable || find(c) { it.isClickable } != null) return c
        }
        return null
    }

    // ------------------------------------------------------------------ tindakan global (tombol melayang)

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun showRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun showNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun lockScreen(): Boolean = if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false

    companion object {
        /** True di edisi Full (Nine-Full.apk): layanan aksesibilitas tersedia. */
        const val AVAILABLE = true

        private const val MAX_FIND = 26
        private const val MAX_VERIFY = 9

        @Volatile
        var instance: NineAccessibilityService? = null
            private set

        /** True bila layanan Nine sudah dinyalakan di Pengaturan > Aksesibilitas (walau belum tersambung). */
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, NineAccessibilityService::class.java)
            val enabled = try {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            } catch (e: Exception) {
                ""
            }
            return enabled.split(':').any { s ->
                val c = ComponentName.unflattenFromString(s.trim())
                c != null && c == me
            }
        }

        /** Terapkan pengaturan tombol melayang. False bila perlu layanan aksesibilitas tetapi belum aktif. */
        fun applyTouch(ctx: Context): Boolean {
            val svc = instance
            if (svc != null) {
                svc.refreshTouch()
                return true
            }
            return !(ctx.applicationContext as NineApp).prefs.assistiveTouch
        }
    }
}
