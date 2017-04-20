import sbt._
import Keys._
import com.typesafe.sbt.osgi.{OsgiKeys, SbtOsgi}
import com.typesafe.tools.mima.plugin.{MimaPlugin, MimaKeys}, MimaKeys._

object ScalaModulePlugin extends Plugin {
  val repoName                   = settingKey[String]("The name of the repository under github.com/scala/.")
  val mimaPreviousVersion        = settingKey[Option[String]]("The version of this module to compare against when running MiMa.")
  val scalaVersionsByJvm         = settingKey[Map[Int, List[(String, Boolean)]]]("For a Java major version (6, 8, 9), a list of Scala version and a flag indicating whether to use this combination for publishing.")

  private val canRunMima         = taskKey[Boolean]("Decides if MiMa should run.")
  private val runMimaIfEnabled   = taskKey[Unit]("Run MiMa if mimaPreviousVersion and the module can be resolved against the current scalaBinaryVersion.")

  lazy val scalaModuleSettings: Seq[Setting[_]] = Seq(
    repoName            := name.value,

    mimaPreviousVersion := None,

    scalaVersionsByJvm  := Map.empty,

    crossScalaVersions  := {
      val OneDot = """1\.(\d).*""".r // 1.6, 1.8
      val Maj    = """(\d+).*""".r   // 9
      val javaVersion = System.getProperty("java.version") match {
        case OneDot(n) => n.toInt
        case Maj(n)    => n.toInt
        case v         => throw new RuntimeException(s"Unknown Java version: $v")
      }
      val isTravisPublishing = Option(System.getenv("TRAVIS_TAG")).getOrElse("").trim.nonEmpty
      val scalaVersions = scalaVersionsByJvm.value.getOrElse(javaVersion, Nil) collect {
        case (v, publish) if !isTravisPublishing || publish => v
      }
      if (scalaVersions.isEmpty) {
        if (isTravisPublishing) {
          sLog.value.warn(s"No Scala version in `scalaVersionsByJvm` in build.sbt needs to be released on Java major version $javaVersion.")
          // Exit successfully, don't fail the (travis) build. This happens for example if `openjdk7`
          // is part of the travis configuration for testing, but it's not used for releasing against
          // any Scala version.
          System.exit(0)
        } else
          throw new RuntimeException(s"No Scala version for Java major version $javaVersion. Adjust `scalaVersionsByJvm` in build.sbt.")
      }
      scalaVersions
    },

    scalaVersion       := crossScalaVersions.value.head,

    organization        := "org.scala-lang.modules",

    // so we don't have to wait for sonatype to synch to maven central when deploying a new module
    resolvers += Resolver.sonatypeRepo("releases"),

    // don't use for doc scope, scaladoc warnings are not to be reckoned with
    // TODO: turn on for nightlies, but don't enable for PR validation... "-Xfatal-warnings"
    scalacOptions in compile ++= Seq("-optimize", "-feature", "-deprecation", "-unchecked", "-Xlint"),

    // Generate $name.properties to store our version as well as the scala version used to build
    resourceGenerators in Compile += Def.task {
      val props = new java.util.Properties
      props.put("version.number", version.value)
      props.put("scala.version.number", scalaVersion.value)
      props.put("scala.binary.version.number", scalaBinaryVersion.value)
      val file = (resourceManaged in Compile).value / s"${name.value}.properties"
      IO.write(props, null, file)
      Seq(file)
    }.taskValue,

    mappings in (Compile, packageBin) += {
       (baseDirectory.value / s"${name.value}.properties") -> s"${name.value}.properties"
    },

    // needed to fix classloader issues (see scala/scala-xml#20)
    // essentially, the problem is that the run-time bootclasspath leaks into the compilation classpath,
    // so that scalac see classes used to run it, as classes used to compile against...
    // forking uses a minimal classpath, so this craziness is avoided
    // alternatively, manage the scala instance as shown at the end of this file (commented)
    fork in Test := true,

    publishArtifact in Test := false,

    // maven publishing
    publishTo := Some(
      if (version.value.trim.endsWith("SNAPSHOT")) Resolver.sonatypeRepo("snapshots")
      else Opts.resolver.sonatypeStaging
    ),
    credentials ++= {
      val file = Path.userHome / ".ivy2" / ".credentials"
      if (file.exists) List(new FileCredentials(file)) else Nil
    },

    publishMavenStyle    := true,
    scmInfo              := Some(ScmInfo(url(s"https://github.com/scala/${repoName.value}"),s"scm:git:git://github.com/scala/${repoName.value}.git")),
    homepage             := Some(url("http://www.scala-lang.org/")),
    organizationHomepage := Some(url("http://www.scala-lang.org/")),
    licenses             := Seq("BSD 3-clause" -> url("http://opensource.org/licenses/BSD-3-Clause")),
    startYear            := Some(2002),
    pomIncludeRepository := { _ => false },
    pomExtra := (
      <issueManagement>
        <system>GitHub</system>
        <url>https://github.com/scala/{repoName.value}/issues</url>
      </issueManagement>
      <developers>
        <developer>
          <id>lamp</id>
          <name>LAMP/EPFL</name>
        </developer>
        <developer>
          <id>Lightbend</id>
          <name>Lightbend, Inc.</name>
        </developer>
      </developers>
    )
  ) ++ mimaSettings

