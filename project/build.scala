import sbt._
import Keys._
import org.scalatra.sbt._
import org.scalatra.sbt.PluginKeys._
import com.earldouglas.xwp.JettyPlugin
// import com.mojolly.scalate.ScalatePlugin._
// import ScalateKeys._

object ScalatraSlickBuild extends Build {
  val Organization = "com.horizon"
  val Name = "Exchange API"
  val Version = "0.1.0"
  val ScalaVersion = "2.11.8"
  val ScalatraVersion = "2.4.1"

  lazy val project = Project (
    "exchange-api",
    file("."),
    // settings = ScalatraPlugin.scalatraSettings ++ scalateSettings ++ Seq(
    settings = ScalatraPlugin.scalatraSettings ++ Seq(
      organization := Organization,
      name := Name,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers += Classpaths.typesafeReleases,
      resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases",
      libraryDependencies ++= Seq(
        "org.scalatra" %% "scalatra" % "latest.integration",
        // "org.scalatra" %% "scalatra-scalate" % "latest.integration",
        "org.scalatra" %% "scalatra-auth" % "latest.integration",
        "org.scalatra" %% "scalatra-specs2" % "latest.integration" % "test",
        "com.typesafe.slick" %% "slick" % "latest.integration",
        "com.typesafe.slick" %% "slick-hikaricp" % "latest.integration",
        "org.postgresql" % "postgresql" % "latest.integration",
        "com.zaxxer" % "HikariCP" % "latest.integration",
        "org.slf4j" % "slf4j-api" % "latest.integration",
        // "ch.qos.logback" % "logback-classic" % "latest.integration" % "runtime",
        "ch.qos.logback" % "logback-classic" % "latest.integration",
        // "org.slf4j" % "slf4j-simple" % "latest.integration",
        // "org.slf4j" % "slf4j-log4j12" % "latest.integration",
        "com.mchange" % "c3p0" % "latest.integration",
        "javax.servlet" % "javax.servlet-api" % "latest.integration" % "provided",
        "org.eclipse.jetty" % "jetty-webapp" % "latest.integration" % "container",
        "org.eclipse.jetty" % "jetty-plus" % "latest.integration" % "container",
        "org.scalatra" %% "scalatra-json" % "latest.integration",
        "org.json4s" %% "json4s-native" % "latest.integration",
        "org.json4s" %% "json4s-jackson" % "latest.integration",
        "org.scalatra" %% "scalatra-swagger"  % "latest.integration",
        "org.scalatest" %% "scalatest" % "latest.integration" % "test",
        "junit" % "junit" % "latest.integration" % "test",
        "org.scalaj" %% "scalaj-http" % "latest.integration",
        "com.typesafe" % "config" % "latest.integration",
        "org.mindrot" % "jbcrypt" % "latest.integration",
        "com.pauldijou" %% "jwt-core" % "latest.integration",
        "javax.mail" % "javax.mail-api" % "latest.integration",
        "com.sun.mail" % "javax.mail" % "latest.integration"
      ),
      scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")
      // scalacOptions += "-deprecation",
      /*
      scalateTemplateConfig in Compile <<= (sourceDirectory in Compile){ base =>
        Seq(
          TemplateConfig(
            base / "webapp" / "WEB-INF" / "templates",
            Seq.empty,  /* default imports should be added here */
            Seq(
              Binding("context", "_root_.org.scalatra.scalate.ScalatraRenderContext", importMembers = true, isImplicit = true)
            ),  /* add extra bindings here */
            Some("templates")
          )
        )
      }
      */
    )
  ).enablePlugins(JettyPlugin)
}
