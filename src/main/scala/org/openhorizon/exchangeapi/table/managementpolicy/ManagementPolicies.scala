package org.openhorizon.exchangeapi.table.managementpolicy

import org.json4s._
import org.json4s.jackson.Serialization.read
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.service.OneProperty
import org.openhorizon.exchangeapi.table.user.UsersTQ
import slick.jdbc.PostgresProfile.api._

import java.util.UUID


class ManagementPolicies(tag: Tag) extends Table[ManagementPolicyRow](tag, "managementpolicies") {
  def managementPolicy = column[String]("managementpolicy", O.PrimaryKey)    // the content of this is orgid/managementPolicy
  def orgid = column[String]("orgid")
  def owner = column[UUID]("owner")
  def label = column[String]("label")
  def description = column[String]("description")
  def properties = column[String]("properties")
  def constraints = column[String]("constraints")
  def patterns = column[String]("patterns")
  def enabled = column[Boolean]("enabled")
  def lastUpdated = column[String]("lastupdated")
  def created = column[String]("created")
  def allowDowngrade = column[Boolean]("allowdowngrade")
  def manifest = column[String]("manifest")
  def start = column[String]("start", O.Default("now"))
  def startWindow = column[Long]("startwindow", O.Default(0))
  
  def * = (managementPolicy, orgid, owner, label, description, properties, constraints, patterns, enabled, lastUpdated, created, allowDowngrade, manifest, start, startWindow).<>(ManagementPolicyRow.tupled, ManagementPolicyRow.unapply)
  
  def user_fk = foreignKey("user_fk", owner, UsersTQ)(_.user, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgid_fk = foreignKey("orgid_fk", orgid, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def idx_mgmt_pol_fk_orgs = index(name = "idx_mgmt_pol_fk_orgs", on = orgid, unique = false)
  def idx_mgmt_pol_fk_users = index(name = "idx_mgmt_pol_fk_users", on = owner, unique = false)
}
