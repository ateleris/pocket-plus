# Compiles the codec sources together with the adapter. SOURCE is substituted by
# `pocket-bench build`.
#
# There is deliberately no MAX_PACKET_BITS here. The C++ codec's CCSDS124_MAX_PACKET_LENGTH only
# feeds the DEFAULT template arg of BitBuffer<>, and the compress<N>/decompress<N> the adapter
# calls always pass explicit N-derived sizes, so the macro is inert on this path and the reference-c
# memset bench gotcha does not apply.
SOURCE ?=

CXX := g++
CC := gcc
COMMON := ../common/c
CXXFLAGS := -std=c++17 -O3 -flto -Wall -I$(SOURCE)/include -I$(COMMON)
# The shared contract plumbing is C, so it is compiled separately. No -flto: that would mix
# gcc/g++ LTO objects, and it holds no codec work to optimize across.
COMMON_CFLAGS := -std=c99 -O2 -Wall -I$(COMMON)
LDFLAGS := -flto

# All codec sources except the CLI, mirroring the CMake library target. Most are stubs: the
# templated classes are header-only and bitreader.cpp is the only non-template implementation.
CODEC_SRCS := $(filter-out %/cli.cpp,$(wildcard $(SOURCE)/src/*.cpp))

build/adapter: src/adapter.cpp $(COMMON)/adapter_common.c $(CODEC_SRCS)
	@mkdir -p build
	$(CC) $(COMMON_CFLAGS) -c $(COMMON)/adapter_common.c -o build/adapter_common.o
	$(CXX) $(CXXFLAGS) src/adapter.cpp build/adapter_common.o $(CODEC_SRCS) $(LDFLAGS) \
	    -o build/adapter

clean:
	rm -rf build

.PHONY: clean
