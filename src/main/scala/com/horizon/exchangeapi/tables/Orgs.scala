package com.horizon.exchangeapi.tables

import java.sql.Timestamp
import org.json4s._
import org.json4s.jackson.Serialization.read
import org.json4s.jackson.Serialization.write
import slick.dbio.Effect
import slick.sql.FixedSqlAction
import com.horizon.exchangeapi.{ApiTime, ApiUtils}
import com.horizon.exchangeapi.tables.ExchangePostgresProfile.api._
import com.horizon.exchangeapi.tables.ResChangeCategory.Value


/** Contains the object representations of the DB tables related to orgs. */

final case class OrgLimits(maxNodes: Int)
object OrgLimits {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def toOrgLimit(limitsString: String): OrgLimits = if (limitsString != "") read[OrgLimits](limitsString) else OrgLimits(0)
}

final case class OrgRow(orgId: String, orgType: String, label: String, description: String, lastUpdated: String, tags: Option[JValue], limits: String, heartbeatIntervals: String) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toOrg: Org = {
    val hbInterval: NodeHeartbeatIntervals = if (heartbeatIntervals != "") read[NodeHeartbeatIntervals](heartbeatIntervals) else NodeHeartbeatIntervals(0, 0, 0)
    val orgLimits: OrgLimits = if (limits != "") read[OrgLimits](limits) else OrgLimits(0)
    Org(orgType, label, description, lastUpdated, tags.flatMap(_.extractOpt[Map[String, String]]), orgLimits, hbInterval)
  }

  // update returns a DB action to update this row
  def update: DBIO[_] = (for { m <- OrgsTQ if m.orgid === orgId } yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = OrgsTQ += this

  // Returns a DB action to insert or update this row
  def upsert: DBIO[_] = OrgsTQ.insertOrUpdate(this)
}

/** Mapping of the orgs db table to a scala class */
class Orgs(tag: Tag) extends Table[OrgRow](tag, "orgs") {
  def orgid = column[String]("orgid", O.PrimaryKey)
  def orgType = column[String]("orgtype")
  def label = column[String]("label")
  def description = column[String]("description")
  def lastUpdated = column[String]("lastupdated")
  def tags = column[Option[JValue]]("tags")
  def limits = column[String]("limits")
  def heartbeatIntervals = column[String]("heartbeatintervals")
  // this describes what you get back when you return rows from a query
  def * = (orgid, orgType, label, description, lastUpdated, tags, limits, heartbeatIntervals).<>(OrgRow.tupled, OrgRow.unapply)
}

// Instance to access the orgs table
object OrgsTQ  extends TableQuery(new Orgs(_)){
  protected implicit val jsonFormats: Formats = DefaultFormats
  
  def getOrgid(orgid: String): Query[Orgs, OrgRow, Seq] = this.filter(_.orgid === orgid)
  def getOrgType(orgid: String): Query[Rep[String], String, Seq] = this.filter(_.orgid === orgid).map(_.orgType)
  def getLabel(orgid: String): Query[Rep[String], String, Seq] = this.filter(_.orgid === orgid).map(_.label)
  def getDescription(orgid: String): Query[Rep[String], String, Seq] = this.filter(_.orgid === orgid).map(_.description)
  def getLastUpdated(orgid: String): Query[Rep[String], String, Seq] = this.filter(_.orgid === orgid).map(_.lastUpdated)
  def getTag(orgid: String, tag: String): Query[Rep[Option[String]], Option[String], Seq] = this.filter(_.orgid === orgid).map(_.tags.map(tags => tags +>> tag))
  def getLimits(orgid: String): Query[Rep[String], String, Seq] = this.filter(_.orgid === orgid).map(_.limits)
  def getHeartbeatIntervals(orgid: String): Query[Rep[String], String, Seq] = this.filter(_.orgid === orgid).map(_.heartbeatIntervals)
  def getOrgidsOfType(orgType: String): Query[Rep[String], String, Seq] = this.filter(_.orgType === orgType).map(_.orgid)

