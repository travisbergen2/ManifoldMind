package com.arm.aichat.internal

import android.content.Context
import android.util.Log
import com.arm.aichat.InferenceEngine
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI-backed implementation that wraps the native functions in lib/src/main/cpp/ai_chat.cpp.
 */
class InferenceEngineImpl private constructor(
    private val nativeLibDir: String
) : InferenceEngine {

    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName

        @Volatile private var instance: InferenceEngineImpl? = null

        fun getInstance(context: Context): InferenceEngineImpl =
            instance ?: synchronized(this) {
                instance ?: run {
                    val dir = context.applicationInfo.nativeLibraryDir
                    require(dir.isNotBlank()) { "Invalid nativeLibraryDir" }
                    InferenceEngineImpl(dir).also { instance = it }
                }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private val _tokenFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val tokenFlow: Flow<String> = _tokenFlow.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val modelLoaded = AtomicBoolean(false)

    private fun ensureInit() {
        if (initialized.compareAndSet(false, true)) {
            _state.value = InferenceEngine.State.Initializing
            Log.i(TAG, "Loading native library ai-chat...")
            System.loadLibrary("ai-chat")
            init(nativeLibDir)
            _state.value = InferenceEngine.State.ModelNotLoaded
        }
    }

    override suspend fun loadModel(modelPath: String) = withContext(Dispatchers.IO) {
        ensureInit()
        _state.value = InferenceEngine.State.LoadingModel

        val rcLoad = load(modelPath)
        if (rcLoad != 0) {
            _state.value = InferenceEngine.State.Error("Failed to load model (rc=$rcLoad)")
            return@withContext
        }

        val rcPrep = prepare()
        if (rcPrep != 0) {
            _state.value = InferenceEngine.State.Error("Failed to prepare runtime (rc=$rcPrep)")
            return@withContext
        }

        modelLoaded.set(true)
        _state.value = InferenceEngine.State.ModelReady
        Log.i(TAG, "Model loaded and ready.")
    }

    override suspend fun sendSystemPrompt(systemPrompt: String) = withContext(Dispatchers.IO) {
        ensureInit()
        if (!modelLoaded.get()) {
            _state.value = InferenceEngine.State.Error("Model not loaded")
            return@withContext
        }
        val rc = processSystemPrompt(systemPrompt)
        if (rc != 0) {
            _state.value = InferenceEngine.State.Error("System prompt failed (rc=$rc)")
            return@withContext
        }
        _state.value = InferenceEngine.State.ModelReady
    }

    override suspend fun sendUserPrompt(userPrompt: String) = withContext(Dispatchers.IO) {
        ensureInit()
        if (!modelLoaded.get()) {
            _state.value = InferenceEngine.State.Error("Model not loaded")
            return@withContext
        }

        val rc = processUserPrompt(userPrompt)
        if (rc != 0) {
            _state.value = InferenceEngine.State.Error("User prompt failed (rc=$rc)")
            return@withContext
        }

        _state.value = InferenceEngine.State.Generating

        // Generate until native returns null.
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val chunk = generateNextToken() ?: break
                    if (chunk.isNotEmpty()) {
                        _tokenFlow.tryEmit(chunk)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Generation error", t)
                _state.value = InferenceEngine.State.Error("Generation error: ${'$'}{t.message}")
            } finally {
                if (_state.value !is InferenceEngine.State.Error) {
                    _state.value = InferenceEngine.State.ModelReady
                }
            }
        }
    }

    override suspend fun unloadModel() = withContext(Dispatchers.IO) {
        if (!initialized.get()) return@withContext
        if (modelLoaded.get()) {
            val rc = unload()
            Log.i(TAG, "Unload rc=$rc")
            modelLoaded.set(false)
        }
        _state.value = InferenceEngine.State.ModelNotLoaded
    }

    override fun destroy() {
        try {
            if (initialized.get()) {
                shutdown()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "shutdown error", t)
        } finally {
            scope.cancel()
            instance = null
            _state.value = InferenceEngine.State.Uninitialized
        }
    }

    // ---------------- JNI ----------------

    @FastNative external fun init(nativeLibDir: String)
    @FastNative external fun load(modelPath: String): Int
    @FastNative external fun prepare(): Int
    @FastNative external fun processSystemPrompt(systemPrompt: String): Int
    @FastNative external fun processUserPrompt(userPrompt: String): Int
    @FastNative external fun generateNextToken(): String?
    @FastNative external fun unload(): Int
    @FastNative external fun shutdown()
}
