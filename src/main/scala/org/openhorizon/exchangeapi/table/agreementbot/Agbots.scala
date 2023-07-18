package org.openhorizon.exchangeapi.table.agreementbot

import org.json4s._
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.{ApiTime, Role, StrConstants}
import slick.jdbc.PostgresProfile.api._


/** Contains the object representations of the DB tables related to agbots. */

final case class AgbotRow(id: String, orgid: String, token: String, name: String, owner: String, /*patterns: String,*/ msgEndPoint: String, lastHeartbeat: String, publicKey: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def toAgbot(superUser: Boolean): Agbot = {
    val tok: String = if (superUser) token else StrConstants.hiddenPw
    //val pat = if (patterns != "") read[List[APattern]](patterns) else List[APattern]()
    new Agbot(tok, name, owner, /*pat,*/ msgEndPoint, lastHeartbeat, publicKey)
  }

  def upsert: DBIO[_] = {
    //val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)  <- token is already hashed
    // If owner is root, do not set owner so we do not take over a user's agbot. It will default to root if upsert turns out to be a insert
    if (Role.isSuperUser(owner)) AgbotsTQ.map(a => (a.id, a.orgid, a.token, a.name, /*a.patterns,*/ a.msgEndPoint, a.lastHeartbeat, a.publicKey)).insertOrUpdate((id, orgid, token, name, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey))
    else AgbotsTQ.insertOrUpdate(AgbotRow(id, orgid, token, name, owner, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey))
  }

  def update: DBIO[_] = {
    //val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)  <- token is already hashed
    if (owner == "") (for { a <- AgbotsTQ if a.id === id } yield (a.id,a.orgid,a.token,a.name,/*a.patterns,*/a.msgEndPoint,a.lastHeartbeat,a.publicKey)).update((id, orgid, token, name, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey))
    else (for { a <- AgbotsTQ if a.id === id } yield a).update(AgbotRow(id, orgid, token, name, owner, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey))
  }
}