  /** Returns a query for the specified org attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(orgid: String, attrName: String): Query[_,_,Seq] = {
    val filter = this.filter(_.orgid === orgid)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    attrName match {
      case "orgType" => filter.map(_.orgType)
      case "label" => filter.map(_.label)
      case "description" => filter.map(_.description)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case "tags" => filter.map(_.tags.getOrElse(ApiUtils.asJValue(Map.empty)))
      case "limits" => filter.map(_.limits)
      case "heartbeatIntervals" => filter.map(_.heartbeatIntervals)
      case _ => null
    }
  }

  /** Returns the actions to delete the org and the blockchains that reference it */
  def getDeleteActions(orgid: String): DBIO[_] = getOrgid(orgid).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows

  // Needed to convert the tags attribute into a string a json to return to the client
  def renderAttribute(attribute: scala.Seq[Any]): String = {
    if (attribute.isEmpty) ""
    else attribute.head match {
      case attr: JValue => write(attr)
      case attr => attr.toString
    }
  }
}

// This is the org table minus the key - used as the data structure to return to the REST clients
final case class Org(orgType: String, label: String, description: String, lastUpdated: String, tags: Option[Map[String, String]], limits: OrgLimits, heartbeatIntervals: NodeHeartbeatIntervals)

/** Contains the object representations of the DB tables related to resource changes. */
object ResChangeCategory extends Enumeration {
  type ResChangeCategory = Value
  val AGBOT: ResChangeCategory.Value = Value("agbot")
  val MGMTPOLICY: ResChangeCategory.Value = Value("mgmtpolicy")
  val NODE: ResChangeCategory.Value = Value("node")
  val ORG: ResChangeCategory.Value = Value("org")
  val PATTERN: ResChangeCategory.Value = Value("pattern")
  val POLICY: ResChangeCategory.Value = Value("policy")
  val SERVICE: ResChangeCategory.Value = Value("service")
}
import com.horizon.exchangeapi.tables.ResChangeCategory._

object ResChangeResource extends Enumeration {
  type ResChangeResource = Value
  val AGBOT: ResChangeResource.Value = Value("agbot")
  val AGBOTAGREEMENTS: ResChangeResource.Value = Value("agbotagreements")
  val AGBOTBUSINESSPOLS: ResChangeResource.Value = Value("agbotbusinesspols")
  val AGBOTMSGS: ResChangeResource.Value = Value("agbotmsgs")
  val AGBOTPATTERNS: ResChangeResource.Value = Value("agbotpatterns")
  val AGENTFILEVERSION: ResChangeResource.Value = Value("agentfileversion")
  val NODE: ResChangeResource.Value = Value("node")
  val NODEAGREEMENTS: ResChangeResource.Value = Value("nodeagreements")
  val NODEERRORS: ResChangeResource.Value = Value("nodeerrors")
  val NODEMGMTPOLSTATUS: ResChangeResource.Value = Value("nodemgmtpolstatus")
  val NODEMSGS: ResChangeResource.Value = Value("nodemsgs")
  val NODEPOLICIES: ResChangeResource.Value = Value("nodepolicies")
  val NODESERVICES_CONFIGSTATE: ResChangeResource.Value = Value("services_configstate")
  val NODESTATUS: ResChangeResource.Value = Value("nodestatus")
  val ORG: ResChangeResource.Value = Value("org")
  
  val MGMTPOLICY: ResChangeResource.Value = Value("mgmtpolicy")
  val PATTERN: ResChangeResource.Value = Value("pattern")
  val PATTERNKEYS: ResChangeResource.Value = Value("patternkeys")
  val POLICY: ResChangeResource.Value = Value("policy")
  
  val SERVICE: ResChangeResource.Value = Value("service")
  val SERVICEDOCKAUTHS: ResChangeResource.Value = Value("servicedockauths")
  val SERVICEKEYS: ResChangeResource.Value = Value("servicekeys")
  val SERVICEPOLICIES: ResChangeResource.Value = Value("servicepolicies")
}
import com.horizon.exchangeapi.tables.ResChangeResource._

object ResChangeOperation extends Enumeration {
  type ResChangeOperation = Value
  val CREATED: ResChangeOperation.Value = Value("created")
  val CREATEDMODIFIED: ResChangeOperation.Value = Value("created/modified")
  val MODIFIED: ResChangeOperation.Value = Value("modified")
  val DELETED: ResChangeOperation.Value = Value("deleted")
}
import com.horizon.exchangeapi.tables.ResChangeOperation._

