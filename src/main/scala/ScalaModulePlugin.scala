import sbt._
import Keys._

object ScalaModulePlugin extends Plugin {

  val includeTestDependencies = settingKey[Boolean]("Include testing dependencies when building.  Used to break cycles when doing full builds.")
  val partestVersion = settingKey[String]("the partest version we want to use.")

  def scalaModuleSettings: Seq[Setting[_]] =
    Seq(
      partestVersion := "1.0-RC5",
      organization := "org.scala-lang.modules",
      // don't use for doc scope, scaladoc warnings are not to be reckoned with
      scalacOptions in compile ++= Seq("-optimize", "-Xfatal-warnings", "-feature", "-deprecation", "-unchecked", "-Xlint"),
      // Generate $name.properties to store our version as well as the scala version used to build
      resourceGenerators in Compile <+= Def.task {
        val props = new java.util.Properties
        props.put("version.number", version.value)
        props.put("scala.version.number", scalaVersion.value)
        props.put("scala.binary.version.number", scalaBinaryVersion.value)
        val file = (resourceManaged in Compile).value / s"${name.value}.properties"
        IO.write(props, null, file)
        Seq(file)
      },
      mappings in (Compile, packageBin) += {
         (baseDirectory.value / s"${name.value}.properties") -> s"${name.value}.properties"
      },
      // maven publishing
      publishTo := {
        val nexus = "https://oss.sonatype.org/"
        if (version.value.trim.endsWith("SNAPSHOT"))
          Some("snapshots" at nexus + "content/repositories/snapshots")
        else
          Some("releases" at nexus + "service/local/staging/deploy/maven2")
      },
      publishMavenStyle := true,
      publishArtifact in Test := false,
      pomIncludeRepository := { _ => false },
      pomExtra := (
        <url>http://www.scala-lang.org/</url>
        <inceptionYear>2002</inceptionYear>
        <licenses>
          <license>
              <distribution>repo</distribution>
              <name>BSD 3-Clause</name>
              <url>https://github.com/scala/{name.value}/blob/master/LICENSE.md</url>
          </license>
         </licenses>
        <scm>
          <connection>scm:git:git://github.com/scala/{name.value}.git</connection>
          <url>https://github.com/scala/{name.value}</url>
        </scm>
        <issueManagement>
          <system>JIRA</system>
          <url>https://issues.scala-lang.org/</url>
        </issueManagement>
        <developers>
          <developer>
            <id>epfl</id>
            <name>EPFL</name>
          </developer>
          <developer>
            <id>Typesafe</id>
            <name>Typesafe, Inc.</name>
          </developer>
        </developers>
      ),
      // default value must be set here
      includeTestDependencies := true,
      // the actual partest the interface calls into -- must be binary version close enough to ours
      // so that it can link to the compiler/lib we're using (testing)
      libraryDependencies ++= (
        if (includeTestDependencies.value)
          Seq("org.scala-lang.modules" %% "scala-partest"           % partestVersion.value % "test",
              "org.scala-lang.modules" %% "scala-partest-interface" % "0.2"     % "test")
        else Seq.empty
      ),
      // necessary for partest -- see comments in its build.sbt
      conflictWarning ~= { _.copy(failOnConflict = false) },
      fork in Test := true,
      javaOptions in Test += "-Xmx1G",
      testFrameworks += new TestFramework("scala.tools.partest.Framework"),
      definedTests in Test += (
        new sbt.TestDefinition(
          "partest",
          // marker fingerprint since there are no test classes
          // to be discovered by sbt:
          new sbt.testing.AnnotatedFingerprint {
            def isModule = true
            def annotationName = "partest"
          }, true, Array())
        )

      // TODO: mima
      // import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
      // import com.typesafe.tools.mima.plugin.MimaKeys.previousArtifact
      // previousArtifact := Some(organization.value %% name.value % binaryReferenceVersion.value)
    )

}
