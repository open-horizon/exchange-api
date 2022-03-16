package com.horizon.exchangeapi.tables

import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization.read
import com.horizon.exchangeapi.{ApiTime, ExchMsg, Role, StrConstants, Version, VersionRange, tables}
import slick.dbio.{Effect, NoStream}
import slick.jdbc.PostgresProfile.api.{DBIO, ForeignKeyAction, Query, Table, TableQuery, Tag, anyToShapedValue, booleanColumnExtensionMethods, booleanColumnType, columnExtensionMethods, intColumnType, queryInsertActionExtensionMethods, queryUpdateActionExtensionMethods, recordQueryActionExtensionMethods, stringColumnExtensionMethods, stringColumnType, valueToConstColumn}
import slick.lifted.Rep
import slick.sql.FixedSqlAction


/** We define this trait because services in the DB and in the search criteria need the same methods, but have slightly different constructor args */
trait RegServiceTrait {
  def url: String   // this is the composite org/svcurl
  def properties: List[Prop]

  /** Returns an error msg if the user input is invalid. */
  def validate: Option[String] = {
    for (p <- properties) {
      p.validate match {
        case Some(msg) => return Option[String](url+": "+msg)     // prepend the url so they know which service was bad
        case None => ;      // continue checking
      }
    }
    None     // this means it is valid
  }

