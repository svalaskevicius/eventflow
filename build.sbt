
  lazy val commonSettings = Seq(
    scalaVersion := "2.11.8",
    organization := "com.hyperlambda",
    version := "0.0.1",
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature",
      "-Xlint", "-Ywarn-unused-import", "-Yno-adapted-args", "-Ywarn-dead-code",
      "-Ywarn-numeric-widen", "-Ywarn-value-discard", "-Ywarn-infer-any")
  )

  lazy val finchCore = sbt.ProjectRef(uri("https://github.com/finagle/finch.git#5fcea10"), "core")

  lazy val core = (project in file("core")).settings(commonSettings: _*)
  lazy val eventstoreBackend = (project in file("eventstore-backend")).settings(commonSettings: _*).dependsOn(core)
  lazy val example = (project in file("example")).settings(commonSettings: _*).dependsOn(core, eventstoreBackend, finchCore)

  lazy val root = (project in file(".")).aggregate(core, eventstoreBackend, example)
