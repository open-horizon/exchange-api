package org.openhorizon.exchangeapi.table.agreementbot

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}

// Instance to access the agbots table
object AgbotsTQ extends TableQuery(new Agbots(_)) {
  def getAllAgbots(orgid: String): Query[Agbots, AgbotRow, Seq] = this.filter(_.orgid === orgid)
  def getAllAgbotsId(orgid: String): Query[Rep[String], String, Seq] = this.filter(_.orgid === orgid).map(_.id)
  def getAgbot(id: String): Query[Agbots, AgbotRow, Seq] = this.filter(_.id === id)
  def getToken(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.token)
  def getOwner(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.owner)
  def getNumOwned(owner: String): Rep[Int] = this.filter(_.owner === owner).length
  def getLastHeartbeat(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.lastHeartbeat)
  def getPublicKey(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.publicKey)
  
  /** Returns a query for the specified agbot attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(id: String, attrName: String): Query[_, _, Seq] = {
    val filter = this.filter(_.id === id) // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    attrName match {
      case "token" => filter.map(_.token)
      case "name" => filter.map(_.name)
      case "owner" => filter.map(_.owner)
      case "msgEndPoint" => filter.map(_.msgEndPoint)
      case "lastHeartbeat" => filter.map(_.lastHeartbeat)
      case "publicKey" => filter.map(_.publicKey)
      case _ => null
    }
  }
}
