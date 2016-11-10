libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.12.4" % "test"
libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.0"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.0" % "test"

//libraryDependencies ++= Seq(
//  "com.github.finagle" %% "finch-core" % "0.11.0-M4"
////  "com.github.finagle" %% "finch-circe" % "0.11.0-M4"
//)


libraryDependencies ++= Seq(
  "io.circe" %% "circe-core" % "0.6.0",
  "io.circe" %% "circe-generic" % "0.6.0",
  "io.circe" %% "circe-parser" % "0.6.0"
)

libraryDependencies += "joda-time" % "joda-time" % "2.9.3"

resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"

resolvers += Resolver.sonatypeRepo("releases")
