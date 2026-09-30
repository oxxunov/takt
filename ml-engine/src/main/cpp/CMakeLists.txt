cmake_minimum_required(VERSION 3.22.1)
project(venative C)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# libdf.so — официальный DeepFilterNet (C API), собран в CI из исходников v0.5.6
set(JNI_LIBS ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs)
add_library(df SHARED IMPORTED)
set_target_properties(df PROPERTIES IMPORTED_LOCATION ${JNI_LIBS}/${ANDROID_ABI}/libdf.so)

# libsamplerate 0.2.2 (BSD-2) — эталонный ресемплер, скачивается в CI
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LIBSAMPLERATE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LIBSAMPLERATE_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/libsamplerate ${CMAKE_BINARY_DIR}/libsamplerate)

add_library(venative SHARED native.c)
target_include_directories(venative PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/generated
        ${CMAKE_CURRENT_SOURCE_DIR}/libsamplerate/include)
target_link_libraries(venative df samplerate log m)
