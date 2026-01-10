#include <vector>
#include <cmath>
#include <numeric>
#include <jni.h>
#include "llama.h"

// Operator 7: Resonance Extraction
// Calculates the projection of current input (v_i) against the Manifold Centroid (C_m)
float calculate_resonance(const std::vector<float>& input_embedding, const std::vector<float>& manifold_centroid) {
    if (input_embedding.size() != manifold_centroid.size() || input_embedding.empty()) return 0.0f;

    float dot_product = 0.0f;
    float norm_a = 0.0f;
    float norm_b = 0.0f;

    for (size_t i = 0; i < input_embedding.size(); ++i) {
        dot_product += input_embedding[i] * manifold_centroid[i];
        norm_a += input_embedding[i] * input_embedding[i];
        norm_b += manifold_centroid[i] * manifold_centroid[i];
    }

    // Returns a value between 0 and 1 representing "Resonance"
    return dot_product / (std::sqrt(norm_a) * std::sqrt(norm_b) + 1e-9f);
}

// Operator 11: Coherence
// Takes a float array and returns a jfloat (coherence score K_{\gamma})
float calculate_coherence(const std::vector<float>& embedding) {
    if (embedding.empty()) return 0.0f;
    float sum = 0.0f;
    for (float val : embedding) {
        sum += std::abs(val);
    }
    // Mock coherence calculation for now
    float score = sum / embedding.size();
    return std::min(1.0f, score);
}

// Identity Stability Metric \Delta I
float calculate_delta_i(const std::vector<float>& history_resonance) {
    if (history_resonance.size() < 2) return 1.0f;
    float sum_diff = 0.0f;
    for (size_t i = 1; i < history_resonance.size(); ++i) {
        sum_diff += std::abs(history_resonance[i] - history_resonance[i-1]);
    }
    return 1.0f - (sum_diff / (history_resonance.size() - 1));
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_manifold_app_NativeLib_operator7Resonance(JNIEnv *env, jobject thiz, jfloatArray j_embedding, jfloatArray j_centroid) {
    jfloat* flt_emb = env->GetFloatArrayElements(j_embedding, 0);
    jfloat* flt_cen = env->GetFloatArrayElements(j_centroid, 0);
    jsize len = env->GetArrayLength(j_embedding);

    std::vector<float> input_vec(flt_emb, flt_emb + len);
    std::vector<float> centroid_vec(flt_cen, flt_cen + len);

    float resonance_score = calculate_resonance(input_vec, centroid_vec);

    env->ReleaseFloatArrayElements(j_embedding, flt_emb, 0);
    env->ReleaseFloatArrayElements(j_centroid, flt_cen, 0);

    return resonance_score;
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_manifold_app_NativeLib_operator11Coherence(JNIEnv *env, jobject thiz, jfloatArray j_embedding) {
    jfloat* flt_emb = env->GetFloatArrayElements(j_embedding, 0);
    jsize len = env->GetArrayLength(j_embedding);

    std::vector<float> embedding(flt_emb, flt_emb + len);
    float coherence_score = calculate_coherence(embedding);

    env->ReleaseFloatArrayElements(j_embedding, flt_emb, 0);
    return coherence_score;
}

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_manifold_app_NativeLib_getDeltaI(JNIEnv *env, jobject thiz, jfloatArray j_history) {
    jfloat* flt_hist = env->GetFloatArrayElements(j_history, 0);
    jsize len = env->GetArrayLength(j_history);

    std::vector<float> history(flt_hist, flt_hist + len);
    float delta_i = calculate_delta_i(history);

    env->ReleaseFloatArrayElements(j_history, flt_hist, 0);
    return delta_i;
}
