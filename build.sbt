name := "eventflow"

version := "1.0"

scalaVersion := "2.11.7"

libraryDependencies += "org.spire-math" %% "cats" % "0.2.0"
libraryDependencies += "com.lihaoyi" %% "pprint" % "0.3.6"
libraryDependencies += "com.lihaoyi" %% "upickle" % "0.3.6"
libraryDependencies += "com.chuusai" %% "shapeless" % "2.2.5"
libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.12.4" % "test"

resolvers += Resolver.sonatypeRepo("releases")

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.6.3")

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature",
                      "-Xlint", "-Ywarn-unused-import", "-Yno-adapted-args", "-Ywarn-dead-code",
                      "-Ywarn-numeric-widen", "-Ywarn-value-discard", "-Ywarn-infer-any",
                      "–explaintypes")
