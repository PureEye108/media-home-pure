cmake_minimum_required(VERSION 3.22.1)
project(playerpure C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Apple ALAC decoder (Apache 2.0). Its endian check only knows x86, so tell it ARM is little-endian too.
add_library(alac STATIC
    third_party/alac/ALACDecoder.cpp
    third_party/alac/ALACBitUtilities.c
    third_party/alac/ag_dec.c
    third_party/alac/dp_dec.c
    third_party/alac/matrix_dec.c
    third_party/alac/EndianPortable.c)
target_compile_definitions(alac PRIVATE TARGET_RT_LITTLE_ENDIAN=1)
target_compile_options(alac PRIVATE -O2 -w)

add_library(playerpure SHARED pp_jni.cpp pp_decoder.cpp pp_dsd.cpp pp_usb.cpp)
target_include_directories(playerpure PRIVATE . third_party third_party/alac)
target_compile_definitions(playerpure PRIVATE TARGET_RT_LITTLE_ENDIAN=1)
target_compile_options(playerpure PRIVATE -O2 -Wall -Wno-unused-function)
target_link_libraries(playerpure alac log)
# 16 KB page alignment (required by newer Android versions, harmless on Android 14)
target_link_options(playerpure PRIVATE "-Wl,-z,max-page-size=16384")