  /** Returns true if this service (the search) matches that service (an entry in the db)
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
    true
  }
}

/** 1 service in the search criteria */
final case class RegServiceSearch(url: String, properties: List[Prop]) extends RegServiceTrait

/** Contains the object representations of the DB tables related to nodes. */
final case class RegService(url: String, numAgreements: Int, configState: Option[String], policy: String, properties: List[Prop], version: Option[String]) extends RegServiceTrait

final case class NodeHeartbeatIntervals(minInterval: Int, maxInterval: Int, intervalAdjustment: Int)

object NodeType extends Enumeration {
  type NodeType = Value
  val DEVICE: tables.NodeType.Value = Value("device")
  val CLUSTER: tables.NodeType.Value = Value("cluster")
  def isDevice(str: String): Boolean = str == DEVICE.toString
  def isCluster(str: String): Boolean = str == CLUSTER.toString
  def containsString(str: String): Boolean = values.find(_.toString == str).orNull != null
  def valuesAsString: String = values.map(_.toString).mkString(", ")
}

// This is the node table minus the key - used as the data structure to return to the REST clients
class Node(var token: String, var name: String, var owner: String, var nodeType: String, var pattern: String, var registeredServices: List[RegService], var userInput: List[OneUserInputService], var msgEndPoint: String, var softwareVersions: Map[String,String], var lastHeartbeat: String, var publicKey: String, var arch: String, var heartbeatIntervals: NodeHeartbeatIntervals, var lastUpdated: String) {
  def copy = new Node(token, name, owner, nodeType, pattern, registeredServices, userInput, msgEndPoint, softwareVersions, lastHeartbeat, publicKey, arch, heartbeatIntervals, lastUpdated)
}

final case class NodeRow(id: String,
                         orgid: String, 
                         token: String, 
                         name: String, 
                         owner: String, 
                         nodeType: String, 
                         pattern: String, 
                         regServices: String, 
                         userInput: String, 
                         msgEndPoint: String, 
                         softwareVersions: String, 
                         lastHeartbeat: Option[String], 
                         publicKey: String, 
                         arch: String, 
                         heartbeatIntervals: String, 
                         lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def toNode(superUser: Boolean): Node = {
    val tok: String = if (superUser) token else StrConstants.hiddenPw
    val nt: String = if (nodeType == "") NodeType.DEVICE.toString else nodeType
    val swv: Map[String, String] = if (softwareVersions != "") read[Map[String,String]](softwareVersions) else Map[String,String]()
    val rsvc: List[RegService] = if (regServices != "") read[List[RegService]](regServices) else List[RegService]()
    // Default new configState attr if it doesnt exist. This ends up being called by GET nodes, GET nodes/id, and POST search/nodes
    val rsvc2: List[RegService] = rsvc.map(rs => RegService(rs.url, rs.numAgreements, rs.configState.orElse(Some("active")), rs.policy, rs.properties, rs.version.orElse(Some(""))))
    val input: List[OneUserInputService] = if (userInput != "") read[List[OneUserInputService]](userInput) else List[OneUserInputService]()
    val hbInterval: NodeHeartbeatIntervals = if (heartbeatIntervals != "") read[NodeHeartbeatIntervals](heartbeatIntervals) else NodeHeartbeatIntervals(0, 0, 0)
    new Node(tok, name, owner, nt, pattern, rsvc2, input, msgEndPoint, swv, lastHeartbeat.orNull, publicKey, arch, hbInterval, lastUpdated)
  }

  def upsert: DBIO[_] = {
    //val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)  <- token is already hashed
    if (Role.isSuperUser(owner)) NodesTQ.map(d => (d.id, d.orgid, d.token, d.name, d.nodeType, d.pattern, d.regServices, d.userInput, d.msgEndPoint, d.softwareVersions, d.lastHeartbeat, d.publicKey, d.arch, d.heartbeatIntervals, d.lastUpdated)).insertOrUpdate((id, orgid, token, name, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions, lastHeartbeat.orElse(None), publicKey, arch, heartbeatIntervals, lastUpdated))
    else NodesTQ.insertOrUpdate(NodeRow(id, orgid, token, name, owner, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions, lastHeartbeat, publicKey, arch, heartbeatIntervals, lastUpdated))
  }

  def update: DBIO[_] = {
    //val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)  <- token is already hashed
    if (owner == "") (
        for { 
          d <- NodesTQ if d.id === id
        } yield (d.id,d.orgid,d.token,d.name,d.nodeType,d.pattern,d.regServices,d.userInput,
            d.msgEndPoint,d.softwareVersions,d.lastHeartbeat,d.publicKey, d.arch, d.heartbeatIntervals, d.lastUpdated))
            .update((id, orgid, token, name, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions, 
                lastHeartbeat.orElse(None), publicKey, arch, heartbeatIntervals, lastUpdated))
    else (for { d <- NodesTQ if d.id === id } yield d).update(NodeRow(id, orgid, token, name, owner, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions, lastHeartbeat, publicKey, arch, heartbeatIntervals, lastUpdated))
  }
}


/** Mapping of the nodes db table to a scala class */
class Nodes(tag: Tag) extends Table[NodeRow](tag, "nodes") {
  def id = column[String]("id", O.PrimaryKey)   // in the form org/nodeid
  def orgid = column[String]("orgid")
  def token = column[String]("token")
  def name = column[String]("name")
  def owner = column[String]("owner", O.Default(Role.superUser))  // root is the default because during upserts by root, we do not want root to take over the node if it already exists
  def nodeType = column[String]("nodetype")
  def pattern = column[String]("pattern")       // this is orgid/patternname
  def regServices = column[String]("regservices")
  def userInput = column[String]("userinput")
  def msgEndPoint = column[String]("msgendpoint")
  def softwareVersions = column[String]("swversions")
  def publicKey = column[String]("publickey")     // this is last because that is where alter table in upgradedb puts it
  def lastHeartbeat = column[Option[String]]("lastheartbeat")
  def arch = column[String]("arch")
  def heartbeatIntervals = column[String]("heartbeatintervals")
  def lastUpdated = column[String]("lastupdated")