/** Mapping of the agbots db table to a scala class */
class Agbots(tag: Tag) extends Table[AgbotRow](tag, "agbots") {
  def id = column[String]("id", O.PrimaryKey)    // the content of this is orgid/username
  def orgid = column[String]("orgid")
  def token = column[String]("token")
  def name = column[String]("name")
  def owner = column[String]("owner", O.Default(Role.superUser))  // root is the default because during upserts by root, we do not want root to take over the agbot if it already exists
  //def patterns = column[String]("patterns")
  def msgEndPoint = column[String]("msgendpoint")
  def lastHeartbeat = column[String]("lastheartbeat")
  def publicKey = column[String]("publickey")
  // this describes what you get back when you return rows from a query
  def * = (id, orgid, token, name, owner, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey).<>(AgbotRow.tupled, AgbotRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the agbots table
object AgbotsTQ extends TableQuery(new Agbots(_)) {
  def getAllAgbots(orgid: String): Query[Agbots, AgbotRow, Seq] = this.filter(_.orgid === orgid)
  def getAllAgbotsId(orgid: String): Query[Rep[String], String, Seq] = this.filter(_.orgid === orgid).map(_.id)
  def getAgbot(id: String): Query[Agbots, AgbotRow, Seq] = this.filter(_.id === id)
  def getToken(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.token)
  def getOwner(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.owner)
  def getNumOwned(owner: String): Rep[Int] = this.filter(_.owner === owner).length
  def getLastHeartbeat(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.lastHeartbeat)
  def getPublicKey(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.publicKey)

  /** Returns a query for the specified agbot attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(id: String, attrName: String): Query[_,_,Seq] = {
    val filter = this.filter(_.id === id)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    attrName match {
      case "token" => filter.map(_.token)
      case "name" => filter.map(_.name)
      case "owner" => filter.map(_.owner)
      case "msgEndPoint" => filter.map(_.msgEndPoint)
      case "lastHeartbeat" => filter.map(_.lastHeartbeat)
      case "publicKey" => filter.map(_.publicKey)
      case _ => null
    }
  }
}

// This is the agbot table minus the key - used as the data structure to return to the REST clients
class Agbot(var token: String, var name: String, var owner: String, /*var patterns: List[APattern],*/ var msgEndPoint: String, var lastHeartbeat: String, var publicKey: String) {
  def copy = new Agbot(token, name, owner, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey)
}


final case class AgbotPatternRow(patId: String, agbotId: String, patternOrgid: String, pattern: String, nodeOrgid: String, lastUpdated: String) {
  def toAgbotPattern: AgbotPattern = AgbotPattern(patternOrgid, pattern, nodeOrgid, lastUpdated)

  def upsert: DBIO[_] = AgbotPatternsTQ.insertOrUpdate(this)
  def insert: DBIO[_] = AgbotPatternsTQ += this
}

class AgbotPatterns(tag: Tag) extends Table[AgbotPatternRow](tag, "agbotpatterns") {
  def patId = column[String]("patid")     // key - this is the pattern's org concatenated with the pattern name
  def agbotId = column[String]("agbotid")               // additional key - the composite orgid/agbotid
  def patternOrgid = column[String]("patternorgid")
  def pattern = column[String]("pattern")
  def nodeOrgid = column[String]("nodeorgid")
  def lastUpdated = column[String]("lastupdated")
  def * = (patId, agbotId, patternOrgid, pattern, nodeOrgid, lastUpdated).<>(AgbotPatternRow.tupled, AgbotPatternRow.unapply)
  def primKey = primaryKey("pk_agp", (patId, agbotId))
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object AgbotPatternsTQ extends TableQuery(new AgbotPatterns(_)) {
  def getPatterns(agbotId: String): Query[AgbotPatterns, AgbotPatternRow, Seq] = this.filter(_.agbotId === agbotId)
  def getPattern(agbotId: String, patId: String): Query[AgbotPatterns, AgbotPatternRow, Seq] = this.filter(r => {r.agbotId === agbotId && r.patId === patId} )
}

final case class AgbotPattern(patternOrgid: String, pattern: String, nodeOrgid: String, lastUpdated: String)


final case class AgbotBusinessPolRow(busPolId: String, agbotId: String, businessPolOrgid: String, businessPol: String, nodeOrgid: String, lastUpdated: String) {
  def toAgbotBusinessPol: AgbotBusinessPol = AgbotBusinessPol(businessPolOrgid, businessPol, nodeOrgid, lastUpdated)

  def upsert: DBIO[_] = AgbotBusinessPolsTQ.insertOrUpdate(this)
  def insert: DBIO[_] = AgbotBusinessPolsTQ += this
}

class AgbotBusinessPols(tag: Tag) extends Table[AgbotBusinessPolRow](tag, "agbotbusinesspols") {
  def busPolId = column[String]("buspolid")     // key - this is the businessPol's org concatenated with the businessPol name
  def agbotId = column[String]("agbotid")               // additional key - the composite orgid/agbotid
  def businessPolOrgid = column[String]("businesspolorgid")
  def businessPol = column[String]("businesspol")
  def nodeOrgid = column[String]("nodeorgid")
  def lastUpdated = column[String]("lastupdated")
  def * = (busPolId, agbotId, businessPolOrgid, businessPol, nodeOrgid, lastUpdated).<>(AgbotBusinessPolRow.tupled, AgbotBusinessPolRow.unapply)
  def primKey = primaryKey("pk_agbp", (busPolId, agbotId))
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object AgbotBusinessPolsTQ extends TableQuery(new AgbotBusinessPols(_)) {
  def getBusinessPols(agbotId: String): Query[AgbotBusinessPols, AgbotBusinessPolRow, Seq] = this.filter(_.agbotId === agbotId)
  def getBusinessPol(agbotId: String, busPolId: String): Query[AgbotBusinessPols, AgbotBusinessPolRow, Seq] = this.filter(r => {r.agbotId === agbotId && r.busPolId === busPolId} )
}

final case class AgbotBusinessPol(businessPolOrgid: String, businessPol: String, nodeOrgid: String, lastUpdated: String)


final case class AAWorkload(orgid: String, pattern: String, url: String)
final case class AAService(orgid: String, pattern: String, url: String)

final case class AgbotAgreementRow(agrId: String, agbotId: String, serviceOrgid: String, servicePattern: String, serviceUrl: String, state: String, lastUpdated: String, dataLastReceived: String) {
  def toAgbotAgreement: AgbotAgreement = AgbotAgreement(AAService(serviceOrgid, servicePattern, serviceUrl), state, lastUpdated, dataLastReceived)

  def upsert: DBIO[_] = AgbotAgreementsTQ.insertOrUpdate(this)
}

class AgbotAgreements(tag: Tag) extends Table[AgbotAgreementRow](tag, "agbotagreements") {
  def agrId = column[String]("agrid", O.PrimaryKey)     // agreeement ids are unique
  def agbotId = column[String]("agbotid")
  def serviceOrgid = column[String]("serviceorgid")
  def servicePattern = column[String]("servicepattern")
  def serviceUrl = column[String]("serviceurl")
  def state = column[String]("state")
  def lastUpdated = column[String]("lastUpdated")
  def dataLastReceived = column[String]("dataLastReceived")
  def * = (agrId, agbotId, serviceOrgid, servicePattern, serviceUrl, state, lastUpdated, dataLastReceived).<>(AgbotAgreementRow.tupled, AgbotAgreementRow.unapply)
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object AgbotAgreementsTQ extends TableQuery(new AgbotAgreements(_)) {
  def getAgreements(agbotId: String): Query[AgbotAgreements, AgbotAgreementRow, Seq] = this.filter(_.agbotId === agbotId)
  def getAgreement(agbotId: String, agrId: String): Query[AgbotAgreements, AgbotAgreementRow, Seq] = this.filter(r => {r.agbotId === agbotId && r.agrId === agrId} )
  def getNumOwned(agbotId: String): Rep[Int] = this.filter(_.agbotId === agbotId).length
  def getAgreementsWithState = this.filter(_.state =!= "")
}

final case class AgbotAgreement(service: AAService, state: String, lastUpdated: String, dataLastReceived: String)


/** The agbotmsgs table holds the msgs sent to agbots by nodes */
final case class AgbotMsgRow(msgId: Int, agbotId: String, nodeId: String, nodePubKey: String, message: String, timeSent: String, timeExpires: String) {
  def toAgbotMsg: AgbotMsg = AgbotMsg(msgId, nodeId, nodePubKey, message, timeSent, timeExpires)

  def insert: DBIO[_] = ((AgbotMsgsTQ returning AgbotMsgsTQ.map(_.msgId)) += this)  // inserts the row and returns the msgId of the new row
  def upsert: DBIO[_] = AgbotMsgsTQ.insertOrUpdate(this)    // do not think we need this
}

class AgbotMsgs(tag: Tag) extends Table[AgbotMsgRow](tag, "agbotmsgs") {
  def msgId = column[Int]("msgid", O.PrimaryKey, O.AutoInc)    // this enables them to delete a msg and helps us deliver them in order
  def agbotId = column[String]("agbotid")       // msg recipient
  def nodeId = column[String]("nodeid")     // msg sender
  def nodePubKey = column[String]("nodepubkey")
  def message = column[String]("message")
  def timeSent = column[String]("timesent")
  def timeExpires = column[String]("timeexpires")
  def * = (msgId, agbotId, nodeId, nodePubKey, message, timeSent, timeExpires).<>(AgbotMsgRow.tupled, AgbotMsgRow.unapply)
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  // Can't keep a node foreign key because if a node is deleted during un-registration, the agbot needs to have a chance to process the agreement cancellation msg and with the node foreign key the message is deleted when the node is deleted
  // def node = foreignKey("node_fk", nodeId, NodesTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object AgbotMsgsTQ extends TableQuery(new AgbotMsgs(_)) {
  def getMsgs(agbotId: String): Query[AgbotMsgs, AgbotMsgRow, Seq] = this.filter(_.agbotId === agbotId)  // this is that agbots msg mailbox
  def getMsg(agbotId: String, msgId: Int): Query[AgbotMsgs, AgbotMsgRow, Seq] = this.filter(r => {r.agbotId === agbotId && r.msgId === msgId} )
  def getMsgsExpired = this.filter(_.timeExpires < ApiTime.nowUTC)
  def getNumOwned(agbotId: String): Rep[Int] = this.filter(_.agbotId === agbotId).length
}

final case class AgbotMsg(msgId: Int, nodeId: String, nodePubKey: String, message: String, timeSent: String, timeExpires: String)
