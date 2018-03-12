package com.horizon.exchangeapi.tables
// import slick.driver.PostgresDriver.api._
import com.horizon.exchangeapi._
import org.json4s._
import org.json4s.jackson.Serialization.read
import slick.jdbc.PostgresProfile.api._
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection

/** We define this trait because services in the DB and in the search criteria need the same methods, but have slightly different constructor args */
trait RegServiceTrait {
  def url: String
  def properties: List[Prop]

  /** Returns an error msg if the user input is invalid. */
  def validate: Option[String] = {
    for (p <- properties) {
      p.validate match {
        case Some(msg) => return Option[String](url+": "+msg)     // prepend the url so they know which microservice was bad
        case None => ;      // continue checking
      }
    }
    return None     // this means it is valid
  }

  /** Returns true if this micro (the search) matches that micro (an entry in the db)
    * Rules for comparison:
    * - if both parties do not have the same property names, it is as if wildcard was specified
    */
  def matches(that: RegService): Boolean = {
    if (url != that.url) return false
    // go thru each of our props, finding and comparing the corresponding prop in that
    for (thatP <- that.properties) {
      properties.find(p => thatP.name == p.name) match {
        case None => ;        // if the node does not specify this property, that is equivalent to it specifying wildcard
        case Some(p) => if (!p.matches(thatP)) return false
      }
    }
    return true
  }
}

/** 1 microservice in the search criteria */
case class RegServiceSearch(url: String, properties: List[Prop]) extends RegServiceTrait

/** Contains the object representations of the DB tables related to nodes. */
case class RegService(url: String, numAgreements: Int, policy: String, properties: List[Prop]) extends RegServiceTrait

case class NodeRow(id: String, orgid: String, token: String, name: String, owner: String, pattern: String, regServices: String, msgEndPoint: String, softwareVersions: String, lastHeartbeat: String, publicKey: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def toNode(superUser: Boolean): Node = {
    val tok = if (superUser) token else StrConstants.hiddenPw
    val swv = if (softwareVersions != "") read[Map[String,String]](softwareVersions) else Map[String,String]()
    val rsvc = if (regServices != "") read[List[RegService]](regServices) else List[RegService]()
    new Node(tok, name, owner, pattern, List[RegMicroservice](), rsvc, msgEndPoint, swv, lastHeartbeat, publicKey)
  }

  def putInHashMap(superUser: Boolean, nodes: MutableHashMap[String,Node]): Unit = {
    nodes.get(id) match {
      case Some(_) => ; // do not need to add the node entry, because it is already there
      case None => nodes.put(id, toNode(superUser))
    }
  }

  def upsert: DBIO[_] = {
    // Note: this currently does not do the right thing for a blank token
    val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)
    if (Role.isSuperUser(owner)) NodesTQ.rows.map(d => (d.id, d.orgid, d.token, d.name, d.pattern, d.regServices, d.msgEndPoint, d.softwareVersions, d.lastHeartbeat, d.publicKey)).insertOrUpdate((id, orgid, tok, name, pattern, regServices, msgEndPoint, softwareVersions, lastHeartbeat, publicKey))
    else NodesTQ.rows.insertOrUpdate(NodeRow(id, orgid, tok, name, owner, pattern, regServices, msgEndPoint, softwareVersions, lastHeartbeat, publicKey))
  }

  def update: DBIO[_] = {
    // Note: this currently does not do the right thing for a blank token
    val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)
    if (owner == "") (for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.orgid,d.token,d.name,d.pattern,d.regServices,d.msgEndPoint,d.softwareVersions,d.lastHeartbeat,d.publicKey)).update((id, orgid, tok, name, pattern, regServices, msgEndPoint, softwareVersions, lastHeartbeat, publicKey))
    else (for { d <- NodesTQ.rows if d.id === id } yield d).update(NodeRow(id, orgid, tok, name, owner, pattern, regServices, msgEndPoint, softwareVersions, lastHeartbeat, publicKey))
  }
}

