lazy val scalatraVersion = "2.5.1"    // can see the latest version at https://github.com/scalatra/scalatra/blob/2.5.x/version.sbt

lazy val root = (project in file(".")).
  settings(
    organization := "com.horizon",
    name := "Exchange API",
    version := "0.1.0",
    scalaVersion := "2.12.1",
    resolvers += Classpaths.typesafeReleases,
    libraryDependencies ++= Seq(
      "org.scalatra" %% "scalatra" % "latest.integration",
      "org.scalatra" %% "scalatra-auth" % "latest.integration",
      "org.scalatra" %% "scalatra-specs2" % "latest.integration" % "test",
      "com.typesafe.slick" %% "slick" % "latest.integration",
      "com.typesafe.slick" %% "slick-hikaricp" % "latest.integration",
      "org.postgresql" % "postgresql" % "latest.integration",
      "com.zaxxer" % "HikariCP" % "latest.integration",
      "org.slf4j" % "slf4j-api" % "latest.integration",
      "ch.qos.logback" % "logback-classic" % "latest.integration",
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
  ).
  enablePlugins(JettyPlugin)
