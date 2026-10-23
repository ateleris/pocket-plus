# Compiles the Stainless GenC output and the codec's pp_* shim together with the adapter. SOURCE is
# substituted by `pocket-bench build`.
SOURCE ?=

CC := gcc
COMMON := ../common/c
NATIVE := $(SOURCE)/native
GENC := $(NATIVE)/generated/pocketplus.c
CFLAGS := -std=c99 -O3 -flto -Wall -I$(NATIVE)/src -I$(COMMON) -D_POSIX_C_SOURCE=199309L
LDFLAGS := -flto

build/adapter: src/adapter.c $(COMMON)/adapter_common.c $(COMMON)/adapter_common.h \
               $(NATIVE)/src/pp_shim.c $(NATIVE)/src/pp_shim.h build/pocketplus.o
	$(CC) $(CFLAGS) src/adapter.c $(COMMON)/adapter_common.c $(NATIVE)/src/pp_shim.c \
	    build/pocketplus.o $(LDFLAGS) -o build/adapter

# GenC emits its own `main`, which would collide with the adapter's.
build/pocketplus.o: $(GENC)
	@mkdir -p build
	$(CC) $(CFLAGS) -Dmain=pocketplus_genc_main -c $(GENC) -o build/pocketplus.o

# The generated C is not committed; the codec's own build script owns the GenC invocation.
$(GENC): $(SOURCE)/scala/src/main/scala/pocket/old/PocketPlus.scala
	$(NATIVE)/build.sh

clean:
	rm -rf build

.PHONY: clean
