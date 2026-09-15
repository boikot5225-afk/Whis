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

file(READ "${WHISPER_ROOT}/CMakeLists.txt" WHISPER_MAIN_CMAKE)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" WHISPER_VERSION_MATCH "${WHISPER_MAIN_CMAKE}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION_STRING "${CMAKE_MATCH_1}")
else()
    set(WHISPER_VERSION_STRING "unknown")
endif()
message(STATUS "Whis: whisper.cpp version ${WHISPER_VERSION_STRING}")

find_library(LOG_LIB log)

# Android is cross-compiled, so never use host-native flags. Tell ggml explicitly
# which ARM features we target. Snapdragon 8 Gen 3 supports FP16 + dot-product,
# which are important for quantized Whisper CPU inference.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR "${WHISPER_ROOT}/ggml")
FetchContent_MakeAvailable(ggml)

add_library(
    whis
    SHARED
    whis_jni.cpp
    "${WHISPER_ROOT}/src/whisper.cpp"
)

target_compile_definitions(
    whis
    PUBLIC GGML_USE_CPU
    PRIVATE WHISPER_VERSION="${WHISPER_VERSION_STRING}"
)

target_compile_options(
    whis
    PRIVATE
    -O3
    -ffast-math
    -fno-math-errno
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(whis PRIVATE -march=armv8.2-a+fp16+dotprod)
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
