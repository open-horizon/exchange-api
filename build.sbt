// For the syntax of this file, see: https://www.scala-sbt.org/1.x/docs/Basic-Def.html

// Note: i tried updating sbt to 1.3.5, but got "java.lang.NoClassDefFoundError: org/scalacheck/Test$TestCallback" when running the automated tests, and couldn't solve it.
//   Looking at https://github.com/sbt/sbt/releases , it's clear there are significant changes in 1.3.x, including with the class loader.

// This plugin is for building the docker image of our exchange svr
import scala.io.Source
import scala.sys.process._
import com.typesafe.sbt.packager.docker._

enablePlugins(JavaAppPackaging, DockerPlugin)

// For latest versions, see https://mvnrepository.com/
lazy val akkaHttpVersion = "10.1.10"  // as of 11/19/2019 this is the latest version
lazy val akkaVersion    = "2.5.26"  // released 10/2019. Version 2.6.0 was released 11/2019

// Red Hat certification Docker labels.
lazy val release = settingKey[String]("A number used to identify the specific build for this image.")
lazy val summary = settingKey[String]("A short overview of the application or component in this image.")
lazy val vendor  = settingKey[String]("Company name.")

val versionFunc = () => {
  val versFile = Source.fromFile("src/main/resources/version.txt")
  val versText = versFile.getLines.next()
  versFile.close()
  versText
}

