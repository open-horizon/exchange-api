package org.openhorizon.exchangeapi.table.user

import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import slick.jdbc.PostgresProfile.api._


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
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}
