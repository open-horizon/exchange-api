package org.openhorizon.exchangeapi.table.managementpolicy

import org.json4s.{DefaultFormats, Formats}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.TableQuery

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
  def getPatterns(managementPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.managementPolicy === managementPolicy).map(_.patterns)
  def getStart(managementPolicy: String): Query[Rep[String], String, Seq] = this.filter(_.managementPolicy === managementPolicy).map(_.start)
  def getStartWindow(managementPolicy: String): Query[Rep[Long], Long, Seq] = this.filter(_.managementPolicy === managementPolicy).map(_.startWindow)
  
  /** Returns a query for the specified managementPolicy attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(managementPolicy: String, attrName: String): Query[_, _, Seq] = {
    val filter: Query[ManagementPolicies, ManagementPolicyRow, Seq] = this.filter(_.managementPolicy === managementPolicy) // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
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
  def getDeleteActions(managementPolicy: String): DBIO[_] = getManagementPolicy(managementPolicy).delete // with the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated blockchain rows
}
