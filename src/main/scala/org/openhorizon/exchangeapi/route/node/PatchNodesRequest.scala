package org.openhorizon.exchangeapi.route.node

import org.openhorizon.exchangeapi.table.{NodeHeartbeatIntervals, OneUserInputService, RegService}

final case class PatchNodesRequest(token: Option[String] = None,
                                   name: Option[String] = None,
                                   nodeType: Option[String] = None,
                                   pattern: Option[String] = None,
                                   registeredServices: Option[List[RegService]] = None,
                                   userInput: Option[List[OneUserInputService]] = None,
                                   msgEndPoint: Option[String] = None,
                                   softwareVersions: Option[Map[String,String]] = None,
                                   publicKey: Option[String] = None,
                                   arch: Option[String] = None,
                                   heartbeatIntervals: Option[NodeHeartbeatIntervals] = None,
                                   clusterNamespace: Option[String] = None)
/** {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    if (token.isDefined && token.get == "") Some(ExchMsg.translate("token.cannot.be.empty.string"))
    // if (token.isDefined && !NodeAgbotTokenValidation.isValid(token.get)) {
    //   if (ExchMsg.getLang.contains("ja") || ExchMsg.getLang.contains("ko") || ExchMsg.getLang.contains("zh")) return Some(ExchMsg.translate("invalid.password.i18n"))
    //   else return Some(ExchMsg.translate("invalid.password"))
    // }
    //else if (!requestBody.trim.startsWith("{") && !requestBody.trim.endsWith("}")) Some(ExchMsg.translate("invalid.input.message", requestBody))
    else None
  }
    /** Returns a tuple of the db action to update parts of the node, and the attribute name being updated. */
  def getDbUpdate(id: String, hashedPw: String): (DBIO[_],String) = {
    val currentTime: String = ApiTime.nowUTC
    //someday: support updating more than 1 attribute, but i think slick does not support dynamic db field names
    // find the 1st non-blank attribute and create a db action to update it for this node
    var dbAction: (DBIO[_], String) = (null, null)
    // nodeType intentionally missing from this 1st list of attributes, because we will default it if it is the only 1 not specified
    if(token.isEmpty &&
       softwareVersions.isDefined &&
       registeredServices.isDefined &&
       name.isDefined &&
       pattern.isDefined &&
       userInput.isDefined &&
       msgEndPoint.isDefined &&
       publicKey.isDefined &&
       arch.isDefined) {
      dbAction = ((for { d <- NodesTQ if d.id === id } yield (d.id,d.softwareVersions, d.regServices, d.name, d.nodeType, d.pattern, d.userInput, d.msgEndPoint, d.publicKey, d.arch, d.lastHeartbeat, d.lastUpdated)).update((id, write(softwareVersions), write(registeredServices), name.get, nodeType.getOrElse(NodeType.DEVICE.toString), pattern.get, write(userInput), msgEndPoint.get, publicKey.get, arch.get, Some(currentTime), currentTime)), "update all but token")
    }
    else if (token.isDefined){
      dbAction = ((for { d <- NodesTQ if d.id === id } yield (d.id,d.token,d.lastHeartbeat, d.lastUpdated)).update((id, hashedPw, Some(currentTime), currentTime)), "token")
    }
    else if (softwareVersions.isDefined){
      val swVersions: String = if (softwareVersions.nonEmpty) write(softwareVersions) else ""
      dbAction = ((for { d <- NodesTQ if d.id === id } yield (d.id,d.softwareVersions,d.lastHeartbeat, d.lastUpdated)).update((id, swVersions, Option(currentTime), currentTime)), "softwareVersions")
    }
    else if (registeredServices.isDefined){
      val regSvc: String = if (registeredServices.nonEmpty) write(registeredServices) else ""
      dbAction =  ((for { d <- NodesTQ if d.id === id } yield (d.id,d.regServices,d.lastHeartbeat, d.lastUpdated)).update((id, regSvc, Option(currentTime), currentTime)), "registeredServices")
    }
    else if (name.isDefined){
      dbAction = ((for { d <- NodesTQ if d.id === id } yield (d.id,d.name,d.lastHeartbeat, d.lastUpdated)).update((id, name.get, Option(currentTime), currentTime)), "name")
    }
    else if (nodeType.isDefined){
      dbAction = ((for { d <- NodesTQ if d.id === id } yield (d.id,d.nodeType,d.lastHeartbeat, d.lastUpdated)).update((id, nodeType.get, Option(currentTime), currentTime)), "nodeType")
    }
    else if (pattern.isDefined){
      dbAction = ((for { d <- NodesTQ if d.id === id } yield (d.id,d.pattern,d.lastHeartbeat, d.lastUpdated)).update((id, pattern.get, Option(currentTime), currentTime)), "pattern")
    }
    else if (userInput.isDefined){
      dbAction = ((for { d <- NodesTQ if d.id === id } yield (d.id,d.userInput,d.lastHeartbeat, d.lastUpdated)).update((id, write(userInput), Option(currentTime), currentTime)), "userInput")
    }
    else if (msgEndPoint.isDefined){
      dbAction = ((for { d <- NodesTQ if d.id === id } yield (d.id,d.msgEndPoint,d.lastHeartbeat, d.lastUpdated)).update((id, msgEndPoint.get, Option(currentTime), currentTime)), "msgEndPoint")
    }
    else if (publicKey.isDefined){
      dbAction = ((for { d <- NodesTQ if d.id === id } yield (d.id,d.publicKey,d.lastHeartbeat, d.lastUpdated)).update((id, publicKey.get, Option(currentTime), currentTime)), "publicKey")
    }
    else if (arch.isDefined){
      dbAction = ((for { d <- NodesTQ if d.id === id } yield (d.id,d.arch,d.lastHeartbeat, d.lastUpdated)).update((id, arch.get, Option(currentTime), currentTime)), "arch")
    }
    else if (heartbeatIntervals.isDefined){
      dbAction = ((for { d <- NodesTQ if d.id === id } yield (d.id,d.heartbeatIntervals,d.lastHeartbeat, d.lastUpdated)).update((id, write(heartbeatIntervals), Option(currentTime), currentTime)), "heartbeatIntervals")
    }
    else if (clusterNamespace.isDefined) {
      dbAction = ((for {d <- NodesTQ if d.id === id} yield (d.id, d.clusterNamespace, d.lastHeartbeat, d.lastUpdated)).update((id, clusterNamespace, Option(currentTime), currentTime)), "heartbeatIntervals")
    }
    else if (clusterNamespace.isDefined) {
      dbAction = ((for {d <- NodesTQ if d.id === id} yield (d.id, d.clusterNamespace, d.lastHeartbeat, d.lastUpdated)).update((id, clusterNamespace, Option(currentTime), currentTime)), "heartbeatIntervals")
    }
    dbAction
  }
}
**/
