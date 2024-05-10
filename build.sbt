val scala3Version = "3.4.1"

val vGears = "0.2.0-SNAPSHOT"
val vFs2 = "3.10.2"
val vRxJava = "3.1.8"

enablePlugins(JmhPlugin)

lazy val root = project
  .in(file("."))
  .settings(
    name := "Stream Benchmark",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "0.7.29" % Test,
      "ch.epfl.lamp" %% "gears" % vGears,
      "co.fs2" %% "fs2-core" % vFs2,
      "co.fs2" %% "fs2-io" % vFs2,
      "io.reactivex.rxjava3" % "rxjava" % vRxJava
    )
  )
