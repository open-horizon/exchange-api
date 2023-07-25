package org.openhorizon.exchangeapi.table.organization

import org.json4s._
import org.json4s.jackson.Serialization.read
import org.openhorizon.exchangeapi.table.node.NodeHeartbeatIntervals
import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._

final case class OrgRow(orgId: String,
                        orgType: String,
                        label: String,
                        description: String,
                        lastUpdated: String,
                        tags: Option[JValue],
                        limits: String,
                        heartbeatIntervals: String) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toOrg: Org = {
    val hbInterval: NodeHeartbeatIntervals = if (heartbeatIntervals != "") read[NodeHeartbeatIntervals](heartbeatIntervals) else NodeHeartbeatIntervals(0, 0, 0)
    val orgLimits: OrgLimits = if (limits != "") read[OrgLimits](limits) else OrgLimits(0)
    Org(orgType, label, description, lastUpdated, tags.flatMap(_.extractOpt[Map[String, String]]), orgLimits, hbInterval)
  }

  // update returns a DB action to update this row
  def update: DBIO[_] = (for { m <- OrgsTQ if m.orgid === orgId } yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = OrgsTQ += this

  // Returns a DB action to insert or update this row
  def upsert: DBIO[_] = OrgsTQ.insertOrUpdate(this)
}
