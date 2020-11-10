package com.horizon.exchangeapi.tables

import com.horizon.exchangeapi._
import slick.jdbc.PostgresProfile.api._

/** Contains the object representations of the DB tables related to users. */

//future: figure out how to use the slick type Timestamp, but have it stored in UTC
final case class UserRow(username: String, orgid: String, hashedPw: String, admin: Boolean, hubAdmin: Boolean, email: String, lastUpdated: String, updatedBy: String) {
  def insertUser(): DBIO[_] = {
    //val pw = if (password == "") "" else if (Password.isHashed(password)) password else Password.hash(password)
    UsersTQ.rows += UserRow(username, orgid, hashedPw, admin, hubAdmin, email, lastUpdated, updatedBy)
  }

  def upsertUser: DBIO[_] = {
    //val pw = if (password == "") "" else if (Password.isHashed(password)) password else Password.hash(password)
    UsersTQ.rows.insertOrUpdate(UserRow(username, orgid, hashedPw, admin, hubAdmin, email, lastUpdated, updatedBy))
  }

  def updateUser(): DBIO[_] = {
    //val pw = if (password == "") "" else if (Password.isHashed(password)) password else Password.hash(password)
    (for { u <- UsersTQ.rows if u.username === username } yield u).update(UserRow(username, orgid, hashedPw, admin, hubAdmin, email, lastUpdated, updatedBy))
    /*
    // if password and/or email are blank, it means they should not be updated <- not supporting this anymore
    (pw, email) match {
      case ("", "") => return (for { u <- UsersTQ.rows if u.username === username } yield (u.username,u.orgid,u.admin,u.lastUpdated)).update((username, orgid, admin, lastUpdated))
      case (_, "") => return (for { u <- UsersTQ.rows if u.username === username } yield (u.username,u.orgid,u.password,u.admin,u.lastUpdated)).update((username, orgid, pw, admin, lastUpdated))
      case ("", _) => return (for { u <- UsersTQ.rows if u.username === username } yield (u.username,u.orgid,u.admin,u.email,u.lastUpdated)).update((username, orgid, admin, email, lastUpdated))
      case (_, _) => return (for { u <- UsersTQ.rows if u.username === username } yield u).update(UserRow(username, orgid, pw, admin, email, lastUpdated))
    }
    */
  }
}

/** Mapping of the users db table to a scala class */
class Users(tag: Tag) extends Table[UserRow](tag, "users") {
  def username = column[String]("username", O.PrimaryKey)    // the content of this is orgid/username
  def orgid = column[String]("orgid")
  def password = column[String]("password")
  def admin = column[Boolean]("admin")
  def hubAdmin = column[Boolean]("hubadmin")
  def email = column[String]("email")
  // def lastUpdated = column[Timestamp]("lastupdated")    //someday: need this is UTC, not local time zone
  def lastUpdated = column[String]("lastupdated")
  def updatedBy = column[String]("updatedby")
  def * = (username, orgid, password, admin, hubAdmin, email, lastUpdated, updatedBy).<>(UserRow.tupled, UserRow.unapply)
  //def primKey = primaryKey("pk_pk", (username, orgid))
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ.rows)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object UsersTQ {
  // Instance to access the users table
  val rows = TableQuery[Users]

  //def getAllUsers(orgid: String) = rows.filter(_.username like orgid+"/%")
  def getAllUsers(orgid: String): Query[Users, UserRow, Seq] = rows.filter(_.orgid === orgid)
  def getAllAdmins(orgid: String): Query[Users, UserRow, Seq] = rows.filter(_.orgid === orgid).filter(_.admin === true)
  def getUser(username: String): Query[Users, UserRow, Seq] = rows.filter(_.username === username)
  def getPassword(username: String): Query[Rep[String], String, Seq] = rows.filter(_.username === username).map(_.password)
  def getAdmin(username: String): Query[Rep[Boolean], Boolean, Seq] = rows.filter(_.username === username).map(_.admin)
  def getHubAdmin(username: String): Query[Rep[Boolean], Boolean, Seq] = rows.filter(_.username === username).map(_.hubAdmin)
  //def getAdminAsString(username: String) = rows.filter(_.username === username).map(u => if (u.admin === Boolean(true)) "admin" else "")
  def getEmail(username: String): Query[Rep[String], String, Seq] = rows.filter(_.username === username).map(_.email)
  def getUpdatedBy(username: String): Query[Rep[String], String, Seq] = rows.filter(_.username === username).map(_.updatedBy)
}

final case class User(password: String, admin: Boolean, hubAdmin: Boolean, email: String, lastUpdated: String, updatedBy: String) {
  def hidePassword: User = User(StrConstants.hiddenPw, admin, hubAdmin, email, lastUpdated, updatedBy)
}
