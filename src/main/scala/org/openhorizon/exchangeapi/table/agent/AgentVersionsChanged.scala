package org.openhorizon.exchangeapi.table.agent

import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import slick.jdbc.PostgresProfile.api._
import slick.model.ForeignKeyAction

class AgentVersionsChanged(tag: Tag) extends Table[(java.sql.Timestamp, String)](tag, "agent_version_last_updated") {
  def changed = column[java.sql.Timestamp]("changed")
  def organization = column[String]("organization", O.PrimaryKey, O.Default("IBM"))
  
  def * = (changed, organization)
  def fkOrg = foreignKey("fk_org", organization, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}
