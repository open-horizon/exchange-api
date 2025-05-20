package org.openhorizon.exchangeapi.table.agreementbot

import org.openhorizon.exchangeapi.auth.Role
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{ApiTime, StrConstants}
import slick.jdbc.PostgresProfile.api._


class Agbots(tag: Tag) extends Table[AgbotRow](tag, "agbots") {
  def id = column[String]("id", O.PrimaryKey)    // the content of this is orgid/username
  def orgid = column[String]("orgid")
  def token = column[String]("token")
  def name = column[String]("name")
  def owner = column[java.util.UUID]("owner")  // root is the default because during upserts by root, we do not want root to take over the agbot if it already exists
  //def patterns = column[String]("patterns")
  def msgEndPoint = column[String]("msgendpoint")
  def lastHeartbeat = column[String]("lastheartbeat")
  def publicKey = column[String]("publickey")
  // this describes what you get back when you return rows from a query
  def * = (id, orgid, token, name, owner, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey).mapTo[AgbotRow]
  def user = foreignKey("user_fk", owner, UsersTQ)(_.user, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}