/** Mapping of the nodes db table to a scala class */
class Nodes(tag: Tag) extends Table[NodeRow](tag, "nodes") {
  def id = column[String]("id", O.PrimaryKey)
  def orgid = column[String]("orgid")
  def token = column[String]("token")
  def name = column[String]("name")
  def owner = column[String]("owner", O.Default(Role.superUser))  // root is the default because during upserts by root, we do not want root to take over the node if it already exists
  def pattern = column[String]("pattern")       // this is orgid/patternname
  def regServices = column[String]("regservices")
  def msgEndPoint = column[String]("msgendpoint")
  def softwareVersions = column[String]("swversions")
  def publicKey = column[String]("publickey")     // this is last because that is where alter table in upgradedb puts it
  def lastHeartbeat = column[String]("lastheartbeat")
  // this describes what you get back when you return rows from a query
  def * = (id, orgid, token, name, owner, pattern, regServices, msgEndPoint, softwareVersions, lastHeartbeat, publicKey) <> (NodeRow.tupled, NodeRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ.rows)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ.rows)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  //def patKey = foreignKey("pattern_fk", pattern, PatternsTQ.rows)(_.pattern, onUpdate=ForeignKeyAction.Cascade)     // <- we can't make this a foreign key because it is optional
}

// Instance to access the nodes table
// object nodes extends TableQuery(new Nodes(_)) {
//   def listUserNodes(username: String) = this.filter(_.owner === username)
// }
object NodesTQ {
  val rows = TableQuery[Nodes]

  def getAllNodes(orgid: String) = rows.filter(_.orgid === orgid)
  def getNonPatternNodes(orgid: String) = rows.filter(r => {r.orgid === orgid && r.pattern === ""})
  def getNode(id: String) = rows.filter(_.id === id)
  def getToken(id: String) = rows.filter(_.id === id).map(_.token)
  def getOwner(id: String) = rows.filter(_.id === id).map(_.owner)
  def getPattern(id: String) = rows.filter(_.id === id).map(_.pattern)
  def getNumOwned(owner: String) = rows.filter(_.owner === owner).length
  def getLastHeartbeat(id: String) = rows.filter(_.id === id).map(_.lastHeartbeat)
  def getPublicKey(id: String) = rows.filter(_.id === id).map(_.publicKey)

  /** Returns a query for the specified node attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(id: String, attrName: String): Query[_,_,Seq] = {
    val filter = rows.filter(_.id === id)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    return attrName match {
      case "token" => filter.map(_.token)
      case "name" => filter.map(_.name)
      case "owner" => filter.map(_.owner)
      case "pattern" => filter.map(_.pattern)
      case "regServices" => filter.map(_.regServices)
      case "msgEndPoint" => filter.map(_.msgEndPoint)
      case "softwareVersions" => filter.map(_.softwareVersions)
      case "lastHeartbeat" => filter.map(_.lastHeartbeat)
      case "publicKey" => filter.map(_.publicKey)
      case _ => null
    }
  }

  /** Separate the join of the nodes, microservices, properties, and swversions tables into their respective scala classes (collapsing duplicates) and return a hash containing it all.
    * Note: this can also be used when querying node rows that have services (not microservices), because the services are faithfully preserved in the Node object. */
  def parseJoin(superUser: Boolean, list: Seq[(NodeRow, Option[RegMicroserviceRow], Option[PropRow])] ): Map[String,Node] = {
    // Separate the partially duplicate join rows into maps that only keep unique values
    val nodes = new MutableHashMap[String,Node]    // the key is node id
    val micros = new MutableHashMap[String,MutableHashMap[String,RegMicroservice]]    // 1st key is node id, 2nd key is micro id
    val props = new MutableHashMap[String,MutableHashMap[String,Prop]]    // 1st key is micro id, 2nd key is prop id
    for ((d, mOption, pOption) <- list) {
      d.putInHashMap(superUser, nodes)
      if (mOption.isDefined) mOption.get.putInHashMap(micros)
      if (pOption.isDefined) pOption.get.putInHashMap(props)
    }

    // Now fill in the nodes map, turning the maps we created above for micros, props into lists
    for ((nodeId, d) <- nodes) {
      if (micros.get(nodeId).isDefined) {
        var microList = ListBuffer[RegMicroservice]()
        for ((msId, m) <- micros(nodeId)) {
          val propList = if (props.get(msId).isDefined) props(msId).values.toList else List[Prop]()
          microList += RegMicroservice(m.url, m.numAgreements, m.policy, propList)
        }
        d.registeredMicroservices = microList.toList    // replace the empty micro list we put in there initially
      }
    }
    nodes.toMap
  }
}

