package org.openhorizon.exchangeapi.table.agent.certificate

import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import slick.jdbc.PostgresProfile.api._


class AgentCertificateVersions(tag: Tag) extends Table[(String, String, Option[Long])](tag, "agent_version_certificate") {
  def certificateVersion = column[String]("version")
  def organization = column[String]("organization", O.Default("IBM"))
  def priority = column[Option[Long]]("priority")
  
  def * = (certificateVersion, organization, priority)
  def pkAgentVerCert = primaryKey("pk_agent_version_certificate", (organization, certificateVersion))
  def fkOrg = foreignKey("fk_organization", organization, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def idxPriority = index("idx_avcert_priority", (organization, priority), unique = true)
}
