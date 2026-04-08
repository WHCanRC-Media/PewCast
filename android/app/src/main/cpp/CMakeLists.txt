cmake_minimum_required(VERSION 3.22.1)
project(audioengine)

# Fetch Oboe for low-latency audio
include(FetchContent)
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG        1.9.0
)
FetchContent_MakeAvailable(oboe)

# Build our library
add_library(audioengine SHARED
    audio_engine.cpp
    audio_engine_jni.cpp
    chirp_tester.cpp
)

target_include_directories(audioengine PRIVATE
    ${oboe_SOURCE_DIR}/include
)

target_link_libraries(audioengine
    oboe
    android
    log
)

# Enable C++17
target_compile_features(audioengine PRIVATE cxx_std_17)
