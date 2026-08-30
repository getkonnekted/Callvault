cmake_minimum_required(VERSION 3.22.1)

project(callvault-native)

# ============================================================
# Android log
# ============================================================

find_library(log-lib log)

# ============================================================
# CallVault - existing resilient recording native component
# ============================================================

add_library(audiohandoff SHARED
        audiohandoff.cpp
)

target_link_libraries(audiohandoff
        ${log-lib}
)

# ============================================================
# Whisper.cpp
# ============================================================

set(WHISPER_DIR
        ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp
)

message(STATUS "CallVault Whisper directory: ${WHISPER_DIR}")

if (NOT EXISTS "${WHISPER_DIR}/include/whisper.h")
    message(FATAL_ERROR
            "whisper.h NOT FOUND at: ${WHISPER_DIR}/include/whisper.h"
    )
endif()

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)

add_subdirectory(
        ${WHISPER_DIR}
        ${CMAKE_CURRENT_BINARY_DIR}/whisper
)

# ============================================================
# CallVault Whisper JNI bridge
# ============================================================

add_library(callvault_whisper SHARED
        whisper_bridge.cpp
)

target_include_directories(callvault_whisper PRIVATE
        ${WHISPER_DIR}/include
)

target_link_libraries(callvault_whisper
        whisper
        ${log-lib}
)