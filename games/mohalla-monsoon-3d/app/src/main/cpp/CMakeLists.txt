cmake_minimum_required(VERSION 3.22.1)
project(mohalla_monsoon LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(NATIVE_APP_GLUE_DIR "${ANDROID_NDK}/sources/android/native_app_glue")

# Compile the glue directly into the shared library. Android's NativeActivity
# loader looks up ANativeActivity_onCreate in libmohalla.so at load time; an
# unreferenced static-archive member can be omitted by the linker.
add_library(mohalla SHARED
    main.cpp
    "${NATIVE_APP_GLUE_DIR}/android_native_app_glue.c"
)
target_include_directories(mohalla PRIVATE "${NATIVE_APP_GLUE_DIR}")

target_compile_options(mohalla PRIVATE
    -Wall -Wextra -Wno-unused-parameter -O2
)
target_link_libraries(mohalla
    android
    log
    EGL
    GLESv2
    OpenSLES
)
