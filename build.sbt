// For the syntax of this file, see: https://www.scala-sbt.org/1.x/docs/Basic-Def.html

// This plugin is for building the docker image of our exchange svr
import scala.io.Source
import scala.sys.process.*
import com.typesafe.sbt.packager.docker.*

enablePlugins(JavaAppPackaging, DockerPlugin)

// Maintains test suite isolation for GET .../v1/admin/status testcases.
addCommandAlias("onlyAdminStatusTests", """set root / Test / testOptions -= Tests.Argument("-l", "org.openhorizon.exchangeapi.tag.AdminStatusTest"); testOnly org.openhorizon.exchangeapi.route.administration.TestGetAdminStatus -- -n org.openhorizon.exchangeapi.tag.AdminStatusTest""".stripMargin)

// For latest versions, see https://mvnrepository.com/
lazy val pekkoHttpVersion = settingKey[String]("Version of Pekko-Http")
lazy val pekkoVersion     = settingKey[String]("Version of Pekko")

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

//Global / envVars := Map("HZN_ORG_ID" -> "mycluster")

lazy val root = (project in file("."))
  .settings(
    description                   := "'Containerized exchange-api'",
    name                          := "amd64_exchange-api",
    organization                  := "org.openhorizon",
    pekkoHttpVersion              := "[1.2.0]",
    pekkoVersion                  := "[1.1.3]",
    release                       := sys.env.getOrElse("GIT_SHORT_SHA", versionFunc()),
    resolvers                     += Classpaths.typesafeReleases,
    scalaVersion                  := "2.13.16",
    summary                       := "'Open Horizon exchange-api image'",
    vendor                        := "'Open Horizon'",
    version                       := sys.env.getOrElse("IMAGE_VERSION", versionFunc()),
    // ThisBuild / scapegoatVersion := "1.4.4",
    // coverageEnabled               := false,
    
    
    // Sbt uses Ivy for dependency resolution, so it supports its version syntax: http://ant.apache.org/ivy/history/latest-milestone/ivyfile/dependency.html#revision
    libraryDependencies ++= Seq(
      "com.github.pjfanning" %% "pekko-http-jackson" % "[3.2.0,)",
      "org.apache.pekko"     %% "pekko-http"         % pekkoHttpVersion.value,
      "org.apache.pekko"     %% "pekko-http-xml"     % pekkoHttpVersion.value,
      // "org.apache.pekko"     %% "pekko-http-caching" % pekkoHttpVersion.value,
      "org.apache.pekko"     %% "pekko-http-cors"    % pekkoHttpVersion.value,
      "org.apache.pekko"     %% "pekko-slf4j"        % pekkoVersion.value,
      "org.apache.pekko"     %% "pekko-protobuf-v3"  % pekkoVersion.value,
      "org.apache.pekko"     %% "pekko-stream"       % pekkoVersion.value,

      "org.springframework.security" % "spring-security-core" % "[6.5.0,)",
      "org.bouncycastle" % "bcprov-jdk18on" % "[1.80,)",
      
      //"org.pac4j" % "pac4j-oauth" % "6.1.2",
      //"org.pac4j" % "pac4j-oidc"  % "6.1.2",

      "org.json4s" %% "json4s-native"  % "4.0.6",
      "org.json4s" %% "json4s-jackson" % "4.0.6",
      
      "jakarta.ws.rs" % "jakarta.ws.rs-api" % "[3.1.0]",
      "com.github.swagger-akka-http" %% "swagger-pekko-http" % "[2.14.0]",
      
      "ch.qos.logback" % "logback-classic" % "[1.5.18,)",
      "com.typesafe.slick" %% "slick-hikaricp" % "[3.4.1]",       // Version 3.4.1 depends on slick-pg and slick-pg_json4s v0.21.0
      "com.github.tminglei" %% "slick-pg_json4s" % "[0.21.0]",    // Version 0.21.0 depends on version 3.4.0 of slick and slick-hikaricp
      "org.postgresql" % "postgresql" % "[42.7.6,)",
      "org.scalaj" %% "scalaj-http" % "[2.4.2]",                  // Deprecated as of April 2022, in v2.4.2
      "com.typesafe" % "config" % "[1.4.3,)",
      "com.github.cb372" %% "scalacache-caffeine" % "[0.28.0]",
      "com.osinka.i18n" %% "scala-i18n" % "[1.1.0,)",
      
      "org.apache.pekko" %% "pekko-http-testkit"    % pekkoHttpVersion.value  % Test,
      "org.apache.pekko" %% "pekko-testkit"         % pekkoVersion.value      % Test,
      "org.apache.pekko" %% "pekko-stream-testkit"  % pekkoVersion.value      % Test,
      
      "org.scalatest" %% "scalatest" % "[3.3.0-SNAP4,)" % Test,
      "org.scalatestplus" %% "junit-4-12" % "[3.3.0.0-SNAP2,)" % Test,
      "org.scalacheck" %% "scalacheck" % "[1.18.1,)" % Test,
      "junit" % "junit" % "[4.13.2,)" % Test
    ),
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"),
    javacOptions ++= Seq("-source", "21", "-target", "21", "-Xlint"),
    //javaOptions ++= Seq("-Dconfig.file=/home/naphelps/git/exchange-api/target/config.json"),
    fork := true,
    Test / javaOptions ++= Seq("--add-opens", "java.base/java.net=ALL-UNNAMED"),
    Test / testOptions += Tests.Argument("-l", "org.openhorizon.exchangeapi.tag.AdminStatusTest"), // No test suite isolation.
    // Used when running test suites with HTTPS.
    // Requires path to your PKCS #12 cryptographic store and its password.
    // fork := true,
    // javaOptions ++= Seq("-Djavax.net.ssl.trustStore=/home/someuser/git/exchange-api/target/localhost.p12", "-Djavax.net.ssl.trustStorePassword=truststore-password"),
    
    //javaOptions ++= Seq("-Djava.security.auth.login.config=src/main/resources/jaas.config", "-Djava.security.policy=src/main/resources/auth.policy")
    
    // These settings are for the Docker subplugin within sbt-native-packager. See: https://sbt-native-packager.readthedocs.io/en/stable/formats/docker.html
    Docker / version        := sys.env.getOrElse("IMAGE_VERSION", versionFunc()), // overwrite this setting to build a test version of the exchange with a custom tag in docker, defaults to exchange version
    Docker / packageName    := sys.env.getOrElse("CONTAINER_REGISTRY", "openhorizon") ++ "/" ++ name.value,
    Docker / daemonUser     := "exchangeuser",
    Docker / daemonUserUid  := Some("1001"),
    Docker / daemonGroup    := "exchangegroup",
    Docker / daemonGroupGid := some("1001"),
    dockerExposedPorts     ++= Seq(8080),
    dockerBaseImage         := "${BASE_IMAGE_REGISTRY}/${BASE_IMAGE}:${BASE_IMAGE_TAG}",
    dockerEnvVars := Map("JAVA_OPTS" -> ""),   // this is here so JAVA_OPTS can be overridden on the docker run cmd with a value like: -Xmx1G
    // dockerEntrypoint ++= Seq("-Djava.security.auth.login.config=src/main/resources/jaas.config")  // <- had trouble getting this to work
    Docker / mappings ++= Seq((baseDirectory.value / "LICENSE.txt") -> "/1/licenses/LICENSE.txt"),
    dockerCommands           := Seq(Cmd("ARG", "RED_HAT_UBI_TYPE=minimal"),
                                    Cmd("ARG", "RHEL_VERSION=10"),
                                    Cmd("ARG", "BASE_IMAGE=ubi${RHEL_VERSION}-${RED_HAT_UBI_TYPE}"),
                                    Cmd("ARG", "BASE_IMAGE_REGISTRY=registry.access.redhat.com"),
                                    Cmd("ARG", "BASE_IMAGE_TAG=latest"),
                                    Cmd("FROM", dockerBaseImage.value ++ " AS stage0"),
                                    Cmd("LABEL", "snp-multi-stage='intermediate'"),
                                    Cmd("LABEL", "snp-multi-stage-id='6466ecf3-c305-40bb-909a-47e60bded33d'"),
                                    Cmd("WORKDIR", "/etc/horizon/exchange"),
                                    Cmd("WORKDIR", "/licenses"),
                                    Cmd("COPY", "1/licenses /1/licenses"),
                                    Cmd("WORKDIR", "/opt/docker"),
                                    Cmd("COPY", "2/opt /2/opt"),
                                    Cmd("COPY", "4/opt /4/opt"),
                                    Cmd("USER", "root"),
                                    Cmd("RUN", "chmod -R u=r,g=r /etc/horizon /licenses && chmod -R u=rX,g=rX /4/opt/docker /2/opt/docker && chmod u+x,g+x /4/opt/docker/bin/" ++ name.value),
                                    Cmd("FROM", dockerBaseImage.value),
                                    Cmd("LABEL", "description=" ++ description.value),
                                    // Cmd("LABEL", "io.k8s.description=''"),
                                    // Cmd("LABEL", "io.k8s.display-name=''"),
                                    // Cmd("LABEL", "io.openshift.tags=''"),
                                    Cmd("LABEL", "name=" ++ name.value),
                                    Cmd("LABEL", "release=" ++ release.value),
                                    Cmd("LABEL", "summary=" ++ summary.value),
                                    Cmd("LABEL", "vendor=" ++ vendor.value),
                                    Cmd("LABEL", "version=" ++ version.value),
                                    Cmd("RUN", "mkdir -p /run/user/" ++ (Docker / daemonUserUid).value.get ++ " && microdnf update -y --nodocs --refresh && microdnf install -y --nodocs shadow-utils gettext java-21-openjdk openssl && microdnf clean all"),
                                    Cmd("USER", "root"),
                                    Cmd("RUN", "id -u " ++ (Docker / daemonUser).value ++ " 1>/dev/null 2>&1 || ((getent group 1001 1>/dev/null 2>&1 || (type groupadd 1>/dev/null 2>&1 && groupadd -g 1001 " ++ (Docker / daemonGroup).value ++ " || addgroup -g 1001 -S " ++ (Docker / daemonGroup).value ++ ")) && (type useradd 1>/dev/null 2>&1 && useradd --system --create-home --uid 1001 --gid 1001 " ++ (Docker / daemonUser).value ++ " || adduser -S -u 1001 -G " ++ (Docker / daemonGroup).value ++ " " ++ (Docker / daemonUser).value ++ "))"),
                                    Cmd("WORKDIR", "/etc/horizon/exchange"),
                                    Cmd("COPY --from=stage0 --chown=" ++ (Docker / daemonUser).value ++ ":" ++ (Docker / daemonGroup).value, "/etc/horizon/exchange /etc/horizon/exchange"),
                                    Cmd("WORKDIR", "/licenses"),
                                    Cmd("COPY --from=stage0 --chown=" ++ (Docker / daemonUser).value ++ ":" ++ (Docker / daemonGroup).value, "/1/licenses /licenses"),
                                    Cmd("WORKDIR", "/opt/docker"),
                                    Cmd("COPY --from=stage0 --chown=" ++ (Docker / daemonUser).value ++ ":" ++ (Docker / daemonGroup).value, "/4/opt/docker /opt/docker"),
                                    Cmd("COPY --from=stage0 --chown=" ++ (Docker / daemonUser).value ++ ":" ++ (Docker / daemonGroup).value, "/2/opt/docker /opt/docker"),
                                    Cmd("ENV", "JAVA_OPTS=''"),
                                    Cmd("EXPOSE", "8080"),
                                    Cmd("EXPOSE", "8083"),
                                    Cmd("USER", "1001:1001"),
                                    Cmd("ENTRYPOINT", "[\"/opt/docker/bin/" ++ name.value ++ "\"]"),
                                    Cmd("CMD", "[]")
                                  )
  )
