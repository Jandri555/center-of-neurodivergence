cmake_minimum_required(VERSION 3.22.1)
project(neurocenter_openjdk21 C)
add_library(neurojvm SHARED neurojvm.c ncapp_runner.c)
target_compile_features(neurojvm PRIVATE c_std_11)
# Header compiled deterministically from the signed JVM-only Java loader.
target_include_directories(neurojvm PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../../build/generated/ncapp-loader")
# Android Gradle's debug native variant normally uses RelWithDebInfo, not
# CMake's Debug. Gradle explicitly sets this per APK build type.
option(NEUROCENTER_ENABLE_DIAGNOSTICS "Enable debug crash pipe" OFF)
if(NEUROCENTER_ENABLE_DIAGNOSTICS)
    target_compile_definitions(neurojvm PRIVATE NEUROCENTER_DEBUG_DIAGNOSTICS=1)
endif()
target_link_libraries(neurojvm PRIVATE dl log)
