addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.6.2")

// incompatible with sbt-gpg (https://github.com/softprops/bintray-sbt/pull/10)
// JZ: is this still true after updating to 0.3.0?
addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.4.0")
