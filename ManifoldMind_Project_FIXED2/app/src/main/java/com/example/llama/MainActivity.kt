package com.example.llama

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadataReader
import com.manifold.app.ManifoldLLM
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var ggufInfoTv: TextView
    private lateinit var chatRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var sendBtn: ImageButton
    private lateinit var modelFab: FloatingActionButton

    private val adapter = MessageAdapter()

    private val engine: InferenceEngine by lazy { AiChat.getInferenceEngine(this) }
    private val manifold: ManifoldLLM by lazy { ManifoldLLM(this) }

    private var loadedModelFile: File? = null
    private var currentAssistantId: String? = null
    private val currentAssistantText = StringBuilder()

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch { onModelSelected(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ggufInfoTv = findViewById(R.id.gguf_info)
        chatRv = findViewById(R.id.chat_rv)
        userInputEt = findViewById(R.id.user_input)
        sendBtn = findViewById(R.id.send_btn)
        modelFab = findViewById(R.id.fab_model)

        chatRv.layoutManager = LinearLayoutManager(this)
        chatRv.adapter = adapter

        modelFab.setOnClickListener {
            pickModel.launch(arrayOf("*/*"))
        }

        sendBtn.setOnClickListener { onSendClicked() }

        // Observe token stream
        lifecycleScope.launch {
            engine.tokenFlow.collectLatest { chunk ->
                currentAssistantText.append(chunk)
                adapter.updateLastAssistant(currentAssistantText.toString())
                chatRv.scrollToPosition(maxOf(0, adapter.itemCount - 1))
            }
        }

        // Observe engine state (optional UI feedback)
        lifecycleScope.launch {
            engine.state.collectLatest { st ->
                when (st) {
                    is InferenceEngine.State.Error -> toast("Engine error: ${'$'}{st.message}")
                    InferenceEngine.State.LoadingModel -> ggufInfoTv.text = "Loading model..."
                    InferenceEngine.State.Generating -> { /* no-op */ }
                    InferenceEngine.State.ModelReady -> { /* no-op */ }
                    InferenceEngine.State.ModelNotLoaded -> { /* no-op */ }
                    else -> { /* no-op */ }
                }
            }
        }
    }

    private fun onSendClicked() {
        val msg = userInputEt.text.toString().trim()
        if (msg.isEmpty()) return
        if (loadedModelFile == null) {
            toast("Select a GGUF model first.")
            return
        }

        userInputEt.setText("")
        adapter.submit(Message(UUID.randomUUID().toString(), msg, isUser = true))
        chatRv.scrollToPosition(maxOf(0, adapter.itemCount - 1))

        lifecycleScope.launch {
            val gate = manifold.evaluate(msg)

            // Always show gate metrics in logs; keep UI clean
            Log.i("ManifoldGate", "res=${'$'}{gate.resonance} coh=${'$'}{gate.coherence} dI=${'$'}{gate.deltaI}")

            if (gate.shouldShortCircuit) {
                val reply = "[Fractal Echo] High resonance (${String.format("%.2f", gate.resonance)}). Using cached pattern."
                adapter.submit(Message(UUID.randomUUID().toString(), reply, isUser = false))
                chatRv.scrollToPosition(maxOf(0, adapter.itemCount - 1))
                return@launch
            }

            // Otherwise, ask the model
            currentAssistantId = UUID.randomUUID().toString()
            currentAssistantText.clear()
            adapter.submit(Message(currentAssistantId!!, "", isUser = false))
            chatRv.scrollToPosition(maxOf(0, adapter.itemCount - 1))

            engine.sendUserPrompt(msg)
        }
    }

    private suspend fun onModelSelected(uri: Uri) {
        // Copy to app-private storage (so native code can read a real filesystem path)
        val modelFile = withContext(Dispatchers.IO) { copyUriToModelsDir(uri) }
        loadedModelFile = modelFile

        // Parse minimal metadata for display
        val metaText = withContext(Dispatchers.IO) { readGgufSummary(modelFile) }
        ggufInfoTv.text = metaText

        // Load model
        withContext(Dispatchers.IO) {
            engine.loadModel(modelFile.absolutePath)
            // Default system prompt; you can expose a UI field later if needed
            engine.sendSystemPrompt("You are a helpful assistant. Keep answers concise.")
        }

        toast("Model loaded.")
    }

    private suspend fun readGgufSummary(file: File): String {
        return runCatching {
            val reader = GgufMetadataReader.create()
            file.inputStream().buffered().use { input ->
                val md = reader.readStructuredMetadata(input)
                val basic = md.basic
                val arch = md.architecture?.architecture ?: "unknown"
                val name = basic.name ?: "GGUF model"
                val uuid = basic.uuid ?: "no-uuid"
                "${'$'}name\narch=${'$'}arch\nctx_train=${'$'}{md.dimensions?.contextLength ?: "?"}\nuid=${'$'}uuid\npath=${'$'}{file.name}"
            }
        }.getOrElse {
            "GGUF model loaded: ${'$'}{file.name}\n(metadata unavailable: ${'$'}{it.message})"
        }
    }

    private fun copyUriToModelsDir(uri: Uri): File {
        val modelsDir = File(filesDir, "models").apply { mkdirs() }
        val outFile = File(modelsDir, "model-${'$'}{System.currentTimeMillis()}.gguf")

        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { out ->
                input.copyTo(out)
            }
        } ?: throw IllegalStateException("Unable to open model URI")

        return outFile
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do not destroy on configuration changes; keep simple for now
        // engine.destroy() can be exposed via menu later if you want.
    }
}
