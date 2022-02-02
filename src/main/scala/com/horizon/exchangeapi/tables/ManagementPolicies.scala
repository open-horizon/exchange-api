package com.horizon.exchangeapi.tables

import com.horizon.exchangeapi.{Version, VersionRange}
import org.json4s._
import org.json4s.jackson.Serialization.read
import slick.dbio.Effect
import slick.jdbc.PostgresProfile.api._
import scala.collection.mutable.ListBuffer


/** Contains the object representations of the DB tables related to management policies. */

final case class AgentUpgradePolicy(atLeastVersion: String, start: String, duration: Int)

// This is the managementpolicies table minus the key - used as the data structure to return to the REST clients
class ManagementPolicy(var owner: String, var label: String, var description: String, var properties: List[OneProperty], var constraints: List[String], var patterns: List[String], var enabled: Boolean, var agentUpgradePolicy: AgentUpgradePolicy, var lastUpdated: String, var created: String) {
  def copy = new ManagementPolicy(owner, label, description, properties, constraints, patterns, enabled, agentUpgradePolicy, lastUpdated, created)
}

// Note: if you add fields to this, you must also add them the update method below
final case class ManagementPolicyRow(managementPolicy: String, orgid: String, owner: String, label: String, description: String, properties: String, constraints: String, patterns: String, enabled: Boolean, agentUpgradePolicy: String, lastUpdated: String, created: String) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toManagementPolicy: ManagementPolicy = {
    val prop: List[OneProperty] = if (properties != "") read[List[OneProperty]](properties) else List[OneProperty]()
    val con: List[String] = if (constraints != "") read[List[String]](constraints) else List[String]()
    val pat: List[String] = if (patterns != "") read[List[String]](patterns) else List[String]()
    val uppol: AgentUpgradePolicy = if (agentUpgradePolicy != "") read[AgentUpgradePolicy](agentUpgradePolicy) else AgentUpgradePolicy("current","now",0)
    new ManagementPolicy(owner, label, description, prop, con, pat, enabled, uppol, lastUpdated, created)
  }

  // update returns a DB action to update this row
  def update: DBIO[_] = (for { m <- ManagementPoliciesTQ.rows if m.managementPolicy === managementPolicy } yield (m.managementPolicy,m.orgid,m.owner,m.label,m.description,m.properties,m.constraints,m.patterns,m.enabled,m.agentUpgradePolicy,m.lastUpdated,m.created)).update((managementPolicy,orgid,owner,label,description,properties,constraints,patterns,enabled,agentUpgradePolicy,lastUpdated,created))

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = ManagementPoliciesTQ.rows += this
}

/** Mapping of the managementpolicies db table to a scala class */
class ManagementPolicies(tag: Tag) extends Table[ManagementPolicyRow](tag, "managementpolicies") {
  def managementPolicy = column[String]("managementpolicy", O.PrimaryKey)    // the content of this is orgid/managementPolicy
  def orgid = column[String]("orgid")
  def owner = column[String]("owner")
  def label = column[String]("label")
  def description = column[String]("description")
  def properties = column[String]("properties")
  def constraints = column[String]("constraints")
  def patterns = column[String]("patterns")
  def enabled = column[Boolean]("enabled")
  def agentUpgradePolicy = column[String]("agentupgradepolicy")
  def lastUpdated = column[String]("lastupdated")
  def created = column[String]("created")
  // this describes what you get back when you return rows from a query
  def * = (managementPolicy, orgid, owner, label, description, properties, constraints, patterns, enabled, agentUpgradePolicy, lastUpdated, created).<>(ManagementPolicyRow.tupled, ManagementPolicyRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ.rows)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the managementpolicies table
object ManagementPoliciesTQ {
  protected implicit val jsonFormats: Formats = DefaultFormats
  val rows = TableQuery[ManagementPolicies]

  def getAllManagementPolicies(orgid: String): Query[ManagementPolicies, ManagementPolicyRow, Seq] = rows.filter(_.orgid === orgid)
  def getManagementPolicy(managementPolicy: String): Query[ManagementPolicies, ManagementPolicyRow, Seq] = if (managementPolicy.contains("%")) rows.filter(_.managementPolicy like managementPolicy) else rows.filter(_.managementPolicy === managementPolicy)
  def getOwner(managementPolicy: String): Query[Rep[String], String, Seq] = rows.filter(_.managementPolicy === managementPolicy).map(_.owner)
  def getNumOwned(owner: String): Rep[Int] = rows.filter(_.owner === owner).length
  def getLabel(managementPolicy: String): Query[Rep[String], String, Seq] = rows.filter(_.managementPolicy === managementPolicy).map(_.label)
  def getDescription(managementPolicy: String): Query[Rep[String], String, Seq] = rows.filter(_.managementPolicy === managementPolicy).map(_.description)
  def getPatterns(managementPolicy: String): Query[Rep[String],String, Seq] = rows.filter(_.managementPolicy === managementPolicy).map(_.patterns)
  def getAgentUpgradePolicy(managementPolicy: String): Query[Rep[String],String, Seq] = rows.filter(_.managementPolicy === managementPolicy).map(_.agentUpgradePolicy)
  def getLastUpdated(managementPolicy: String): Query[Rep[String], String, Seq] = rows.filter(_.managementPolicy === managementPolicy).map(_.lastUpdated)
  def getCreated(managementPolicy: String): Query[Rep[String], String, Seq] = rows.filter(_.managementPolicy === managementPolicy).map(_.created)

  /** Returns a query for the specified managementPolicy attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(managementPolicy: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.managementPolicy === managementPolicy)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    attrName match {
      case "owner" => filter.map(_.owner)
      case "label" => filter.map(_.label)
      case "description" => filter.map(_.description)
      case "properties" => filter.map(_.properties)
      case "constraints" => filter.map(_.constraints)
      case "patterns" => filter.map(_.patterns)
      case "enabled" => filter.map(_.enabled)
      case "agentUpgradePolicy" => filter.map(_.agentUpgradePolicy)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case "created" => filter.map(_.created)
      case _ => null
    }
  }

  /** Returns the actions to delete the managementPolicy and the blockchains that reference it */
  def getDeleteActions(managementPolicy: String): DBIO[_] = getManagementPolicy(managementPolicy).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}

final case class MgmtPolCurrentVersionsRow()

//class MgmtPolCurrentVersions(tag: Tag) extends Table[MgmtPolCurrentVersionsRow](tag, "management_policy_version_current")
