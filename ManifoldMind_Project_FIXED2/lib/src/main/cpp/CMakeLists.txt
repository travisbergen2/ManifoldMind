cmake_minimum_required(VERSION 3.22.1)

project(ai-chat LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ------------------------------
# llama.cpp via FetchContent
# ------------------------------
include(FetchContent)

# Keep the llama.cpp build lean (no examples/tests/server)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)

# Avoid extra backends unless you explicitly need them
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        master
)

# Downloads + adds llama.cpp as a subproject
FetchContent_MakeAvailable(llama_cpp)

# ------------------------------
# Our JNI shared library
# ------------------------------
add_library(ai-chat SHARED
        ai_chat.cpp
        manifold_operators.cpp
)

# Include our headers + llama.cpp public headers
# (llama.cpp exports target include dirs too; we add ours explicitly)
target_include_directories(ai-chat PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link against llama.cpp target(s)
# Primary C API library target is typically 'llama'
# (If your CMake sync fails on this name, I’ll give you the exact fallback list.)
target_link_libraries(ai-chat
        llama
        android
        log
)
