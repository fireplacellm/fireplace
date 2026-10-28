# Fireplace native engine. Builds the JNI bridge against a pinned llama.cpp.
# Not built in this repo snapshot (needs the Android NDK + llama.cpp checkout).
cmake_minimum_required(VERSION 3.22)
project(fireplace_engine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is added as a pinned checkout (revision in engine/llama-revision.txt).
# Keep network + heavyweight tooling OUT of the mobile build:
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)  # no HF/download/arg code
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)  # no TLS; offline by construction
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)  # NDK has no OpenMP runtime
# First build keeps backends statically linked (BACKEND_DL off) for simple
# packaging; runtime arm64 variant dispatch is a later optimization.
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp ${CMAKE_BINARY_DIR}/llama.cpp)

add_library(fireplace_jni SHARED fireplace_jni.cpp)
find_library(log-lib log)
target_link_libraries(fireplace_jni llama ${log-lib})

# Export only the JNI symbols.
target_link_options(fireplace_jni PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
