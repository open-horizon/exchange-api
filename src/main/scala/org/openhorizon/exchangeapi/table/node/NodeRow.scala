package org.openhorizon.exchangeapi.table.node

import org.json4s.jackson.Serialization.{read, write}
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.route.node.PutNodesRequest
import org.openhorizon.exchangeapi.table.deploymentpattern.OneUserInputService
import org.openhorizon.exchangeapi.utility.ApiTime.fixFormatting
import org.openhorizon.exchangeapi.utility.StrConstants
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import java.sql.Timestamp
import java.time.ZoneId
import java.util.UUID

final case class NodeRow(id: String,
                         orgid: String,
                         token: String,
                         name: String,
                         owner: UUID,
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
  def this(heartbeat: Option[String],
           modified_at: Timestamp,
           node: String,
           organization: String,
           owner: UUID,
           request: PutNodesRequest)(implicit defaultFormats: DefaultFormats) =
    this(arch = request.arch.get,
         clusterNamespace = request.clusterNamespace,
         heartbeatIntervals = write(request.heartbeatIntervals.get),
         id = node,
         isNamespaceScoped = request.isNamespaceScoped.get,
         lastHeartbeat = heartbeat,
         lastUpdated =
           fixFormatting(modified_at.toInstant
                                    .atZone(ZoneId.of("UTC"))
                                    .withZoneSameInstant(ZoneId.of("UTC"))
                                    .toString),
         msgEndPoint = request.msgEndPoint.get,
         name = request.name,
         nodeType = request.nodeType.get,
         orgid = organization,
         owner = owner,
         pattern = request.pattern.getOrElse(""),
         publicKey = request.publicKey.getOrElse(""),
         regServices = write(request.registeredServices.get.map(rs => RegService(rs.url, rs.numAgreements, rs.configState.orElse(Option("active")), rs.policy, rs.properties, rs.version))),
         softwareVersions = write(request.softwareVersions.get),
         token = Password.hash(request.token.getOrElse("")),
         userInput = write(request.userInput.get))

  def upsert: DBIO[_] = {
    //val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.fastHash(token)  <- token is already hashed
    if (Role.isSuperUser(owner.toString))
      NodesTQ.map(d => (d.id, d.orgid, d.token, d.name, d.nodeType, d.pattern, d.regServices, d.userInput, d.msgEndPoint, d.softwareVersions, d.lastHeartbeat, d.publicKey, d.arch, d.heartbeatIntervals, d.lastUpdated, d.clusterNamespace, d.isNamespaceScoped)).insertOrUpdate((id, orgid, token, name, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions, lastHeartbeat.orElse(None), publicKey, arch, heartbeatIntervals, lastUpdated, clusterNamespace, isNamespaceScoped))
    else
      NodesTQ.insertOrUpdate(NodeRow(id, orgid, token, name, owner, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions, lastHeartbeat, publicKey, arch, heartbeatIntervals, lastUpdated, clusterNamespace, isNamespaceScoped))
  }

  def update: DBIO[_] = {
    //val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.fastHash(token)  <- token is already hashed
    /*if (owner == "") (
        for {
          d <- NodesTQ if d.id === id
        } yield (d.id,d.orgid,d.token,d.name,d.nodeType,d.pattern,d.regServices,d.userInput,
            d.msgEndPoint,d.softwareVersions,d.lastHeartbeat,d.publicKey, d.arch, d.heartbeatIntervals, d.lastUpdated, d.clusterNamespace, d.isNamespaceScoped))
            .update((id, orgid, token, name, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions,
                lastHeartbeat.orElse(None), publicKey, arch, heartbeatIntervals, lastUpdated, clusterNamespace, isNamespaceScoped))
    else */(for { d <- NodesTQ if d.id === id } yield d).update(NodeRow(id, orgid, token, name, owner, nodeType, pattern, regServices, userInput, msgEndPoint, softwareVersions, lastHeartbeat, publicKey, arch, heartbeatIntervals, lastUpdated, clusterNamespace, isNamespaceScoped))
  }
}
