name := "eventflow"

version := "1.0"

scalaVersion := "2.11.8"

libraryDependencies += "org.typelevel" %% "cats" % "0.4.1"
libraryDependencies += "com.lihaoyi" %% "upickle" % "0.3.8"
libraryDependencies += "com.chuusai" %% "shapeless" % "2.1.0"

libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.12.4" % "test"
libraryDependencies += "org.scalactic" %% "scalactic" % "2.2.6"
libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.6" % "test"

libraryDependencies += "com.geteventstore" % "eventstore-client_2.11" % "2.2.1"

val http4sVersion = "0.12.4"
libraryDependencies += "org.http4s" %% "http4s-dsl"          % http4sVersion  // to use the core dsl
libraryDependencies += "org.http4s" %% "http4s-blaze-server" % http4sVersion  // to use the blaze backend
libraryDependencies += "org.http4s" %% "http4s-servlet"      % http4sVersion  // to use the raw servlet backend
libraryDependencies += "org.http4s" %% "http4s-jetty"        % http4sVersion  // to use the jetty servlet backend
libraryDependencies += "org.http4s" %% "http4s-blaze-client" % http4sVersion  // to use the blaze client

resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"

resolvers += Resolver.sonatypeRepo("releases")

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.7.1")

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature",
                      "-Xlint", "-Ywarn-unused-import", "-Yno-adapted-args", "-Ywarn-dead-code",
                      "-Ywarn-numeric-widen", "-Ywarn-value-discard", "-Ywarn-infer-any")
