package id.nine.assistant.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.SearchManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import id.nine.assistant.NineApp
import id.nine.assistant.nlu.SettingsTarget

/**
 * Layanan aksesibilitas Nine. Android tidak mengizinkan aplikasi biasa menyalakan Wi-Fi/Bluetooth/mode pesawat/hotspot
 * atau menekan tombol di aplikasi lain; layanan ini melakukannya seperti jari pengguna: membuka Panel Cepat dan
 * mengetuk tombolnya, memilih lagu pertama di YouTube Music, serta menutup semua aplikasi di layar Recents.
 *
 * Layanan hanya bekerja saat dipanggil perintah. Ia tidak membaca layar di luar tugas itu dan tidak mengirim
 * apa pun ke internet.
 */
class NineAccessibilityService : AccessibilityService() {

    enum class Outcome { DONE, ALREADY, CLICKED, NOT_FOUND, BUSY, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var busyAt = 0L

    /** Langkah terakhir otomasi (untuk diagnosis di pesan galat beta). */
    @Volatile
    var lastStage: String = ""
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Tidak memakai peristiwa: semua tugas berjalan lewat polling singkat saat diperintah.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
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

    // ------------------------------------------------------------------ keadaan sebenarnya & sentuhan nyata

    /** Keadaan sebenarnya dari sistem (bukan tebakan dari layar), atau null bila tidak bisa dibaca. */
    private fun systemState(t: SettingsTarget): Boolean? = try {
        when (t) {
            SettingsTarget.WIFI -> (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
            SettingsTarget.BLUETOOTH -> (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled
            SettingsTarget.AIRPLANE -> Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** True selagi Wi-Fi/Bluetooth sedang beralih; jangan mengetuk lagi supaya tidak malah membatalkannya. */
    private fun transitioning(t: SettingsTarget): Boolean = try {
        when (t) {
            SettingsTarget.WIFI -> {
                val s = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.wifiState
                s == WifiManager.WIFI_STATE_ENABLING || s == WifiManager.WIFI_STATE_DISABLING
            }
            SettingsTarget.BLUETOOTH -> {
                val s = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.state
                s == BluetoothAdapter.STATE_TURNING_ON || s == BluetoothAdapter.STATE_TURNING_OFF
            }
            else -> false
        }
    } catch (e: Exception) {
        false
    }

    /** Sentuhan nyata di (x, y). [done] dipanggil di main thread; false bila gestur ditolak/dibatalkan. */
    private fun tapAt(x: Float, y: Float, done: (Boolean) -> Unit) {
        var called = false
        val cb: (Boolean) -> Unit = { ok ->
            if (!called) {
                called = true
                done(ok)
            }
        }
        val sent = try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 70L)
            dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        cb(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        cb(false)
                    }
                },
                main
            )
        } catch (e: Exception) {
            false
        }
        if (!sent) cb(false)
    }

    /** Sentuh titik di dalam [node] ([xFrac] = posisi horizontal 0..1, vertikal selalu di tengah). */
    private fun tapNode(node: AccessibilityNodeInfo, xFrac: Float, done: (Boolean) -> Unit) {
        val b = Rect()
        node.getBoundsInScreen(b)
        if (b.width() <= 0 || b.height() <= 0) {
            done(false)
            return
        }
        tapAt(b.left + b.width() * xFrac, b.exactCenterY(), done)
    }

    private fun collectSwitches(n: AccessibilityNodeInfo?, depth: Int, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null || depth > 40 || out.size >= 12) return
        val cls = n.className?.toString().orEmpty()
        if ((cls.contains("Switch") || n.isCheckable) && n.isVisibleToUser) out.add(n)
        for (i in 0 until n.childCount) {
            val c = try {
                n.getChild(i)
            } catch (e: Exception) {
                null
            }
            collectSwitches(c, depth + 1, out)
        }
    }

    // ------------------------------------------------------------------ Wi-Fi, Bluetooth, dll.

