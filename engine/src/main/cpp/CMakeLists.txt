cmake_minimum_required(VERSION 3.22.1)
project(taktnative LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(taktnative SHARED
        analyzer.cpp
        click_engine.cpp
        crash_handler.cpp
        jni_bridge.cpp)

target_compile_options(taktnative PRIVATE -O3 -ffast-math -fno-rtti -Wall)

find_library(log-lib log)

# aaudio есть начиная с API 26 — minSdk модуля выставлен соответственно.
# dl нужна для dladdr в посмертной трассировке.
target_link_libraries(taktnative ${log-lib} aaudio dl)
