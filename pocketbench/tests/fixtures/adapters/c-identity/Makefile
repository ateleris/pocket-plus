# The shared C plumbing's test double, built by tests/test_plumbing.py.
COMMON := ../../../../adapters/common/c

build/adapter: adapter.c $(COMMON)/adapter_common.c $(COMMON)/adapter_common.h
	@mkdir -p build
	gcc -std=c99 -O2 -Wall -Wextra -I$(COMMON) adapter.c $(COMMON)/adapter_common.c -o build/adapter

clean:
	rm -rf build

.PHONY: clean
