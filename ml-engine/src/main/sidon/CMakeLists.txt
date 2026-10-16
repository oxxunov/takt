# Sidon (SaruLab, MIT) — восстановление речи «как в студии», рантайм CrispASR (MIT) на ggml.
# Собирается в CI отдельно от Gradle (как libdf.so) в двух вариантах для arm64:
#   libvesidon.so      — базовый armv8-a, работает на любом 64-битном телефоне;
#   libvesidon_v82.so  — armv8.2-a + fp16 + dotprod, заметно быстрее на современных процессорах.
# Нужный вариант выбирается в приложении по /proc/cpuinfo.
cmake_minimum_required(VERSION 3.22.1)
project(vesidon C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if (NOT CRISP_DIR)
    message(FATAL_ERROR "Укажите -DCRISP_DIR=<путь к исходникам CrispASR>")
endif()
if (NOT VESIDON_NAME)
    set(VESIDON_NAME vesidon)
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory(${CRISP_DIR}/ggml ggml EXCLUDE_FROM_ALL)

add_library(${VESIDON_NAME} SHARED
        sidon_jni.cpp
        ${CRISP_DIR}/src/sidon.cpp
        ${CRISP_DIR}/src/core/gguf_loader.cpp)
target_include_directories(${VESIDON_NAME} PRIVATE
        ${CRISP_DIR}/src ${CRISP_DIR}/include ${CRISP_DIR}/ggml/src)
target_compile_options(${VESIDON_NAME} PRIVATE -O3)
target_link_libraries(${VESIDON_NAME} PRIVATE ggml ggml-base ggml-cpu)
if (ANDROID)
    target_link_libraries(${VESIDON_NAME} PRIVATE log)
    # наружу торчат только JNI-функции
    target_link_options(${VESIDON_NAME} PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endif()

# проверка модели на ПК в CI (в APK не попадает)
if (VESIDON_HOST_CHECK)
    add_executable(host_check host_check.cpp ${CRISP_DIR}/src/sidon.cpp ${CRISP_DIR}/src/core/gguf_loader.cpp)
    target_include_directories(host_check PRIVATE ${CRISP_DIR}/src ${CRISP_DIR}/include ${CRISP_DIR}/ggml/src)
    target_link_libraries(host_check PRIVATE ggml ggml-base ggml-cpu)
endif()
