package org.openhorizon.exchangeapi.table.organization

import org.json4s._
import org.json4s.jackson.Serialization.write
import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._
import org.openhorizon.exchangeapi.utility.ApiUtils

// Instance to access the orgs table
object OrgsTQ extends TableQuery(new Orgs(_)){
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
