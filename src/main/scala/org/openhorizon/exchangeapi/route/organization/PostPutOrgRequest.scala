package org.openhorizon.exchangeapi.route.organization

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.node.NodeHeartbeatIntervals
import org.openhorizon.exchangeapi.table.organization.{OrgLimits, OrgRow}
import org.openhorizon.exchangeapi.{ApiTime, ApiUtils, ExchConfig, ExchMsg}

final case class PostPutOrgRequest(orgType: Option[String],
                                   label: String,
                                   description: String,
                                   tags: Option[Map[String, String]],
                                   limits: Option[OrgLimits],
                                   heartbeatIntervals: Option[NodeHeartbeatIntervals]) {
  require(label!=null && description!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem(orgMaxNodes: Int): Option[String] = {
    val exchangeMaxNodes: Int = ExchConfig.getInt("api.limits.maxNodes")
    if (orgMaxNodes > exchangeMaxNodes) Some.apply(ExchMsg.translate("org.limits.cannot.be.over.exchange.limits", orgMaxNodes, exchangeMaxNodes))
    else None
  }

  def toOrgRow(orgId: String): OrgRow = OrgRow.apply(orgId, orgType.getOrElse(""), label, description, ApiTime.nowUTC, tags.map(ts => ApiUtils.asJValue(ts)), write(limits), write(heartbeatIntervals))
}
