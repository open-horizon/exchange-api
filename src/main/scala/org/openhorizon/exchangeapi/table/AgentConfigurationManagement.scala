package org.openhorizon.exchangeapi.table

import org.openhorizon.exchangeapi.{ApiTime, Version, VersionRange}
import org.json4s._
import org.json4s.jackson.Serialization.read
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import slick.dbio.Effect
import slick.jdbc.PostgresProfile.api._

import java.sql.Timestamp
import scala.collection.mutable.ListBuffer


trait AgentVersions {
  def agentCertVersions: Seq[String]
  def agentConfigVersions: Seq[String]
  def agentSoftwareVersions: Seq[String]
}

final case class AgentVersionsRequest(agentCertVersions: Seq[String],
                                      agentConfigVersions: Seq[String],
                                      agentSoftwareVersions: Seq[String]) extends AgentVersions

final case class AgentVersionsResponse(agentCertVersions: Seq[String],
                                       agentConfigVersions: Seq[String],
                                       agentSoftwareVersions: Seq[String],
                                       lastUpdated: String) extends AgentVersions


class AgentCertificateVersions(tag: Tag) extends Table[(String, String, Option[Long])](tag, "agent_version_certificate") {
  def certificateVersion = column[String]("version")
  def organization = column[String]("organization", O.Default("IBM"))
  def priority = column[Option[Long]]("priority")
  
  def * = (certificateVersion, organization, priority)
  def pkAgentVerCert = primaryKey("pk_agent_version_certificate", (organization, certificateVersion))
  def fkOrg = foreignKey("fk_organization", organization, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def idxPriority = index("idx_avcert_priority", (organization, priority), unique = true)
}

object AgentCertificateVersionsTQ extends TableQuery(new AgentCertificateVersions(_)) {
  def getAgentCertificateVersions(organization: String): Query[Rep[String], String, Seq] = this.filter(_.organization === organization).map(_.certificateVersion)
}


class AgentConfigurationVersions(tag: Tag) extends Table[(String, String, Option[Long])](tag, "agent_version_configuration") {
  def configurationVersion = column[String]("version")
  def organization = column[String]("organization", O.Default("IBM"))
  def priority  = column[Option[Long]]("priority")
  
  def * = (configurationVersion, organization, priority)
  def pkAgentVerConfig = primaryKey("pk_agent_version_configuration", (organization, configurationVersion))
  def fkOrg = foreignKey("fk_organization", organization, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def idxPriority = index("idx_avconfig_priority", (organization, priority), unique = true)
}

object AgentConfigurationVersionsTQ extends TableQuery(new AgentConfigurationVersions(_)) {
  def getAgentConfigurationVersions(organization: String): Query[Rep[String], String, Seq] = this.filter(_.organization === organization).map(_.configurationVersion)
}


class AgentSoftwareVersions(tag: Tag) extends Table[(String, String, Option[Long])](tag, "agent_version_software") {
  def organization = column[String]("organization", O.Default("IBM"))
  def softwareVersion = column[String]("version")
  def priority  = column[Option[Long]]("priority")
  
  def * = (organization, softwareVersion, priority)
  def pkAgentVerSoft = primaryKey("pk_agent_version_software", (organization, softwareVersion))
  def fkOrg = foreignKey("fk_organization", organization, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def idxPriority = index("idx_avsoft_priority", (organization, priority), unique = true)
}

object AgentSoftwareVersionsTQ extends TableQuery(new AgentSoftwareVersions(_)) {
  def getAgentSoftwareVersions(organization: String): Query[Rep[String], String, Seq] = this.filter(_.organization === organization).map(_.softwareVersion)
}


class AgentVersionsChanged(tag: Tag) extends Table[(java.sql.Timestamp, String)](tag, "agent_version_last_updated") {
  def changed = column[java.sql.Timestamp]("changed")
  def organization = column[String]("organization", O.PrimaryKey, O.Default("IBM"))
  
  def * = (changed, organization)
  def fkOrg = foreignKey("fk_org", organization, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object AgentVersionsChangedTQ extends TableQuery(new AgentVersionsChanged(_)) {
  def getChanged(organization: String): Query[Rep[Timestamp], Timestamp, Seq] = this.filter(_.organization === organization).map(_.changed)
}