final case class ResourceChangeRow(changeId: Long, orgId: String, id: String, category: String, public: String, resource: String, operation: String, lastUpdated: java.sql.Timestamp) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  //def toResourceChange: ResourceChange = ResourceChange(changeId, orgId, id, category, public, resource, operation, lastUpdated)

  // update returns a DB action to update this row
  //def update: DBIO[_] = (for { m <- ResourceChangesTQ if m.changeId === changeId} yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = ResourceChangesTQ += this

  // Returns a DB action to insert or update this row
  //def upsert: DBIO[_] = ResourceChangesTQ.insertOrUpdate(this)
}

/** Mapping of the resourcechanges db table to a scala class */
class ResourceChanges(tag: Tag) extends Table[ResourceChangeRow](tag, "resourcechanges") {
  def changeId = column[Long]("changeid", O.PrimaryKey, O.AutoInc)
  def orgId = column[String]("orgid")
  def id = column[String]("id")
  def category = column[String]("category")
  def public = column[String]("public")
  def resource = column[String]("resource")
  def operation = column[String]("operation")
  def lastUpdated = column[java.sql.Timestamp]("lastupdated")
  // this describes what you get back when you return rows from a query
  def * = (changeId, orgId, id, category, public, resource, operation, lastUpdated).<>(ResourceChangeRow.tupled, ResourceChangeRow.unapply)
  def orgIndex = index("org_index", orgId)
  def idIndex = index("id_index", id)
  def catIndex = index("cat_index", category)
  def pubIndex = index("pub_index", public)
  def luIndex = index("lu_index", lastUpdated)

}

// Instance to access the ResourceChanges table
object ResourceChangesTQ  extends TableQuery(new ResourceChanges(_)){
  def getChangeId(changeid: Long): Query[Rep[Long], Long, Seq] = this.filter(_.changeId === changeid).map(_.changeId)
  def getOrgid(changeid: Long): Query[Rep[String], String, Seq] = this.filter(_.changeId === changeid).map(_.orgId)
  def getId(changeid: Long): Query[Rep[String], String, Seq] = this.filter(_.changeId === changeid).map(_.id)
  def getCategory(changeid: Long): Query[Rep[String], String, Seq] = this.filter(_.changeId === changeid).map(_.category)
  def getPublic(changeid: Long): Query[Rep[String], String, Seq] = this.filter(_.changeId === changeid).map(_.public)
  def getResource(changeid: Long): Query[Rep[String], String, Seq] = this.filter(_.changeId === changeid).map(_.resource)
  def getOperation(changeid: Long): Query[Rep[String], String, Seq] = this.filter(_.changeId === changeid).map(_.operation)
  def getLastUpdated(changeid: Long): Query[Rep[Timestamp], Timestamp, Seq] = this.filter(_.changeId === changeid).map(_.lastUpdated)
  def getRowsExpired(timeExpired: java.sql.Timestamp): Query[ResourceChanges, ResourceChangeRow, Seq] = this.filter(_.lastUpdated < timeExpired)

  /** Returns a query for the specified org attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(changeid: Long, attrName: String): Query[_,_,Seq] = {
    val filter = this.filter(_.changeId === changeid)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    attrName match {
      case "changeId" => filter.map(_.changeId)
      case "orgId" => filter.map(_.orgId)
      case "id" => filter.map(_.id)
      case "category" => filter.map(_.category)
      case "public" => filter.map(_.public)
      case "resource" => filter.map(_.resource)
      case "operation" => filter.map(_.operation)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }
  
  def dropAllChanges(): FixedSqlAction[Int, NoStream, Effect.Write] =
    this.delete
}

final case class ResourceChange(changeId: Long, orgId: String, id: String, category: ResChangeCategory, public: Boolean, resource: ResChangeResource, operation: ResChangeOperation) {

  def toResourceChangeRow = ResourceChangeRow(changeId, orgId, id, category.toString, public.toString, resource.toString, operation.toString, ApiTime.nowUTCTimestamp)

  // update returns a DB action to update this row
  //def update: DBIO[_] = toResourceChangeRow.update

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = toResourceChangeRow.insert

  // Returns a DB action to insert or update this row
  //def upsert: DBIO[_] = toResourceChangeRow.upsert
}
