cmake_minimum_required(VERSION 3.22.1)
project(neurocenter_openjdk21 C)
add_library(neurojvm SHARED neurojvm.c ncapp_runner.c)
target_compile_features(neurojvm PRIVATE c_std_11)
# Buildtype-specific compilation: the pipe/stderr capture code is absent
# from the release libneurojvm.so, not merely hidden in the UI.
target_compile_definitions(neurojvm PRIVATE
    $<$<CONFIG:Debug>:NEUROCENTER_DEBUG_DIAGNOSTICS=1>)
target_link_libraries(neurojvm PRIVATE dl log)
