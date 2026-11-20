package id.nine.assistant

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import id.nine.assistant.core.AppLang
import id.nine.assistant.core.AssistantController
import id.nine.assistant.core.ConversationLog
import id.nine.assistant.core.Prefs
import id.nine.assistant.databinding.ActivityMainBinding
import id.nine.assistant.llm.LlmBrain
import id.nine.assistant.llm.ModelManager
import id.nine.assistant.ui.MessageAdapter
import id.nine.assistant.ui.NineOrbView
import id.nine.assistant.wake.WakeWordService
import id.nine.assistant.widget.WidgetUpdater

class MainActivity : AppCompatActivity(), AssistantController.Ui {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: AssistantController
    private lateinit var nine: NineApp
    private lateinit var adapter: MessageAdapter
    private var modelCardDismissed = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            controller.onPermissionsResult(result)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) nine.models.importFrom(uri)
        }

    private val logListener = object : ConversationLog.Listener {
        override fun onAdded(index: Int) {
            adapter.notifyItemInserted(index)
            binding.list.scrollToPosition(index)
        }

        override fun onChanged(index: Int) {
            adapter.notifyItemChanged(index)
            binding.list.scrollToPosition(index)
        }

        override fun onCleared() {
            @Suppress("NotifyDataSetChanged")
            adapter.notifyDataSetChanged()
        }
    }

    private val modelListener = ModelManager.Listener { renderModel(it) }

    private var lastBrainState = LlmBrain.State.NO_MODEL
    private var lastBrainDetail = ""

    private fun renderBrain() {
        val state = lastBrainState
        val detail = lastBrainDetail
        if (nine.gemini.isUsable()) {
            binding.txtBrain.text = getString(R.string.brain_online)
            return
        }
        binding.txtBrain.text = when (state) {
            LlmBrain.State.NO_MODEL -> getString(R.string.brain_none)
            LlmBrain.State.IDLE -> getString(R.string.brain_idle)
            LlmBrain.State.LOADING -> getString(R.string.brain_loading)
            LlmBrain.State.READY, LlmBrain.State.BUSY -> getString(R.string.brain_ready, detail.uppercase())
            LlmBrain.State.ERROR -> getString(R.string.brain_error)
        }
    }

    private val brainListener = object : LlmBrain.StateListener {
        override fun onBrainState(state: LlmBrain.State, detail: String) {
            lastBrainState = state
            if (state != LlmBrain.State.ERROR) lastBrainDetail = detail
            renderBrain()
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nine = application as NineApp
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupInsets()

        adapter = MessageAdapter(nine.log)
        binding.list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.list.adapter = adapter
        binding.list.itemAnimator = null

        controller = AssistantController(this, this)
        onLanguageChanged(controller.lang)
        onState(AssistantController.State.IDLE)

        if (nine.log.entries.isEmpty()) nine.log.add(false, welcomeText())

        binding.orb.setOnClickListener {
            when (controller.state) {
                AssistantController.State.LISTENING -> controller.stopListening()
                AssistantController.State.SPEAKING, AssistantController.State.THINKING -> controller.cancel()
                AssistantController.State.IDLE -> {
                    hideKeyboard()
                    controller.startListening()
                }
            }
        }
        binding.orb.setOnLongClickListener {
            toggleKeyboard()
            true
        }
        binding.btnKeyboard.setOnClickListener { toggleKeyboard() }
        binding.btnTodo.setOnClickListener { startActivity(Intent(this, TodoActivity::class.java)) }
        binding.btnSend.setOnClickListener { sendTyped() }
        binding.inputText.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN
            if (enter || actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                sendTyped()
                true
            } else false
        }
        binding.btnLang.setOnClickListener {
            controller.lang = if (controller.lang == AppLang.ID) AppLang.EN else AppLang.ID
        }
        binding.btnTheme.setOnClickListener { toggleTheme() }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        binding.btnModelPrimary.setOnClickListener { onModelPrimary() }
        binding.btnModelSecondary.setOnClickListener { onModelSecondary() }

        nine.log.addListener(logListener)
        nine.models.addListener(modelListener)
        nine.brain.addListener(brainListener)
        nine.brain.preload()
        WidgetUpdater.refresh(this)

        handleLaunchIntent(intent)
    }

    private fun welcomeText(): String {
        val p = nine.prefs.persona
        return if (controller.lang == AppLang.ID) {
            "Selamat datang${p.sfx(AppLang.ID)}. Saya Nine, asisten pribadi Anda yang bekerja sepenuhnya di perangkat ini. " +
                "Ketuk orb dan ucapkan perintah, misalnya \"pasang alarm jam 6 pagi\", atau ajak saya mengobrol."
        } else {
            "Welcome${p.sfx(AppLang.EN)}. I'm Nine, your personal assistant, running entirely on this device. " +
                "Tap the orb and say something like \"set an alarm for 6 AM\", or simply chat with me."
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /** Dibuka lewat tombol asisten (ACTION_ASSIST / sesi asisten): langsung mendengarkan. */
    private fun handleLaunchIntent(i: Intent?) {
        if (i == null) return
        val auto = i.getBooleanExtra(EXTRA_AUTO_LISTEN, false) || i.action == Intent.ACTION_ASSIST
        if (auto) {
            // Dipanggil dari tombol samping / wake word: boleh tampil di atas layar kunci.
            if (Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            i.removeExtra(EXTRA_AUTO_LISTEN)
            binding.root.post { controller.startListening() }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        onLanguageChanged(controller.lang)
        renderBrain()
        // Layar Nine terbuka: hapus notifikasi "ketuk untuk bicara" dan lepaskan mikrofon dari wake word.
        NotificationManagerCompat.from(this).cancel(WakeWordService.ALERT_ID)
        if (nine.prefs.wakeEnabled) WakeWordService.pause(this)
    }

    override fun onStop() {
        super.onStop()
        visible = false
        controller.onBackground()
        if (nine.prefs.wakeEnabled) WakeWordService.resume(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        nine.log.removeListener(logListener)
        nine.models.removeListener(modelListener)
        nine.brain.removeListener(brainListener)
        controller.destroy()
    }

    // ------------------------------------------------------------ tampilan

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun toggleTheme() {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val next = if (dark) Prefs.Theme.LIGHT else Prefs.Theme.DARK
        nine.prefs.theme = next
        AppCompatDelegate.setDefaultNightMode(next.nightMode)
    }

    private fun toggleKeyboard() {
        val show = binding.inputRow.visibility != View.VISIBLE
        binding.inputRow.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.inputText.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.inputText, InputMethodManager.SHOW_IMPLICIT)
        } else {
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.inputText.windowToken, 0)
    }

    private fun sendTyped() {
        val t = binding.inputText.text?.toString().orEmpty().trim()
        if (t.isEmpty()) return
        binding.inputText.setText("")
        // Setelah Enter/kirim: tutup keyboard agar jawaban Nine terlihat.
        hideKeyboard()
        binding.inputText.clearFocus()
        controller.submitText(t)
    }

    // ------------------------------------------------------------ kartu model

    private fun size(b: Long): String = Formatter.formatShortFileSize(this, b)

    private fun renderModel(s: ModelManager.Status) {
        val card = binding.modelCard
        val progress = binding.modelProgress
        binding.btnModelSecondary.visibility = View.VISIBLE
        when (s) {
            ModelManager.Status.Ready -> {
                card.visibility = View.GONE
                return
            }
            ModelManager.Status.Missing -> {
                binding.modelTitle.setText(R.string.model_card_title)
                binding.modelText.setText(R.string.model_card_text)
                progress.visibility = View.GONE
                binding.btnModelPrimary.setText(R.string.model_download)
                binding.btnModelSecondary.setText(R.string.model_later)
            }
            is ModelManager.Status.Downloading -> {
                binding.modelTitle.setText(R.string.model_card_title)
                binding.modelText.text = getString(R.string.model_downloading, size(s.downloaded), size(s.total))
                progress.visibility = View.VISIBLE
                progress.max = 1000
                if (s.total > 0) progress.setProgressCompat((s.downloaded * 1000 / s.total).toInt(), true)
                binding.btnModelPrimary.setText(R.string.model_cancel)
                binding.btnModelSecondary.setText(R.string.model_later)
            }
            is ModelManager.Status.Importing -> {
                binding.modelText.text = getString(R.string.model_importing, size(s.copied), size(s.total))
                progress.visibility = View.VISIBLE
                progress.max = 1000
                if (s.total > 0) progress.setProgressCompat((s.copied * 1000 / s.total).toInt(), true)
                binding.btnModelPrimary.setText(R.string.model_later)
                binding.btnModelSecondary.visibility = View.GONE
            }
            is ModelManager.Status.Failed -> {
                progress.visibility = View.GONE
                binding.modelText.text = failureText(s)
                binding.btnModelPrimary.setText(R.string.model_retry)
                binding.btnModelSecondary.setText(R.string.model_import)
                modelCardDismissed = false
            }
        }
        card.visibility = if (modelCardDismissed) View.GONE else View.VISIBLE
    }

    private fun failureText(s: ModelManager.Status.Failed): String = when (s.reason) {
        ModelManager.Reason.NEED_TOKEN -> getString(R.string.model_fail_token)
        ModelManager.Reason.STORAGE -> getString(R.string.model_fail_storage)
        ModelManager.Reason.CORRUPT -> getString(R.string.model_fail_corrupt)
        ModelManager.Reason.IMPORT -> getString(R.string.model_fail_import, s.detail)
        else -> getString(R.string.model_fail_network, s.detail)
    }

    private fun onModelPrimary() {
        when (val s = nine.models.status) {
            ModelManager.Status.Missing -> nine.models.startDownload()
            is ModelManager.Status.Failed ->
                if (s.reason == ModelManager.Reason.NEED_TOKEN) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else {
                    nine.models.startDownload()
                }
            is ModelManager.Status.Downloading -> nine.models.cancelDownload()
            else -> dismissModelCard()
        }
    }

    private fun onModelSecondary() {
        when (nine.models.status) {
            is ModelManager.Status.Failed -> importLauncher.launch(arrayOf("*/*"))
            else -> dismissModelCard()
        }
    }

    private fun dismissModelCard() {
        modelCardDismissed = true
        binding.modelCard.visibility = View.GONE
    }

    // ------------------------------------------------------------ AssistantController.Ui

    override fun onState(state: AssistantController.State) {
        val (label, mode) = when (state) {
            AssistantController.State.IDLE -> Pair(R.string.status_idle, NineOrbView.Mode.IDLE)
            AssistantController.State.LISTENING -> Pair(R.string.status_listening, NineOrbView.Mode.LISTENING)
            AssistantController.State.THINKING -> Pair(R.string.status_thinking, NineOrbView.Mode.THINKING)
            AssistantController.State.SPEAKING -> Pair(R.string.status_speaking, NineOrbView.Mode.SPEAKING)
        }
        binding.txtStatus.setText(label)
        binding.orb.mode = mode
        when (state) {
            AssistantController.State.IDLE -> binding.edgeGlow.setIntensity(0f, 40f)
            AssistantController.State.LISTENING -> binding.edgeGlow.setIntensity(1f, 60f)
            AssistantController.State.THINKING -> binding.edgeGlow.setIntensity(0.7f, 220f)
            AssistantController.State.SPEAKING -> binding.edgeGlow.setIntensity(0.45f, 50f)
        }
        if (state == AssistantController.State.IDLE) {
            binding.orb.setLevel(0f)
            binding.edgeGlow.setLevel(0f)
        }
    }

    override fun onPartial(text: String) {
        binding.txtPartial.text = text
    }

    override fun onLevel(level: Float) {
        binding.orb.setLevel(level)
        binding.edgeGlow.setLevel(level)
    }

    override fun onRequestPermissions(permissions: Array<String>) {
        permissionLauncher.launch(permissions)
    }

    override fun onLaunch(intent: Intent, fallback: Intent?): Boolean {
        for (i in listOfNotNull(intent, fallback)) {
            try {
                startActivity(i)
                return true
            } catch (e: ActivityNotFoundException) {
                // coba berikutnya
            } catch (e: SecurityException) {
                // coba berikutnya
            }
        }
        return false
    }

    override fun onLanguageChanged(lang: AppLang) {
        binding.btnLang.text = if (lang == AppLang.ID) "ID" else "EN"
    }

    override fun onNotice(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_AUTO_LISTEN = "id.nine.assistant.AUTO_LISTEN"
        const val EXTRA_FROM_WAKE = "id.nine.assistant.FROM_WAKE"

        /** True saat layar utama Nine sedang tampil (dipakai layanan wake word). */
        @Volatile
        var visible = false
    }
}
