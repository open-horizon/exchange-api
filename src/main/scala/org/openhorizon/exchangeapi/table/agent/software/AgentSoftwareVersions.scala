package org.openhorizon.exchangeapi.table.agent.software

import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import slick.jdbc.PostgresProfile.api._

class AgentSoftwareVersions(tag: Tag) extends Table[(String, String, Option[Long])](tag, "agent_version_software") {
  def organization = column[String]("organization", O.Default("IBM"))
  def softwareVersion = column[String]("version")
  def priority  = column[Option[Long]]("priority")
  
  def * = (organization, softwareVersion, priority)
  def pkAgentVerSoft = primaryKey("pk_agent_version_software", (organization, softwareVersion))
  def fkOrg = foreignKey("fk_organization", organization, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def idxPriority = index("idx_avsoft_priority", (organization, priority), unique = true)
}
