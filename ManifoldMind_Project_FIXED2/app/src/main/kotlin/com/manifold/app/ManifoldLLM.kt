package com.manifold.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Manifold gate in front of the LLM:
 * - Computes a lightweight text embedding (hashing) locally
 * - Uses native Manifold Operators (C++) for resonance/coherence
 * - Applies policy: if high resonance, short-circuit with a cached response
 */
class ManifoldLLM(private val context: Context) {

    private val nativeLib = NativeLib()

    private var centroid: FloatArray = FloatArray(128) { 0.0f }
    private val resonanceHistory = mutableListOf<Float>()

    private val stateFile: File by lazy { File(context.filesDir, "manifold_state.json") }

    init {
        loadState()
    }

    data class GateResult(
        val resonance: Float,
        val coherence: Float,
        val deltaI: Float,
        val shouldShortCircuit: Boolean
    )

    /**
     * Runs the manifold gate for a given input and returns both metrics and a decision.
     */
    fun evaluate(userInput: String): GateResult {
        val embedding = embed128(userInput)
        val resonance = nativeLib.operator7Resonance(embedding, centroid)
        val coherence = nativeLib.operator11Coherence(embedding)

        resonanceHistory.add(resonance)
        val deltaI = nativeLib.getDeltaI(resonanceHistory.toFloatArray())

        // Policy thresholds (tune here)
        val shortCircuit = resonance > 0.85f && coherence > 0.50f

        // Optional centroid adaptation (slow drift)
        updateCentroid(embedding, lr = 0.02f)

        persistStateSafe()

        Log.d("ManifoldLLM", "Gate resonance=$resonance coherence=$coherence deltaI=$deltaI shortCircuit=$shortCircuit")

        return GateResult(
            resonance = resonance,
            coherence = coherence,
            deltaI = deltaI,
            shouldShortCircuit = shortCircuit
        )
    }

    fun getResonanceHistory(): List<Float> = resonanceHistory.toList()

    // ---------------- Embedding + centroid ----------------

    private fun embed128(text: String): FloatArray {
        // Deterministic 128-dim hashing embedding (no external deps, no network).
        val v = FloatArray(128)
        val s = text.trim().lowercase()
        if (s.isEmpty()) return v

        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        // 3-gram rolling hash
        for (i in bytes.indices) {
            val b0 = bytes[i].toInt()
            val b1 = bytes.getOrNull(i + 1)?.toInt() ?: 0
            val b2 = bytes.getOrNull(i + 2)?.toInt() ?: 0
            val h = mix(b0, b1, b2)
            val idx = (h and 127)
            // signed contribution
            val sign = if ((h shr 8) and 1 == 0) 1f else -1f
            v[idx] += sign * (1f + (abs(h % 7) / 7f))
        }

        // L2 normalize
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(max(1e-8f, norm))
        for (i in v.indices) v[i] /= norm
        return v
    }

    private fun mix(a: Int, b: Int, c: Int): Int {
        var x = a * 0x45d9f3b
        x = x xor (x ushr 16)
        x += b * 0x45d9f3b
        x = x xor (x ushr 16)
        x += c * 0x45d9f3b
        x = x xor (x ushr 16)
        return x
    }

    private fun updateCentroid(embedding: FloatArray, lr: Float) {
        // centroid = (1-lr)*centroid + lr*embedding, with renorm
        for (i in centroid.indices) {
            centroid[i] = (1f - lr) * centroid[i] + lr * embedding[i]
        }
        // renorm
        var norm = 0f
        for (x in centroid) norm += x * x
        norm = sqrt(max(1e-8f, norm))
        for (i in centroid.indices) centroid[i] /= norm
    }

    // ---------------- persistence ----------------

    private fun loadState() {
        if (!stateFile.exists()) {
            // Initialize centroid to small uniform values to avoid NaNs in cosine sim
            centroid = FloatArray(128) { 0.1f }
            return
        }
        runCatching {
            val obj = JSONObject(stateFile.readText())
            val c = obj.getJSONArray("centroid")
            centroid = FloatArray(128) { idx -> c.optDouble(idx, 0.0).toFloat() }
            val h = obj.optJSONArray("history") ?: JSONArray()
            resonanceHistory.clear()
            for (i in 0 until h.length()) resonanceHistory.add(h.getDouble(i).toFloat())
        }.onFailure {
            Log.w("ManifoldLLM", "Failed to load manifold state; resetting.", it)
            centroid = FloatArray(128) { 0.1f }
            resonanceHistory.clear()
        }
    }

    private fun persistStateSafe() {
        runCatching {
            val obj = JSONObject()
            val c = JSONArray()
            centroid.forEach { c.put(it.toDouble()) }
            obj.put("centroid", c)

            val h = JSONArray()
            resonanceHistory.takeLast(256).forEach { h.put(it.toDouble()) }
            obj.put("history", h)

            stateFile.writeText(obj.toString())
        }
    }
}
