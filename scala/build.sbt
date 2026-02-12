ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.8.1"

libraryDependencies ++= Seq(
  "io.qtjambi" % "qtjambi" % "6.10.2",
  "io.qtjambi" % "qtjambi-native-windows-x64" % "6.10.2",
)

lazy val root = (project in file("."))
  .settings(
    name := "scala"
  )
