package com.example.llama

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Process
import android.os.Build
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import java.util.UUID
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** IzzAI: entirely on-device GGUF inference, using the official llama.android engine. */
class MainActivity : AppCompatActivity() {
    companion object {
        // Re-created only if Android starts a new Linux app process.
        private val processSessionId: String = UUID.randomUUID().toString()
    }
    private val bg = Color.rgb(11, 15, 23)
    private val panel = Color.rgb(25, 31, 44)
    private val accent = Color.rgb(93, 194, 169)
    private val foreground = Color.rgb(239, 243, 249)
    private val muted = Color.rgb(159, 173, 191)

    private val systemInstruction = "You are IzzAI, an offline assistant. Reply in natural Bahasa Melayu Malaysia. Answer the latest question directly using your general knowledge for general-knowledge questions. If the user asks which train, service, person, city, food, place, or other named thing, state the specific name in the first sentence when you know it, then explain briefly. For transport questions, distinguish a known named route from an unknown origin; if location is essential and missing, ask for the departure city, but provide a likely relevant known option with a clear condition. Do not respond with generic advice about checking ticket websites instead of naming a known service. Use previous conversation only for follow-up questions and user-provided facts. Conversation history is not a factual reference source; prior assistant responses can be incorrect. Never claim that general knowledge is unavailable merely because it is absent from conversation history. If the question is ambiguous, ask a concise clarifying question. Avoid inventing facts, food names, places, or prices. Do not guess a named answer when uncertain; say what is uncertain. Do not claim live schedules or ticket availability while offline. For ages without a birthday, give the possible birth-year range. Express uncertainty when necessary. Do not reveal internal reasoning."
    private lateinit var engine: InferenceEngine
    private var initialized = false
    private var modelReady = false
    private var loading = false
    private var generation: Job? = null
    private lateinit var status: TextView
    private lateinit var lifecycleDiagnostic: TextView
    private lateinit var conversation: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var importButton: Button
    private lateinit var clearButton: Button
    private lateinit var composeBar: LinearLayout
    private var activeReplyView: TextView? = null
    private val items = mutableListOf<Pair<Boolean, String>>()
    private val store by lazy { File(filesDir, "conversation.json") }
    private val models by lazy { File(filesDir, "models").apply { mkdirs() } }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
        val diagnosticPrefs = getSharedPreferences("izzai_process_diagnostics", MODE_PRIVATE)
        val lastProcessId = diagnosticPrefs.getString("process_session_id", null)
        val newProcess = lastProcessId != processSessionId
        if (newProcess) {
            diagnosticPrefs.edit().putString("process_session_id", processSessionId).apply()
        }
        val previousPid = diagnosticPrefs.getInt("last_pid", -1)
        val exitReport = if (newProcess && previousPid > 0 && Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                // Query prior process exit diagnostics maintained by Android.
                val exits = manager.getHistoricalProcessExitReasons(packageName, previousPid, 1)
                exits.firstOrNull()?.let { exit ->
                    val reason = when (exit.reason) {
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                        ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                        ApplicationExitInfo.REASON_ANR -> "ANR"
                        ApplicationExitInfo.REASON_SIGNALED -> "SIGNAL"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "RESOURCE_LIMIT"
                        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                        ApplicationExitInfo.REASON_OTHER -> "OTHER"
                        else -> "UNKNOWN(${exit.reason})"
                    }
                    " • Exit: $reason • RSS ${exit.rss / 1024} MB"
                } ?: " • Exit: TIADA REKOD"
            }.getOrElse { " • Exit: TIDAK DAPAT DIBACA" }
        } else ""
        diagnosticPrefs.edit().putInt("last_pid", Process.myPid()).apply()
        val diagnosticMessage = if (newProcess) {
            "Diagnostik: PROSES BAHARU • PID ${Process.myPid()}$exitReport"
        } else {
            "Diagnostik: ACTIVITY DICIPTA SEMULA • PID ${Process.myPid()}"
        }
        lifecycleDiagnostic.text = diagnosticMessage
        Log.i("IzzAI-Lifecycle", diagnosticMessage)
        loadHistory()
        renderHistory()
        setStatus("Memulakan enjin AI lokal…")
        lifecycleScope.launch {
            try {
                engine = withContext(Dispatchers.Default) { AiChat.getInferenceEngine(applicationContext) }
                initialized = true
                val previous = getPreferences(MODE_PRIVATE).getString("model_path", null)
                if (previous != null && File(previous).isFile) {
                    val modelFile = File(previous)
                    // The engine is a process-wide singleton. Reuse it if the
                    // Activity was recreated while the Android process survived.
                    val initialState = engine.state.value
                    Log.i("IzzAI-Lifecycle", "onCreate engine state: ${initialState.javaClass.simpleName}")
                    val state = when (initialState) {
                        is InferenceEngine.State.Uninitialized,
                        is InferenceEngine.State.Initializing,
                        is InferenceEngine.State.LoadingModel,
                        is InferenceEngine.State.ProcessingSystemPrompt,
                        is InferenceEngine.State.Generating,
                        is InferenceEngine.State.ProcessingUserPrompt -> {
                            withTimeoutOrNull(120_000L) {
                                engine.state.first {
                                    it is InferenceEngine.State.ModelReady ||
                                        it is InferenceEngine.State.Initialized ||
                                        it is InferenceEngine.State.Error
                                }
                            }
                        }
                        else -> initialState
                    }
                    when (state) {
                        is InferenceEngine.State.ModelReady -> {
                            modelReady = true
                            loading = false
                            setStatus("● Model kekal dalam RAM • ${modelFile.name}")
                            Log.i("IzzAI-Lifecycle", "Reused loaded GGUF without reload")
                            updateButtons()
                        }
                        is InferenceEngine.State.Initialized -> loadModel(modelFile)
                        is InferenceEngine.State.Error -> {
                            setStatus("Memulihkan enjin model…")
                            withContext(Dispatchers.Default) { engine.cleanUp() }
                            loadModel(modelFile)
                        }
                        else -> {
                            setStatus("Enjin belum sedia; buka semula jika loading tersekat")
                            updateButtons()
                        }
                    }
                } else {
                    setStatus("Tiada model • Pilih fail GGUF")
                    updateButtons()
                }
            } catch (e: Exception) {
                setStatus("Enjin gagal bermula: ${e.message}")
                updateButtons()
            }
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        setContentView(root)
        val heading = TextView(this).apply {
            text = "IzzAI"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(this@MainActivity.foreground)
        }
        root.addView(heading)
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(accent)
            setPadding(0, dp(3), 0, dp(14))
        }
        root.addView(status)
        lifecycleDiagnostic = TextView(this).apply {
            textSize = 10f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(lifecycleDiagnostic)
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        importButton = makeButton("Import GGUF", accent).apply { setOnClickListener { if (!loading) pickModel.launch(arrayOf("*/*")) } }
        clearButton = makeButton("Chat baharu", muted).apply {
            setOnClickListener {
                if (generation?.isActive == true) return@setOnClickListener
                if (!modelReady || loading) return@setOnClickListener
                val path = getPreferences(MODE_PRIVATE).getString("model_path", null) ?: return@setOnClickListener
                loading = true
                updateButtons()
                setStatus("Memulakan sesi baharu…")
                lifecycleScope.launch {
                    try {
                        // Reset the native KV cache and chat history without reloading the 3B model.
                        withContext(Dispatchers.Default) {
                            engine.setSystemPrompt(systemInstruction)
                        }
                        items.clear()
                        saveHistory()
                        renderHistory()
                        modelReady = true
                        setStatus("● Sesi baharu • ${File(path).name}")
                    } catch (e: Exception) {
                        modelReady = false
                        setStatus("Reset gagal: ${e.message}")
                    } finally {
                        loading = false
                        updateButtons()
                    }
                }
            }
        }
        tools.addView(importButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        tools.addView(clearButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        root.addView(tools)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        scroll.addView(conversation)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        composeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(bg)
        }
        input = EditText(this).apply {
            hint = "Tanya IzzAI…"
            setHintTextColor(muted)
            setTextColor(this@MainActivity.foreground)
            textSize = 14f
            minLines = 1
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(12), dp(12), dp(12))
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        composeBar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        send = makeButton("Hantar", accent).apply { setOnClickListener { submit() } }
        composeBar.addView(send, LinearLayout.LayoutParams(dp(92), dp(48)).apply { leftMargin = dp(8) })
        root.addView(composeBar)
        applyInsets(root)
        updateButtons()
    }

    private fun makeButton(label: String, color: Int): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.BLACK)
        backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun importModel(uri: Uri) {
        if (!initialized || loading || generation?.isActive == true) return
        loading = true
        updateButtons()
        setStatus("Menyalin model ke storan aplikasi…")
        lifecycleScope.launch {
            try {
                val originalName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "imported-model.gguf"
                val safeName = originalName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    .replace(Regex("(?i)\\.txt$"), ".gguf")
                    .let { if (it.endsWith(".gguf", ignoreCase = true)) it else "${it}.gguf" }
                val target = File(models, safeName)
                if (modelReady) {
                    withContext(Dispatchers.Default) { engine.cleanUp() }
                    modelReady = false
                }
                withContext(Dispatchers.IO) {
                    val temp = File(models, "izzai-model.tmp")
                    try {
                        contentResolver.openInputStream(uri)?.use { src ->
                            FileOutputStream(temp).use { dst -> src.copyTo(dst) }
                        } ?: error("Fail GGUF tidak dapat dibaca")
                        require(temp.length() > 1024L * 1024L) { "Fail terlalu kecil untuk model GGUF" }
                        if (target.exists() && !target.delete()) error("Tidak dapat menggantikan model")
                        require(temp.renameTo(target)) { "Tidak dapat menyimpan model" }
                    } finally {
                        temp.delete()
                    }
                }
                loadModel(target)
            } catch (e: Exception) {
                setStatus("Import gagal: ${e.message}")
            } finally {
                loading = false
                updateButtons()
            }
        }
    }

    private suspend fun loadModel(file: File) {
        loading = true
        modelReady = false
        updateButtons()
        setStatus("Memuatkan ${file.name}…")
        try {
            withContext(Dispatchers.Default) {
                engine.loadModel(file.absolutePath)
                engine.setSystemPrompt(systemInstruction)
            }
            getPreferences(MODE_PRIVATE).edit().putString("model_path", file.absolutePath).apply()
            modelReady = true
            setStatus("● Model lokal • ${file.name}")
        } catch (e: Exception) {
            setStatus("Model gagal dimuatkan: ${e.message}")
        } finally {
            loading = false
            updateButtons()
        }
    }

    private fun submit() {
        if (!modelReady || loading || generation?.isActive == true) return
        val message = input.text.toString().trim()
        if (message.isEmpty()) return
        input.setText("")
        // Rebuild the native context from the UI conversation each turn.
        // The llama.cpp sample maintains a separate chat/KV history that can
        // diverge from restored Android messages. Keep one bounded source.
        val previousTurns = items.takeLast(8).filter { (user, body) ->
            user || (body.isNotBlank() && !body.startsWith("Ralat inferens") &&
                body != "Sedang menjawab…" && !body.startsWith("Model tidak menghasilkan"))
        }.map { (user, body) ->
            (if (user) "Pengguna: " else "IzzAI: ") + body.take(300)
        }.joinToString("\n").takeLast(1600)
        val sessionInstruction = if (previousTurns.isBlank()) systemInstruction else
            systemInstruction +
            "\n\nBerikut ialah sejarah sebenar sesi ini. Gunakan fakta yang diberikan pengguna " +
            "untuk menjawab soalan susulan tentang chat sahaja. Untuk pengetahuan am, " +
            "gunakan pengetahuan umum model. Jawapan AI terdahulu mungkin salah." +
            "\nSEJARAH:\n" + previousTurns + "\nTAMAT SEJARAH."
        val evidence = OfflineKnowledge.retrieve(applicationContext, message)
        items += true to message
        items += false to "Sedang menjawab…"
        renderHistory()
        saveHistory()
        val activeModel = getPreferences(MODE_PRIVATE).getString("model_path", null)?.let { File(it).name } ?: "model tidak diketahui"
        setStatus("Menjana jawapan • $activeModel")
        generation = lifecycleScope.launch {
            val raw = StringBuilder()
            try {
                withContext(Dispatchers.Default) {
                    var lastUiRefresh = 0L
                    // setSystemPrompt clears the native KV cache/chat state, ensuring
                    // old or duplicated native turns do not influence this reply.
                    val groundedInstruction = sessionInstruction +
                        (evidence?.let { OfflineKnowledge.instruction(it) } ?: "")
                    engine.setSystemPrompt(groundedInstruction)
                    engine.sendUserPrompt(message, 256).collect { token ->
                        raw.append(token)
                        val now = SystemClock.uptimeMillis()
                        if (now - lastUiRefresh >= 250L) {
                            lastUiRefresh = now
                            val currentText = cleanThinking(raw.toString())
                                .ifBlank { "Sedang menjawab… (${raw.length} aksara)" }
                            withContext(Dispatchers.Main) {
                                items[items.lastIndex] = false to currentText
                                activeReplyView?.text = currentText
                            }
                        }
                    }
                }
                val filtered = cleanThinking(raw.toString())
                items[items.lastIndex] = false to when {
                    filtered.isNotBlank() -> filtered
                    raw.isNotBlank() -> "Model menjana teks tetapi tiada jawapan akhir. Output diagnostik:\n${raw.toString().take(2500)}"
                    else -> "Model tidak menghasilkan token. Sila laporkan mesej ini untuk diagnosis enjin."
                }
                setStatus("● ${activeModel} • ${raw.length} aksara" +
                    (if (evidence != null) " • Rujukan offline" else ""))
            } catch (e: Exception) {
                items[items.lastIndex] = false to "Ralat inferens (${e.javaClass.simpleName}): ${e.message}"
                setStatus("Inferens gagal: ${e.message}")
            } finally {
                renderHistory()
                saveHistory()
                generation = null
                updateButtons()
            }
        }
    }

    private fun cleanThinking(text: String): String {
        // Hide both Qwen-style internal reasoning and older llama.cpp thinking markers.
        var result = text.replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(Regex("(?s)\\[Start thinking].*?\\[End thinking]"), "")
        val start = listOf(result.indexOf("<think>"), result.indexOf("[Start thinking]"))
            .filter { it >= 0 }.minOrNull()
        if (start != null) result = result.substring(0, start)
        return result.trim()
    }

    private fun renderHistory() {
        activeReplyView = null
        conversation.removeAllViews()
        if (items.isEmpty()) {
            conversation.addView(TextView(this).apply {
                text = "IzzAI berjalan sepenuhnya dalam telefon. Import model GGUF untuk mula berbual."
                textSize = 14f
                setTextColor(muted)
                setPadding(dp(8), dp(28), dp(8), dp(12))
            })
        }
        items.forEachIndexed { index, (user, body) ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (user) Gravity.END else Gravity.START
            }
            val bubble = TextView(this).apply {
                text = body
                textSize = 14f
                setTextColor(this@MainActivity.foreground)
                setBackgroundColor(if (user) Color.rgb(35, 70, 68) else panel)
                setPadding(dp(12), dp(11), dp(12), dp(11))
            }
            if (!user && index == items.lastIndex) activeReplyView = bubble
            line.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            conversation.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(11) })
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun loadHistory() {
        runCatching {
            if (store.isFile) {
                val array = JSONArray(store.readText())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    items += obj.getBoolean("user") to obj.getString("text")
                }
            }
        }
    }

    private fun saveHistory() {
        runCatching {
            val data = JSONArray()
            items.takeLast(100).forEach { (user, text) ->
                data.put(JSONObject().put("user", user).put("text", text))
            }
            store.writeText(data.toString())
        }
    }

    private fun setStatus(value: String) { runOnUiThread { status.text = value } }
    private fun updateButtons() {
        if (!::send.isInitialized) return
        runOnUiThread {
            val busy = loading || generation?.isActive == true
            // Block text editing / long-press Paste while the GGUF model is loading.
            // Leave typing available during generation; only sending is blocked.
            input.isEnabled = modelReady && !loading
            input.hint = when {
                loading -> "Memuatkan model GGUF…"
                !modelReady -> "Import GGUF untuk mula berbual"
                else -> "Tanya IzzAI…"
            }
            send.isEnabled = modelReady && !busy
            importButton.isEnabled = initialized && !busy
            clearButton.isEnabled = !busy
        }
    }

    private fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(bars.bottom, ime.bottom)

            root.updatePadding(
                left = dp(16),
                top = bars.top + dp(14),
                right = dp(16),
                bottom = dp(12)
            )

            composeBar.updatePadding(bottom = bottomInset + dp(8))
            scroll.updatePadding(bottom = dp(12))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        generation?.cancel()
        // Never destroy the process-wide AiChat singleton from an Activity.
        // Android may recreate the Activity while the same process is alive.
        // Destroying it here invalidates the shared native inference engine.
        Log.i("IzzAI-Lifecycle", "Activity destroyed; native engine retained in process")
        super.onDestroy()
    }
}
