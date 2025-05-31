package org.openhorizon.exchangeapi.route.deploymentpattern

/**
  * Json4s cannot serialize nested case class structures from Json to Scala. These test variants are standard classes.
  * We can get away with this as the content of these classes is not used in the runtime, and we are parsing
  * from Strings.
  */

class TestOneSecretBindingService(val serviceOrgid: String,
                                  val serviceUrl: String,
                                  val serviceArch: Option[String],
                                  val serviceVersionRange: Option[String],
                                  val secrets: List[Map[String, String]],
                                  val enableNodeLevelSecrets: Option[Boolean] = Option(false))

class TestOneUserInputValue(val name: String,
                            val value: Any)

class TestOneUserInputService(val serviceOrgid: String,
                              val serviceUrl: String,
                              val serviceArch: Option[String],
                              val serviceVersionRange: Option[String],
                              val inputs: List[TestOneUserInputValue])

class TestPServiceVersions(val version: String,
                           val deployment_overrides: Option[String],
                           val deployment_overrides_signature: Option[String],
                           val priority: Option[Map[String,Int]],
                           val upgradePolicy: Option[Map[String,String]])

class TestPServices(val serviceUrl: String,
                    val serviceOrgid: String,
                    val serviceArch: String,
                    val agreementLess: Option[Boolean],
                    val serviceVersions: List[TestPServiceVersions],
                    val dataVerification: Option[Map[String,Any]],
                    val nodeHealth: Option[Map[String,Int]])

class TestPattern(val owner: String,
                  val label: String,
                  val description: String,
                  val public: Boolean,
                  val services: List[TestPServices],
                  val userInput: List[TestOneUserInputService],
                  val secretBinding: List[TestOneSecretBindingService],
                  val agreementProtocols: List[Map[String,String]],
                  val lastUpdated: String,
                  val clusterNamespace: String = "")

class TestGetPatternsResponse(val patterns: Map[String, TestPattern] = Map.empty[String, TestPattern],
                              val lastIndex: Int = 0)
