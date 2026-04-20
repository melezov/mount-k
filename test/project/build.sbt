scalaVersion := "2.12.21"
scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-unchecked",
  "-deprecation",
  "-feature",
  "-Xsource:3",
)

libraryDependencies += "net.java.dev.jna" % "jna-platform" % "5.18.1"

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")
