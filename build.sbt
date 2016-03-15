name := "eventflow"

version := "1.0"

scalaVersion := "2.11.8"

libraryDependencies += "org.typelevel" %% "cats" % "0.4.1"
libraryDependencies += "com.lihaoyi" %% "upickle" % "0.3.8"
libraryDependencies += "com.chuusai" %% "shapeless" % "2.2.5"

libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.12.4" % "test"
libraryDependencies += "org.scalactic" %% "scalactic" % "2.2.6"
libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.6" % "test"

libraryDependencies += "com.geteventstore" % "eventstore-client_2.11" % "2.2.1"

libraryDependencies ++= Seq(
  "com.github.finagle" %% "finch-core" % "0.10.0",
  "com.github.finagle" %% "finch-circe" % "0.10.0"
)

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core" % "0.3.0",
  "io.circe" %% "circe-generic" % "0.3.0",
  "io.circe" %% "circe-parser" % "0.3.0"
)

resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"

resolvers += Resolver.sonatypeRepo("releases")

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.7.1")

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature",
                      "-Xlint", "-Ywarn-unused-import", "-Yno-adapted-args", "-Ywarn-dead-code",
                      "-Ywarn-numeric-widen", "-Ywarn-value-discard", "-Ywarn-infer-any")
