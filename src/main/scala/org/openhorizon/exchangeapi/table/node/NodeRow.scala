package org.openhorizon.exchangeapi.table.node

import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.deploymentpattern.OneUserInputService
import org.openhorizon.exchangeapi.{Role, StrConstants}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

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
                         lastUpdated: String,
                         clusterNamespace: Option[String] = None,
                         isNamespaceScoped: Boolean = false) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def toNode(superUser: Boolean, ha_group: Option[String]): Node = {
    val hbInterval: NodeHeartbeatIntervals = if (heartbeatIntervals != "") read[NodeHeartbeatIntervals](heartbeatIntervals) else NodeHeartbeatIntervals(0, 0, 0)
    val input: List[OneUserInputService] = if (userInput != "") read[List[OneUserInputService]](userInput) else List[OneUserInputService]()
    val nt: String = if (nodeType == "") NodeType.DEVICE.toString else nodeType
    val rsvc: List[RegService] = if (regServices != "") read[List[RegService]](regServices) else List[RegService]()
    // Default new configState attr if it doesnt exist. This ends up being called by GET nodes, GET nodes/id, and POST search/nodes
    val rsvc2: List[RegService] = rsvc.map(rs => RegService(rs.url, rs.numAgreements, rs.configState.orElse(Option("active")), rs.policy, rs.properties, rs.version.orElse(Some(""))))
    val swv: Map[String, String] = if (softwareVersions != "") read[Map[String,String]](softwareVersions) else Map[String,String]()
    val tok: String = if (superUser) token else StrConstants.hiddenPw
    
    new Node(arch = arch,
             clusterNamespace = clusterNamespace.getOrElse(""),
             ha_group = ha_group,
             heartbeatIntervals = hbInterval,
             isNamespaceScoped = isNamespaceScoped,
             lastHeartbeat = lastHeartbeat.orNull,
             lastUpdated = lastUpdated,
             msgEndPoint = msgEndPoint,
             name = name,
             nodeType = nt,
             owner = owner,
             pattern = pattern,
             publicKey = publicKey,
             registeredServices = rsvc2,
             softwareVersions = swv,
             token = tok,
             userInput = input)
  }

  def upsert: DBIO[_] = {
    //val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)  <- token is already hashed
    if (Role.isSuperUser(owner))
      NodesTQ.map(d => (d.id, d.orgid, d.token, d.name, d.nodeType, d.pattern, d.regServices, d.userInput, d.msgEndPoint, d.softwareVersions, d.lastHeartbeat, d.publicKey, d.arch, d.heartbeatIntervals, d.lastUpdated, d.clusterNamespace, d.isNamespaceScoped)).insertOrUpdate((id, orgid, token, name, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions, lastHeartbeat.orElse(None), publicKey, arch, heartbeatIntervals, lastUpdated, clusterNamespace, isNamespaceScoped))
    else
      NodesTQ.insertOrUpdate(NodeRow(id, orgid, token, name, owner, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions, lastHeartbeat, publicKey, arch, heartbeatIntervals, lastUpdated, clusterNamespace, isNamespaceScoped))
  }

  def update: DBIO[_] = {
    //val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.hash(token)  <- token is already hashed
    if (owner == "") (
        for {
          d <- NodesTQ if d.id === id
        } yield (d.id,d.orgid,d.token,d.name,d.nodeType,d.pattern,d.regServices,d.userInput,
            d.msgEndPoint,d.softwareVersions,d.lastHeartbeat,d.publicKey, d.arch, d.heartbeatIntervals, d.lastUpdated, d.clusterNamespace, d.isNamespaceScoped))
            .update((id, orgid, token, name, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions,
                lastHeartbeat.orElse(None), publicKey, arch, heartbeatIntervals, lastUpdated, clusterNamespace, isNamespaceScoped))
    else (for { d <- NodesTQ if d.id === id } yield d).update(NodeRow(id, orgid, token, name, owner, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions, lastHeartbeat, publicKey, arch, heartbeatIntervals, lastUpdated, clusterNamespace, isNamespaceScoped))
  }
}
