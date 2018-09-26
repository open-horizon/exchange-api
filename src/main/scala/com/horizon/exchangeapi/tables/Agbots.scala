package com.horizon.exchangeapi.tables
// import slick.driver.PostgresDriver.api._
import com.horizon.exchangeapi._
import org.json4s._
//import org.json4s.jackson.Serialization.read
import slick.jdbc.PostgresProfile.api._


/** Contains the object representations of the DB tables related to agbots. */
//case class APattern(orgid: String, pattern: String)

case class AgbotRow(id: String, orgid: String, token: String, name: String, owner: String, /*patterns: String,*/ msgEndPoint: String, lastHeartbeat: String, publicKey: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def toAgbot(superUser: Boolean): Agbot = {
    val tok = if (superUser) token else StrConstants.hiddenPw
    //val pat = if (patterns != "") read[List[APattern]](patterns) else List[APattern]()
    new Agbot(tok, name, owner, /*pat,*/ msgEndPoint, lastHeartbeat, publicKey)
  }

  def upsert: DBIO[_] = {
    val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)
    // If owner is root, do not set owner so we do not take over a user's agbot. It will default to root if upsert turns out to be a insert
    if (Role.isSuperUser(owner)) AgbotsTQ.rows.map(a => (a.id, a.orgid, a.token, a.name, /*a.patterns,*/ a.msgEndPoint, a.lastHeartbeat, a.publicKey)).insertOrUpdate((id, orgid, tok, name, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey))
    else AgbotsTQ.rows.insertOrUpdate(AgbotRow(id, orgid, tok, name, owner, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey))
  }

  def update: DBIO[_] = {
    val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)
    if (owner == "") (for { a <- AgbotsTQ.rows if a.id === id } yield (a.id,a.orgid,a.token,a.name,/*a.patterns,*/a.msgEndPoint,a.lastHeartbeat,a.publicKey)).update((id, orgid, tok, name, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey))
    else (for { a <- AgbotsTQ.rows if a.id === id } yield a).update(AgbotRow(id, orgid, tok, name, owner, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey))
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
  def * = (id, orgid, token, name, owner, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey) <> (AgbotRow.tupled, AgbotRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ.rows)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// Instance to access the agbots table
object AgbotsTQ {
  val rows = TableQuery[Agbots]

  def getAllAgbots(orgid: String) = rows.filter(_.orgid === orgid)
  def getAgbot(id: String) = rows.filter(_.id === id)
  def getToken(id: String) = rows.filter(_.id === id).map(_.token)
  def getOwner(id: String) = rows.filter(_.id === id).map(_.owner)
  def getNumOwned(owner: String) = rows.filter(_.owner === owner).length
  def getLastHeartbeat(id: String) = rows.filter(_.id === id).map(_.lastHeartbeat)
  def getPublicKey(id: String) = rows.filter(_.id === id).map(_.publicKey)

