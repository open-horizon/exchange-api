lazy val scalatraVersion = "2.6.3"    // can see the latest version at https://github.com/scalatra/scalatra/releases

lazy val root = (project in file(".")).
  settings(
    organization := "com.horizon",
    name := "Exchange API",
    version := "0.1.0",
    scalaVersion := "2.12.7",
    resolvers += Classpaths.typesafeReleases,
    libraryDependencies ++= Seq(
      "org.scalatra" %% "scalatra" % "latest.release",
      "org.scalatra" %% "scalatra-auth" % "latest.release",
      "org.scalatra" %% "scalatra-specs2" % "latest.release" % "test",
      "com.typesafe.slick" %% "slick" % "latest.release",
      "com.typesafe.slick" %% "slick-hikaricp" % "latest.release",
      "org.postgresql" % "postgresql" % "latest.release",
      "com.zaxxer" % "HikariCP" % "latest.release",
      //"com.zaxxer" % "HikariCP" % "2.5.1",
      //"org.slf4j" % "slf4j-api" % "latest.release",  // they put version 1.8.0-alpha2 prematurely into latest.release
      "org.slf4j" % "slf4j-api" % "1.7.25",
      //"ch.qos.logback" % "logback-classic" % "latest.release",  // they put version 1.3.0-alpha2 prematurely into latest.release
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.mchange" % "c3p0" % "latest.release",
      "javax.servlet" % "javax.servlet-api" % "latest.release" % "provided",
      "org.eclipse.jetty" % "jetty-webapp" % "latest.release" % "container",
      "org.eclipse.jetty" % "jetty-plus" % "latest.release" % "container",
      "org.scalatra" %% "scalatra-json" % "latest.release",
      "org.json4s" %% "json4s-native" % "latest.release",
      "org.json4s" %% "json4s-jackson" % "latest.release",
      //"org.json4s" %% "json4s-native" % "3.5.3",
      //"org.json4s" %% "json4s-jackson" % "3.5.3",
      "org.scalatra" %% "scalatra-swagger"  % "latest.release",
      "org.scalatest" %% "scalatest" % "latest.release" % "test",
      "org.scalacheck" %% "scalacheck" % "latest.release" % "test",
      "junit" % "junit" % "latest.release" % "test",
      "org.scalaj" %% "scalaj-http" % "latest.release",
      "com.typesafe" % "config" % "latest.release",
      "org.mindrot" % "jbcrypt" % "latest.release",
      "com.pauldijou" %% "jwt-core" % "latest.release",
      "javax.mail" % "javax.mail-api" % "latest.release"
      //"com.sun.mail" % "javax.mail" % "latest.release"
    ),
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")
  ).
  enablePlugins(JettyPlugin)
