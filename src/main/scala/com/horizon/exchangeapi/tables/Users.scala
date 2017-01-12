package com.horizon.exchangeapi.tables

import org.scalatra._
// import slick.driver.PostgresDriver.api._
import slick.jdbc.PostgresProfile.api._
import java.sql.Timestamp
import com.horizon.exchangeapi._
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection

/** Contains the object representations of the DB tables related to users. */

//TODO: figure out how to use the slick type Timestamp, but have it stored in UTC
// case class UserRow(username: String, password: String, email: String, lastUpdated: Timestamp) {
case class UserRow(username: String, password: String, email: String, lastUpdated: String) {
  def insertUser: DBIO[_] = {
    val pw = if (password == "") "" else if (Password.isHashed(password)) password else Password.hash(password)
    UsersTQ.rows += (UserRow(username, pw, email, lastUpdated))
  }

  def upsertUser: DBIO[_] = {
    val pw = if (password == "") "" else if (Password.isHashed(password)) password else Password.hash(password)
    UsersTQ.rows.insertOrUpdate(UserRow(username, pw, email, lastUpdated))
  }

  def updateUser: DBIO[_] = {
    val pw = if (password == "") "" else if (Password.isHashed(password)) password else Password.hash(password)
    // if password and/or email are blank, it means they should not be updated
    (pw, email) match {
      case ("", "") => return (for { u <- UsersTQ.rows if u.username === username } yield (u.username,u.lastUpdated)).update((username, lastUpdated))
      case (_, "") => return (for { u <- UsersTQ.rows if u.username === username } yield (u.username,u.password,u.lastUpdated)).update((username, pw, lastUpdated))
      case ("", _) => return (for { u <- UsersTQ.rows if u.username === username } yield (u.username,u.email,u.lastUpdated)).update((username, email, lastUpdated))
      case (_, _) => return (for { u <- UsersTQ.rows if u.username === username } yield u).update(UserRow(username, pw, email, lastUpdated))
    }
  }
}

/** Mapping of the users db table to a scala class */
class Users(tag: Tag) extends Table[UserRow](tag, "users") {
  def username = column[String]("username", O.PrimaryKey)
  def password = column[String]("password")
  def email = column[String]("email")
  // def lastUpdated = column[Timestamp]("lastupdated")    //TODO: need this is UTC, not local time zone
  def lastUpdated = column[String]("lastupdated")    //TODO: need this is UTC, not local time zone
  def * = (username, password, email, lastUpdated) <> (UserRow.tupled, UserRow.unapply)
}

object UsersTQ {
  // Instance to access the users table
  val rows = TableQuery[Users]

  def getUser(username: String) = rows.filter(_.username === username)
  def getPassword(username: String) = rows.filter(_.username === username).map(_.password)
  def getEmail(username: String) = rows.filter(_.username === username).map(_.email)
}

case class User(password: String, email: String, lastUpdated: String) {
  def hidePassword = User(StrConstants.hiddenPw, email, lastUpdated)
}
