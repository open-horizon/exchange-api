package org.openhorizon.exchangeapi.table.agent.configuration

import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import slick.jdbc.PostgresProfile.api._
import slick.model.ForeignKeyAction


class AgentConfigurationVersions(tag: Tag) extends Table[(String, String, Option[Long])](tag, "agent_version_configuration") {
  def configurationVersion = column[String]("version")
  def organization = column[String]("organization", O.Default("IBM"))
  def priority  = column[Option[Long]]("priority")
  
  def * = (configurationVersion, organization, priority)
  def pkAgentVerConfig = primaryKey("pk_agent_version_configuration", (organization, configurationVersion))
  def fkOrg = foreignKey("fk_organization", organization, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def idxPriority = index("idx_avconfig_priority", (organization, priority), unique = true)
}
