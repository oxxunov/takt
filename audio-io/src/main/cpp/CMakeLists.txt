cmake_minimum_required(VERSION 3.22.1)
project(veaudio C)

# LAME 3.100 (LGPL) — эталонный MP3-энкодер, исходники скачиваются в CI.
# Собирается отдельной динамической библиотекой libmp3lame.so, как требует LGPL.
set(LAME ${CMAKE_CURRENT_SOURCE_DIR}/lame)
set(LAME_SRC
        bitstream.c encoder.c fft.c gain_analysis.c id3tag.c lame.c newmdct.c presets.c
        psymodel.c quantize.c quantize_pvt.c reservoir.c set_get.c tables.c takehiro.c
        util.c vbrquantize.c VbrTag.c version.c)
list(TRANSFORM LAME_SRC PREPEND ${LAME}/libmp3lame/)
add_library(mp3lame SHARED ${LAME_SRC})
target_include_directories(mp3lame PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/lame_config ${LAME}/include ${LAME}/libmp3lame)
target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H)
target_compile_options(mp3lame PRIVATE -O3 -w
        -Wno-error=implicit-function-declaration -Wno-error=int-conversion
        -Wno-error=incompatible-pointer-types -Wno-error=implicit-int)
target_link_libraries(mp3lame m)

add_library(veaudio SHARED mp3_jni.c)
target_include_directories(veaudio PRIVATE ${LAME}/include)
target_link_libraries(veaudio mp3lame log)