// This is the node table minus the key - used as the data structure to return to the REST clients
class Node(var token: String, var name: String, var owner: String, var pattern: String, var registeredMicroservices: List[RegMicroservice], var registeredServices: List[RegService], var msgEndPoint: String, var softwareVersions: Map[String,String], var lastHeartbeat: String, var publicKey: String) {
  def copy = new Node(token, name, owner, pattern, registeredMicroservices, registeredServices, msgEndPoint, softwareVersions, lastHeartbeat, publicKey)
}


case class ContainerStatus(name: String, image: String, created: Int, state: String)
case class OneMicroservice(specRef: String, orgid: String, version: String, arch: String, containerStatus: List[ContainerStatus])
case class OneWorkload(agreementId: String, workloadUrl: String, orgid: String, version: String, arch: String, containerStatus: List[ContainerStatus])
case class OneService(agreementId: String, serviceUrl: String, orgid: String, version: String, arch: String, containerStatus: List[ContainerStatus])

case class NodeStatusRow(nodeId: String, connectivity: String, microservices: String, workloads: String, services: String, lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def toNodeStatus: NodeStatus = {
    val con = if (connectivity != "") read[Map[String,Boolean]](connectivity) else Map[String,Boolean]()
    val ms = if (microservices != "") read[List[OneMicroservice]](microservices) else List[OneMicroservice]()
    val wrk = if (workloads != "") read[List[OneWorkload]](workloads) else List[OneWorkload]()
    val svc = if (services != "") read[List[OneService]](services) else List[OneService]()
    return NodeStatus(con, ms, wrk, svc, lastUpdated)
  }

  def upsert: DBIO[_] = NodeStatusTQ.rows.insertOrUpdate(this)
}

class NodeStatuses(tag: Tag) extends Table[NodeStatusRow](tag, "nodestatus") {
  def nodeId = column[String]("nodeid", O.PrimaryKey)
  def connectivity = column[String]("connectivity")
  def microservices = column[String]("microservice")
  def workloads = column[String]("workloads")
  def services = column[String]("services")
  def lastUpdated = column[String]("lastUpdated")
  def * = (nodeId, connectivity, microservices, workloads, services, lastUpdated) <> (NodeStatusRow.tupled, NodeStatusRow.unapply)
  def node = foreignKey("node_fk", nodeId, NodesTQ.rows)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
}

object NodeStatusTQ {
  val rows = TableQuery[NodeStatuses]
  def getNodeStatus(nodeId: String) = rows.filter(_.nodeId === nodeId)
}

case class NodeStatus(connectivity: Map[String,Boolean], microservices: List[OneMicroservice], workloads: List[OneWorkload], services: List[OneService], lastUpdated: String)


case class NAMicroservice(orgid: String, url: String)
case class NAService(orgid: String, url: String)
case class NAWorkload(orgid: String, pattern: String, url: String)
case class NAgrService(orgid: String, pattern: String, url: String)

case class NodeAgreementRow(agId: String, nodeId: String, microservices: String, workloadOrgid: String, workloadPattern: String, workloadUrl: String, services: String, agrSvcOrgid: String, agrSvcPattern: String, agrSvcUrl: String, state: String, lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  // Translates the MS string into a data structure
  def getMicroservices: List[NAMicroservice] = if (microservices != "") read[List[NAMicroservice]](microservices) else List[NAMicroservice]()
  def getServices: List[NAService] = if (services != "") read[List[NAService]](services) else List[NAService]()
  def getWorkload = NAWorkload(workloadOrgid, workloadPattern, workloadUrl)
  def getNAgrService = NAgrService(agrSvcOrgid, agrSvcPattern, agrSvcUrl)

  def toNodeAgreement = {
    NodeAgreement(getMicroservices, getWorkload, getServices, getNAgrService, state, lastUpdated)
  }

  def upsert: DBIO[_] = NodeAgreementsTQ.rows.insertOrUpdate(this)
}