  // adapted from https://github.com/typesafehub/migration-manager/blob/0.1.6/sbtplugin/src/main/scala/com/typesafe/tools/mima/plugin/SbtMima.scala#L69
  def artifactExists(organization: String, name: String, scalaBinaryVersion: String, version: String, ivy: IvySbt, s: TaskStreams): Boolean = {
    val moduleId = new ModuleID(organization, s"${name}_$scalaBinaryVersion", version)
    val moduleSettings = InlineConfiguration(
      "dummy" % "test" % "version",
      ModuleInfo("dummy-test-project-for-resolving"),
      dependencies = Seq(moduleId))
    val ivyModule = new ivy.Module(moduleSettings)
    try {
      IvyActions.update(
        ivyModule,
        new UpdateConfiguration(
          retrieve = None,
          missingOk = false,
          logging = UpdateLogging.DownloadOnly),
        s.log)
      true
    } catch {
      case _: ResolveException => false
    }
  }

  lazy val mimaSettings: Seq[Setting[_]] = MimaPlugin.mimaDefaultSettings ++ Seq(
    // manual cross-versioning because https://github.com/typesafehub/migration-manager/issues/62
    mimaPreviousArtifacts := Set(organization.value % s"${name.value}_${scalaBinaryVersion.value}" % mimaPreviousVersion.value.getOrElse("dummy")),

    canRunMima := {
      val mimaVer = mimaPreviousVersion.value
      val s = streams.value
      if (mimaVer.isEmpty) {
        s.log.warn("MiMa will NOT run because no mimaPreviousVersion is provided.")
        false
      } else if (!artifactExists(organization.value, name.value, scalaBinaryVersion.value, mimaVer.get, ivySbt.value, s)) {
        s.log.warn(s"""MiMa will NOT run because the previous artifact "${organization.value}" % "${name.value}_${scalaBinaryVersion.value}" % "${mimaVer.get}" could not be resolved (note the binary Scala version).""")
        false
      } else {
        true
      }
    },

    runMimaIfEnabled := Def.taskDyn({
      if(canRunMima.value) Def.task { mimaReportBinaryIssues.value }
      else Def.task { () }
    }).value,

    test in Test := {
      runMimaIfEnabled.value
      (test in Test).value
    }
  )

  // a setting-transform to turn the regular version into something osgi can deal with
  val osgiVersion = version(_.replace('-', '.'))

  lazy val scalaModuleOsgiSettings = SbtOsgi.osgiSettings ++ Seq(
    OsgiKeys.bundleSymbolicName  := s"${organization.value}.${name.value}",
    OsgiKeys.bundleVersion       := osgiVersion.value,

    // Sources should also have a nice MANIFEST file
    packageOptions in packageSrc := Seq(Package.ManifestAttributes(
                          ("Bundle-SymbolicName", s"${organization.value}.${name.value}.source"),
                          ("Bundle-Name", s"${name.value} sources"),
                          ("Bundle-Version", osgiVersion.value),
                          ("Eclipse-SourceBundle", s"""${organization.value}.${name.value};version="${osgiVersion.value}";roots:="."""")
                      ))
  )
}


// ALTERNATIVE to fork in test for fixing classpath issues noted above:
// manage the Scala instance ourselves to exclude the published scala-xml (scala-compiler depends on it)
// since this dependency hides the classes we're testing
// managedScalaInstance := false
//
// ivyConfigurations    += Configurations.ScalaTool
//
// libraryDependencies ++= Seq(
//    "org.scala-lang" % "scala-library" % scalaVersion.value,
//    ("org.scala-lang" % "scala-compiler" % scalaVersion.value % "scala-tool").exclude("org.scala-lang.modules", s"scala-xml_${scalaBinaryVersion.value}")
// )
