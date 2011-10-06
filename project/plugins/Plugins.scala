import sbt._

class Plugins(info: ProjectInfo) extends PluginDefinition(info) {
  val sbtIdeaRepo = "sbt-idea-repo" at "http://mpeltonen.github.com/maven/"
  val sbtIdea = "com.github.mpeltonen" % "sbt-idea-plugin" % "0.2.0"

  val akkaRepo = "Akka Repository" at "http://akka.io/repository/"
  val akkaPlugin = "se.scalablesolutions.akka" % "akka-sbt-plugin" % "1.2"
}
