package org.openhorizon.exchangeapi.table

import org.openhorizon.exchangeapi.{Version, VersionRange}
import org.json4s._
import org.json4s.jackson.Serialization.read
import slick.dbio.Effect
import slick.jdbc.PostgresProfile.api._
import scala.collection.mutable.ListBuffer


/** Contains the object representations of the DB tables related to management policies. */

final case class AgentUpgradePolicy(allowDowngrade: Boolean, manifest: String) {
  override def clone = new AgentUpgradePolicy(allowDowngrade, manifest)
}

// This is the managementpolicies table minus the key - used as the data structure to return to the REST clients
class ManagementPolicy(var agentUpgradePolicy: AgentUpgradePolicy,
                       var constraints: List[String],
                       var created: String,
                       var description: String,
                       var enabled: Boolean,
                       var label: String,
                       var lastUpdated: String,
                       var owner: String,
                       var patterns: List[String],
                       var properties: List[OneProperty],
                       var start: String = "now",
                       var startWindow: Long = 0) {
  override def clone = new ManagementPolicy(agentUpgradePolicy.clone(), constraints, created, description, enabled, label, lastUpdated, owner, patterns, properties, start, startWindow)
  def copy: ManagementPolicy = clone()
}

// Note: if you add fields to this, you must also add them the update method below
final case class ManagementPolicyRow(managementPolicy: String,
                                     orgid: String,
                                     owner: String,
                                     label: String,
                                     description: String,
                                     properties: String,
                                     constraints: String,
                                     patterns: String,
                                     enabled: Boolean,
                                     lastUpdated: String,
                                     created: String,
                                     allowDowngrade: Boolean,
                                     manifest: String,
                                     start: String = "now",
                                     startWindow: Long = 0) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toManagementPolicy: ManagementPolicy = {
    val prop: List[OneProperty] = if (properties != "") read[List[OneProperty]](properties) else List[OneProperty]()
    val con: List[String] = if (constraints != "") read[List[String]](constraints) else List[String]()
    val pat: List[String] = if (patterns != "") read[List[String]](patterns) else List[String]()
    
    new ManagementPolicy(new AgentUpgradePolicy(allowDowngrade = allowDowngrade,
                                                manifest = manifest),
                         constraints = con,
                         created = created,
                         description = description,
                         enabled = enabled,
                         label = label,
                         lastUpdated = lastUpdated,
                         owner = owner,
                         patterns = pat,
                         properties = prop,
                         start = start,
                         startWindow = startWindow)
  }

  // update returns a DB action to update this row
  def update: DBIO[_] = (for { m <- ManagementPoliciesTQ if m.managementPolicy === managementPolicy } yield (m.managementPolicy,m.orgid,m.owner,m.label,m.description,m.properties,m.constraints,m.patterns,m.enabled,m.lastUpdated,m.created,m.allowDowngrade,m.manifest,m.start,m.startWindow)).update((managementPolicy,orgid,owner,label,description,properties,constraints,patterns,enabled,lastUpdated,created,allowDowngrade,manifest,start,startWindow))

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = ManagementPoliciesTQ += this
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
  def lastUpdated = column[String]("lastupdated")
  def created = column[String]("created")
  def allowDowngrade = column[Boolean]("allowdowngrade")
  def manifest = column[String]("manifest")
  def start = column[String]("start", O.Default("now"))
  def startWindow = column[Long]("startwindow", O.Default(0))
  
  // this describes what you get back when you return rows from a query
  def * = (managementPolicy, orgid, owner, label, description, properties, constraints, patterns, enabled, lastUpdated, created, allowDowngrade, manifest, start, startWindow).<>(ManagementPolicyRow.tupled, ManagementPolicyRow.unapply)
  def user_fk = foreignKey("user_fk", owner, UsersTQ)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgid_fk = foreignKey("orgid_fk", orgid, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the managementpolicies table
object ManagementPoliciesTQ extends TableQuery(new ManagementPolicies(_)) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAllManagementPolicies(orgid: String): Query[ManagementPolicies, ManagementPolicyRow, Seq] = this.filter(_.orgid === orgid)
  def getAllowDowngrade(managementPolicy: String): Query[Rep[Boolean], Boolean, Seq] = this.filter(_.managementPolicy === managementPolicy).map(_.allowDowngrade)
  def getCreated(managementPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.managementPolicy === managementPolicy).map(_.created)
  def getDescription(managementPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.managementPolicy === managementPolicy).map(_.description)
  def getLabel(managementPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.managementPolicy === managementPolicy).map(_.label)
  def getLastUpdated(managementPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.managementPolicy === managementPolicy).map(_.lastUpdated)
  def getManifest(managementPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.managementPolicy === managementPolicy).map(_.manifest)
  def getManagementPolicy(managementPolicy: String): Query[ManagementPolicies, ManagementPolicyRow, Seq] = if (managementPolicy.contains("%")) this.filter(_.managementPolicy like managementPolicy) else this.filter(_.managementPolicy === managementPolicy)
  def getNumOwned(owner: String): Rep[Int] = this.filter(_.owner === owner).length
  def getOwner(managementPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.managementPolicy === managementPolicy).map(_.owner)
  def getPatterns(managementPolicy: String): Query[Rep[String],String, Seq] = this.filter(_.managementPolicy === managementPolicy).map(_.patterns)
  def getStart(managementPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.managementPolicy === managementPolicy).map(_.start)
  def getStartWindow(managementPolicy: String): Query[Rep[Long], Long, Seq] = this.filter(_.managementPolicy === managementPolicy).map(_.startWindow)

  /** Returns a query for the specified managementPolicy attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(managementPolicy: String, attrName: String): Query[_,_,Seq] = {
    val filter = this.filter(_.managementPolicy === managementPolicy)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    attrName match {
      case "allowDowngrade" => filter.map(_.allowDowngrade)
      case "constraints" => filter.map(_.constraints)
      case "created" => filter.map(_.created)
      case "description" => filter.map(_.description)
      case "enabled" => filter.map(_.enabled)
      case "label" => filter.map(_.label)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case "manifest" => filter.map(_.manifest)
      case "owner" => filter.map(_.owner)
      case "patterns" => filter.map(_.patterns)
      case "properties" => filter.map(_.properties)
      case "start" => filter.map(_.start)
      case "startWindow" => filter.map(_.startWindow)
      case _ => null
    }
  }

  /** Returns the actions to delete the managementPolicy and the blockchains that reference it */
  def getDeleteActions(managementPolicy: String): DBIO[_] = getManagementPolicy(managementPolicy).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}
