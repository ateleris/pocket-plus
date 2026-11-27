// Stainless comes from Maven Central: its library as a dependency, and Stainless itself as a plugin
// of the Scala 3 compiler (https://epfl-lara.github.io/stainless/installation.html#maven-central).
// The plugin bundles the native Z3 interface, so no solver or Stainless install is needed.
val stainlessVersion = "0.11.0"

// The files Stainless verifies (the `verify` project) and translates to C (the `genc` task). Their
// paths are relative to src/main/scala/pocket.
val verifiedSources = Seq(
  "PocketExecSpec.scala",
  "PocketImperative.scala",
  "datastructure/arrays/BitArray.scala",
  "datastructure/arrays/ByteArray.scala",
  "datastructure/arrays/IntArray.scala",
  "datastructure/Utils.scala",
)
// What GenC compiles: the codec, and the bit array it stores its vectors in
val gencSources = Seq(
  "PocketImperative.scala",
  "datastructure/arrays/BitArray.scala",
  "datastructure/Utils.scala",
)

lazy val stainlessSettings = Seq(
  version := "0.1.0-SNAPSHOT",
  // The Scala 3 compiler of the lab, with which Stainless is built
  scalaOrganization := "ch.epfl.lara",
  scalaVersion := "3.10.1-RC1-bin-20260903-e1f9361-NIGHTLY",
  libraryDependencies += "ch.epfl.lara" %% "stainless-library" % stainlessVersion,
  addCompilerPlugin(("ch.epfl.lara" % "stainless-dotty-plugin" % stainlessVersion).cross(CrossVersion.full)),
)

val pocketDir = settingKey[File]("Directory of the Scala sources of POCKET+")
val genc = taskKey[Unit]("Translates the imperative codec to C with Stainless GenC, into ../native/generated")

// The program, its tests and demos. The plugin only removes the ghost code here: the verification
// is done by the `verify` project, on the verified sources only.
lazy val root = (project in file("."))
  .settings(stainlessSettings)
  .settings(
    name := "PocketPlus",
    pocketDir := (Compile / scalaSource).value / "pocket",
    scalacOptions += "-P:stainless:verify:false",
    run / fork := true,
    javaOptions += "-Xss256m",

    // GenC runs the main class of Stainless, which the plugin JAR contains with its dependencies
    genc := {
      val output = (baseDirectory.value / ".." / "native" / "generated" / "pocketplus.c").getCanonicalFile
      val stainlessJars = (StainlessGenC / update).value.select(configurationFilter(StainlessGenC.name))
      val sources = gencSources.map(f => (pocketDir.value / f).getAbsolutePath)
      val args = Seq("--config-file=false", "--genc", "--skip-verification", s"--genc-output=$output") ++ sources
      val options = ForkOptions()
        .withRunJVMOptions(Vector("-Xss512m", "--sun-misc-unsafe-memory-access=allow"))
        .withWorkingDirectory(baseDirectory.value)
      val exit = Fork.java(options, Seq("-cp", stainlessJars.mkString(java.io.File.pathSeparator), "stainless.Main") ++ args)
      if (exit != 0) sys.error(s"GenC failed with exit code $exit")
    },
    ivyConfigurations += StainlessGenC,
    libraryDependencies += ("ch.epfl.lara" % "stainless-dotty-plugin" % stainlessVersion % StainlessGenC).cross(CrossVersion.full),
  )

lazy val StainlessGenC = config("stainless-genc").hide

// Verifies the verified sources: `sbt verify/clean verify/compile` (`clean` so that all the files
// are extracted again; the VC cache in .stainless-cache keeps the checks of the unchanged VCs).
// Stainless reads its options from stainless.conf, which it finds in the working directory of sbt.
// sbt needs a base directory per project, but this one has no files of its own: its sources are
// those of `root`, and its build output goes to target/verify.
lazy val verify = (project in file("target/verify"))
  .settings(stainlessSettings)
  .settings(
    name := "PocketPlus-verify",
    target := baseDirectory.value,
    Compile / unmanagedSourceDirectories := Seq.empty,
    Compile / unmanagedSources := verifiedSources.map(f => (root / pocketDir).value / f),
    Compile / unmanagedResourceDirectories := Seq.empty,
    Test / unmanagedSourceDirectories := Seq.empty,
  )