class NodeAgreements(tag: Tag) extends Table[NodeAgreementRow](tag, "nodeagreements") {
  def agId = column[String]("agid", O.PrimaryKey)     // ethereum agreeement ids are unique
  def nodeId = column[String]("nodeid")
  def microservices = column[String]("microservice")
  def workloadOrgid = column[String]("workloadorgid")
  def workloadPattern = column[String]("workloadpattern")
  def workloadUrl = column[String]("workloadurl")
  def services = column[String]("services")
  def agrSvcOrgid = column[String]("agrsvcorgid")
  def agrSvcPattern = column[String]("agrsvcpattern")
  def agrSvcUrl = column[String]("agrsvcurl")
  def state = column[String]("state")
  def lastUpdated = column[String]("lastUpdated")
  def * = (agId, nodeId, microservices, workloadOrgid, workloadPattern, workloadUrl, services, agrSvcOrgid, agrSvcPattern, agrSvcUrl, state, lastUpdated) <> (NodeAgreementRow.tupled, NodeAgreementRow.unapply)
  def node = foreignKey("node_fk", nodeId, NodesTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object NodeAgreementsTQ {
  val rows = TableQuery[NodeAgreements]

  def getAgreements(nodeId: String) = rows.filter(_.nodeId === nodeId)
  def getAgreement(nodeId: String, agId: String) = rows.filter( r => {r.nodeId === nodeId && r.agId === agId} )
  def getNumOwned(nodeId: String) = rows.filter(_.nodeId === nodeId).length
  def getAgreementsWithState = rows.filter(_.state =!= "")
}

case class NodeAgreement(microservices: List[NAMicroservice], workload: NAWorkload, services: List[NAService], agrService: NAgrService, state: String, lastUpdated: String)

/** Builds a hash of the current number of agreements for each node and microservice in the org, so we can check them quickly */
class AgreementsHash(dbNodesAgreements: Seq[NodeAgreementRow], useServices: Boolean = true) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  // The 1st level key of this hash is the node id, the 2nd level key is the microservice url, the leaf value is current number of agreements
  var agHash = new MutableHashMap[String,MutableHashMap[String,Int]]()

  if (useServices) {
    for (a <- dbNodesAgreements) {
      val micros = a.getServices
      agHash.get(a.nodeId) match {
        case Some(nodeHash) => for (ms <- micros) {
          val numAgs = nodeHash.get(ms.url) // node hash is there so find or create the service hashes within it
          numAgs match {
            case Some(numAgs2) => nodeHash.put(ms.url, numAgs2 + 1)
            case None => nodeHash.put(ms.url, 1)
          }
        }
        case None => val nodeHash = new MutableHashMap[String, Int]() // this node is not in the hash yet, so create it and add the service hashes
          for (ms <- micros) {
            nodeHash.put(ms.url, 1)
          }
          agHash += ((a.nodeId, nodeHash))
      }
    }
  } else {
    for (a <- dbNodesAgreements) {
      val micros = a.getMicroservices
      agHash.get(a.nodeId) match {
        case Some(nodeHash) => for (ms <- micros) {
          val numAgs = nodeHash.get(ms.url) // node hash is there so find or create the microservice hashes within it
          numAgs match {
            case Some(numAgs2) => nodeHash.put(ms.url, numAgs2 + 1)
            case None => nodeHash.put(ms.url, 1)
          }
        }
        case None => val nodeHash = new MutableHashMap[String, Int]() // this node is not in the hash yet, so create it and add the microservice hashes
          for (ms <- micros) {
            nodeHash.put(ms.url, 1)
          }
          agHash += ((a.nodeId, nodeHash))
      }
    }
  }
}


/** The nodemsgs table holds the msgs sent to nodes by agbots */
case class NodeMsgRow(msgId: Int, nodeId: String, agbotId: String, agbotPubKey: String, message: String, timeSent: String, timeExpires: String) {
  def toNodeMsg = NodeMsg(msgId, agbotId, agbotPubKey, message, timeSent, timeExpires)

  def insert: DBIO[_] = ((NodeMsgsTQ.rows returning NodeMsgsTQ.rows.map(_.msgId)) += this)  // inserts the row and returns the msgId of the new row
  def upsert: DBIO[_] = NodeMsgsTQ.rows.insertOrUpdate(this)    // do not think we need this
}

class NodeMsgs(tag: Tag) extends Table[NodeMsgRow](tag, "nodemsgs") {
  def msgId = column[Int]("msgid", O.PrimaryKey, O.AutoInc)    // this enables them to delete a msg and helps us deliver them in order
  def nodeId = column[String]("nodeid")       // msg recipient
  def agbotId = column[String]("agbotid")         // msg sender
  def agbotPubKey = column[String]("agbotpubkey")
  def message = column[String]("message")
  def timeSent = column[String]("timesent")
  def timeExpires = column[String]("timeexpires")
  def * = (msgId, nodeId, agbotId, agbotPubKey, message, timeSent, timeExpires) <> (NodeMsgRow.tupled, NodeMsgRow.unapply)
  def node = foreignKey("node_fk", nodeId, NodesTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object NodeMsgsTQ {
  val rows = TableQuery[NodeMsgs]

  def getMsgs(nodeId: String) = rows.filter(_.nodeId === nodeId)  // this is that nodes msg mailbox
  def getMsg(nodeId: String, msgId: Int) = rows.filter( r => {r.nodeId === nodeId && r.msgId === msgId} )
  def getMsgsExpired = rows.filter(_.timeExpires < ApiTime.nowUTC)
  def getNumOwned(nodeId: String) = rows.filter(_.nodeId === nodeId).length
}

case class NodeMsg(msgId: Int, agbotId: String, agbotPubKey: String, message: String, timeSent: String, timeExpires: String)

/*
case class SoftwareVersionRow(swId: Int, nodeId: String, name: String, version: String) {
  def toSoftwareVersion = version

  def putInHashMap(swVersions: MutableHashMap[String,MutableHashMap[String,String]]): Unit = {
    swVersions.get(nodeId) match {
      case Some(node) => ; // do not need to add the entry, because it is already there
      case None => swVersions.put(nodeId, new MutableHashMap[String,String])
    }
    val sMap = swVersions.get(nodeId).get
    sMap.get(name) match {
      case Some(sw) => ; // do not need to add the entry, because it is already there
      case None => sMap.put(name, toSoftwareVersion)
    }
  }
}

class SoftwareVersions(tag: Tag) extends Table[SoftwareVersionRow](tag, "swversions") {
  def swId = column[Int]("swid", O.PrimaryKey, O.AutoInc)
  def nodeId = column[String]("nodeid")
  def name = column[String]("name")
  def version = column[String]("version")
  def * = (swId, nodeId, name, version) <> (SoftwareVersionRow.tupled, SoftwareVersionRow.unapply)
  def node = foreignKey("node_fk", nodeId, NodesTQ.rows)(_.id)
}

object SoftwareVersionsTQ {
  val rows = TableQuery[SoftwareVersions]
}
*/

case class RegMicroserviceRow(msId: String, nodeId: String, url: String, numAgreements: Int, policy: String) {
  def toRegMicroservice = RegMicroservice(url, numAgreements, policy, List[Prop]())

  def putInHashMap(micros: MutableHashMap[String,MutableHashMap[String,RegMicroservice]]): Unit = {
    micros.get(nodeId) match {
      case Some(_) => ; // do not need to add the entry, because it is already there
      case None => micros.put(nodeId, new MutableHashMap[String,RegMicroservice])
    }
    val mMap = micros(nodeId)
    mMap.get(msId) match {
      case Some(_) => ; // do not need to add the entry, because it is already there
      case None => mMap.put(msId, toRegMicroservice)
    }
  }

  def upsert: DBIO[_] = RegMicroservicesTQ.rows.insertOrUpdate(this)
  def update: DBIO[_] = RegMicroservicesTQ.rows.update(this)
}

class RegMicroservices(tag: Tag) extends Table[RegMicroserviceRow](tag, "nodemicros") {   // <- not changing table name to regmicroservices because i do not want to cause a db schema change
  def msId = column[String]("msid", O.PrimaryKey)     // we form this key as <nodeId>|<url>
  def nodeId = column[String]("nodeid")
  def url = column[String]("url")
  def numAgreements = column[Int]("numagreements")
  def policy = column[String]("policy")
  def * = (msId, nodeId, url, numAgreements, policy) <> (RegMicroserviceRow.tupled, RegMicroserviceRow.unapply)
  def node = foreignKey("node_fk", nodeId, NodesTQ.rows)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

// object testmicros extends TableQuery(new TestMicros(_)) {}
object RegMicroservicesTQ {
  val rows = TableQuery[RegMicroservices]
}

/** We define this trait because microservices in the DB and in the search criteria need the same methods, but have slightly different constructor args */
trait RegMicroserviceTrait {
  def url: String
  def properties: List[Prop]

  /** Returns an error msg if the user input is invalid. */
  def validate: Option[String] = {
    for (p <- properties) {
      p.validate match {
        case Some(msg) => return Option[String](url+": "+msg)     // prepend the url so they know which microservice was bad
        case None => ;      // continue checking
      }
    }
    return None     // this means it is valid
  }

  /** Returns true if this micro (the search) matches that micro (an entry in the db)
   * Rules for comparison:
   * - if both parties do not have the same property names, it is as if wildcard was specified
   */
  def matches(that: RegMicroservice): Boolean = {
    if (url != that.url) return false
    // go thru each of our props, finding and comparing the corresponding prop in that
    for (thatP <- that.properties) {
      properties.find(p => thatP.name == p.name) match {
        case None => ;        // if the node does not specify this property, that is equivalent to it specifying wildcard
        case Some(p) => if (!p.matches(thatP)) return false
      }
    }
    return true
  }
}

/** 1 microservice in the search criteria */
case class RegMicroserviceSearch(url: String, properties: List[Prop]) extends RegMicroserviceTrait

/** 1 microservice within a node in the DB */
case class RegMicroservice(url: String, numAgreements: Int, policy: String, properties: List[Prop]) extends RegMicroserviceTrait {
  def toRegMicroserviceRow(nodeId: String) = RegMicroserviceRow(nodeId+"|"+url, nodeId, url, numAgreements, policy)
}

case class PropRow(propId: String, msId: String, name: String, value: String, propType: String, op: String) {
  def toProp = Prop(name, value, propType, op)

  def putInHashMap(props: MutableHashMap[String,MutableHashMap[String,Prop]]) {
    props.get(msId) match {
      case Some(_) => ; // do not need to add the entry, because it is already there
      case None => props.put(msId, new MutableHashMap[String,Prop])
    }
    val pMap = props(msId)
    pMap.get(propId) match {
      case Some(_) => ; // do not need to add the entry, because it is already there
      case None => pMap.put(propId, toProp)
    }
  }

  def upsert: DBIO[_] = PropsTQ.rows.insertOrUpdate(this)
  def update: DBIO[_] = PropsTQ.rows.update(this)
}

class Props(tag: Tag) extends Table[PropRow](tag, "properties") {
  def propId = column[String]("propid", O.PrimaryKey)     // we form this key as <msId>|<name>
  def msId = column[String]("msid")
  def name = column[String]("name")
  def value = column[String]("value")
  def propType = column[String]("proptype")
  def op = column[String]("op")
  def * = (propId, msId, name, value, propType, op) <> (PropRow.tupled, PropRow.unapply)
  // def node = foreignKey("node_fk", nodeId, NodesTQ.rows)(_.id)
  def ms = foreignKey("ms_fk", msId, RegMicroservicesTQ.rows)(_.msId, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object PropsTQ {
  val rows = TableQuery[Props]
}

/** 1 generic property that is used in the node search criteria */
case class Prop(name: String, value: String, propType: String, op: String) {
  def toPropRow(nodeId: String, msUrl: String) = PropRow(nodeId+"|"+msUrl+"|"+name, nodeId+"|"+msUrl, name, value, propType, op)

  /** Returns an error msg if the user input is invalid. */
  def validate: Option[String] = {
    if (!PropType.contains(propType)) return Option[String]("invalid propType '"+propType+"' specified for "+name)
    if (!Op.contains(op)) return Option[String]("invalid op '"+op+"' specified for "+name)
    if (propType==PropType.BOOLEAN) {
      if (op!=Op.EQUAL) return Option[String]("invalid op '"+op+"' specified for "+name+" (only '"+Op.EQUAL+"' is supported for propType '"+PropType.BOOLEAN+"')")
      if (value.toLowerCase != "true" && value.toLowerCase != "false" && value != "*") return Option[String]("invalid boolean value '"+value+"' specified for "+name)
    }
    if ((propType==PropType.LIST || propType==PropType.STRING) && op!=Op.IN) return Option[String]("invalid op '"+op+"' specified for "+name+" (only '"+Op.IN+"' is supported for propType '"+PropType.STRING+"' and '"+PropType.LIST+"')")
    if (propType==PropType.INT) {
      if (op==Op.IN) return Option[String]("invalid op '"+op+"' specified for "+name)
      if (value != "*") {
        // ensure its a valid integer number
        try { value.toInt }
        catch { case _: Exception => return Option[String]("invalid integer value '"+value+"' specified for "+name) }
      }
    }
    if (propType==PropType.VERSION) {
      if (!(op==Op.EQUAL || op==Op.IN)) return Option[String]("invalid op '"+op+"' specified for "+name+" (only '"+Op.EQUAL+"' or '"+Op.IN+"' is supported for propType '"+PropType.VERSION+"')")
      if (value != "*") {       // verify it is a valid version or range format
        if (!VersionRange(value).isValid) return Option[String]("invalid version value '"+value+"' specified for "+name)
      }
    }
    return None
  }

  /** Returns true if this property (the search) matches that property (an entry in the db) */
  def matches(that: Prop): Boolean = {
    if (name != that.name) return false     // comparison can only be done on the same name
    if (op != that.op) return false         // comparison only makes sense if they both have the same operator
    if (propType==PropType.WILDCARD || that.propType==PropType.WILDCARD) return true
    if (value=="*" || that.value=="*") return true
    (propType, that.propType) match {
      case (PropType.BOOLEAN, PropType.BOOLEAN) => op match {
        case Op.EQUAL => return (value == that.value)
        case _ => return false
      }
      // this will automatically transform a string into a list of strings
      case (PropType.LIST, PropType.LIST) | (PropType.STRING, PropType.LIST) | (PropType.LIST, PropType.STRING) | (PropType.STRING, PropType.STRING) => op match {
        case Op.IN => return ( value.split(",").intersect(that.value.split(",")).length > 0 )
        case _ => return false
      }
      case (PropType.INT, PropType.INT) => op match {
        case Op.EQUAL => return (value == that.value)
        case Op.GTEQUAL => try { return (that.value.toInt >= value.toInt) } catch { case _: Exception => return false }
        case Op.LTEQUAL => try { return (that.value.toInt <= value.toInt) } catch { case _: Exception => return false }
        case _ => return false
      }
      case (PropType.VERSION, PropType.VERSION) => op match {
        case Op.EQUAL => return (Version(value) == Version(that.value))
        case Op.IN => return (Version(that.value) in VersionRange(value))
        case _ => return false
      }
      case _ => return false
    }
  }
}

abstract class PropVar
case class PropList(args: String*) extends PropVar

// I do not think we can use actual enums in classes that get mapped to json and swagger
object PropType {
  val STRING = "string"
  val LIST = "list"
  val VERSION = "version"
  val BOOLEAN = "boolean"
  val INT = "int"
  val WILDCARD = "wildcard"       // means 1 side does not care what value the other side has
  val all = Set(STRING, LIST, VERSION, BOOLEAN, INT, WILDCARD)
  def contains(s: String): Boolean = all.contains(s)

}

/** The valid operators for properties */
object Op {
  val EQUAL = "="
  val GTEQUAL = ">="
  val LTEQUAL = "<="
  val IN = "in"
  val all = Set(EQUAL, GTEQUAL, LTEQUAL, IN )
  def contains(s: String): Boolean = all.contains(s)
}
