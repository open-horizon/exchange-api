// For the syntax of this file, see: https://www.scala-sbt.org/1.x/docs/Basic-Def.html

// Note: i tried updating sbt to 1.3.5, but got "java.lang.NoClassDefFoundError: org/scalacheck/Test$TestCallback" when running the automated tests, and couldn't solve it.
//   Looking at https://github.com/sbt/sbt/releases , it's clear there are significant changes in 1.3.x, including with the class loader.

// This plugin is for building the docker image of our exchange svr
enablePlugins(JavaAppPackaging, DockerPlugin)

// For latest versions, see https://mvnrepository.com/
lazy val akkaHttpVersion = "10.1.10"  // as of 11/19/2019 this is the latest version
lazy val akkaVersion    = "2.5.26"  // released 10/2019. Version 2.6.0 was released 11/2019

import scala.io.Source
val versionFunc = () => {
  val versFile = Source.fromFile("src/main/resources/version.txt")
  val versText = versFile.getLines.next()
  versFile.close()
  versText
}

lazy val root = (project in file(".")).
  settings(
    //inThisBuild(List( // <- this is to have global settings across multiple sub-projects, but we only have 1 project
    organization    := "com.horizon",
    scalaVersion    := "2.12.10",  // tried updating to scala 2.13.1, but got many compile errors in intellij related to JavaConverters being deprecated
    //)),
    name := "Exchange API",
    version := versionFunc(),
    //version := "2.0.0",
    resolvers += Classpaths.typesafeReleases,

    // Sbt uses Ivy for dependency resolution, so it supports its version syntax: http://ant.apache.org/ivy/history/latest-milestone/ivyfile/dependency.html#revision
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-xml"        % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-stream"          % akkaVersion,
      //"com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
      "de.heikoseeberger" %% "akka-http-jackson" % "1.29.1",  // version 1.30.0 pulls in akka 2.6.1 and akkahttp 10.1.11
      //"com.typesafe.akka" %% "akka-http-jackson" % akkaHttpVersion, <- can not find any recent documentation on how to use this

      "org.json4s" %% "json4s-native" % "latest.release",
      "org.json4s" %% "json4s-jackson" % "latest.release",

      "javax.ws.rs" % "javax.ws.rs-api" % "2.0.1",  // this is from 8/2014. Version 2.1.1 from 9/2018 gets an error loading
      "org.glassfish.jersey.core" % "jersey-common" % "latest.release",  // required at runtime by javax.ws.rs-api
      "com.github.swagger-akka-http" %% "swagger-akka-http" % "latest.release", // the 9/2019 versions of these 2 cause incompatible warings,but the 6/2019 versions give exceptions
      "com.github.swagger-akka-http" %% "swagger-scala-module" % "latest.release",
      "io.swagger.core.v3" % "swagger-core" % "latest.release",
      "io.swagger.core.v3" % "swagger-annotations" % "latest.release",
      "io.swagger.core.v3" % "swagger-models" % "latest.release",
      "io.swagger.core.v3" % "swagger-jaxrs2" % "latest.release",

      "com.typesafe.slick" %% "slick" % "latest.release",
      "com.typesafe.slick" %% "slick-hikaricp" % "latest.release",
      "com.github.tminglei" %% "slick-pg" % "latest.release",
      "com.github.tminglei" %% "slick-pg_json4s" % "latest.release",
      "org.postgresql" % "postgresql" % "latest.release",
      "com.zaxxer" % "HikariCP" % "latest.release",
      "org.slf4j" % "slf4j-api" % "1.7.26", // these 2 seem to be needed by slick
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.mchange" % "c3p0" % "latest.release",
      "org.scalaj" %% "scalaj-http" % "latest.release",
      "com.typesafe" % "config" % "latest.release",
      "org.mindrot" % "jbcrypt" % "latest.release",
      "com.pauldijou" %% "jwt-core" % "latest.release",
      "com.github.cb372" %% "scalacache-guava" % "latest.release",
      "com.osinka.i18n" %% "scala-i18n" % "latest.release",

      "com.typesafe.akka" %% "akka-http-testkit"    % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-testkit"         % akkaVersion     % Test,
      "com.typesafe.akka" %% "akka-stream-testkit"  % akkaVersion     % Test,

      "org.scalatest" %% "scalatest" % "latest.release" % "test",
      "org.scalacheck" %% "scalacheck" % "latest.release" % "test",
      "junit" % "junit" % "latest.release" % "test"
    ),
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
    //javaOptions ++= Seq("-Djava.security.auth.login.config=src/main/resources/jaas.config", "-Djava.security.policy=src/main/resources/auth.policy")

    // These settings are for the sbt-native-packager plugin building the docker image. See: https://sbt-native-packager.readthedocs.io/en/stable/formats/docker.html
    packageName in Docker := "openhorizon/amd64_exchange-api",
    daemonUser in Docker := "exchangeuser",
    dockerExposedPorts ++= Seq(8080),
    dockerBaseImage := "openjdk:8-jre"
    //dockerEntrypoint ++= Seq("-Djava.security.auth.login.config=src/main/resources/jaas.config")
  )
