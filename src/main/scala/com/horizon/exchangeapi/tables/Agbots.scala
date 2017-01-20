package com.horizon.exchangeapi.tables

import org.scalatra._
// import slick.driver.PostgresDriver.api._
import slick.jdbc.PostgresProfile.api._
import java.sql.Timestamp
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import org.scalatra.json._
import com.horizon.exchangeapi._
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection

/** Contains the object representations of the DB tables related to agbots. */

// case class AgbotRow(id: String, token: String, name: String, owner: String, msgEndPoint: String, lastHeartbeat: String) {
case class AgbotRow(id: String, token: String, name: String, owner: String, msgEndPoint: String, lastHeartbeat: String, publicKey: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def toAgbot(superUser: Boolean): Agbot = {
    val tok = if (superUser) token else StrConstants.hiddenPw
    new Agbot(tok, name, owner, msgEndPoint, lastHeartbeat, publicKey)
  }

  def upsert: DBIO[_] = {
    val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)
    // If owner is root, do not set owner so we do not take over a user's agbot. It will default to root if upsert turns out to be a insert
    if (owner == "root") AgbotsTQ.rows.map(a => (a.id, a.token, a.name, a.msgEndPoint, a.lastHeartbeat, a.publicKey)).insertOrUpdate((id, tok, name, msgEndPoint, lastHeartbeat, publicKey))
    else AgbotsTQ.rows.insertOrUpdate(AgbotRow(id, tok, name, owner, msgEndPoint, lastHeartbeat, publicKey))
  }

  def update: DBIO[_] = {
    val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)
    if (owner == "") (for { a <- AgbotsTQ.rows if a.id === id } yield (a.id,a.token,a.name,a.msgEndPoint,a.lastHeartbeat,a.publicKey)).update((id, tok, name, msgEndPoint, lastHeartbeat, publicKey))
    else (for { a <- AgbotsTQ.rows if a.id === id } yield a).update(AgbotRow(id, tok, name, owner, msgEndPoint, lastHeartbeat, publicKey))
  }
}

/** Mapping of the agbots db table to a scala class */
class Agbots(tag: Tag) extends Table[AgbotRow](tag, "agbots") {
  def id = column[String]("id", O.PrimaryKey)
  def token = column[String]("token")
  def name = column[String]("name")
  def owner = column[String]("owner", O.Default("root"))  // root is the default because during upserts by root, we do not want root to take over the agbot if it already exists
  def msgEndPoint = column[String]("msgendpoint")
  def lastHeartbeat = column[String]("lastheartbeat")
  def publicKey = column[String]("publickey")
  // this describes what you get back when you return rows from a query
  def * = (id, token, name, owner, msgEndPoint, lastHeartbeat, publicKey) <> (AgbotRow.tupled, AgbotRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the agbots table
// object agbots extends TableQuery(new Agbots(_)) {
//   def listUserAgbots(username: String) = this.filter(_.owner === username)
// }
object AgbotsTQ {
  val rows = TableQuery[Agbots]

  def getAgbot(id: String) = rows.filter(_.id === id)
  def getToken(id: String) = rows.filter(_.id === id).map(_.token)
  def getOwner(id: String) = rows.filter(_.id === id).map(_.owner)
  def getLastHeartbeat(id: String) = rows.filter(_.id === id).map(_.lastHeartbeat)
  def getPublicKey(id: String) = rows.filter(_.id === id).map(_.publicKey)

  /** Returns the actions to delete the agbot and the agreements that reference it */
  def getDeleteActions(id: String): DBIO[_] = DBIO.seq(
      // now with all the foreign keys set up correctly and onDelete=cascade, the db will automatically delete these associated rows
      // AgbotAgreementsTQ.getAgreements(id).delete,            // delete agreements that reference this agbot
      getAgbot(id).delete    // delete the agbot
    )
}

// This is the agbot table minus the key - used as the data structure to return to the REST clients
class Agbot(var token: String, var name: String, var owner: String, var msgEndPoint: String, var lastHeartbeat: String, var publicKey: String) {
  def copy = new Agbot(token, name, owner, msgEndPoint, lastHeartbeat, publicKey)
}

case class AgbotAgreementRow(agrId: String, agbotId: String, workload: String, state: String, lastUpdated: String, dataLastReceived: String) {
  def toAgbotAgreement = AgbotAgreement(workload, state, lastUpdated, dataLastReceived)

  def upsert: DBIO[_] = AgbotAgreementsTQ.rows.insertOrUpdate(this)
}

class AgbotAgreements(tag: Tag) extends Table[AgbotAgreementRow](tag, "agbotagreements") {
  def agrId = column[String]("agrid", O.PrimaryKey)     // ethereum agreeement ids are unique
  def agbotId = column[String]("agbotid")
  def workload = column[String]("workload")
  def state = column[String]("state")
  def lastUpdated = column[String]("lastUpdated")
  def dataLastReceived = column[String]("dataLastReceived")
  def * = (agrId, agbotId, workload, state, lastUpdated, dataLastReceived) <> (AgbotAgreementRow.tupled, AgbotAgreementRow.unapply)
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object AgbotAgreementsTQ {
  val rows = TableQuery[AgbotAgreements]

  def getAgreements(agbotId: String) = rows.filter(_.agbotId === agbotId)
  def getAgreement(agbotId: String, agrId: String) = rows.filter( r => {r.agbotId === agbotId && r.agrId === agrId} )
  def getAgreementsWithState = rows.filter(_.state =!= "")
}

case class AgbotAgreement(workload: String, state: String, lastUpdated: String, dataLastReceived: String)


case class AgbotMsgRow(msgId: Int, agbotId: String, deviceId: String, devicePubKey: String, message: String, timeSent: String) {
  def toAgbotMsg = AgbotMsg(msgId, deviceId, devicePubKey, message, timeSent)

  def insert: DBIO[_] = (AgbotMsgsTQ.rows += this)
  def upsert: DBIO[_] = AgbotMsgsTQ.rows.insertOrUpdate(this)    // do not think we need this
}

class AgbotMsgs(tag: Tag) extends Table[AgbotMsgRow](tag, "agbotmsgs") {
  def msgId = column[Int]("msgid", O.PrimaryKey, O.AutoInc)    // this enables them to delete a msg and helps us deliver them in order
  def agbotId = column[String]("agbotid")
  def deviceId = column[String]("deviceid")
  def devicePubKey = column[String]("devicepubkey")
  def message = column[String]("message")
  def timeSent = column[String]("timesent")
  def * = (msgId, agbotId, deviceId, devicePubKey, message, timeSent) <> (AgbotMsgRow.tupled, AgbotMsgRow.unapply)
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def device = foreignKey("device_fk", deviceId, DevicesTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object AgbotMsgsTQ {
  val rows = TableQuery[AgbotMsgs]

  def getMsgs(agbotId: String) = rows.filter(_.agbotId === agbotId)  // this is that agbots msg mailbox
  def getMsg(agbotId: String, msgId: Int) = rows.filter( r => {r.agbotId === agbotId && r.msgId === msgId} )
  // def getMsgsExpired = rows.filter(_.state =!= "")
}

case class AgbotMsg(msgId: Int, deviceId: String, devicePubKey: String, message: String, timeSent: String)
