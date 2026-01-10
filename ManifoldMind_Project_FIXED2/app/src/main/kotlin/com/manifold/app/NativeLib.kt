package com.manifold.app

class NativeLib {
    companion object {
        init {
            System.loadLibrary("manifold-mind")
        }
    }

    /**
     * Operator 7: Resonance Extraction
     * Takes user input embedding and manifold centroid, returns resonance score.
     */
    external fun operator7Resonance(embedding: FloatArray, centroid: FloatArray): Float

    /**
     * Operator 11: Coherence
     * Takes an embedding and returns a coherence score K_gamma.
     */
    external fun operator11Coherence(embedding: FloatArray): Float

    /**
     * Identity Stability Metric Delta I
     * Calculates the identity stability metric based on conversation history resonance scores.
     */
    external fun getDeltaI(history: FloatArray): Float
}
