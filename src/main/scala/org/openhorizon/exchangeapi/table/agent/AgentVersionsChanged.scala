package org.openhorizon.exchangeapi.table.agent

import org.openhorizon.exchangeapi.table.organization.{OrgRow, Orgs, OrgsTQ}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{ForeignKeyQuery, ProvenShape}
import slick.model.ForeignKeyAction

import java.time.Instant

class AgentVersionsChanged(tag: Tag) extends Table[(Instant, String)](tag, "agent_version_last_updated") {
  def changed: Rep[Instant] = column[Instant]("changed")
  def organization: Rep[String] = column[String]("organization", O.PrimaryKey, O.Default("IBM"))
  
  def * : ProvenShape[(Instant, String)] = (changed, organization)
  def fkOrg: ForeignKeyQuery[Orgs, OrgRow] = foreignKey("fk_org", organization, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}
