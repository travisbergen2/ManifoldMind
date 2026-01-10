# Manifold Mind: Local-First Android LLM with Manifold Operators

Manifold Mind is an Android application that integrates `llama.cpp` for local LLM inference with a custom "Manifold Layer" for mathematical embedding processing.

## Architecture

- **Android UI (Kotlin)**: Handles the chat interface and model management.
- **Manifold Layer (Kotlin/C++)**: Implements Manifold Operators for Resonance, Coherence, and Identity Stability.
- **Core Engine (C++)**: `llama.cpp` for GGUF model inference.

## Manifold Operators

1. **Operator 7 (Resonance)**: Calculates the cosine similarity between the input embedding and the manifold centroid.
2. **Operator 11 (Coherence)**: Evaluates the stability of the input embedding.
3. **Delta I (Identity Stability)**: Tracks the evolution of resonance over time.

## Build Instructions

### Prerequisites

- Android Studio Ladybug or newer.
- Android NDK (Side-by-side) and CMake.
- A GGUF model file (e.g., Llama-3.2-1B-Instruct-Q4_K_M.gguf).

### Steps

1. Open the project in Android Studio.
2. Ensure the NDK path is configured in `local.properties` or via Project Structure.
3. Build the project. The `manifold-mind` shared library will be compiled using CMake.
4. Deploy to an Android device (API 29+).

## Usage

1. Launch the app.
2. Tap the "Folder" icon to select a `.gguf` model from your device's storage.
3. Once loaded, type a message to start the inference.
4. The app will first check for **Coherence** and **Resonance** before proceeding to full LLM decoding.
5. If **Resonance > 0.85**, a "Fractal Echo" is triggered, using cached logic to save battery.

## File Structure

- `app/src/main/cpp/manifold_operators.cpp`: C++ implementation of Manifold math.
- `app/src/main/kotlin/com/manifold/app/NativeLib.kt`: JNI bridge.
- `app/src/main/kotlin/com/manifold/app/ManifoldLLM.kt`: Logic layer for manifold processing.
- `app/src/main/java/com/example/llama/MainActivity.kt`: Main UI and inference loop.
