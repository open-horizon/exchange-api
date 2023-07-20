package org.openhorizon.exchangeapi.table.user

import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class UserRow(username: String,
                         orgid: String,
                         hashedPw: String,
                         admin: Boolean,
                         hubAdmin: Boolean,
                         email: String,
                         lastUpdated: String,
                         updatedBy: String) {
  def insertUser(): DBIO[_] = { //val pw = if (password == "") "" else if (Password.isHashed(password)) password else Password.hash(password)
    UsersTQ += UserRow(username, orgid, hashedPw, admin, hubAdmin, email, lastUpdated, updatedBy)
  }
  
  def upsertUser: DBIO[_] = { //val pw = if (password == "") "" else if (Password.isHashed(password)) password else Password.hash(password)
    UsersTQ.insertOrUpdate(UserRow(username, orgid, hashedPw, admin, hubAdmin, email, lastUpdated, updatedBy))
  }
  
  def updateUser(): DBIO[_] = { //val pw = if (password == "") "" else if (Password.isHashed(password)) password else Password.hash(password)
    (for {u <- UsersTQ if u.username === username} yield u).update(UserRow(username, orgid, hashedPw, admin, hubAdmin, email, lastUpdated, updatedBy)) /*
    // if password and/or email are blank, it means they should not be updated <- not supporting this anymore
    (pw, email) match {
      case ("", "") => return (for { u <- UsersTQ if u.username === username } yield (u.username,u.orgid,u.admin,u.lastUpdated)).update((username, orgid, admin, lastUpdated))
      case (_, "") => return (for { u <- UsersTQ if u.username === username } yield (u.username,u.orgid,u.password,u.admin,u.lastUpdated)).update((username, orgid, pw, admin, lastUpdated))
      case ("", _) => return (for { u <- UsersTQ if u.username === username } yield (u.username,u.orgid,u.admin,u.email,u.lastUpdated)).update((username, orgid, admin, email, lastUpdated))
      case (_, _) => return (for { u <- UsersTQ if u.username === username } yield u).update(UserRow(username, orgid, pw, admin, email, lastUpdated))
    }
    */
  }
}
