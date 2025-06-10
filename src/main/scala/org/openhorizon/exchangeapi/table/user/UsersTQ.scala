package org.openhorizon.exchangeapi.table.user

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Query, Rep, TableQuery}

object UsersTQ extends TableQuery(new Users(_)) {
  //def getAllUsers(organization: String) = this.filter(_.organization like organization+"/%")
  def getAllUsers(organization: String): Query[Users, UserRow, Seq] = this.filter(_.organization === organization)
  def getAllAdmins(organization: String): Query[Users, UserRow, Seq] = this.filter(_.organization === organization).filter(r => {
    r.isHubAdmin || r.isOrgAdmin
  })
  //def getAllUsersUsername(organization: String): Query[Rep[String], String, Seq] = this.filter(_.organization === organization).map(_.username)
  //def getUser(resource: String): Query[Users, UserRow, Seq] = this.filter(user => ((user.organization++"/"++user.username) == resource))
  /*def getUserIfAdmin(resource: String): Query[Users, UserRow, Seq] =
    this.filter(
      r => {
        (r.organization + "/" + r.username).equals(resource)
      })
        .filter(r => r.isHubAdmin || r.isOrgAdmin)*/
  //def getPassword(resource: String): Query[Rep[Option[String]], Option[String], Seq] = this.filter(user => ((user.organization ++ "/" ++ user.username) === resource)).map(_.password)
  //def getIsOrgAdmin(resource: String): Query[Rep[Boolean], Boolean, Seq] = this.filter(user => ((user.organization ++ "/" ++ user.username) === resource)).map(_.isOrgAdmin)
  //def getIsHubAdmin(resource: String): Query[Rep[Boolean], Boolean, Seq] = this.filter(user => ((user.organization ++ "/" ++ user.username) === resource)).map(_.isHubAdmin)
  
  //def getAdminAsString(username: String) = this.filter(_.username === username).map(u => if (u.admin === Boolean(true)) "admin" else "")
  //def getEmail(resource: String): Query[Rep[Option[String]], Option[String], Seq] = this.filter(user => ((user.organization ++ "/" ++ user.username) === resource)).map(_.email)
  //def getModifiedBy(resource: String): Query[Rep[String], String, Seq] = this.filter(user => ((user.organization ++ "/" ++ user.username) === resource)).map(user => (user.organization ++ "/" ++ user.username))
}
