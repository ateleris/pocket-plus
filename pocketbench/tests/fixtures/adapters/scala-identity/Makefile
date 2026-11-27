# The shared Scala plumbing's test double, built by tests/test_plumbing.py.
SOURCE ?=

COMMON := ../../../../adapters/common/scala
STAINLESS := $(SOURCE)/tools/stainless
SRCS := Adapter.scala $(COMMON)/Plumbing.scala

build/adapter: $(SRCS) $(COMMON)/build.sh $(wildcard $(STAINLESS)/lib/*.jar)
	bash $(COMMON)/build.sh $(STAINLESS) build scalaidentity.Adapter $(SRCS)

clean:
	rm -rf build

.PHONY: clean
