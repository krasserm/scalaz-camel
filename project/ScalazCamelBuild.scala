import sbt._
import Keys._

object ScalazCamelBuild extends Build {

  lazy val buildSettings = Seq(
    organization := "scalaz.camel",
    version := "0.4-SNAPSHOT",
    scalaVersion := "2.9.2"
  )

  override lazy val settings = super.settings ++ buildSettings
  lazy val baseSettings = Defaults.defaultSettings
  lazy val defaultSettings = baseSettings ++ Seq(
    resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
    resolvers += "Akka Repository" at "http://akka.io/repository",
    // compile options
    scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-unchecked") ++ (
      if (true || (System getProperty "java.runtime.version" startsWith "1.7")) Seq() else Seq("-optimize")), // -optimize fails with jdk7
    javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation")
  )

  object Dependencies {

    object V {
      val Scalaz = "6.0.4"
      val Camel = "2.9.2"
      val Akka = "1.3"
      val ActiveMQ = "5.6.0"
      val Slf4j = "1.6.4"
      val ScalaTest = "1.7.2"
      val Junit = "4.8.2"
    }

    lazy val scalazCore = "org.scalaz" %% "scalaz-core" % V.Scalaz
    lazy val camelCore = "org.apache.camel" % "camel-core" % V.Camel
    lazy val camelJms = "org.apache.camel" % "camel-jms" % V.Camel
    lazy val cameHttp = "org.apache.camel" % "camel-http" % V.Camel
    lazy val camelJetty = "org.apache.camel" % "camel-jetty" % V.Camel
    lazy val camelSpring = "org.apache.camel" % "camel-spring" % V.Camel
    lazy val akkaCamel = "se.scalablesolutions.akka" % "akka-camel" % V.Akka

    lazy val activemqCore = "org.apache.activemq" % "activemq-core" % V.ActiveMQ
    lazy val slf4jSimple = "org.slf4j" % "slf4j-simple" % V.Slf4j
    lazy val scalatest = "org.scalatest" %% "scalatest" % V.ScalaTest
    lazy val junit = "junit" % "junit" % V.Junit
  }

  lazy val root = Project("root", file(".")) aggregate(core, actor, samples)

  lazy val core = Project(
    id = "scalaz-camel-core",
    base = file("scalaz-camel-core"),
    settings = defaultSettings ++ Seq(libraryDependencies ++= Seq(
      Dependencies.scalazCore % "compile", Dependencies.camelCore % "compile",
      Dependencies.camelJms % "test", Dependencies.cameHttp % "test",
      Dependencies.camelJetty % "test", Dependencies.camelSpring % "test",
      Dependencies.activemqCore % "test", Dependencies.slf4jSimple % "test",
      Dependencies.scalatest % "test", Dependencies.junit % "test"))
  )

  lazy val actor = Project(
    id = "scalaz-camel-akka",
    base = file("scalaz-camel-akka"),
    dependencies = Seq(core),
    settings = defaultSettings ++ Seq(libraryDependencies ++= Seq(
      Dependencies.camelCore % "compile", Dependencies.akkaCamel,
      Dependencies.scalatest % "test", Dependencies.slf4jSimple % "test"))
  )

  lazy val samples = Project(
    id = "scalaz-camel-samples",
    base = file("scalaz-camel-samples"),
    dependencies = Seq(core),
    settings = defaultSettings ++ Seq(libraryDependencies ++= Seq(
      Dependencies.scalazCore % "compile", Dependencies.camelCore % "compile",
      Dependencies.camelJetty % "test", Dependencies.camelSpring % "compile",
      Dependencies.camelJms % "test", Dependencies.camelJms % "test",
      Dependencies.camelJetty % "test", Dependencies.camelSpring % "compile",
      Dependencies.activemqCore % "test", Dependencies.slf4jSimple % "test",
      Dependencies.scalatest % "test", Dependencies.junit % "test"))
  )

}