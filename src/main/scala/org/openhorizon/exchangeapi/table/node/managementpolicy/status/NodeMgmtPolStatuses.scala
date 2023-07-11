package org.openhorizon.exchangeapi.table.node.managementpolicy.status

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}

object NodeMgmtPolStatuses extends TableQuery(new NodeMgmtPolStatus(_)) {
  
  def getActualStartTime(node: String, policy: String): Query[Rep[Option[String]], Option[String], Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.actualStartTime))
  def getCertificateVersion(node: String, policy: String): Query[Rep[Option[String]], Option[String], Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.certificateVersion))
  def getConfigurationVersion(node: String, policy: String): Query[Rep[Option[String]], Option[String], Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.configurationVersion))
  def getEndTime(node: String, policy: String): Query[Rep[Option[String]], Option[String], Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.endTime))
  def getErrorMessage(node: String, policy: String): Query[Rep[Option[String]], Option[String], Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.errorMessage))
  def getNodeMgmtPolStatus(node: String, policy: String): Query[NodeMgmtPolStatus, NodeMgmtPolStatusRow, Seq] = this.filter(s => {s.node === node && s.policy === policy})
  def getNodeMgmtPolStatuses(node: String): Query[NodeMgmtPolStatus, NodeMgmtPolStatusRow, Seq] = this.filter(s => {s.node === node})
  def getScheduledStartTime(node: String, policy: String): Query[Rep[String], String, Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.scheduledStartTime))
  def getSoftwareVersion(node: String, policy: String): Query[Rep[Option[String]], Option[String], Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.softwareVersion))
  def getStatus(node: String, policy: String): Query[Rep[Option[String]], Option[String], Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.status))
  def getUpdated(node: String, policy: String): Query[Rep[String], String, Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.updated))
}
