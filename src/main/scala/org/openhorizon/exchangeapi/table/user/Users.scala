package org.openhorizon.exchangeapi.table.user

import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import slick.jdbc.PostgresProfile.api._
import slick.lifted.MappedToBase.mappedToIsomorphism
import slick.lifted.{BaseColumnExtensionMethods, MappedProjection, ProvenShape, ShapedValue}

import java.sql.Timestamp
import java.util.UUID


class Users(tag: Tag) extends Table[UserRow](tag, "users") {
  def createdAt = column[java.sql.Timestamp]("created_at")
  def email = column[Option[String]]("email")
  def identityProvider = column[String]("identity_provider", O.Default("Open Horizon"))
  def isHubAdmin = column[Boolean]("is_hub_admin", O.Default(false))
  def isOrgAdmin = column[Boolean]("is_org_admin", O.Default(false))
  def modifiedAt = column[java.sql.Timestamp]("modified_at")
  def modifiedBy = column[Option[UUID]]("modified_by")
  def organization = column[String]("organization")
  def password = column[Option[String]]("password")
  def user = column[UUID]("user", O.PrimaryKey)
  def username = column[String]("username")
  
  def * : ProvenShape[UserRow] = (createdAt, email, identityProvider, isHubAdmin, isOrgAdmin, modifiedAt, modifiedBy, organization, password, user, username).mapTo[UserRow]
  //def apple: MappedProjection[Apple, (Option[String], UUID)] = (email, user).mapTo[Apple]
  def something: MappedProjection[UserRow, (Timestamp, Option[String], String, Boolean, Boolean, Timestamp, Option[UUID], String, UUID, String)] = (createdAt, email, identityProvider, isHubAdmin, isOrgAdmin, modifiedAt, modifiedBy, organization, user, username).<>(toUser, toRow)
  
  def usersUnqKey = index(name = "users_uk", on = (organization, username), unique = true)
  def usersOrgForKey = foreignKey("users_org_fk", organization, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def usersUsrForKey = foreignKey("users_usr_fk", modifiedBy, UsersTQ)(_.user.?, onUpdate=ForeignKeyAction.SetNull, onDelete=ForeignKeyAction.Cascade)
  
  def toUser(row: (Timestamp, Option[String], String, Boolean, Boolean, Timestamp, Option[UUID], String, UUID, String)): UserRow = new UserRow(row)
  
  def toRow(user: UserRow): Option[(Timestamp, Option[String], String, Boolean, Boolean, Timestamp, Option[UUID], String, UUID, String)] =
    Some((user.createdAt, user.email, user.identityProvider, user.isHubAdmin, user.isOrgAdmin, user.modifiedAt, user.modified_by, user.organization, user.user, user.username))
}
