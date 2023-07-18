package org.openhorizon.exchangeapi.route.managementpolicy

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.{AgentUpgradePolicy, ManagementPolicyRow, OneProperty}
import org.openhorizon.exchangeapi.{ApiTime, ExchMsg}
import slick.dbio.DBIO

final case class PostPutManagementPolicyRequest(label: String,
                                                description: Option[String],
                                                properties: Option[List[OneProperty]],
                                                constraints: Option[List[String]],
                                                patterns: Option[List[String]],
                                                enabled: Boolean,
                                                start: String = "now",
                                                startWindow: Long = 0,
                                                agentUpgradePolicy: Option[AgentUpgradePolicy])  {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    if (constraints.nonEmpty && patterns.nonEmpty) return Option(ExchMsg.translate("mgmtpol.constraints.or.patterns"))
    None
  }

  // Note: write() handles correctly the case where the optional fields are None.
  def getDbInsert(managementPolicy: String, orgid: String, owner: String): DBIO[_] = {
    ManagementPolicyRow(allowDowngrade = if(agentUpgradePolicy.nonEmpty) agentUpgradePolicy.get.allowDowngrade else false,
                        constraints = write(constraints),
                        created = ApiTime.nowUTC,
                        description = description.getOrElse(label),
                        enabled = enabled,
                        label = label,
                        lastUpdated = ApiTime.nowUTC,
                        managementPolicy = managementPolicy,
                        manifest = if(agentUpgradePolicy.nonEmpty) agentUpgradePolicy.get.manifest else "",
                        orgid = orgid,
                        owner = owner,
                        patterns = write(patterns),
                        properties = write(properties),
                        start = start,
                        startWindow = if(startWindow < 0) 0 else startWindow).insert
  }

  def getDbUpdate(managementPolicy: String, orgid: String, owner: String): DBIO[_] = {
    ManagementPolicyRow(allowDowngrade = if(agentUpgradePolicy.nonEmpty) agentUpgradePolicy.get.allowDowngrade else false,
                        constraints = write(constraints),
                        created = ApiTime.nowUTC,
                        description = description.getOrElse(label),
                        enabled = enabled,
                        label = label,
                        lastUpdated = ApiTime.nowUTC,
                        managementPolicy = managementPolicy,
                        manifest = if(agentUpgradePolicy.nonEmpty) agentUpgradePolicy.get.manifest else "",
                        orgid = orgid,
                        owner = owner,
                        patterns = write(patterns),
                        properties = write(properties),
                        start = start,
                        startWindow = if(startWindow < 0) 0 else startWindow).update
  }
}
