package com.horizon.exchangeapi.tables

import org.json4s._
//import org.json4s.jackson.Serialization.read
import ExchangePostgresProfile.api._
import ExchangePostgresProfile.jsonMethods._


/** Contains the object representations of the DB tables related to orgs. */

case class OrgRow(orgId: String, label: String, description: String, lastUpdated: String) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toOrg: Org = {
    new Org(label, description, lastUpdated)
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
  def label = column[String]("label")
  def description = column[String]("description")
  def lastUpdated = column[String]("lastupdated")
  def tags = column[Option[JValue]]("tags")
  // this describes what you get back when you return rows from a query
  def * = (orgid, label, description, lastUpdated) <> (OrgRow.tupled, OrgRow.unapply)
}

// Instance to access the orgs table
object OrgsTQ {
  val rows = TableQuery[Orgs]

  def getOrgid(orgid: String) = rows.filter(_.orgid === orgid)
  def getLabel(orgid: String) = rows.filter(_.orgid === orgid).map(_.label)
  def getDescription(orgid: String) = rows.filter(_.orgid === orgid).map(_.description)
  def getLastUpdated(orgid: String) = rows.filter(_.orgid === orgid).map(_.lastUpdated)
  def getTag(orgid: String, tag: String) = rows.filter(_.orgid === orgid).map(_.tags.map(tags => tags +>> tag))

  /** Returns a query for the specified org attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(orgid: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.orgid === orgid)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
      case "label" => filter.map(_.label)
      case "description" => filter.map(_.description)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }

  /** Returns the actions to delete the org and the blockchains that reference it */
  def getDeleteActions(orgid: String): DBIO[_] = getOrgid(orgid).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}

// This is the org table minus the key - used as the data structure to return to the REST clients
class Org(var label: String, var description: String, var lastUpdated: String) {
  //def copy = new Org(label, description, lastUpdated)
}

