import sbt._

class Project(info: ProjectInfo) extends DefaultProject(info) {
  val ScalazVersion = "5.0"
  val CamelVersion = "2.5.0"

  val scalazCore = "com.googlecode.scalaz" % "scalaz-core_2.8.0" % ScalazVersion % "compile"

  val camelCore = "org.apache.camel" % "camel-core" % CamelVersion % "compile" withSources
  val camelJms = "org.apache.camel" % "camel-jms" % CamelVersion % "compile" withSources
  val cameHttp = "org.apache.camel" % "camel-http" % CamelVersion % "compile" withSources
  val camelJetty = "org.apache.camel" % "camel-jetty" % CamelVersion % "compile" withSources
  val camelSpring = "org.apache.camel" % "camel-spring" % CamelVersion % "compile" withSources
  val activemqCore = "org.apache.activemq" % "activemq-core" % "5.3.2" % "compile" withSources

  val scalatest = "org.scalatest" % "scalatest" % "1.2" % "test"
  val junit = "junit" % "junit" % "4.8.2" % "test"
}
