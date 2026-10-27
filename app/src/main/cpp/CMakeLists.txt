cmake_minimum_required(VERSION 3.22.1...4.4)
project(dsre_audio LANGUAGES C CXX)

set(FFMPEG_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/include")
set(FFMPEG_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/libs/${ANDROID_ABI}")

foreach(lib avutil swresample swscale avcodec avformat)
    add_library(${lib} SHARED IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION "${FFMPEG_LIB_DIR}/lib${lib}.so")
endforeach()

add_library(dsre_audio SHARED dsre_audio_jni.cpp)
target_compile_features(dsre_audio PRIVATE cxx_std_17)
target_compile_options(dsre_audio PRIVATE -Wall -Wextra -Werror=return-type)
target_include_directories(dsre_audio PRIVATE "${FFMPEG_INCLUDE_DIR}")
target_link_libraries(dsre_audio PRIVATE avformat avcodec swscale swresample avutil log android)