lazy val root = (project in file("."))
    .settings(
        description          := "'Containerized exchange-api'", 
        name                 := "amd64_exchange-api", 
        organization         := "com.horizon", 
        release              := "4.1.0", 
        resolvers            += Classpaths.typesafeReleases, 
        scalaVersion         := "2.12.10",     // tried updating to scala 2.13.1, but got many compile errors in intellij related to JavaConverters being deprecated
        summary              := "'Open Horizon exchange-api image'", 
        vendor               := "IBM", 
        version              := versionFunc(), 
        scapegoatVersion in ThisBuild := "1.4.4", 
        coverageEnabled      := false, 
        

        // Sbt uses Ivy for dependency resolution, so it supports its version syntax: http://ant.apache.org/ivy/history/latest-milestone/ivyfile/dependency.html#revision
        libraryDependencies ++= Seq(
            "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion, 
            "com.typesafe.akka" %% "akka-http-xml"        % akkaHttpVersion, 
            "com.typesafe.akka" %% "akka-stream"          % akkaVersion, 
            //"com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
            "de.heikoseeberger" %% "akka-http-jackson" % "1.29.1",  // version 1.30.0 pulls in akka 2.6.1 and akkahttp 10.1.11
            //"com.typesafe.akka" %% "akka-http-jackson" % akkaHttpVersion, <- can not find any recent documentation on how to use this

            "org.json4s" %% "json4s-native" % "3.7.0-M2",  // Version 3.7.0-M3 is incompatible.
            "org.json4s" %% "json4s-jackson" % "3.7.0-M2",  // Version 3.7.0-M3 is incompatible.

            "javax.ws.rs" % "javax.ws.rs-api" % "2.0.1",  // this is from 8/2014. Version 2.1.1 from 9/2018 gets an error loading
            "org.glassfish.jersey.core" % "jersey-common" % "latest.release",  // required at runtime by javax.ws.rs-api
            "com.github.swagger-akka-http" %% "swagger-akka-http" % "2.0.4",  // Version 2.0.5 now requires v10.1.11 Akka modules.
            "com.github.swagger-akka-http" %% "swagger-scala-module" % "latest.release",
            "io.swagger.core.v3" % "swagger-core" % "2.1.2", // Version 2.1.3 causes incompatability error with Jackson Databind -- https://mvnrepository.com/artifact/io.swagger.core.v3/swagger-core
            "io.swagger.core.v3" % "swagger-annotations" % "2.1.2", // Version 2.1.3 causes incompatability error with Jackson Databind -- https://mvnrepository.com/artifact/io.swagger.core.v3/swagger-annotations
            "io.swagger.core.v3" % "swagger-models" % "2.1.2", // Version 2.1.3 causes incompatability error with Jackson Databind -- https://mvnrepository.com/artifact/io.swagger.core.v3/swagger-models
            "io.swagger.core.v3" % "swagger-jaxrs2" % "2.1.2", // Version 2.1.3 causes incompatability error with Jackson Databind -- https://mvnrepository.com/artifact/io.swagger.core.v3/swagger-jaxrs2

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
            "org.scalatestplus" %% "junit-4-12" % "latest.release" % "test", 
            "org.scalacheck" %% "scalacheck" % "latest.release" % "test", 
            "junit" % "junit" % "latest.release" % "test"
        ), 
        scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
        //javaOptions ++= Seq("-Djava.security.auth.login.config=src/main/resources/jaas.config", "-Djava.security.policy=src/main/resources/auth.policy")

        // These settings are for the Docker subplugin within sbt-native-packager. See: https://sbt-native-packager.readthedocs.io/en/stable/formats/docker.html
        packageName in Docker    := "openhorizon/" ++ name.value, 
        daemonUser in Docker     := "exchangeuser", 
        daemonGroup in Docker    := "exchangegroup", 
        daemonGroupGid in Docker := some("1001"), 
        dockerExposedPorts      ++= Seq(8080), 
        dockerBaseImage          := "registry.access.redhat.com/ubi8-minimal:latest",
        dockerEnvVars            := Map("JAVA_OPTS" -> ""),   // this is here so JAVA_OPTS can be overridden on the docker run cmd with a value like: -Xmx1G
        //dockerEntrypoint ++= Seq("-Djava.security.auth.login.config=src/main/resources/jaas.config")  // <- had trouble getting this to work
        mappings in Docker ++= Seq((baseDirectory.value / "LICENSE.txt") -> "/1/licenses/LICENSE.txt", 
                                   (baseDirectory.value / "config" / "exchange-api.tmpl") -> "/2/etc/horizon/exchange/exchange-api.tmpl"
                                  ), 
        dockerCommands           := Seq(Cmd("FROM", dockerBaseImage.value ++ " as stage0"), 
                                        Cmd("LABEL", "snp-multi-stage='intermediate'"), 
                                        Cmd("LABEL", "snp-multi-stage-id='6466ecf3-c305-40bb-909a-47e60bded33d'"), 
                                        Cmd("WORKDIR", "/etc/horizon/exchange"), 
                                        Cmd("COPY", "2/etc/horizon/exchange /2/etc/horizon/exchange"), 
                                        Cmd("RUN", "> /2/etc/horizon/exchange/config.json"), 
                                        Cmd("WORKDIR", "/licenses"), 
                                        Cmd("COPY", "1/licenses /1/licenses"), 
                                        Cmd("WORKDIR", "/opt/docker"), 
                                        Cmd("COPY", "1/opt /1/opt"), 
                                        Cmd("COPY", "2/opt /2/opt"), 
                                        Cmd("USER", "root"), 
                                        Cmd("RUN", "chmod -R u=r,g=r /2/etc/horizon /licenses && chmod -R u+w,g+w /2/etc/horizon/exchange && chmod -R u=rX,g=rX /1/opt/docker /2/opt/docker && chmod u+x,g+x /1/opt/docker/bin/" ++ name.value), 
                                        Cmd("FROM", dockerBaseImage.value), 
                                        Cmd("LABEL", "description=" ++ description.value), 
                                        //Cmd("LABEL", "io.k8s.description=''"), 
                                        //Cmd("LABEL", "io.k8s.display-name=''"), 
                                        //Cmd("LABEL", "io.openshift.tags=''"), 
                                        Cmd("LABEL", "name=" ++ name.value), 
                                        Cmd("LABEL", "release=" ++ release.value), 
                                        Cmd("LABEL", "summary=" ++ summary.value), 
                                        Cmd("LABEL", "vendor=" ++ vendor.value), 
                                        Cmd("LABEL", "version=" ++ version.value), 
                                        Cmd("RUN", "mkdir -p /run/user/$UID && microdnf update -y --nodocs && microdnf install -y --nodocs shadow-utils gettext java-1.8.0-openjdk && microdnf clean all"), 
                                        Cmd("USER", "root"), 
                                        Cmd("RUN", "id -u " ++ (daemonUser in Docker).value ++ " 1>/dev/null 2>&1 || ((getent group 1001 1>/dev/null 2>&1 || (type groupadd 1>/dev/null 2>&1 && groupadd -g 1001 " ++ (daemonGroup in Docker).value ++ " || addgroup -g 1001 -S " ++ (daemonGroup in Docker).value ++ ")) && (type useradd 1>/dev/null 2>&1 && useradd --system --create-home --uid 1001 --gid 1001 " ++ (daemonUser in Docker).value ++ " || adduser -S -u 1001 -G " ++ (daemonGroup in Docker).value ++ " " ++ (daemonUser in Docker).value ++ "))"), 
                                        Cmd("WORKDIR", "/etc/horizon/exchange"), 
                                        Cmd("COPY --from=stage0 --chown=" ++ (daemonUser in Docker).value ++ ":" ++ (daemonGroup in Docker).value, "/2/etc/horizon/exchange /etc/horizon/exchange"), 
                                        Cmd("WORKDIR", "/licenses"), 
                                        Cmd("COPY --from=stage0 --chown=" ++ (daemonUser in Docker).value ++ ":" ++ (daemonGroup in Docker).value, "/1/licenses /licenses"), 
                                        Cmd("WORKDIR", "/opt/docker"), 
                                        Cmd("COPY --from=stage0 --chown=" ++ (daemonUser in Docker).value ++ ":" ++ (daemonGroup in Docker).value, "/1/opt/docker /opt/docker"), 
                                        Cmd("COPY --from=stage0 --chown=" ++ (daemonUser in Docker).value ++ ":" ++ (daemonGroup in Docker).value, "/2/opt/docker /opt/docker"), 
                                        Cmd("ENV", "JAVA_OPTS=''"), 
                                        Cmd("EXPOSE", "8080"), 
                                        Cmd("USER", "1001:1001"), 
                                        /*
                                         * If bind-mounting your own config.json rename the configuration file in the container's filesystem to exchange-api.tmpl. This will overwrite the 
                                         * provided exchange-api.tmpl provided in this docker image and prevent cases where a bind-mount config.json is set with read-only permissions. 
                                         * Any mounted config.json can choose to use variables to take advantage of the substitution below.
                                         */
                                        Cmd("ENTRYPOINT", "/usr/bin/envsubst < /etc/horizon/exchange/exchange-api.tmpl > /etc/horizon/exchange/config.json && /opt/docker/bin/" ++ name.value), 
                                        Cmd("CMD", "[]")
                                       )
       )
