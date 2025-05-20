package org.openhorizon.exchangeapi.table.deploymentpolicy

import org.json4s._
import org.json4s.jackson.Serialization.read
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.deploymentpattern.{OneSecretBindingService, OneUserInputService}
import org.openhorizon.exchangeapi.table.deploymentpolicy.search.{SearchOffsetPolicy, SearchOffsetPolicyAttributes}
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.service.{OneProperty, ServiceRef2, ServicesTQ}
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{Version, VersionRange}
import slick.dbio.Effect
import slick.jdbc.PostgresProfile.api._
import slick.sql.FixedSqlAction

import java.util.UUID
import scala.collection.mutable.ListBuffer


class BusinessPolicies(tag: Tag) extends Table[BusinessPolicyRow](tag, "businesspolicies") {
  def businessPolicy = column[String]("businessPolicy", O.PrimaryKey)    // the content of this is orgid/businessPolicy
  def orgid = column[String]("orgid")
  def owner = column[UUID]("owner")
  def label = column[String]("label")
  def description = column[String]("description")
  def service = column[String]("service")
  def userInput = column[String]("userinput")
  def secretBinding = column[String]("secretbinding")
  def properties = column[String]("properties")
  def constraints = column[String]("constraints")
  def lastUpdated = column[String]("lastupdated")
  def created = column[String]("created")
  
  def * = (businessPolicy, orgid, owner, label, description, service, userInput,secretBinding, properties, constraints, lastUpdated, created).<>(BusinessPolicyRow.tupled, BusinessPolicyRow.unapply)
  
  def user = foreignKey("user_fk", owner, UsersTQ)(_.user, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}