  // this describes what you get back when you return this.from a query
  def * = (id, orgid, token, name, owner, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions, lastHeartbeat, publicKey, arch, heartbeatIntervals, lastUpdated).<>(NodeRow.tupled, NodeRow.unapply)
  def user = foreignKey("user_fk", owner, UsersTQ)(_.username, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def orgidKey = foreignKey("orgid_fk", orgid, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  //def patKey = foreignKey("pattern_fk", pattern, PatternsTQ)(_.pattern, onUpdate=ForeignKeyAction.Cascade)     // <- we can't make this a foreign key because it is optional
}

// Instance to access the nodes table
// object nodes extends TableQuery(new Nodes(_)) {
//   def listUserNodes(username: String) = this.filter(_.owner === username)
// }
object NodesTQ  extends TableQuery(new Nodes(_)){
  // Build a list of db actions to verify that the referenced services exist
  // Note: this currently doesn't check the registeredServices because only the agent creates that, and its content might be changing
  def validateServiceIds(userInput: List[OneUserInputService]): (DBIO[Vector[Int]], Vector[ServiceRef2]) = {
    val actions: ListBuffer[DBIO[Int]] = ListBuffer[DBIO[Int]]()
    val svcRefs: ListBuffer[ServiceRef2] = ListBuffer[ServiceRef2]()
    // Go thru the services referenced in the userInput section
    for (s <- userInput) {
      svcRefs += ServiceRef2(s.serviceUrl, s.serviceOrgid, s.serviceVersionRange.getOrElse("[0.0.0,INFINITY)"), s.serviceArch.getOrElse(""))  // the service ref is just for reporting bad input errors
      val arch: String = if (s.serviceArch.isEmpty || s.serviceArch.get == "") "%" else s.serviceArch.get
      //perf: the best we can do is use the version if the range is a single version, otherwise use %
      val svc: String = if (s.serviceVersionRange.getOrElse("") == "") "%"
      else {
        val singleVer: Option[Version] = VersionRange(s.serviceVersionRange.get).singleVersion
        if (singleVer.isDefined) singleVer.toString
        else "%"
      }
      val svcId: String = ServicesTQ.formId(s.serviceOrgid, s.serviceUrl, svc, arch)
      actions += ServicesTQ.getService(svcId).length.result
    }
    (DBIO.sequence(actions.toVector), svcRefs.toVector)      // convert the list of actions to a DBIO sequence
  }

  def getAllNodes(orgid: String): Query[Nodes, NodeRow, Seq] = this.filter(_.orgid === orgid)
  def getAllNodesId(orgid: String): Query[Rep[String], String, Seq] = this.filter(_.orgid === orgid).map(_.id)
  def getNodeTypeNodes(orgid: String, nodeType: String): Query[Nodes, NodeRow, Seq] = this.filter(r => {r.orgid === orgid && r.nodeType === nodeType})
  def getNonPatternNodes(orgid: String): Query[Nodes, NodeRow, Seq] = this.filter(r => {r.orgid === orgid && r.pattern === ""})
  def getRegisteredNodesInOrg(orgid: String): Query[Nodes, NodeRow, Seq] = this.filter(node => {node.orgid === orgid && node.publicKey =!= ""})
  def getNode(id: String): Query[Nodes, NodeRow, Seq] = this.filter(_.id === id)
  def getToken(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.token)
  def getOwner(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.owner)
  def getRegisteredServices(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.regServices)
  def getUserInput(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.userInput)
  def getNodeType(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.nodeType)
  def getPattern(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.pattern)
  def getNumOwned(owner: String): Rep[Int] = this.filter(_.owner === owner).length
  def getLastHeartbeat(id: String): Query[Rep[Option[String]], Option[String], Seq] = this.filter(_.id === id).map(_.lastHeartbeat)
  def getPublicKey(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.publicKey)
  def getArch(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.arch)
  def getHeartbeatIntervals(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.heartbeatIntervals)
  def getLastUpdated(id: String): Query[Rep[String], String, Seq] = this.filter(_.id === id).map(_.lastUpdated)
  def getNodeUsingPolicy(id: String): Query[(Rep[String], Rep[String]), (String, String), Seq] = this.filter(_.id === id).map(x => (x.pattern, x.publicKey))

  def setLastHeartbeat(id: String, lastHeartbeat: String): FixedSqlAction[Int, NoStream, Effect.Write] = this.filter(_.id === id).map(_.lastHeartbeat).update(Some(lastHeartbeat))
  def setLastUpdated(id: String, lastUpdated: String): FixedSqlAction[Int, NoStream, Effect.Write] = this.filter(_.id === id).map(_.lastUpdated).update(lastUpdated)


  /** Returns a query for the specified node attribute value. Returns null if an invalid attribute name is given. */
  def getAttribute(id: String, attrName: String): Query[_,_,Seq] = {
    val filter = this.filter(_.id === id)
    // According to 1 post by a slick developer, there is not yet a way to do this properly dynamically
    attrName match {
      case "token" => filter.map(_.token)
      case "name" => filter.map(_.name)
      case "owner" => filter.map(_.owner)
      case "nodeType" => filter.map(_.nodeType)
      case "pattern" => filter.map(_.pattern)
      case "regServices" => filter.map(_.regServices)
      case "userInput" => filter.map(_.userInput)
      case "msgEndPoint" => filter.map(_.msgEndPoint)
      case "softwareVersions" => filter.map(_.softwareVersions)
      case "lastHeartbeat" => filter.map(_.lastHeartbeat)
      case "publicKey" => filter.map(_.publicKey)
      case "arch" => filter.map(_.arch)
      case "heartbeatIntervals" => filter.map(_.heartbeatIntervals)
      case "lastUpdated" => filter.map(_.lastUpdated)
      case _ => null
    }
  }

  /* Not needed anymore, because node properties are no longer in a separate table that needs to be joined...
  Separate the join of the nodes and properties tables into their respective scala classes (collapsing duplicates) and return a hash containing it all.
    * Note: this can also be used when querying node rows that have services, because the services are faithfully preserved in the Node object.
  def parseJoin(isSuperUser: Boolean, list: Seq[NodeRow] ): Map[String,Node] = {
    // Separate the partially duplicate join rows into maps that only keep unique values
    val nodes = new MutableHashMap[String,Node]    // the key is node id
    for (d <- list) {
      d.putInHashMap(isSuperUser, nodes)
    }
    nodes.toMap
  }
  */
}

// Agreement is a sub-resource of node
final case class NAService(orgid: String, url: String)
final case class NAgrService(orgid: String, pattern: String, url: String)

final case class NodeAgreementRow(agId: String, nodeId: String, services: String, agrSvcOrgid: String, agrSvcPattern: String, agrSvcUrl: String, state: String, lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  
  // Translates the MS string into a data structure
  def getServices: List[NAService] = if (services != "") read[List[NAService]](services) else List[NAService]()
  def getNAgrService: NAgrService = NAgrService(agrSvcOrgid, agrSvcPattern, agrSvcUrl)
  
  def toNodeAgreement: NodeAgreement = {
    NodeAgreement(getServices, getNAgrService, state, lastUpdated)
  }

  def upsert: DBIO[_] = NodeAgreementsTQ.insertOrUpdate(this)
}

class NodeAgreements(tag: Tag) extends Table[NodeAgreementRow](tag, "nodeagreements") {
  def agId = column[String]("agid", O.PrimaryKey)     // agreement ids are unique
  def nodeId = column[String]("nodeid")   // in the form org/nodeid
  def services = column[String]("services")
  def agrSvcOrgid = column[String]("agrsvcorgid")
  def agrSvcPattern = column[String]("agrsvcpattern")
  def agrSvcUrl = column[String]("agrsvcurl")
  def state = column[String]("state")
  def lastUpdated = column[String]("lastUpdated")
  def * = (agId, nodeId, services, agrSvcOrgid, agrSvcPattern, agrSvcUrl, state, lastUpdated).<>(NodeAgreementRow.tupled, NodeAgreementRow.unapply)
  def node = foreignKey("node_fk", nodeId, NodesTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object NodeAgreementsTQ  extends TableQuery(new NodeAgreements(_)){
  def getAgreements(nodeId: String): Query[NodeAgreements, NodeAgreementRow, Seq] = this.filter(_.nodeId === nodeId)
  def getAgreement(nodeId: String, agId: String): Query[NodeAgreements, NodeAgreementRow, Seq] = this.filter(r => {r.nodeId === nodeId && r.agId === agId} )
  def getNumOwned(nodeId: String): Rep[Int] = this.filter(_.nodeId === nodeId).length
  def getAgreementsWithState(orgid: String): Query[NodeAgreements, NodeAgreementRow, Seq] = this.filter(a => {(a.nodeId like orgid + "/%") && a.state =!= ""} )
}

final case class NodeAgreement(services: List[NAService], agrService: NAgrService, state: String, lastUpdated: String)

/** Builds a hash of the current number of agreements for each node and service in the org, so we can check them quickly */
class AgreementsHash(dbNodesAgreements: Seq[NodeAgreementRow]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  
  // The 1st level key of this hash is the node id, the 2nd level key is the service url, the leaf value is current number of agreements
  var agHash = new MutableHashMap[String,MutableHashMap[String,Int]]()
  
  for (a <- dbNodesAgreements) {
    val svcs: Seq[NAService] = a.getServices
    agHash.get(a.nodeId) match {
      case Some(nodeHash) => for (ms <- svcs) {
        val svcurl: String = ms.orgid + "/" + ms.url
        val numAgs: Option[Int] = nodeHash.get(svcurl) // node hash is there so find or create the service hashes within it
        numAgs match {
          case Some(numAgs2) => nodeHash.put(svcurl, numAgs2 + 1)
          case None => nodeHash.put(svcurl, 1)
        }
      }
      case None => val nodeHash = new MutableHashMap[String, Int]() // this node is not in the hash yet, so create it and add the service hashes
        for (ms <- svcs) {
          val svcurl: String = ms.orgid + "/" + ms.url
          nodeHash.put(svcurl, 1)
        }
        agHash += ((a.nodeId, nodeHash))
    }
  }
}

//Node Errors
// We are using the type Any instead of this case class so anax and the UI can change the fields w/o our code having to change
//case class ErrorLogEvent(record_id: String, message: String, event_code: String, hidden: Boolean)

final case class NodeErrorRow(nodeId: String, errors: String, lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  
  def toNodeError: NodeError = {
    val err: List[Any] = if (errors != "") read[List[Any]](errors) else List[Any]()
    NodeError(err, lastUpdated)
  }
  
  def upsert: DBIO[_] = NodeErrorTQ.insertOrUpdate(this)
}

class NodeErrors(tag: Tag) extends Table[NodeErrorRow](tag, "nodeerror") {
  def nodeId = column[String]("nodeid", O.PrimaryKey)
  def errors = column[String]("errors")
  def lastUpdated = column[String]("lastUpdated")
  def * = (nodeId, errors, lastUpdated).<>(NodeErrorRow.tupled, NodeErrorRow.unapply)
  def node = foreignKey("node_fk", nodeId, NodesTQ)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
}

object NodeErrorTQ extends TableQuery(new NodeErrors(_)) {
  def getNodeError(nodeId: String): Query[NodeErrors, NodeErrorRow, Seq] = this.filter(_.nodeId === nodeId)
}

final case class NodeError(errors: List[Any], lastUpdated: String)

final case class UpgradedVersions(softwareVersion: String,
                                  certVersion: String,
                                  configVersion: String)

final case class policyStatus(scheduledTime: String, startTime: String, endTime: String, upgradedVersions: UpgradedVersions, status: String, errorMessage: String, lastUpdated: String)

class NMPStatus(var agentUpgradePolicyStatus: policyStatus){
  def copy = new NMPStatus(agentUpgradePolicyStatus)
}


final case class NodeMgmtPolStatusRow(actualStartTime: String,
                                      certificateVersion: String,
                                      configurationVersion: String,
                                      endTime: String,
                                      errorMessage: String,
                                      node: String,
                                      policy: String,
                                      scheduledStartTime: String,
                                      softwareVersion: String,
                                      status: String,
                                      updated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def upgradedVersions: UpgradedVersions = UpgradedVersions(certificateVersion, configurationVersion, softwareVersion)

  def agentUpgradePolicyStatus: policyStatus = policyStatus(scheduledStartTime, actualStartTime, endTime, upgradedVersions, status, errorMessage, updated)

  def toNodeMgmtPolStatus: NMPStatus = {
    new NMPStatus(agentUpgradePolicyStatus)
  }

  def upsert: DBIO[_] = NodeMgmtPolStatuses.rows.insertOrUpdate(this)
}

class NodeMgmtPolStatus(tag: Tag) extends Table[NodeMgmtPolStatusRow](tag, "management_policy_status_node") {
  def actualStartTime = column[String]("time_start_actual")
  def certificateVersion = column[String]("version_certificate")
  def configurationVersion = column[String]("version_configuration")
  def endTime = column[String]("time_end")
  def errorMessage = column[String]("error_message")
  def node = column[String]("node")
  def policy = column[String]("policy")
  def scheduledStartTime = column[String]("time_start_scheduled")
  def softwareVersion = column[String]("version_software")
  def status = column[String]("status")
  def updated = column[String]("updated")
  
  def * = (actualStartTime,
    certificateVersion,
    configurationVersion,
    endTime,
    errorMessage,
    node,
    policy,
    scheduledStartTime,
    softwareVersion,
    status,
    updated).<>(NodeMgmtPolStatusRow.tupled, NodeMgmtPolStatusRow.unapply)
  def pkNodeMgmtPolStatus = primaryKey("pk_management_policy_status_node", (node, policy))
  
  def fkNode = foreignKey("fk_node", node, NodesTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def fkManagementPolicy = foreignKey("fk_management_policy", policy, ManagementPoliciesTQ)(_.managementPolicy, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object NodeMgmtPolStatuses extends TableQuery(new NodeMgmtPolStatus(_)) {

  val rows = TableQuery[NodeMgmtPolStatus]
  def getActualStartTime(node: String, policy: String): Query[Rep[String], String, Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.actualStartTime))
  def getCertificateVersion(node: String, policy: String): Query[Rep[String], String, Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.certificateVersion))
  def getConfigurationVersion(node: String, policy: String): Query[Rep[String], String, Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.configurationVersion))
  def getEndTime(node: String, policy: String): Query[Rep[String], String, Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.endTime))
  def getErrorMessage(node: String, policy: String): Query[Rep[String], String, Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.errorMessage))
  def getNodeMgmtPolStatus(node: String, policy: String): Query[NodeMgmtPolStatus, NodeMgmtPolStatusRow, Seq] = this.filter(s => {s.node === node && s.policy === policy})
  def getNodeMgmtPolStatuses(node: String): Query[NodeMgmtPolStatus, NodeMgmtPolStatusRow, Seq] = this.filter(s => {s.node === node})
  def getScheduledStartTime(node: String, policy: String): Query[Rep[String], String, Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.scheduledStartTime))
  def getSoftwareVersion(node: String, policy: String): Query[Rep[String], String, Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.softwareVersion))
  def getStatus(node: String, policy: String): Query[Rep[String], String, Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.status))
  def getUpdated(node: String, policy: String): Query[Rep[String], String, Seq] = this.filter(_.node === node).filter(_.policy === policy).map(status => (status.updated))
}

