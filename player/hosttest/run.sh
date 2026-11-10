#!/bin/sh
# Builds and runs the native host tests (decoders, DSD, DoP, packet timing) on the build machine.
set -e
cd "$(dirname "$0")"
C=../src/main/cpp
mkdir -p build
gcc -c -O2 -w -DTARGET_RT_LITTLE_ENDIAN=1 -I$C/third_party/alac $C/third_party/alac/ALACBitUtilities.c -o build/abu.o
gcc -c -O2 -w -DTARGET_RT_LITTLE_ENDIAN=1 -I$C/third_party/alac $C/third_party/alac/ag_dec.c -o build/ag.o
gcc -c -O2 -w -DTARGET_RT_LITTLE_ENDIAN=1 -I$C/third_party/alac $C/third_party/alac/dp_dec.c -o build/dp.o
gcc -c -O2 -w -DTARGET_RT_LITTLE_ENDIAN=1 -I$C/third_party/alac $C/third_party/alac/matrix_dec.c -o build/mx.o
gcc -c -O2 -w -DTARGET_RT_LITTLE_ENDIAN=1 -I$C/third_party/alac $C/third_party/alac/EndianPortable.c -o build/ep.o
g++ -std=c++17 -O2 -Wall -Wno-unused-function -DTARGET_RT_LITTLE_ENDIAN=1 -I$C -I$C/third_party -I$C/third_party/alac \
    host_test.cpp $C/pp_decoder.cpp $C/pp_dsd.cpp $C/pp_usb.cpp $C/third_party/alac/ALACDecoder.cpp \
    build/abu.o build/ag.o build/dp.o build/mx.o build/ep.o -lpthread -o build/host_test
./build/host_test data
