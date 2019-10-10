val http4sVersion = "0.21.0-M5"
scalaVersion := "2.13.1"
name := "http4s-client-bug"
testFrameworks += new TestFramework("utest.runner.Framework")
fork := true
libraryDependencies ++= List(
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % http4sVersion,
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "com.lihaoyi" %% "utest" % "0.7.1" % Test,
)
scalacOptions ++= List(
  "-deprecation",
  "-unchecked",
  "-feature",
  "-language:higherKinds",
  "-language:experimental.macros",
  "-language:existentials",
  "-Ywarn-value-discard",
  "-Ywarn-unused:imports",
  "-Ywarn-unused:implicits",
  "-Ywarn-unused:params",
  "-Ywarn-unused:patvars",
)
addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full)