/** The nodemsgs table holds the msgs sent to nodes by agbots */
final case class NodeMsgRow(msgId: Int, nodeId: String, agbotId: String, agbotPubKey: String, message: String, timeSent: String, timeExpires: String) {
  def toNodeMsg: NodeMsg = NodeMsg(msgId, agbotId, agbotPubKey, message, timeSent, timeExpires)

  def insert: DBIO[_] = ((NodeMsgsTQ returning NodeMsgsTQ.map(_.msgId)) += this)  // inserts the row and returns the msgId of the new row
  def upsert: DBIO[_] = NodeMsgsTQ.insertOrUpdate(this)    // do not think we need this
}

class NodeMsgs(tag: Tag) extends Table[NodeMsgRow](tag, "nodemsgs") {
  def msgId = column[Int]("msgid", O.PrimaryKey, O.AutoInc)    // this enables them to delete a msg and helps us deliver them in order
  def nodeId = column[String]("nodeid")       // msg recipient
  def agbotId = column[String]("agbotid")         // msg sender
  def agbotPubKey = column[String]("agbotpubkey")
  def message = column[String]("message")
  def timeSent = column[String]("timesent")
  def timeExpires = column[String]("timeexpires")
  def * = (msgId, nodeId, agbotId, agbotPubKey, message, timeSent, timeExpires).<>(NodeMsgRow.tupled, NodeMsgRow.unapply)
  def node = foreignKey("node_fk", nodeId, NodesTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}

object NodeMsgsTQ  extends TableQuery(new NodeMsgs(_)){
  def getMsgs(nodeId: String): Query[NodeMsgs, NodeMsgRow, Seq] = this.filter(_.nodeId === nodeId)  // this is that nodes msg mailbox
  def getMsg(nodeId: String, msgId: Int): Query[NodeMsgs, NodeMsgRow, Seq] = this.filter(r => {r.nodeId === nodeId && r.msgId === msgId} )
  def getMsgsExpired = this.filter(_.timeExpires < ApiTime.nowUTC)
  def getNumOwned(nodeId: String): Rep[Int] = this.filter(_.nodeId === nodeId).length
  def getNodeMsgsInOrg(orgid: String): Query[NodeMsgs, NodeMsgRow, Seq] = this.filter(a => {(a.nodeId like orgid + "/%")} )
}

final case class NodeMsg(msgId: Int, agbotId: String, agbotPubKey: String, message: String, timeSent: String, timeExpires: String)

// Node Policy
final case class PropertiesAndConstraints(properties: Option[List[OneProperty]], constraints: Option[List[String]])

final case class NodePolicyRow(nodeId: String, label: String, description: String, properties: String, constraints: String, deployment: String, management: String, nodePolicyVersion: String, lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  
  def toNodePolicy: NodePolicy = {
    val prop: List[OneProperty] = if (properties != "") read[List[OneProperty]](properties) else List[OneProperty]()
    val con: List[String] = if (constraints != "") read[List[String]](constraints) else List[String]()
    val dep: PropertiesAndConstraints = if (deployment != "") read[PropertiesAndConstraints](deployment) else PropertiesAndConstraints(None, None)
    val mgmt: PropertiesAndConstraints = if (management != "") read[PropertiesAndConstraints](management) else PropertiesAndConstraints(None, None)
    NodePolicy(label, description, prop, con, dep, mgmt, nodePolicyVersion, lastUpdated)
  }
  
  def upsert: DBIO[_] = NodePolicyTQ.insertOrUpdate(this)
}

class NodePolicies(tag: Tag) extends Table[NodePolicyRow](tag, "nodepolicies") {
  def nodeId = column[String]("nodeid", O.PrimaryKey)
  def label = column[String]("label")
  def description = column[String]("description")
  def properties = column[String]("properties")
  def constraints = column[String]("constraints")
  def deployment = column[String]("deployment")
  def management = column[String]("management")
  def nodePolicyVersion = column[String]("nodepolicyversion")
  def lastUpdated = column[String]("lastUpdated")
  def * = (nodeId, label, description, properties, constraints, deployment, management, nodePolicyVersion, lastUpdated).<>(NodePolicyRow.tupled, NodePolicyRow.unapply)
  def node = foreignKey("node_fk", nodeId, NodesTQ)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
}

object NodePolicyTQ extends TableQuery(new NodePolicies(_)) {
  def getNodePolicy(nodeId: String): Query[NodePolicies, NodePolicyRow, Seq] = this.filter(_.nodeId === nodeId)
}

final case class NodePolicy(label: String, description: String, properties: List[OneProperty], constraints: List[String], deployment: PropertiesAndConstraints, management: PropertiesAndConstraints, nodePolicyVersion: String, lastUpdated: String)

// Node Status is a sub-resource of Node
final case class ContainerStatus(name: String, image: String, created: Int, state: String)
final case class OneService(agreementId: String, serviceUrl: String, orgid: String, version: String, arch: String, containerStatus: List[ContainerStatus], operatorStatus: Option[Any], configState: Option[String])

final case class NodeStatusRow(nodeId: String, connectivity: String, services: String, runningServices: String, lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def toNodeStatus: NodeStatus = {
    val con: Map[String, Boolean] = if (connectivity != "") read[Map[String,Boolean]](connectivity) else Map[String,Boolean]()
    val svc: List[OneService] = if (services != "") read[List[OneService]](services) else List[OneService]()
    NodeStatus(con, svc, runningServices, lastUpdated)
  }

  def upsert: DBIO[_] = NodeStatusTQ.insertOrUpdate(this)
}

class NodeStatuses(tag: Tag) extends Table[NodeStatusRow](tag, "nodestatus") {
  def nodeId = column[String]("nodeid", O.PrimaryKey)
  def connectivity = column[String]("connectivity")
  def services = column[String]("services")
  def runningServices = column[String]("runningservices")
  def lastUpdated = column[String]("lastUpdated")
  def * = (nodeId, connectivity, services, runningServices, lastUpdated).<>(NodeStatusRow.tupled, NodeStatusRow.unapply)
  def node = foreignKey("node_fk", nodeId, NodesTQ)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
}

object NodeStatusTQ extends TableQuery(new NodeStatuses(_)) {
  def getNodeStatus(nodeId: String): Query[NodeStatuses, NodeStatusRow, Seq] = this.filter(_.nodeId === nodeId)
}

final case class NodeStatus(connectivity: Map[String,Boolean], services: List[OneService], runningServices: String, lastUpdated: String)

/** 1 generic property that is used in the node search criteria */
final case class Prop(name: String, value: String, propType: String, op: String) {
  //def toPropRow(nodeId: String, msUrl: String) = PropRow(nodeId+"|"+msUrl+"|"+name, nodeId+"|"+msUrl, name, value, propType, op)

  /** Returns an error msg if the user input is invalid. */
  def validate: Option[String] = {
    if (!PropType.contains(propType)) return Option[String](ExchMsg.translate("invalid.proptype.for.name", propType, name))
    if (!Op.contains(op)) return Option[String](ExchMsg.translate("invalid.op.for.name", op, name))
    if (propType==PropType.BOOLEAN) {
      if (op!=Op.EQUAL) return Option[String](ExchMsg.translate("invalid.op.for.name.opequal", op, name, Op.EQUAL, PropType.BOOLEAN))
      if (value.toLowerCase != "true" && value.toLowerCase != "false" && value != "*") return Option[String](ExchMsg.translate("invalid.boolean.value.for.name", value, name))
    }
    if ((propType==PropType.LIST || propType==PropType.STRING) && op!=Op.IN) return Option[String](ExchMsg.translate("invalid.op.for.name.proplist", op, name, Op.IN, PropType.STRING, PropType.LIST))
    if (propType==PropType.INT) {
      if (op==Op.IN) return Option[String](ExchMsg.translate("invalid.op.for.name", op, name))
      //      if (op==Op.IN) return Option[String]("invalid op '"+op+"' specified for "+name)
      if (value != "*") {
        // ensure its a valid integer number
        try { value.toInt }
        catch { case _: Exception => return Option[String](ExchMsg.translate("invalid.int.for.name", value, name)) }
      }
    }
    if (propType==PropType.VERSION) {
      if (!(op==Op.EQUAL || op==Op.IN)) return Option[String](ExchMsg.translate("invalid.op.for.name.propversion", op, name, Op.EQUAL, Op.IN, PropType.VERSION))
      if (value != "*") {       // verify it is a valid version or range format
        if (!VersionRange(value).isValid) return Option[String](ExchMsg.translate("invalid.version.for.name", value, name))
      }
    }
    None
  }

  /** Returns true if this property (the search) matches that property (an entry in the db) */
  def matches(that: Prop): Boolean = {
    if (name != that.name) return false     // comparison can only be done on the same name
    if (op != that.op) return false         // comparison only makes sense if they both have the same operator
    if (propType==PropType.WILDCARD || that.propType==PropType.WILDCARD) return true
    if (value=="*" || that.value=="*") return true
    (propType, that.propType) match {
      case (PropType.BOOLEAN, PropType.BOOLEAN) => op match {
        case Op.EQUAL => (value == that.value)
        case _ => false
      }
      // this will automatically transform a string into a list of strings
      case (PropType.LIST, PropType.LIST) | (PropType.STRING, PropType.LIST) | (PropType.LIST, PropType.STRING) | (PropType.STRING, PropType.STRING) => op match {
        case Op.IN => ( value.split(",").intersect(that.value.split(",")).length > 0 )
        case _ => false
      }
      case (PropType.INT, PropType.INT) => op match {
        case Op.EQUAL => (value == that.value)
        case Op.GTEQUAL => try { (that.value.toInt >= value.toInt) } catch { case _: Exception => false }
        case Op.LTEQUAL => try { (that.value.toInt <= value.toInt) } catch { case _: Exception => false }
        case _ => false
      }
      case (PropType.VERSION, PropType.VERSION) => op match {
        case Op.EQUAL => (Version(value) == Version(that.value))
        case Op.IN => (Version(that.value) in VersionRange(value))
        case _ => false
      }
      case _ => false
    }
  }
}

abstract class PropVar
final case class PropList(args: String*) extends PropVar

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
