lazy val scalatraVersion = "2.6.5"    // can see the latest version at https://github.com/scalatra/scalatra/releases

lazy val root = (project in file(".")).
  settings(
    organization := "com.horizon",
    name := "Exchange API",
    version := "0.1.0",
    scalaVersion := "2.12.7",
    resolvers += Classpaths.typesafeReleases,
    // Sbt uses Ivy for dependency resolution, so it supports its version syntax: http://ant.apache.org/ivy/history/latest-milestone/ivyfile/dependency.html#revision
    libraryDependencies ++= Seq(
      "org.scalatra" %% "scalatra" % "2.6.5",
      "org.scalatra" %% "scalatra-auth" % "2.6.5",
      "org.scalatra" %% "scalatra-specs2" % "2.6.5" % "test",
      "org.scalatra" %% "scalatra-json" % "2.6.5",
      //"org.scalatra" %% "scalatra-swagger"  % "latest.release",  <- the new 2.7.x release requires some swagger changes in our code, see issue 173
      "org.scalatra" %% "scalatra-swagger"  % "2.6.5",
      "com.typesafe.slick" %% "slick" % "latest.release",
      "com.typesafe.slick" %% "slick-hikaricp" % "latest.release",
      //"com.github.tminglei" %% "slick-pg" % "0.16.3",
      //"com.github.tminglei" %% "slick-pg_json4s" % "0.16.3",
      "com.github.tminglei" %% "slick-pg" % "latest.release",
      "com.github.tminglei" %% "slick-pg_json4s" % "latest.release",
      "org.postgresql" % "postgresql" % "latest.release",
      "com.zaxxer" % "HikariCP" % "latest.release",
      //"com.zaxxer" % "HikariCP" % "2.5.1",
      //"org.slf4j" % "slf4j-api" % "latest.stable",  // they put version 1.8.0-beta4 prematurely into latest.release
      "org.slf4j" % "slf4j-api" % "1.7.26",
      //"ch.qos.logback" % "logback-classic" % "latest.stable",  // they put version 1.3.0-alpha4 prematurely into latest.release
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.mchange" % "c3p0" % "latest.release",
      "javax.servlet" % "javax.servlet-api" % "latest.release" % "provided",
      "org.eclipse.jetty" % "jetty-webapp" % "latest.release" % "container",
      "org.eclipse.jetty" % "jetty-plus" % "latest.release" % "container",
      //"org.eclipse.jetty" % "jetty-webapp" % "latest.release",
      //"org.eclipse.jetty" % "jetty-plus" % "latest.release",
      "org.json4s" %% "json4s-native" % "latest.release",
      "org.json4s" %% "json4s-jackson" % "latest.release",
      // For a while using latest for json4s was causing it to select 3.5.3, and that resulted in swagger throwing: NoSuchMethodError: org.json4s.JsonDSL$.seq2jvalue
      //"org.json4s" %% "json4s-native" % "3.5.2",
      //"org.json4s" %% "json4s-jackson" % "3.5.2",
      "org.scalatest" %% "scalatest" % "latest.release" % "test",
      "org.scalacheck" %% "scalacheck" % "latest.release" % "test",
      "junit" % "junit" % "latest.release" % "test",
      "org.scalaj" %% "scalaj-http" % "latest.release",
      "com.typesafe" % "config" % "latest.release",
      "org.mindrot" % "jbcrypt" % "latest.release",
      //"com.pauldijou" %% "jwt-core" % "latest.release", // <- version 3.0.0 get a class loader exception
      "com.pauldijou" %% "jwt-core" % "2.1.0",
      //"com.github.cb372" %% "scalacache-guava" % "0.26.0",
      "com.github.cb372" %% "scalacache-guava" % "latest.release",
      //"org.joda" % "joda-convert" % "2.1.2",
      //"javax.mail" % "javax.mail-api" % "latest.release"
      "com.osinka.i18n" %% "scala-i18n" % "1.0.3"
    ),
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
    javaOptions ++= Seq("-Djava.security.auth.login.config=src/main/resources/jaas.config", "-Djava.security.policy=src/main/resources/auth.policy")
  ).
  enablePlugins(JettyPlugin)
