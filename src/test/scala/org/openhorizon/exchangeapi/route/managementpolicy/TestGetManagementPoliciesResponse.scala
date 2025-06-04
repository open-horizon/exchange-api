package org.openhorizon.exchangeapi.route.managementpolicy

/**
  * Json4s cannot serialize nested case class structures from Json to Scala. These test variants are standard classes.
  * We can get away with this as the content of these classes is not used in the runtime, and we are parsing
  * from Strings.
  */

class TestAgentUpgradePolicy(val allowDowngrade: Boolean,
                             val manifest: String)

class TestOneProperty(val name: String,
                      val `type`: Option[String] = None,
                      val value: Any)

class TestManagementPolicy(val agentUpgradePolicy: TestAgentUpgradePolicy,
                           val constraints: List[String] = List.empty[String],
                           val created: String = "",
                           val description: String = "",
                           val enabled: Boolean = false,
                           val label: String = "",
                           val lastUpdated: String,
                           val owner: String,
                           val patterns: List[String] = List.empty[String],
                           val properties: List[TestOneProperty] = List.empty[TestOneProperty],
                           val start: String = "now",
                           val startWindow: Long = 0L)

class TestGetManagementPoliciesResponse(val managementPolicy: Map[String, TestManagementPolicy] = Map.empty[String, TestManagementPolicy],
                                        val lastIndex: Int = 0)
