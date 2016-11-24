
libraryDependencies += "org.typelevel" %% "cats" % "0.8.1"
libraryDependencies += "com.lihaoyi" %% "upickle" % "0.4.4"
libraryDependencies += "com.chuusai" %% "shapeless" % "2.3.2"
//libraryDependencies += "org.scalameta" %% "scalameta" % "1.2.0"

resolvers += Resolver.sonatypeRepo("releases")

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")


// New-style macro annotations are under active development.  As a result, in
// this build we'll be referring to snapshot versions of both scala.meta and
// macro paradise.
resolvers += Resolver.url("scalameta", url("http://dl.bintray.com/scalameta/maven"))(Resolver.ivyStylePatterns)

// A dependency on macro paradise 3.x is required to both write and expand
// new-style macros.  This is similar to how it works for old-style macro
// annotations and a dependency on macro paradise 2.x.  A new release is
// published on every merged PR into paradise.  To find the latest PR number,
// see https://github.com/scalameta/paradise/commits/master and replace "122"
addCompilerPlugin("org.scalameta" % "paradise" % "3.0.0.132" cross CrossVersion.full)
scalacOptions += "-Xplugin-require:macroparadise"
// temporary workaround for https://github.com/scalameta/paradise/issues/10
scalacOptions in (Compile, console) += "-Yrepl-class-based" // necessary to use console
// temporary workaround for https://github.com/scalameta/paradise/issues/55
sources in (Compile, doc) := Nil

libraryDependencies += "org.scalameta" %% "scalameta" % "1.3.0"
