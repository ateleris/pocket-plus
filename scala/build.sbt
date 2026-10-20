name := "PocketPlus"
version := "0.1.0-SNAPSHOT"
scalaOrganization := "ch.epfl.lara"
scalaVersion := "3.10.1-RC1-bin-20260903-e1f9361-NIGHTLY"

run / fork := true
javaOptions += "-Xss256m"

stainlessEnabled := false

enablePlugins(StainlessPlugin)
