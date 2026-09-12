# SMRITI AQUA native DSP module (module A).
cmake_minimum_required(VERSION 3.22.1)
project(smriti_dsp LANGUAGES C CXX)

add_library(smriti_dsp SHARED
        dsp_core.cpp
        jni_bridge.cpp
        kiss_fft.c
        kiss_fftr.c)

set_target_properties(smriti_dsp PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
        C_STANDARD 99)

target_compile_options(smriti_dsp PRIVATE -O2)

if(ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(smriti_dsp ${LOG_LIB})
endif()
