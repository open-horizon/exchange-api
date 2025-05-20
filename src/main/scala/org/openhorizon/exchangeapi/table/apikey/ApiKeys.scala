package org.openhorizon.exchangeapi.table.apikey
import org.openhorizon.exchangeapi.table.user.UsersTQ
import slick.jdbc.PostgresProfile.api._


class ApiKeys(tag: Tag) extends Table[ApiKeyRow](tag, "apikeys") {
  def orgid = column[String]("orgid")
  def id = column[String]("id", O.PrimaryKey) // UUID v4 string
  def username = column[String]("username")   // composite id: org/user
  def description = column[String]("description")
  def hashedKey = column[String]("hashedkey")
  def createdAt = column[String]("created_at")
  def createdBy = column[String]("created_by")
  def modifiedAt = column[String]("modified_at")
  def modifiedBy = column[String]("modified_by")
def * = (orgid, id, username, description, hashedKey, createdAt, createdBy, modifiedAt, modifiedBy).<>(ApiKeyRow.tupled, ApiKeyRow.unapply)

  def userFk = foreignKey("user_fk", username, UsersTQ)(_.username, onDelete = ForeignKeyAction.Cascade)

  def hashIndex = index("apikey_hash_idx", hashedKey, unique = true)
  
  // Add an index for orgid and user 
  def orgidIndex = index("orgid_index", orgid, unique = false)
  def userIndex = index("apikey_user_idx", username,unique=false)



}
