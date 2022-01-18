// For the syntax of this file, see: https://www.scala-sbt.org/1.x/docs/Basic-Def.html

// This plugin is for building the docker image of our exchange svr
import scala.io.Source
import scala.sys.process._
import com.typesafe.sbt.packager.docker._

enablePlugins(JavaAppPackaging, DockerPlugin)

// For latest versions, see https://mvnrepository.com/
lazy val akkaHttpVersion = "[10.2.4]"  // as of 11/19/2019 this is the latest version
lazy val akkaVersion    = "[2.6.14]"  // released 10/2019. Version 2.6.0 was released 11/2019

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

Global / excludeLintKeys += daemonGroupGid  // was getting unused error, even tho i think they are used
Global / excludeLintKeys += dockerEnvVars

lazy val root = (project in file("."))
    .settings(
        description                   := "'Containerized exchange-api'",
        name                          := "amd64_exchange-api",
        organization                  := "com.horizon",
        release                       := versionFunc(),
        resolvers                     += Classpaths.typesafeReleases,
        scalaVersion                  := "2.13.5",
        summary                       := "'Open Horizon exchange-api image'",
        vendor                        := "'Open Horizon'",
        version                       := versionFunc(),
        //ThisBuild / scapegoatVersion := "1.4.4",
        //coverageEnabled               := false,
        

        // Sbt uses Ivy for dependency resolution, so it supports its version syntax: http://ant.apache.org/ivy/history/latest-milestone/ivyfile/dependency.html#revision
        libraryDependencies ++= Seq(
          "com.typesafe.akka" %% "akka-http"            % "[10.2.4]",
          "com.typesafe.akka" %% "akka-http-xml"        % "[10.2.4]",
          // "com.typesafe.akka" %% "akka-stream"          % "[2.6.14,)",
          // "com.typesafe.akka" %% "akka-http-spray-json" % "[10.2.1,)",
          "de.heikoseeberger" %% "akka-http-jackson" % "[1.37.0]",  // version 1.35.3 pulls in akka 2.6.10 and akkahttp 10.2.2
          // "com.typesafe.akka" %% "akka-http-jackson" % "[10.2.1,)", //<- can not find any recent documentation on how to use this
          
          "org.json4s" %% "json4s-native" % "3.6.6",  // Version 3.7.0-M3 is incompatible.
          "org.json4s" %% "json4s-jackson" % "3.6.6",  // Version 3.7.0-M3 is incompatible.
  
          "javax.ws.rs" % "javax.ws.rs-api" % "[2.1.1,)",  // this is from 8/2014. Version 2.1.1 from 9/2018 gets an error loading
          //"org.glassfish.jersey.core" % "jersey-common" % "1.2.1",  // required at runtime by javax.ws.rs-api
          "com.github.swagger-akka-http" %% "swagger-akka-http" % "[2.4.2]",  // Version 2.5.0 now requires v10.2.6 Akka modules.
          "com.github.swagger-akka-http" %% "swagger-scala-module" % "[1.0.6,)",
          "io.swagger.core.v3" % "swagger-core" % "[2.1.5,)", // Version 2.1.3 causes incompatability error with Jackson Databind -- https://mvnrepository.com/artifact/io.swagger.core.v3/swagger-core
          "io.swagger.core.v3" % "swagger-annotations" % "[2.1.5,)", // Version 2.1.3 causes incompatability error with Jackson Databind -- https://mvnrepository.com/artifact/io.swagger.core.v3/swagger-annotations
          "io.swagger.core.v3" % "swagger-models" % "[2.1.5,)", // Version 2.1.3 causes incompatability error with Jackson Databind -- https://mvnrepository.com/artifact/io.swagger.core.v3/swagger-models
          "io.swagger.core.v3" % "swagger-jaxrs2" % "[2.1.5,)", // Version 2.1.3 causes incompatability error with Jackson Databind -- https://mvnrepository.com/artifact/io.swagger.core.v3/swagger-jaxrs2
  
          "com.typesafe.slick" %% "slick" % "[3.3.3]",
          "com.typesafe.slick" %% "slick-hikaricp" % "[3.3.3]",
          "com.github.tminglei" %% "slick-pg" % "[0.19.3]",
          "com.github.tminglei" %% "slick-pg_json4s" % "[0.19.3]",
          "org.postgresql" % "postgresql" % "42.2.19",  // Version number must be manually specified to prevent Maven from selecting a jre6/7 version of this dependency.
          //"com.zaxxer" % "HikariCP" % "[3.4.5,)",
          "org.slf4j" % "slf4j-simple" % "[1.7.30]", // Needed by scalacache, slick, and swagger.
          //"ch.qos.logback" % "logback-classic" % "1.3.0-alpha5",
          "com.mchange" % "c3p0" % "[0.9.5.5,)",
          "org.scalaj" %% "scalaj-http" % "[2.4.2,)",
          "com.typesafe" % "config" % "[1.4.0,)",
          "org.mindrot" % "jbcrypt" % "[0.4,)",
          "com.pauldijou" %% "jwt-core" % "[4.3.0,)",
          "com.github.cb372" %% "scalacache-guava" % "[0.28.0,)",
          "com.osinka.i18n" %% "scala-i18n" % "[1.0.3,)",
  
          "com.typesafe.akka" %% "akka-http-testkit"    % "[10.2.4]"     % Test,
          "com.typesafe.akka" %% "akka-testkit"         % "[2.6.14]"     % Test,
          "com.typesafe.akka" %% "akka-stream-testkit"  % "[2.6.14]"     % Test,
  
          "org.scalatest" %% "scalatest" % "[3.3.0-SNAP2,)" % "test",
          "org.scalatestplus" %% "junit-4-12" % "[3.3.0.0-SNAP2,)" % "test",
          "org.scalacheck" %% "scalacheck" % "[1.15.0-M1,)" % "test",
          "junit" % "junit" % "[4.13.1,)" % "test"
        ), 
        scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
        
        // Used when running test suites with HTTPS.
        // Requires path to your PKCS #12 cryptographic store and its password.
        // fork := true,
        // javaOptions ++= Seq("-Djavax.net.ssl.trustStore=/home/someuser/git/exchange-api/target/localhost.p12", "-Djavax.net.ssl.trustStorePassword=truststore-password"),
      
        //javaOptions ++= Seq("-Djava.security.auth.login.config=src/main/resources/jaas.config", "-Djava.security.policy=src/main/resources/auth.policy")

        // These settings are for the Docker subplugin within sbt-native-packager. See: https://sbt-native-packager.readthedocs.io/en/stable/formats/docker.html
        Docker / version        := versionFunc(), // overwrite this setting to build a test version of the exchange with a custom tag in docker, defaults to exchange version
        Docker / packageName    := "openhorizon/" ++ name.value,
        Docker / daemonUser     := "exchangeuser",
        Docker / daemonGroup    := "exchangegroup",
        Docker / daemonGroupGid := some("1001"),
        dockerExposedPorts     ++= Seq(8080),
        dockerBaseImage         := "registry.access.redhat.com/ubi8-minimal:latest",
        dockerEnvVars := Map("JAVA_OPTS" -> ""),   // this is here so JAVA_OPTS can be overridden on the docker run cmd with a value like: -Xmx1G
        //dockerEntrypoint ++= Seq("-Djava.security.auth.login.config=src/main/resources/jaas.config")  // <- had trouble getting this to work
        Docker / mappings ++= Seq((baseDirectory.value / "LICENSE.txt") -> "/1/licenses/LICENSE.txt",
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
                                        Cmd("RUN", "mkdir -p /run/user/$UID && microdnf update -y --nodocs && microdnf install -y --nodocs shadow-utils gettext java-11-openjdk openssl && microdnf clean all"),
                                        Cmd("USER", "root"), 
                                        Cmd("RUN", "id -u " ++ (Docker / daemonUser).value ++ " 1>/dev/null 2>&1 || ((getent group 1001 1>/dev/null 2>&1 || (type groupadd 1>/dev/null 2>&1 && groupadd -g 1001 " ++ (Docker / daemonGroup).value ++ " || addgroup -g 1001 -S " ++ (Docker / daemonGroup).value ++ ")) && (type useradd 1>/dev/null 2>&1 && useradd --system --create-home --uid 1001 --gid 1001 " ++ (Docker / daemonUser).value ++ " || adduser -S -u 1001 -G " ++ (Docker / daemonGroup).value ++ " " ++ (Docker / daemonUser).value ++ "))"),
                                        Cmd("WORKDIR", "/etc/horizon/exchange"), 
                                        Cmd("COPY --from=stage0 --chown=" ++ (Docker / daemonUser).value ++ ":" ++ (Docker / daemonGroup).value, "/2/etc/horizon/exchange /etc/horizon/exchange"),
                                        Cmd("WORKDIR", "/licenses"), 
                                        Cmd("COPY --from=stage0 --chown=" ++ (Docker / daemonUser).value ++ ":" ++ (Docker / daemonGroup).value, "/1/licenses /licenses"),
                                        Cmd("WORKDIR", "/opt/docker"), 
                                        Cmd("COPY --from=stage0 --chown=" ++ (Docker / daemonUser).value ++ ":" ++ (Docker / daemonGroup).value, "/1/opt/docker /opt/docker"),
                                        Cmd("COPY --from=stage0 --chown=" ++ (Docker / daemonUser).value ++ ":" ++ (Docker / daemonGroup).value, "/2/opt/docker /opt/docker"),
                                        Cmd("ENV", "JAVA_OPTS=''"),
                                        Cmd("ENV", "ENVSUBST_CONFIG=''"),
                                        Cmd("EXPOSE", "8080"),
                                        Cmd("EXPOSE", "8083"),
                                        Cmd("USER", "1001:1001"), 
                                        /*
                                         * If bind-mounting your own config.json rename the configuration file in the container's filesystem to exchange-api.tmpl. This will overwrite the 
                                         * exchange-api.tmpl provided in this docker image and prevent cases where a bind-mount config.json is set with read-only permissions. 
                                         * Any mounted config.json can choose to use variables to take advantage of the substitution below.
                                         */
                                        Cmd("ENTRYPOINT", "/usr/bin/envsubst $ENVSUBST_CONFIG < /etc/horizon/exchange/exchange-api.tmpl > /etc/horizon/exchange/config.json && /opt/docker/bin/" ++ name.value),
                                        Cmd("CMD", "[]")
                                       )
       )