    /**
     * Nyalakan/matikan [target]. Urutannya: (0) bila sistem sudah dalam keadaan itu, selesai; (1) Panel Cepat: ketuk
     * tombolnya (klik biasa, lalu sentuhan nyata bila tak berefek); (2) cadangan: halaman pengaturannya, ketuk saklarnya.
     * Setiap langkah diperiksa ke keadaan sebenarnya sistem (Wi-Fi/Bluetooth/mode pesawat) bila terbaca.
     * [done] dipanggil di main thread, tepat satu kali. [lastStage] menyimpan langkah terakhir untuk diagnosis.
     */
    fun toggle(target: SettingsTarget, on: Boolean, done: (Outcome) -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (busy && now - busyAt < BUSY_MAX_MS) {
            done(Outcome.BUSY)
            return
        }
        busy = true
        busyAt = now
        lastStage = "mulai"
        var finished = false
        val finish: (Outcome) -> Unit = { o ->
            if (!finished) {
                finished = true
                busy = false
                done(o)
            }
        }
        // Pengaman: apa pun yang terjadi, layanan tidak boleh macet di status "sibuk".
        main.postDelayed({
            if (!finished) {
                lastStage = "waktu-habis"
                closeShade()
                finish(Outcome.FAILED)
            }
        }, BUSY_MAX_MS)

        try {
            if (systemState(target) == on) {
                lastStage = "sudah"
                finish(Outcome.ALREADY)
                return
            }

            fun viaPage(reason: Outcome) {
                closeShade()
                if (systemState(target) == on) {
                    finish(Outcome.DONE)
                } else if (pageIntent(target) == null) {
                    finish(reason)
                } else {
                    main.postDelayed({
                        try {
                            PageTask(target, on) { o -> finish(o) }.begin()
                        } catch (e: Exception) {
                            lastStage = "halaman-error:${e.javaClass.simpleName}"
                            finish(Outcome.FAILED)
                        }
                    }, 500)
                }
            }

            val opened = try {
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            } catch (e: Exception) {
                false
            }
            if (!opened) {
                lastStage = "panel-tidak-terbuka"
                viaPage(Outcome.FAILED)
                return
            }
            TileTask(target, on) { outcome ->
                when (outcome) {
                    Outcome.DONE, Outcome.ALREADY, Outcome.CLICKED -> {
                        closeShade()
                        finish(outcome)
                    }
                    else -> viaPage(outcome)
                }
            }.begin()
        } catch (e: Exception) {
            lastStage = "error:${e.javaClass.simpleName}"
            finish(Outcome.FAILED)
        }
    }

