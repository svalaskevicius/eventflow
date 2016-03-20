import sbt._
import Keys._

object EventflowBuild extends Build {

  lazy val buildSettings = Defaults.defaultSettings ++ Seq(
    scalaVersion := "2.11.8",
    organization := "com.hyperlambda",
    version := "0.0.1",
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature",
      "-Xlint", "-Ywarn-unused-import", "-Yno-adapted-args", "-Ywarn-dead-code",
      "-Ywarn-numeric-widen", "-Ywarn-value-discard", "-Ywarn-infer-any")
  )
  //  resolvers += Resolver.sonatypeRepo("snapshots")
  //  resolvers += Resolver.sonatypeRepo("releases")

  lazy val root = Project(
    id = "eventflow",
    base = file("."),
    settings = buildSettings
  ) aggregate(core, example)

  lazy val core = Project(
    id = "eventflow-core",
    base = file("core"),
    settings = buildSettings
  )

  lazy val example = Project(
    id = "eventflow-example",
    base = file("example"),
    settings = buildSettings) dependsOn(core)
}
