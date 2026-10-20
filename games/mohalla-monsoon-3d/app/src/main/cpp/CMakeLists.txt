cmake_minimum_required(VERSION 3.22.1)
project(mohalla_monsoon LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(NATIVE_APP_GLUE_DIR "${ANDROID_NDK}/sources/android/native_app_glue")
add_library(native_app_glue STATIC "${NATIVE_APP_GLUE_DIR}/android_native_app_glue.c")
target_include_directories(native_app_glue PUBLIC "${NATIVE_APP_GLUE_DIR}")

add_library(mohalla SHARED main.cpp)

target_compile_options(mohalla PRIVATE
    -Wall -Wextra -Wno-unused-parameter -O2
)
target_link_libraries(mohalla
    native_app_glue
    android
    log
    EGL
    GLESv2
    OpenSLES
)
