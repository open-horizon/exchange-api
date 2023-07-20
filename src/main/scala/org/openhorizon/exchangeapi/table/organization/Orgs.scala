package org.openhorizon.exchangeapi.table.organization

import org.json4s._
import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._


class Orgs(tag: Tag) extends Table[OrgRow](tag, "orgs") {
  def orgid = column[String]("orgid", O.PrimaryKey)
  def orgType = column[String]("orgtype")
  def label = column[String]("label")
  def description = column[String]("description")
  def lastUpdated = column[String]("lastupdated")
  def tags = column[Option[JValue]]("tags")
  def limits = column[String]("limits")
  def heartbeatIntervals = column[String]("heartbeatintervals")
  // this describes what you get back when you return rows from a query
  def * = (orgid, orgType, label, description, lastUpdated, tags, limits, heartbeatIntervals).<>(OrgRow.tupled, OrgRow.unapply)
}
