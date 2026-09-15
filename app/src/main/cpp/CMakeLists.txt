cmake_minimum_required(VERSION 3.10)
project(whis LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(
    WHISPER_ROOT
    "${CMAKE_CURRENT_LIST_DIR}/../../../../vendor/whisper.cpp"
    ABSOLUTE
)

if (NOT EXISTS "${WHISPER_ROOT}/src/whisper.cpp")
    message(FATAL_ERROR "whisper.cpp not found at ${WHISPER_ROOT}. Run scripts/fetch-whisper.sh first.")
endif()

find_library(LOG_LIB log)

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR "${WHISPER_ROOT}/ggml")
FetchContent_MakeAvailable(ggml)

add_library(
    whis
    SHARED
    whis_jni.cpp
    "${WHISPER_ROOT}/src/whisper.cpp"
)

target_compile_definitions(whis PUBLIC GGML_USE_CPU)
target_compile_options(whis PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(whis PRIVATE -march=armv8.2-a+fp16)
endif()

target_include_directories(
    whis
    PRIVATE
    "${WHISPER_ROOT}"
    "${WHISPER_ROOT}/include"
    "${WHISPER_ROOT}/src"
    "${WHISPER_ROOT}/ggml/include"
    "${WHISPER_ROOT}/ggml/src"
    "${WHISPER_ROOT}/ggml/src/ggml-cpu"
)

target_link_libraries(whis ${LOG_LIB} android ggml)
