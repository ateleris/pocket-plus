# -Dparallel is a JVM system property read by Stainless, not a Stainless option: the launcher
# forwards $JAVA_OPTS to java and everything else to stainless.Main, which drops -D arguments.
# Passing it as an argument (as this script used to) ran the VCs one at a time. Override the
# worker count with PARALLEL=N.
JAVA_OPTS="-Dparallel=${PARALLEL:-2} ${JAVA_OPTS:-}" stainless\
  ./src/main/scala/pocket/PocketExecSpec.scala\
  ./src/main/scala/pocket/datastructure/arrays/BitArray.scala\
  ./src/main/scala/pocket/datastructure/arrays/ByteArray.scala\
  ./src/main/scala/pocket/datastructure/arrays/IntArray.scala\
  ./src/main/scala/pocket/datastructure/Utils.scala\
  --config-file=stainless.conf $1
