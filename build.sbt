name := "eventflow"

version := "1.0"

scalaVersion := "2.11.7"

libraryDependencies += "org.spire-math" %% "cats" % "0.2.0"

resolvers += Resolver.sonatypeRepo("releases")

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.6.3")

wartremoverErrors ++= Warts.allBut(Wart.Any, Wart.Nothing, Wart.Throw, Wart.AsInstanceOf, Wart.NoNeedForMonad)

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature",
                      "-Xlint", "-Ywarn-unused-import", "-Yno-adapted-args", "-Ywarn-dead-code",
                      "-Ywarn-numeric-widen", "-Ywarn-value-discard", "-Ywarn-infer-any",
                      "–explaintypes")
