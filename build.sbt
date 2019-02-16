sbtPlugin         := true

git.baseVersion   := "1.0.0"
versionWithGit

name                := "sbt-scala-module"
organization        := "org.scala-lang.modules"
licenses            := Seq(("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0")))
resolvers           += Classpaths.sbtPluginReleases
bintrayRepository   := "sbt-plugins"
bintrayOrganization := None
publishMavenStyle   := false

addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.9.4")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.3.0")
