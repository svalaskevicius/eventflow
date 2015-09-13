name := "eventflow"

version := "1.0"

scalaVersion := "2.11.7"

libraryDependencies += "org.spire-math" %% "cats" % "0.2.0"

resolvers += Resolver.sonatypeRepo("releases")

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.6.3")

// if your project uses multiple Scala versions, use this for cross building
addCompilerPlugin("org.spire-math" % "kind-projector" % "0.6.3" cross CrossVersion.binary)

wartremoverErrors ++= Warts.allBut(Wart.Any, Wart.Nothing, Wart.Serializable, Wart.Throw, Wart.AsInstanceOf)

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-Xlint")
