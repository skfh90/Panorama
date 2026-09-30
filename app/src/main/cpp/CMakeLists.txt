# Builds libpanorama_stitch.so against the unpacked OpenCV Android SDK.
cmake_minimum_required(VERSION 3.6)
project(panorama_stitch)

# The Android SDK ships release static libraries only. Debug app builds still link those.
set(CMAKE_MAP_IMPORTED_CONFIG_DEBUG Release)
set(CMAKE_MAP_IMPORTED_CONFIG_RELWITHDEBINFO Release)
set(CMAKE_MAP_IMPORTED_CONFIG_MINSIZEREL Release)

if(NOT EXISTS "${OpenCV_DIR}/OpenCVConfig.cmake")
    message(FATAL_ERROR
            "OpenCV Android SDK not found at ${OpenCV_DIR}. "
            "Run a Gradle sync so fetchOpenCvSdk can download OpenCV 4.5.5.")
endif()

find_package(OpenCV REQUIRED core imgproc imgcodecs)

add_library(panorama_stitch SHARED stitcher.cpp)
target_compile_options(panorama_stitch PRIVATE -O2)
target_link_libraries(panorama_stitch ${OpenCV_LIBS} log)
