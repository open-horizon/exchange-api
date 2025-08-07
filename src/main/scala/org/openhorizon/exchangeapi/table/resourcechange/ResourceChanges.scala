package org.openhorizon.exchangeapi.table.resourcechange

import slick.jdbc.PostgresProfile.api._

import java.time.Instant

/** Mapping of the resourcechanges db table to a scala class */
class ResourceChanges(tag: Tag) extends Table[ResourceChangeRow](tag, "resourcechanges") {
  def changeId = column[Long]("changeid", O.PrimaryKey, O.AutoInc)
  def orgId = column[String]("orgid")
  def id = column[String]("id")
  def category = column[String]("category")
  def public = column[String]("public")
  def resource = column[String]("resource")
  def operation = column[String]("operation")
  def lastUpdated = column[Instant]("lastupdated")
  def testcolumn = column[Option[Instant]]("epoch")
  
  // this describes what you get back when you return rows from a query
  def * = (changeId, orgId, id, category, public, resource, operation, lastUpdated, testcolumn).<>(ResourceChangeRow.tupled, ResourceChangeRow.unapply)
  def orgIndex = index("org_index", orgId)
  def idIndex = index("id_index", id)
  def catIndex = index("cat_index", category)
  def pubIndex = index("pub_index", public)
  def luIndex = index("lu_index", lastUpdated)
}
