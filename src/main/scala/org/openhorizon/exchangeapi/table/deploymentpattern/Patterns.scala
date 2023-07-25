package org.openhorizon.exchangeapi.table.deploymentpattern

import org.json4s._
import org.json4s.jackson.Serialization.read
import org.openhorizon.exchangeapi.table.deploymentpattern.key.{PatternKeyRow, PatternKeys}
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.user.UsersTQ
import slick.jdbc.PostgresProfile.api._


class Patterns(tag: Tag) extends Table[PatternRow](tag, "patterns") {
  def pattern = column[String]("pattern", O.PrimaryKey)    // the content of this is orgid/pattern
  def orgid = column[String]("orgid")
  def owner = column[String]("owner")
  def label = column[String]("label")
  def description = column[String]("description")
  def public = column[Boolean]("public")
  def services = column[String]("services")
  def userInput = column[String]("userinput")
  def secretBinding = column[String]("secretbinding")
  def agreementProtocols = column[String]("agreementProtocols")
  def lastUpdated = column[String]("lastupdated")
  def clusterNamespace = column[Option[String]]("cluster_namespace")
  
  // this describes what you get back when you return rows from a query
  def * = (pattern, orgid, owner, label, description, public, services, userInput,secretBinding, agreementProtocols, lastUpdated, clusterNamespace).<>(PatternRow.tupled, PatternRow.unapply)
  
  def user = foreignKey("user_fk", owner, UsersTQ)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}
