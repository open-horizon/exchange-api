package org.openhorizon.exchangeapi.table.user

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Query, Rep, TableQuery}

object UsersTQ extends TableQuery(new Users(_)) {
  //def getAllUsers(orgid: String) = this.filter(_.username like orgid+"/%")
  def getAllUsers(orgid: String): Query[Users, UserRow, Seq] = this.filter(_.orgid === orgid)
  def getAllAdmins(orgid: String): Query[Users, UserRow, Seq] = this.filter(_.orgid === orgid).filter(r => {
    r.admin || r.hubAdmin
  })
  def getAllUsersUsername(orgid: String): Query[Rep[String], String, Seq] = this.filter(_.orgid === orgid).map(_.username)
  def getUser(username: String): Query[Users, UserRow, Seq] = this.filter(_.username === username)
  def getUserIfAdmin(username: String): Query[Users, UserRow, Seq] = this.filter(r => {
    r.username === username && (r.admin || r.hubAdmin)
  })
  def getPassword(username: String): Query[Rep[String], String, Seq] = this.filter(_.username === username).map(_.password)
  def getAdmin(username: String): Query[Rep[Boolean], Boolean, Seq] = this.filter(_.username === username).map(_.admin)
  def getHubAdmin(username: String): Query[Rep[Boolean], Boolean, Seq] = this.filter(_.username === username).map(_.hubAdmin)
  
  //def getAdminAsString(username: String) = this.filter(_.username === username).map(u => if (u.admin === Boolean(true)) "admin" else "")
  def getEmail(username: String): Query[Rep[String], String, Seq] = this.filter(_.username === username).map(_.email)
  def getUpdatedBy(username: String): Query[Rep[String], String, Seq] = this.filter(_.username === username).map(_.updatedBy)
}
