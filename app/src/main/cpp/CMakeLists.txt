cmake_minimum_required(VERSION 3.18)
project(xcamhd)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fvisibility=hidden -fexceptions -frtti")

# ncnn prebuilt (Vulkan) diunduh oleh workflow GitHub Actions ke folder ini.
set(ncnn_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn-20240820-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(xcamhd SHARED hd_jni.cpp hd_core.cpp)
target_link_libraries(xcamhd ncnn jnigraphics log)
