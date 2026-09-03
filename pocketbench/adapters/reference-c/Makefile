# Compiles the codec sources together with the adapter so both agree on
# CCSDS124_MAX_PACKET_LENGTH, which sizes structs in the shared header. SOURCE and
# MAX_PACKET_BITS are substituted by `pocket-bench build`.
SOURCE ?=
MAX_PACKET_BITS ?=
BITS := $(or $(MAX_PACKET_BITS),65535)

CC := gcc
COMMON := ../common/c
CFLAGS := -std=c99 -O3 -flto -Wall -I$(SOURCE)/include -I$(COMMON) \
          -DCCSDS124_MAX_PACKET_LENGTH=$(BITS) -D_POSIX_C_SOURCE=199309L
LDFLAGS := -flto

CODEC_SRCS := $(filter-out %/cli.c,$(wildcard $(SOURCE)/src/*.c))

# FORCE prerequisite: always recompile so a change to MAX_PACKET_BITS (a -D flag, which make's
# timestamp check ignores) actually takes effect when a command switches packet sizes.
build/adapter: FORCE
	@mkdir -p build
	$(CC) $(CFLAGS) src/adapter.c $(COMMON)/adapter_common.c $(CODEC_SRCS) $(LDFLAGS) \
	    -o build/adapter

clean:
	rm -rf build

FORCE:

.PHONY: clean FORCE
