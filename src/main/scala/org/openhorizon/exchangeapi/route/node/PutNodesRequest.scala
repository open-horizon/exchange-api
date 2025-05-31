package org.openhorizon.exchangeapi.route.node

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.deploymentpattern.OneUserInputService
import org.openhorizon.exchangeapi.table.node.{NodeHeartbeatIntervals, NodeRow, NodeType, NodesTQ, RegService}
import org.openhorizon.exchangeapi.table.service.ServiceRef2
import org.openhorizon.exchangeapi.utility.{ApiTime, ExchMsg}
import slick.dbio.DBIO

import java.util.UUID

/** Input format for PUT /orgs/{organization}/nodes/<node-id> */
final case class PutNodesRequest(token: String,
                                 name: String,
                                 nodeType: Option[String] = Option(NodeType.DEVICE.toString),
                                 pattern: String,
                                 registeredServices: Option[List[RegService]] = Option(List.empty[RegService]),
                                 userInput: Option[List[OneUserInputService]] = Option(List.empty[OneUserInputService]),
                                 msgEndPoint: Option[String] = Option(""),
                                 softwareVersions: Option[Map[String,String]] = Option(Map.empty[String, String]),
                                 publicKey: Option[String] = None,
                                 arch: Option[String] = Option(""),
                                 heartbeatIntervals: Option[NodeHeartbeatIntervals] = Option(NodeHeartbeatIntervals(0,0,0)),
                                 clusterNamespace: Option[String] = None,
                                 isNamespaceScoped: Option[Boolean] = Option(false)) {
  require(token!=null && name!=null && pattern!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  /** Halts the request with an error msg if the user input is invalid. */
  def getAnyProblem(id: String, noheartbeat: Option[String]): Option[String] = {
    if (id == "iamapikey" || id == "iamtoken") return Option(ExchMsg.translate("node.id.not.iamapikey.or.iamtoken"))
    if (noheartbeat.isDefined && noheartbeat.get.toLowerCase != "true" && noheartbeat.get.toLowerCase != "false") return Option(ExchMsg.translate("bad.noheartbeat.param"))
    if (token == "") return Option(ExchMsg.translate("token.must.not.be.blank"))
    // if (!NodeAgbotTokenValidation.isValid(token)) {
    //   if (ExchMsg.getLang.contains("ja") || ExchMsg.getLang.contains("ko") || ExchMsg.getLang.contains("zh")) return Some(ExchMsg.translate("invalid.password.i18n"))
    //   else return Some(ExchMsg.translate("invalid.password"))
    // }
    // if (publicKey == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, "publicKey must be specified."))  <-- skipping this check because POST /agbots/{id}/msgs checks for the publicKey
    if (nodeType.isDefined && !NodeType.containsString(nodeType.get)) return Option(ExchMsg.translate("invalid.node.type", NodeType.valuesAsString))
    if (pattern != "" && """.*/.*""".r.findFirstIn(pattern).isEmpty) return Option(ExchMsg.translate("pattern.must.have.orgid.prepended"))
    for (m <- registeredServices.getOrElse(List())) {
      // now we support more than 1 agreement for a MS
      // if (m.numAgreements != 1) halt(HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, "invalid value "+m.numAgreements+" for numAgreements in "+m.url+". Currently it must always be 1."))
      m.validate match {
        case Some(s) => return Option(s)
        case None => ;
      }
    }
    None
  }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: (DBIO[Vector[Int]], Vector[ServiceRef2]) = { NodesTQ.validateServiceIds(userInput.getOrElse(List())) }

  /** Get the db actions to insert or update all parts of the node */
  def getDbUpsert(id: String,
                  orgid: String,
                  owner: String,
                  hashedTok: String,
                  lastHeartbeat: Option[String]): DBIO[_] = {
    // default new field configState in registeredServices
    val rsvc2: Seq[RegService] = registeredServices.getOrElse(List()).map(rs => RegService(rs.url, rs.numAgreements, rs.configState.orElse(Option("active")), rs.policy, rs.properties, rs.version))
    
    NodeRow(arch = arch.getOrElse(""),
            clusterNamespace = clusterNamespace,
            heartbeatIntervals = write(heartbeatIntervals),
            id = id,
            isNamespaceScoped = isNamespaceScoped.getOrElse(false),
            lastHeartbeat = lastHeartbeat,
            lastUpdated = ApiTime.nowUTC,
            msgEndPoint = msgEndPoint.getOrElse(""),
            name = name,
            nodeType = nodeType.getOrElse(NodeType.DEVICE.toString),
            orgid = orgid,
            owner = UUID.randomUUID(),
            pattern = pattern,
            publicKey = publicKey.getOrElse(""),
            regServices = write(rsvc2),
            softwareVersions = write(softwareVersions),
            token = hashedTok,
            userInput = write(userInput)).upsert
  }

  /** Get the db actions to update all parts of the node. This is run, instead of getDbUpsert(), when it is a node doing it,
   * because we can't let a node create new nodes. */
  def getDbUpdate(id: String,
                  orgid: String,
                  owner: String,
                  hashedTok: String,
                  lastHeartbeat: Option[String]): DBIO[_] = {
    // default new field configState in registeredServices
    val rsvc2: Seq[RegService] = registeredServices.getOrElse(List()).map(rs => RegService(rs.url, rs.numAgreements, rs.configState.orElse(Option("active")), rs.policy, rs.properties, rs.version))
  
    NodeRow(arch = arch.getOrElse(""),
            clusterNamespace = clusterNamespace,
            heartbeatIntervals = write(heartbeatIntervals),
            id = id,
            isNamespaceScoped = isNamespaceScoped.getOrElse(false),
            lastHeartbeat = lastHeartbeat,
            lastUpdated = ApiTime.nowUTC,
            msgEndPoint = msgEndPoint.getOrElse(""),
            name = name,
            nodeType = nodeType.getOrElse(NodeType.DEVICE.toString),
            orgid = orgid,
            owner = UUID.randomUUID(),
            pattern = pattern,
            publicKey = publicKey.getOrElse(""),
            regServices = write(rsvc2),
            softwareVersions = write(softwareVersions),
            token = hashedTok,
            userInput = write(userInput)).update
  }
}
