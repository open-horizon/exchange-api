package org.openhorizon.exchangeapi.table.node.managementpolicy.status

import org.json4s.{DefaultFormats, Formats}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class NodeMgmtPolStatusRow(actualStartTime: Option[String],
                                      certificateVersion: Option[String],
                                      configurationVersion: Option[String],
                                      endTime: Option[String],
                                      errorMessage: Option[String],
                                      node: String,
                                      policy: String,
                                      scheduledStartTime: String,
                                      softwareVersion: Option[String],
                                      status: Option[String],
                                      updated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def upsert: DBIO[_] = NodeMgmtPolStatuses.insertOrUpdate(this)
}
