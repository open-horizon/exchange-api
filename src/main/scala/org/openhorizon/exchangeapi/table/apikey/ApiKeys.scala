package org.openhorizon.exchangeapi.table.apikey

import java.util.UUID
import org.openhorizon.exchangeapi.table.user.UsersTQ
import slick.jdbc.PostgresProfile.api._

class ApiKeys(tag: Tag) extends Table[ApiKeyRow](tag, "apikeys") {
  def createdAt = column[java.sql.Timestamp]("created_at")
  def createdBy = column[UUID]("created_by")
  def description = column[Option[String]]("description")
  def hashedKey = column[String]("hashedkey")
  def id = column[UUID]("id", O.PrimaryKey)
  def modifiedAt = column[java.sql.Timestamp]("modified_at")
  def modifiedBy = column[UUID]("modified_by")
  def orgid = column[String]("orgid")
  def user = column[UUID]("user")
  def label = column[Option[String]]("label")

  def * = (createdAt, createdBy, description, hashedKey, id, modifiedAt, modifiedBy, orgid, user, label).<>(ApiKeyRow.tupled, ApiKeyRow.unapply)

  def userFk = foreignKey("user_fk", user, UsersTQ)(_.user, onDelete = ForeignKeyAction.Cascade)
  
  def hashIndex = index("apikey_hash_idx", hashedKey, unique = true)
  def orgidIndex = index("orgid_index", orgid, unique = false)
  def userIndex = index("apikey_user_idx", user, unique = false)
}