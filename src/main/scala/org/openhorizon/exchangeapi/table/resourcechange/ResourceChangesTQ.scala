package org.openhorizon.exchangeapi.table.resourcechange

import slick.dbio.{Effect, NoStream}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}
import slick.sql.FixedSqlAction

import java.time.Instant

// Instance to access the ResourceChanges table
object ResourceChangesTQ extends TableQuery(new ResourceChanges(_)) {
  def getChangeId(changeid: Long): Query[Rep[Long], Long, Seq] = this.filter(_.changeId === changeid).map(_.changeId)
  def getOrgid(changeid: Long): Query[Rep[String], String, Seq] = this.filter(_.changeId === changeid).map(_.orgId)
  def getId(changeid: Long): Query[Rep[String], String, Seq] = this.filter(_.changeId === changeid).map(_.id)
  def getCategory(changeid: Long): Query[Rep[String], String, Seq] = this.filter(_.changeId === changeid).map(_.category)
  def getPublic(changeid: Long): Query[Rep[String], String, Seq] = this.filter(_.changeId === changeid).map(_.public)
  def getResource(changeid: Long): Query[Rep[String], String, Seq] = this.filter(_.changeId === changeid).map(_.resource)
  def getOperation(changeid: Long): Query[Rep[String], String, Seq] = this.filter(_.changeId === changeid).map(_.operation)
  def getLastUpdated(changeid: Long): Query[Rep[Instant], Instant, Seq] = this.filter(_.changeId === changeid).map(_.lastUpdated)
  def getRowsExpired(timeExpired: Instant): Query[ResourceChanges, ResourceChangeRow, Seq] = this.filter(_.lastUpdated < timeExpired)

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
