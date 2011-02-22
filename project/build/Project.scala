import sbt._

class Project(info: ProjectInfo) extends ParentProject(info) with IdeaProject {
  val ScalazVersion = "5.0"
  val CamelVersion = "2.5.0"

  lazy val scalazCamelCore    = project("scalaz-camel-core", "scalaz-camel-core", new ProjectCore(_))
  lazy val scalazCamelAkka    = project("scalaz-camel-akka", "scalaz-camel-akka", new ProjectAkka(_), scalazCamelCore)
  lazy val scalazCamelSamples = project("scalaz-camel-samples", "scalaz-camel-samples", new ProjectSamples(_), scalazCamelCore)

  object Dependencies {
    lazy val camelCore    = "org.apache.camel" % "camel-core" % CamelVersion
    lazy val camelJms     = "org.apache.camel" % "camel-jms" % CamelVersion
    lazy val cameHttp     = "org.apache.camel" % "camel-http" % CamelVersion
    lazy val camelJetty   = "org.apache.camel" % "camel-jetty" % CamelVersion
    lazy val camelSpring  = "org.apache.camel" % "camel-spring" % CamelVersion
    lazy val activemqCore = "org.apache.activemq" % "activemq-core" % "5.3.2"
    lazy val scalatest    = "org.scalatest" % "scalatest" % "1.2"
    lazy val junit        = "junit" % "junit" % "4.8.2"
  }

  class ProjectCore(info: ProjectInfo) extends DefaultProject(info) with IdeaProject {
    // Compile
    lazy val camelCore    = Dependencies.camelCore % "compile"

    // Test
    lazy val camelJms     = Dependencies.camelJms % "test"
    lazy val cameHttp     = Dependencies.cameHttp % "test"
    lazy val camelJetty   = Dependencies.camelJetty % "test"
    lazy val camelSpring  = Dependencies.camelSpring % "test"
    lazy val activemqCore = Dependencies.activemqCore % "test"
    lazy val scalatest    = Dependencies.scalatest % "test"
    lazy val junit        = Dependencies.junit % "test"
  }

  class ProjectAkka(info: ProjectInfo) extends DefaultProject(info) with IdeaProject {
  }

  class ProjectSamples(info: ProjectInfo) extends DefaultProject(info) with IdeaProject {
    override def testResourcesPath = "src" / "main" / "resources"

    // Compile
    lazy val camelJms     = Dependencies.camelJms % "compile"
    lazy val cameHttp     = Dependencies.cameHttp % "compile"
    lazy val camelJetty   = Dependencies.camelJetty % "compile"
    lazy val camelSpring  = Dependencies.camelSpring % "compile"
    lazy val activemqCore = Dependencies.activemqCore % "compile"

    // Test
    lazy val scalatest    = Dependencies.scalatest % "test"
    lazy val junit        = Dependencies.junit % "test"
  }
}
