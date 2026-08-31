name := "PocketPlus"
version := "0.1.0-SNAPSHOT"
scalaOrganization := "ch.epfl.lara"
scalaVersion := "3.10.0-RC1-bin-20260608-cf86bba-NIGHTLY"

run / fork := true
javaOptions += "-Xss256m"

stainlessEnabled := false

enablePlugins(StainlessPlugin)
