package com.horizon.exchangeapi.tables

import org.json4s._
//import org.json4s.jackson.Serialization.read
import slick.jdbc.PostgresProfile.api._


/** Contains the object representations of the DB tables related to orgs. */

case class OrgRow(orgId: String, label: String, description: String, lastUpdated: String) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def toOrg: Org = {
    new Org(label, description, lastUpdated)
  }

  // update returns a DB action to update this row
  def update: DBIO[_] = (for { m <- OrgsTQ.rows if m.orgId === orgId } yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = OrgsTQ.rows += this
}

/** Mapping of the orgs db table to a scala class */
class Orgs(tag: Tag) extends Table[OrgRow](tag, "orgs") {
  def orgId = column[String]("orgid", O.PrimaryKey)     // the org id
  def label = column[String]("label")
  def description = column[String]("description")
  def lastUpdated = column[String]("lastupdated")
  // this describes what you get back when you return rows from a query
  def * = (orgId, label, description, lastUpdated) <> (OrgRow.tupled, OrgRow.unapply)
}

// Instance to access the orgs table
object OrgsTQ {
  val rows = TableQuery[Orgs]

  def getOrg(org: String) = rows.filter(_.orgId === org)
  def getLabel(org: String) = rows.filter(_.orgId === org).map(_.label)
  def getDescription(org: String) = rows.filter(_.orgId === org).map(_.description)
  def getLastUpdated(org: String) = rows.filter(_.orgId === org).map(_.lastUpdated)

  /** Returns a query for the specified org attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(org: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.orgId === org)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
      case "label" => filter.map(_.label)
      case "description" => filter.map(_.description)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }

  /** Returns the actions to delete the org and the blockchains that reference it */
  def getDeleteActions(org: String): DBIO[_] = getOrg(org).delete   // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}

// This is the org table minus the key - used as the data structure to return to the REST clients
class Org(var label: String, var description: String, var lastUpdated: String) {
  //def copy = new Org(label, description, lastUpdated)
}

