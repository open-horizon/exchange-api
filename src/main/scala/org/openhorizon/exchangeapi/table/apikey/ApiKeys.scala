package org.openhorizon.exchangeapi.table.apikey

import java.util.UUID
import org.openhorizon.exchangeapi.table.user.{UserRow, Users, UsersTQ}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{ForeignKeyQuery, Index, ProvenShape}
import slick.model.ForeignKeyAction.Cascade

import java.time.Instant

class ApiKeys(tag: Tag) extends Table[ApiKeyRow](tag, "apikeys") {
  def createdAt = column[Instant]("created_at")
  def createdBy = column[UUID]("created_by")
  def description = column[Option[String]]("description")
  def hashedKey = column[String]("hashedkey")
  def id = column[UUID]("id", O.PrimaryKey)
  def modifiedAt = column[Instant]("modified_at")
  def modifiedBy = column[UUID]("modified_by")
  def orgid = column[String]("orgid")
  def user = column[UUID]("user")
  def label = column[Option[String]]("label")

  def * : ProvenShape[ApiKeyRow] = (createdAt, createdBy, description, hashedKey, id, modifiedAt, modifiedBy, orgid, user, label).<>(ApiKeyRow.tupled, ApiKeyRow.unapply)

  def userFk: ForeignKeyQuery[Users, UserRow] = foreignKey("api_key_user_fk", user, UsersTQ)(_.user, onDelete = ForeignKeyAction.Cascade, onUpdate = Cascade)
  
  def hashIndex: Index = index("apikey_hash_idx", hashedKey, unique = true)
  def orgidIndex: Index = index("orgid_index", orgid, unique = false)
  def userIndex: Index = index("apikey_user_idx", user, unique = false)
}