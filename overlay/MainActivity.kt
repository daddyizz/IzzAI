package com.example.llama

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
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
    private val bg = Color.rgb(11, 15, 23)
    private val panel = Color.rgb(25, 31, 44)
    private val accent = Color.rgb(93, 194, 169)
    private val foreground = Color.rgb(239, 243, 249)
    private val muted = Color.rgb(159, 173, 191)

    private lateinit var engine: InferenceEngine
    private var initialized = false
    private var modelReady = false
    private var loading = false
    private var generation: Job? = null
    private lateinit var status: TextView
    private lateinit var conversation: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var importButton: Button
    private lateinit var clearButton: Button
    private lateinit var composeBar: LinearLayout
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
        loadHistory()
        renderHistory()
        setStatus("Memulakan enjin AI lokal…")
        lifecycleScope.launch {
            try {
                engine = withContext(Dispatchers.Default) { AiChat.getInferenceEngine(applicationContext) }
                initialized = true
                val previous = getPreferences(MODE_PRIVATE).getString("model_path", null)
                if (previous != null && File(previous).isFile) {
                    loadModel(File(previous))
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
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        importButton = makeButton("Import GGUF", accent).apply { setOnClickListener { if (!loading) pickModel.launch(arrayOf("*/*")) } }
        clearButton = makeButton("Chat baharu", muted).apply {
            setOnClickListener {
                if (generation?.isActive == true) return@setOnClickListener
                items.clear()
                saveHistory()
                renderHistory()
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
                val target = File(models, "izzai-model.gguf")
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
            withContext(Dispatchers.Default) { engine.loadModel(file.absolutePath) }
            getPreferences(MODE_PRIVATE).edit().putString("model_path", file.absolutePath).apply()
            modelReady = true
            setStatus("● Offline • ${file.name}")
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
        items += true to message
        items += false to "Sedang menjawab…"
        renderHistory()
        saveHistory()
        updateButtons()
        generation = lifecycleScope.launch {
            val raw = StringBuilder()
            try {
                withContext(Dispatchers.Default) {
                    engine.sendUserPrompt(message).collect { token ->
                        raw.append(token)
                        val clean = cleanThinking(raw.toString())
                        withContext(Dispatchers.Main) {
                            items[items.lastIndex] = false to (clean.ifBlank { "Sedang menjawab…" })
                            renderHistory()
                        }
                    }
                }
                items[items.lastIndex] = false to cleanThinking(raw.toString()).ifBlank { "(Tiada respons)" }
            } catch (e: Exception) {
                items[items.lastIndex] = false to "Ralat inferens: ${e.message}"
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
        conversation.removeAllViews()
        if (items.isEmpty()) {
            conversation.addView(TextView(this).apply {
                text = "IzzAI berjalan sepenuhnya dalam telefon. Import model GGUF untuk mula berbual."
                textSize = 14f
                setTextColor(muted)
                setPadding(dp(8), dp(28), dp(8), dp(12))
            })
        }
        items.forEach { (user, body) ->
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
        if (initialized) lifecycleScope.launch(Dispatchers.Default) { runCatching { engine.destroy() } }
        super.onDestroy()
    }
}