    private inner class TileTask(val target: SettingsTarget, val on: Boolean, val done: (Outcome) -> Unit) {
        private val re = labelRegex(target)
        private var tries = 0
        private var scrolls = 0
        private var verifyTries = 0
        private var lastBounds: Rect? = null
        private var tapped = false
        private var dialogHandled = false
        private var finished = false

        private fun end(o: Outcome) {
            if (finished) return
            finished = true
            done(o)
        }

        private fun later(ms: Long, f: () -> Unit) {
            main.postDelayed({
                try {
                    f()
                } catch (e: Exception) {
                    lastStage = "tombol-error:${e.javaClass.simpleName}"
                    end(Outcome.FAILED)
                }
            }, ms)
        }

        fun begin() {
            lastStage = "panel-terbuka"
            later(800) { seek() }
        }

        private fun seek() {
            tries++
            val tile = findTile(re)
            if (tile == null) {
                if (tries > MAX_FIND) {
                    lastStage = "tombol-tidak-ditemukan"
                    end(Outcome.NOT_FOUND)
                    return
                }
                if (tries % 4 == 0 && scrolls < 4) {
                    scrolls++
                    scrollShade()
                }
                later(300) { seek() }
                return
            }
            // Tunggu panel selesai beranimasi: batas tombol harus sama dua kali berturut-turut.
            val b = Rect()
            tile.getBoundsInScreen(b)
            val prev = lastBounds
            lastBounds = b
            if ((prev == null || prev != b) && tries < MAX_FIND - 2) {
                later(250) { seek() }
                return
            }
            val sys = systemState(target)
            if (sys == on || (sys == null && stateOf(tile) == on)) {
                lastStage = "sudah"
                end(Outcome.ALREADY)
                return
            }
            if (clickNode(tile)) {
                lastStage = "tombol-diketuk"
                later(650) { verify() }
            } else {
                lastStage = "tombol-klik-ditolak"
                tapped = true
                tapNode(tile, 0.5f) { ok ->
                    later(0) {
                        if (ok) {
                            lastStage = "tombol-disentuh"
                            later(650) { verify() }
                        } else {
                            lastStage = "tombol-sentuhan-gagal"
                            end(Outcome.FAILED)
                        }
                    }
                }
            }
        }

        private fun verify() {
            verifyTries++
            val sys = systemState(target)
            if (sys == on) {
                lastStage = "berhasil"
                end(Outcome.DONE)
                return
            }
            // Android 12+: tombol "Internet" membuka dialog; saklar Wi-Fi/data seluler ada di dalamnya.
            if ((target == SettingsTarget.WIFI || target == SettingsTarget.MOBILE_DATA) && !dialogHandled) {
                val re2 = if (target == SettingsTarget.WIFI) Regex("""wi-?fi""") else Regex("""mobile data|data seluler|data ponsel|cellular""")
                val sw = findDialogSwitch(re2)
                if (sw != null) {
                    dialogHandled = true
                    if (sw.isChecked == on) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        later(250) { end(Outcome.ALREADY) }
                    } else {
                        lastStage = "dialog-saklar"
                        if (!clickNode(sw)) tapNode(sw, 0.5f) {}
                        later(900) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            val after = systemState(target)
                            end(if (after == null || after == on) Outcome.DONE else Outcome.FAILED)
                        }
                    }
                    return
                }
            }
            val tile = findTile(re)
            tile?.refresh()
            val st = tile?.let { stateOf(it) }
            if (sys == null && st == on) {
                lastStage = "berhasil-dari-layar"
                end(Outcome.DONE)
                return
            }
            if (verifyTries >= MAX_VERIFY) {
                // Klik biasa tak berefek: coba satu sentuhan nyata, kecuali sistem masih beralih atau tombol sudah berubah.
                val known = sys != null || st != null // tanpa cara memastikan, jangan mengetuk lagi (bisa membatalkan)
                if (!tapped && tile != null && known && !transitioning(target) && st != on) {
                    tapped = true
                    verifyTries = 0
                    lastStage = "tombol-disentuh-ulang"
                    tapNode(tile, 0.5f) { later(700) { verify() } }
                    return
                }
                lastStage = "tombol-tanpa-hasil"
                end(if (sys == null) Outcome.CLICKED else Outcome.FAILED)
                return
            }
            later(350) { verify() }
        }
    }

    private fun pageIntent(t: SettingsTarget): Intent? = when (t) {
        SettingsTarget.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        SettingsTarget.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        SettingsTarget.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        else -> null
    }

    /** Cadangan: buka halaman pengaturan lalu ketuk saklar utamanya, dan pastikan sistem benar-benar berubah. */
    private inner class PageTask(val target: SettingsTarget, val on: Boolean, val done: (Outcome) -> Unit) {
        private var tries = 0
        private var polls = 0
        private var escalated = false
        private var finished = false
        private val pageLabel = when (target) {
            SettingsTarget.WIFI -> Regex("""wi-?fi""")
            SettingsTarget.BLUETOOTH -> Regex("""bluetooth""")
            else -> Regex("""airplane|pesawat|flight|penerbangan""")
        }

        private fun end(o: Outcome) {
            if (finished) return
            finished = true
            done(o)
        }

        private fun later(ms: Long, f: () -> Unit) {
            main.postDelayed({
                try {
                    f()
                } catch (e: Exception) {
                    lastStage = "halaman-error:${e.javaClass.simpleName}"
                    end(Outcome.FAILED)
                }
            }, ms)
        }

        fun begin() {
            val i = pageIntent(target)
            if (i == null) {
                end(Outcome.NOT_FOUND)
                return
            }
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                lastStage = "halaman-tidak-terbuka"
                end(Outcome.FAILED)
                return
            }
            lastStage = "halaman-terbuka"
            later(900) { look() }
        }

        private fun findSwitch(): AccessibilityNodeInfo? {
            val all = ArrayList<AccessibilityNodeInfo>()
            for (r in roots()) {
                if ((r.packageName?.toString() ?: "").contains("systemui")) continue
                collectSwitches(r, 0, all)
            }
            val named = all.firstOrNull { sw ->
                val sub = ArrayList<String>()
                texts(sw, 0, sub)
                sw.parent?.let { texts(it, 2, sub) }
                sub.any { pageLabel.containsMatchIn(norm(it).lowercase()) }
            }
            return named ?: all.firstOrNull()
        }

        private fun look() {
            tries++
            val sw = findSwitch()
            if (sw == null) {
                if (tries > 14) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    lastStage = "halaman-saklar-tidak-ada"
                    end(Outcome.NOT_FOUND)
                } else {
                    later(300) { look() }
                }
                return
            }
            if (transitioning(target) && tries < 12) {
                later(500) { look() } // sedang beralih sendiri (mis. dari ketukan Panel Cepat yang lambat)
                return
            }
            val sys = systemState(target)
            if (sys == on || (sys == null && sw.isChecked == on)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                end(Outcome.ALREADY)
                return
            }
            if (clickNode(sw)) {
                lastStage = "halaman-diketuk"
                later(700) { watch() }
            } else {
                lastStage = "halaman-klik-ditolak"
                escalated = true
                tapNode(sw, 0.5f) { later(700) { watch() } }
            }
        }

        private fun watch() {
            polls++
            val sys = systemState(target)
            if (sys == on) {
                lastStage = "berhasil-lewat-halaman"
                performGlobalAction(GLOBAL_ACTION_BACK)
                end(Outcome.DONE)
                return
            }
            if (polls >= 9) {
                lastStage = "halaman-tanpa-hasil"
                performGlobalAction(GLOBAL_ACTION_BACK)
                end(if (sys == null) Outcome.CLICKED else Outcome.FAILED)
                return
            }
            if (polls == 5 && !escalated && !transitioning(target)) {
                escalated = true
                val sw = findSwitch()
                if (sw != null) {
                    lastStage = "halaman-disentuh"
                    tapNode(sw, 0.5f) { later(600) { watch() } }
                    return
                }
            }
            later(450) { watch() }
        }
    }

    // ------------------------------------------------------------------ YouTube Music

    private fun musicActive(): Boolean = try {
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive == true
    } catch (e: Exception) {
        false
    }

    private fun toast(id: String, en: String) {
        val lang = (application as NineApp).prefs.lang
        Toast.makeText(applicationContext, lang.t(id, en), Toast.LENGTH_LONG).show()
    }

    /**
     * Dipanggil sesaat setelah hasil pencarian YouTube Music dibuka: pilih tab "Lagu", lalu ketuk lagu pertama.
     * Setiap ketukan diperiksa dengan mendengar apakah musik benar-benar berbunyi; bila belum, dicoba cara lain
     * (sentuhan nyata, lagu kedua, lalu perintah putar-dari-pencarian). Bila semuanya gagal, pengguna diberi tahu.
     */
    fun playFirstSong(query: String = "") {
        SongTask(query).begin()
    }

    private inner class SongTask(val query: String) {
        private val startedAt = SystemClock.uptimeMillis()
        private val wasActive = musicActive()
        private var tries = 0
        private var chipTried = false
        private var chipAt = 0L
        private var attempt = 0
        private var lastResort = false
        private var finished = false

        private fun later(ms: Long, f: () -> Unit) {
            main.postDelayed({
                if (finished) return@postDelayed
                try {
                    f()
                } catch (e: Exception) {
                    lastStage = "lagu-error:${e.javaClass.simpleName}"
                    giveUp()
                }
            }, ms)
        }

        /** Berbunyi, dibandingkan dengan keadaan sebelum perintah (bila sebelumnya sudah ada musik, tak bisa dibedakan). */
        private fun playing(): Boolean = !wasActive && musicActive()

        private fun giveUp() {
            if (finished) return
            finished = true
            toast(
                "Nine (beta): lagunya belum bisa saya putar otomatis. Ketuk lagu yang diinginkan di YouTube Music.",
                "Nine (beta): I couldn't start the song automatically. Tap the song you want in YouTube Music."
            )
        }

        fun begin() {
            lastStage = "lagu-mulai"
            later(1300) { step() }
        }

        private fun step() {
            tries++
            if (SystemClock.uptimeMillis() - startedAt > 45_000L) {
                lastStage = "lagu-waktu-habis"
                giveUp()
                return
            }
            if (playing()) {
                lastStage = "lagu-berbunyi"
                finished = true
                return
            }
            val root = rootOf(YTM)
            if (root == null) {
                if (tries < 45) later(350) { step() } else {
                    lastStage = "ytm-tidak-terbuka"
                    giveUp()
                }
                return
            }
            if (lastResort) {
                // Perintah putar-dari-pencarian sudah dikirim; beri waktu lalu putuskan.
                if (SystemClock.uptimeMillis() - chipAt > 6000L) {
                    if (playing()) finished = true else giveUp()
                } else later(500) { step() }
                return
            }
            if (!chipTried) {
                val chip = findChip(root)
                if (chip != null) {
                    chipTried = true
                    lastStage = "lagu-tab"
                    if (!clickNode(chip)) tapNode(chip, 0.5f) {}
                    chipAt = SystemClock.uptimeMillis()
                    later(1500) { step() }
                } else if (tries < 14) {
                    later(350) { step() }
                } else {
                    chipTried = true // tak ada tab "Lagu": langsung ke hasil pertama
                    chipAt = 0L
                    later(100) { step() }
                }
                return
            }
            val rows = candidateRows(root)
            if (rows.isEmpty()) {
                if (tries < 70) later(350) { step() } else tryLastResort()
                return
            }
            if (attempt >= 3) {
                tryLastResort()
                return
            }
            // Percobaan: (0) baris 1 klik biasa, (1) baris 1 sentuhan nyata, (2) baris 2 sentuhan nyata.
            val row = if (attempt == 2 && rows.size > 1) rows[1] else rows[0]
            val a = attempt
            attempt++
            lastStage = "lagu-baris-$a"
            val afterTap: () -> Unit = {
                if (wasActive) {
                    finished = true // tak bisa diverifikasi; anggap berhasil
                } else {
                    later(4000) { step() } // step() memeriksa playing() di awal
                }
            }
            if (a == 0 && clickNode(row)) {
                afterTap()
            } else {
                tapNode(row, 0.3f) { ok ->
                    if (!ok && !clickNode(row)) lastStage = "lagu-baris-gagal"
                    afterTap()
                }
            }
        }

        private fun tryLastResort() {
            if (lastResort) return
            lastResort = true
            lastStage = "lagu-putar-dari-pencarian"
            chipAt = SystemClock.uptimeMillis()
            try {
                if (query.isNotBlank()) {
                    startActivity(
                        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                            .setPackage(YTM)
                            .putExtra(SearchManager.QUERY, query)
                            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: Exception) {
                lastStage = "lagu-putar-dari-pencarian-ditolak"
                giveUp()
                return
            }
            later(500) { step() }
        }
    }

    /** Tab/chip "Lagu"/"Songs" di hasil pencarian (teks atau deskripsi konten), di bagian atas layar. */
    private fun findChip(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val (_, sh) = screenSize()
        val re = Regex("""^(songs|lagu)\b""")
        fun look(needClickable: Boolean): AccessibilityNodeInfo? = find(root) { n ->
            if (!n.isVisibleToUser || (needClickable && !n.isClickable)) return@find false
            val label = norm(n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim().lowercase()
            if (label.isEmpty() || label.length > 24 || !re.containsMatchIn(label)) return@find false
            val b = Rect()
            n.getBoundsInScreen(b)
            b.height() in 1 until (sh * 0.12).toInt() && b.top < sh * 0.45
        }
        return look(true) ?: look(false)
    }

    /**
     * Baris hasil yang bisa diketuk, urut dari atas: lebar (≥ 60% layar), setinggi baris lagu (52–150 dp), berisi teks,
     * dan di bawah kotak pencarian. Tidak bergantung pada kelas daftar tertentu (YouTube Music memakai komponen khusus).
     */
    private fun candidateRows(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val (sw, sh) = screenSize()
        val d = resources.displayMetrics.density
        val minH = (52 * d).toInt()
        val maxH = (150 * d).toInt()
        val top = (sh * 0.17f).toInt()
        val out = ArrayList<Pair<Int, AccessibilityNodeInfo>>()
        fun scan(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || out.size >= 12) return
            val b = Rect()
            n.getBoundsInScreen(b)
            if (n.isVisibleToUser && b.width() >= sw * 0.6 && b.height() in minH..maxH && b.top >= top && b.bottom <= sh) {
                val cls = n.className?.toString().orEmpty()
                if (!cls.contains("EditText")) {
                    val sub = ArrayList<String>()
                    texts(n, 4, sub)
                    if (n.contentDescription?.isNotBlank() == true || sub.isNotEmpty()) {
                        if (n.isClickable || find(n) { it.isClickable } != null) {
                            out.add(Pair(b.top, n))
                            return // satu baris = satu satuan; jangan turun ke isinya
                        }
                    }
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
        return out.sortedBy { it.first }.map { it.second }
    }

    // ------------------------------------------------------------------ tutup semua aplikasi (layar Recents)

    /** Sapuan jari nyata dari (x1, y1) ke (x2, y2). [done] dipanggil di main thread. */
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, done: (Boolean) -> Unit) {
        var called = false
        val cb: (Boolean) -> Unit = { ok ->
            if (!called) {
                called = true
                done(ok)
            }
        }
        val sent = try {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 260L)
            dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        cb(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        cb(false)
                    }
                },
                main
            )
        } catch (e: Exception) {
            false
        }
        if (!sent) cb(false)
    }

    /**
     * Buka layar Recents lalu ketuk "Hapus semua"/"Tutup semua"/"Clear all". Hasil: DONE bila tombolnya diketuk,
     * NOT_FOUND bila tidak ada tombol seperti itu di layar Recents perangkat ini, BUSY/FAILED bila tak bisa dijalankan.
     * Setelah itu layar kembali ke Home. [done] dipanggil di main thread, tepat satu kali.
     */
    fun clearRecents(done: (Outcome) -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (busy && now - busyAt < BUSY_MAX_MS) {
            done(Outcome.BUSY)
            return
        }
        busy = true
        busyAt = now
        lastStage = "recents-mulai"
        var finished = false
        val finish: (Outcome) -> Unit = { o ->
            if (!finished) {
                finished = true
                busy = false
                done(o)
            }
        }
        main.postDelayed({
            if (!finished) {
                lastStage = "recents-waktu-habis"
                finish(Outcome.FAILED)
            }
        }, RECENTS_MAX_MS)
        try {
            val opened = try {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            } catch (e: Exception) {
                false
            }
            if (!opened) {
                lastStage = "recents-tidak-terbuka"
                finish(Outcome.FAILED)
                return
            }
            RecentsTask { o ->
                try {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } catch (e: Exception) {
                    // abaikan
                }
                finish(o)
            }.begin()
        } catch (e: Exception) {
            lastStage = "recents-error:${e.javaClass.simpleName}"
            finish(Outcome.FAILED)
        }
    }

    private inner class RecentsTask(val done: (Outcome) -> Unit) {
        private var tries = 0
        private var swipes = 0
        private var finished = false

        private fun end(o: Outcome) {
            if (finished) return
            finished = true
            done(o)
        }

        private fun later(ms: Long, f: () -> Unit) {
            main.postDelayed({
                try {
                    if (!finished) f()
                } catch (e: Exception) {
                    lastStage = "recents-error:${e.javaClass.simpleName}"
                    end(Outcome.FAILED)
                }
            }, ms)
        }

        fun begin() {
            lastStage = "recents-terbuka"
            later(900) { seek() }
        }

        private fun button(): AccessibilityNodeInfo? {
            for (r in roots()) {
                val hit = find(r) { n ->
                    val t = norm(n.text?.toString().orEmpty()).trim()
                    val d = norm(n.contentDescription?.toString().orEmpty()).trim()
                    (t.isNotEmpty() && CLEAR_ALL_RE.matches(t)) || (d.isNotEmpty() && CLEAR_ALL_RE.matches(d))
                }
                if (hit != null) return hit
            }
            return null
        }

        private fun seek() {
            tries++
            val b = button()
            if (b != null) {
                lastStage = "recents-tombol-ditemukan"
                if (clickNode(b)) {
                    lastStage = "recents-diketuk"
                    later(500) { end(Outcome.DONE) }
                } else {
                    tapNode(b, 0.5f) { ok ->
                        lastStage = if (ok) "recents-diketuk-gestur" else "recents-gagal-ketuk"
                        later(500) { end(if (ok) Outcome.DONE else Outcome.FAILED) }
                    }
                }
                return
            }
            if (tries >= RECENTS_TRIES) {
                lastStage = "recents-tombol-tidak-ada"
                end(Outcome.NOT_FOUND)
                return
            }
            // Pada sebagian peluncur (mis. Pixel) tombol baru muncul setelah deretan kartu digeser ke ujungnya.
            if (tries >= 3 && swipes < 3) {
                swipes++
                val (w, h) = screenSize()
                swipe(w * 0.15f, h * 0.45f, w * 0.9f, h * 0.45f) { later(550) { seek() } }
            } else {
                later(450) { seek() }
            }
        }
    }

    companion object {
        /** True di edisi Full (Nine-Full.apk): layanan aksesibilitas tersedia. */
        const val AVAILABLE = true

        private const val MAX_FIND = 26
        private const val MAX_VERIFY = 14
        private const val BUSY_MAX_MS = 45_000L
        private const val RECENTS_MAX_MS = 14_000L
        private const val RECENTS_TRIES = 10
        private val CLEAR_ALL_RE = Regex("""(?i)^(clear all|close all|dismiss all|hapus semua|tutup semua|bersihkan semua|singkirkan semua|tutup semua aplikasi|clear all apps)$""")
        private const val YTM = "com.google.android.apps.youtube.music"

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
    }
}
