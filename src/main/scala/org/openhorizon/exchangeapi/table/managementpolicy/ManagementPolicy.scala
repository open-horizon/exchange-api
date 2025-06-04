package org.openhorizon.exchangeapi.table.managementpolicy

import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization.read
import org.openhorizon.exchangeapi.table.service.OneProperty


case class ManagementPolicy(agentUpgradePolicy: AgentUpgradePolicy = AgentUpgradePolicy(allowDowngrade = false, manifest = ""),
                            constraints: List[String] = List.empty[String],
                            created: String = "",
                            description: String = "",
                            enabled: Boolean = false,
                            label: String = "",
                            lastUpdated: String,
                            owner: String,
                            patterns: List[String] = List.empty[String],
                            properties: List[OneProperty] = List.empty[OneProperty],
                            start: String = "now",
                            startWindow: Long = 0L) {
  def this (tuple: (Boolean, String, String, String, Boolean, String, String, String, String, String, String, String, Long))(implicit format: Formats) =
    this(agentUpgradePolicy = AgentUpgradePolicy(allowDowngrade = tuple._1, manifest = tuple._8),
         constraints = if (tuple._2 != "") read[List[String]](tuple._2) else List[String](),
         created = tuple._3,
         description = tuple._4,
         enabled = tuple._5,
         label = tuple._6,
         lastUpdated = tuple._7,
         owner = tuple._9,
         patterns = if (tuple._10 != "") read[List[String]](tuple._10) else List[String](),
         properties = if (tuple._11 != "") read[List[OneProperty]](tuple._11) else List[OneProperty](),
         start = tuple._12,
         startWindow = tuple._13)
}
