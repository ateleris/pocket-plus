# Compiles the adapter together with pocketplus's functional specification (PocketExecSpec.scala),
# using the Scala compiler of the Stainless install. SOURCE is substituted by `pocket-bench build`.
SOURCE ?=

COMMON := ../common/scala
STAINLESS := $(SOURCE)/tools/stainless
SPEC := $(SOURCE)/scala/src/main/scala/pocket
SRCS := src/Adapter.scala $(COMMON)/Plumbing.scala $(SPEC)/PocketExecSpec.scala \
        $(SPEC)/datastructure/Utils.scala

build/adapter: $(SRCS) $(COMMON)/build.sh $(wildcard $(STAINLESS)/lib/*.jar)
	bash $(COMMON)/build.sh $(STAINLESS) build pocketplusscala.Adapter $(SRCS)

clean:
	rm -rf build

.PHONY: clean
