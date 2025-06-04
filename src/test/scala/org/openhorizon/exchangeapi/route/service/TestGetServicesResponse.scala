package org.openhorizon.exchangeapi.route.service

/**
  * Json4s cannot serialize nested case class structures from Json to Scala. These test variants are standard classes.
  * We can get away with this as the content of these classes is not used in the runtime, and we are parsing
  * from Strings.
  */

class TestServiceRef(val url: String,
                     val org: String,
                     val version: Option[String],
                     val versionRange: Option[String],
                     val arch: String)

class TestService(val arch: String,
                  val clusterDeployment: String,
                  val clusterDeploymentSignature: String,
                  val deployment: String,
                  val deploymentSignature: String,
                  val description: String,
                  val documentation: String,
                  val imageStore: Map[String, Any],
                  val label: String,
                  val lastUpdated: String,
                  val matchHardware: Map[String, Any],
                  val organization: String,
                  val owner: String,
                  val public: Boolean,
                  val requiredServices: List[TestServiceRef],
                  val sharable: String,
                  val url: String,
                  val userInput: List[Map[String, String]],
                  val version: String)

class TestGetServicesResponse(val services: Map[String, TestService],
                              val lastIndex: Int = 0)
