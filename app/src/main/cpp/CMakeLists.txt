# Native half of "Resilient recording" (audio-capture handoff): extracts the IAudioRecord binder +
# cblk ashmem fd in the daemon, and drains the cblk ring in the app.
cmake_minimum_required(VERSION 3.22.1)
project(audiohandoff)

add_library(audiohandoff SHARED audiohandoff.cpp)

find_library(log-lib log)

target_link_libraries(audiohandoff ${log-lib})
