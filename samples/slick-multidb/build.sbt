libraryDependencies ++= List(
  "org.slf4j" % "slf4j-nop" % "1.7.26",
  "com.h2database" % "h2" % "1.4.200",
  "org.xerial" % "sqlite-jdbc" % "3.36.0.1"
)

scalacOptions += "-deprecation"

run / fork := true
