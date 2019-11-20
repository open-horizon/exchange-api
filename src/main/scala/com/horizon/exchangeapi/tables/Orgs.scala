package com.horizon.exchangeapi.tables

import com.horizon.exchangeapi.ApiJsonUtil
import org.json4s._
import ExchangePostgresProfile.api._
import slick.lifted.ProvenShape
//import org.json4s.jackson.Serialization.read
//import ExchangePostgresProfile.jsonMethods._


/** Contains the object representations of the DB tables related to orgs. */

case class OrgRow(orgId: String, orgType: String, label: String, description: String, lastUpdated: String, tags: Option[JValue]) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toOrg: Org = {
    new Org(orgType, label, description, lastUpdated, tags.flatMap(_.extractOpt[Map[String, String]]))
  }

  // update returns a DB action to update this row
  def update: DBIO[_] = (for { m <- OrgsTQ.rows if m.orgid === orgId } yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = OrgsTQ.rows += this

  // Returns a DB action to insert or update this row
  def upsert: DBIO[_] = OrgsTQ.rows.insertOrUpdate(this)
}

/** Mapping of the orgs db table to a scala class */
class Orgs(tag: Tag) extends Table[OrgRow](tag, "orgs") {
  def orgid = column[String]("orgid", O.PrimaryKey)
  def orgType = column[String]("orgtype")
  def label = column[String]("label")
  def description = column[String]("description")
  def lastUpdated = column[String]("lastupdated")
  def tags = column[Option[JValue]]("tags")
  // this describes what you get back when you return rows from a query
  def * = (orgid, orgType, label, description, lastUpdated, tags) <> (OrgRow.tupled, OrgRow.unapply)
}

// Instance to access the orgs table
object OrgsTQ {
  val rows = TableQuery[Orgs]

  def getOrgid(orgid: String) = rows.filter(_.orgid === orgid)
  def getOrgType(orgid: String) = rows.filter(_.orgid === orgid).map(_.orgType)
  def getLabel(orgid: String) = rows.filter(_.orgid === orgid).map(_.label)
  def getDescription(orgid: String) = rows.filter(_.orgid === orgid).map(_.description)
  def getLastUpdated(orgid: String) = rows.filter(_.orgid === orgid).map(_.lastUpdated)
  def getTag(orgid: String, tag: String) = rows.filter(_.orgid === orgid).map(_.tags.map(tags => tags +>> tag))
  def getOrgidsOfType(orgType: String) = rows.filter(_.orgType === orgType).map(_.orgid)

  /** Returns a query for the specified org attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(orgid: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.orgid === orgid)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
      case "orgType" => filter.map(_.orgType)
      case "label" => filter.map(_.label)
      case "description" => filter.map(_.description)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case "tags" => filter.map(_.tags.getOrElse(ApiJsonUtil.asJValue(Map.empty)))
      case _ => null
    }
  }

  /** Returns the actions to delete the org and the blockchains that reference it */
  def getDeleteActions(orgid: String): DBIO[_] = getOrgid(orgid).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}

// This is the org table minus the key - used as the data structure to return to the REST clients
class Org(var orgType: String, var label: String, var description: String, var lastUpdated: String, var tags: Option[Map[String, String]]) {
  //def copy = new Org(orgType, label, description, lastUpdated)
}

/** Contains the object representations of the DB tables related to resource changes. */
case class ResourceChangeRow(changeId: Int, orgId: String, id: String, category: String, public: String, resource: String, lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def toResourceChange: ResourceChange = {
    new ResourceChange(changeId, orgId, id, category, public, resource, lastUpdated)
  }

  // update returns a DB action to update this row
  def update: DBIO[_] = (for { m <- ResourceChangesTQ.rows if m.changeId === changeId} yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = ResourceChangesTQ.rows += this

  // Returns a DB action to insert or update this row
  def upsert: DBIO[_] = ResourceChangesTQ.rows.insertOrUpdate(this)
}

/** Mapping of the orgs db table to a scala class */
class ResourceChanges(tag: Tag) extends Table[ResourceChangeRow](tag, "orgs") {
  def changeId = column[Int]("changeid", O.PrimaryKey)
  def orgId = column[String]("orgid")
  def id = column[String]("id")
  def category = column[String]("category")
  def public = column[String]("public")
  def resource = column[String]("resource")
  def lastUpdated = column[String]("lastupdated")
  // this describes what you get back when you return rows from a query
  def * = (changeId, orgId, id, category, public, resource, lastUpdated) <> (ResourceChangeRow.tupled, ResourceChangeRow.unapply)
}

// Instance to access the orgs table
object ResourceChangesTQ {
  val rows = TableQuery[ResourceChanges]

  def getChangeId(changeid: Int) = rows.filter(_.changeId === changeid).map(_.changeId)
  def getOrgid(changeid: Int) = rows.filter(_.changeId === changeid).map(_.orgId)
  def getId(changeid: Int) = rows.filter(_.changeId === changeid).map(_.id)
  def getCategory(changeid: Int) = rows.filter(_.changeId === changeid).map(_.category)
  def getPublic(changeid: Int) = rows.filter(_.changeId === changeid).map(_.public)
  def getResource(changeid: Int) = rows.filter(_.changeId=== changeid).map(_.resource)
  def getLastUpdated(changeid: Int) = rows.filter(_.changeId === changeid).map(_.lastUpdated)

  /** Returns a query for the specified org attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(changeid: Int, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.changeId === changeid)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
      case "changeId" => filter.map(_.changeId)
      case "orgId" => filter.map(_.orgId)
      case "id" => filter.map(_.id)
      case "category" => filter.map(_.category)
      case "public" => filter.map(_.public)
      case "resource" => filter.map(_.resource)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }
}

// This is the org table minus the key - used as the data structure to return to the REST clients
class ResourceChange(var changeId: Int, var orgId: String, var id: String, var category: String, var public: String, var resource: String, var lastUpdated: String) {
  //def copy = new Org(orgType, label, description, lastUpdated)
}