  /** Returns a query for the specified agbot attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(id: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.id === id)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
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


case class AgbotPatternRow(patId: String, agbotId: String, patternOrgid: String, pattern: String, nodeOrgid: String, lastUpdated: String) {
  def toAgbotPattern = AgbotPattern(patternOrgid, pattern, nodeOrgid, lastUpdated)

  def upsert: DBIO[_] = AgbotPatternsTQ.rows.insertOrUpdate(this)
  def insert: DBIO[_] = AgbotPatternsTQ.rows += this
}

class AgbotPatterns(tag: Tag) extends Table[AgbotPatternRow](tag, "agbotpatterns") {
  def patId = column[String]("patid")     // key - this is the pattern's org concatenated with the pattern name
  def agbotId = column[String]("agbotid")               // additional key - the composite orgid/agbotid
  def patternOrgid = column[String]("patternorgid")
  def pattern = column[String]("pattern")
  def nodeOrgid = column[String]("nodeorgid")
  def lastUpdated = column[String]("lastupdated")
  def * = (patId, agbotId, patternOrgid, pattern, nodeOrgid, lastUpdated) <> (AgbotPatternRow.tupled, AgbotPatternRow.unapply)
  def primKey = primaryKey("pk_agp", (patId, agbotId))
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object AgbotPatternsTQ {
  val rows = TableQuery[AgbotPatterns]

  def getPatterns(agbotId: String) = rows.filter(_.agbotId === agbotId)
  def getPattern(agbotId: String, patId: String) = rows.filter( r => {r.agbotId === agbotId && r.patId === patId} )
}

case class AgbotPattern(patternOrgid: String, pattern: String, nodeOrgid: String, lastUpdated: String)


case class AAWorkload(orgid: String, pattern: String, url: String)
case class AAService(orgid: String, pattern: String, url: String)

case class AgbotAgreementRow(agrId: String, agbotId: String, workloadOrgid: String, workloadPattern: String, workloadUrl: String, serviceOrgid: String, servicePattern: String, serviceUrl: String, state: String, lastUpdated: String, dataLastReceived: String) {
  def toAgbotAgreement = AgbotAgreement(AAWorkload(workloadOrgid, workloadPattern, workloadUrl), AAService(serviceOrgid, servicePattern, serviceUrl), state, lastUpdated, dataLastReceived)

  def upsert: DBIO[_] = AgbotAgreementsTQ.rows.insertOrUpdate(this)
}

class AgbotAgreements(tag: Tag) extends Table[AgbotAgreementRow](tag, "agbotagreements") {
  def agrId = column[String]("agrid", O.PrimaryKey)     // ethereum agreeement ids are unique
  def agbotId = column[String]("agbotid")
  def workloadOrgid = column[String]("workloadorgid")
  def workloadPattern = column[String]("workloadpattern")
  def workloadUrl = column[String]("workloadurl")
  def serviceOrgid = column[String]("serviceorgid")
  def servicePattern = column[String]("servicepattern")
  def serviceUrl = column[String]("serviceurl")
  def state = column[String]("state")
  def lastUpdated = column[String]("lastUpdated")
  def dataLastReceived = column[String]("dataLastReceived")
  def * = (agrId, agbotId, workloadOrgid, workloadPattern, workloadUrl, serviceOrgid, servicePattern, serviceUrl, state, lastUpdated, dataLastReceived) <> (AgbotAgreementRow.tupled, AgbotAgreementRow.unapply)
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object AgbotAgreementsTQ {
  val rows = TableQuery[AgbotAgreements]

  def getAgreements(agbotId: String) = rows.filter(_.agbotId === agbotId)
  def getAgreement(agbotId: String, agrId: String) = rows.filter( r => {r.agbotId === agbotId && r.agrId === agrId} )
  def getNumOwned(agbotId: String) = rows.filter(_.agbotId === agbotId).length
  def getAgreementsWithState = rows.filter(_.state =!= "")
}

case class AgbotAgreement(workload: AAWorkload, service: AAService, state: String, lastUpdated: String, dataLastReceived: String)


/** The agbotmsgs table holds the msgs sent to agbots by nodes */
case class AgbotMsgRow(msgId: Int, agbotId: String, nodeId: String, nodePubKey: String, message: String, timeSent: String, timeExpires: String) {
  def toAgbotMsg = AgbotMsg(msgId, nodeId, nodePubKey, message, timeSent, timeExpires)

  def insert: DBIO[_] = ((AgbotMsgsTQ.rows returning AgbotMsgsTQ.rows.map(_.msgId)) += this)  // inserts the row and returns the msgId of the new row
  def upsert: DBIO[_] = AgbotMsgsTQ.rows.insertOrUpdate(this)    // do not think we need this
}

class AgbotMsgs(tag: Tag) extends Table[AgbotMsgRow](tag, "agbotmsgs") {
  def msgId = column[Int]("msgid", O.PrimaryKey, O.AutoInc)    // this enables them to delete a msg and helps us deliver them in order
  def agbotId = column[String]("agbotid")       // msg recipient
  def nodeId = column[String]("nodeid")     // msg sender
  def nodePubKey = column[String]("nodepubkey")
  def message = column[String]("message")
  def timeSent = column[String]("timesent")
  def timeExpires = column[String]("timeexpires")
  def * = (msgId, agbotId, nodeId, nodePubKey, message, timeSent, timeExpires) <> (AgbotMsgRow.tupled, AgbotMsgRow.unapply)
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def node = foreignKey("node_fk", nodeId, NodesTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object AgbotMsgsTQ {
  val rows = TableQuery[AgbotMsgs]

  def getMsgs(agbotId: String) = rows.filter(_.agbotId === agbotId)  // this is that agbots msg mailbox
  def getMsg(agbotId: String, msgId: Int) = rows.filter( r => {r.agbotId === agbotId && r.msgId === msgId} )
  def getMsgsExpired = rows.filter(_.timeExpires < ApiTime.nowUTC)
  def getNumOwned(agbotId: String) = rows.filter(_.agbotId === agbotId).length
}

case class AgbotMsg(msgId: Int, nodeId: String, nodePubKey: String, message: String, timeSent: String, timeExpires: String)
