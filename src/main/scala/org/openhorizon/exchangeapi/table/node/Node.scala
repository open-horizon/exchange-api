package org.openhorizon.exchangeapi.table.node

import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization.read
import org.openhorizon.exchangeapi.table.deploymentpattern.OneUserInputService

import java.util.UUID

// This is the node table minus the key - used as the data structure to return to the REST clients
// Default Constructor
case class Node(var token: String,
                var name: String,
                var owner: String,
                var nodeType: String,
                var pattern: String,
                var registeredServices: List[RegService],
                var userInput: List[OneUserInputService],
                var msgEndPoint: String,
                var softwareVersions: Map[String,String],
                var lastHeartbeat: String,
                var publicKey: String,
                var arch: String,
                var heartbeatIntervals: NodeHeartbeatIntervals,
                var ha_group: Option[String],
                var lastUpdated: String,
                var clusterNamespace: String = "",
                var isNamespaceScoped: Boolean = false) {
    // NodeRow Constructor. Have to provide the HA Group separately.
    def this(haGroup: Option[String], nodeRow: NodeRow) =
      this (arch = nodeRow.arch,
          clusterNamespace = nodeRow.clusterNamespace.getOrElse(""),
          ha_group = haGroup,
          heartbeatIntervals =
              if (nodeRow.heartbeatIntervals.nonEmpty) {
                implicit val jsonFormats: Formats = DefaultFormats
                read[NodeHeartbeatIntervals](nodeRow.heartbeatIntervals)
              }
              else
                  NodeHeartbeatIntervals(0, 0, 0),
          isNamespaceScoped = nodeRow.isNamespaceScoped,
          lastHeartbeat = nodeRow.lastHeartbeat.orNull,
          lastUpdated = nodeRow.lastUpdated,
          msgEndPoint = nodeRow.msgEndPoint,
          name = nodeRow.name,
          nodeType =
              if (nodeRow.nodeType.isEmpty)
                  NodeType.DEVICE.toString
              else
                  nodeRow.nodeType,
          owner = nodeRow.owner.toString,
          pattern = nodeRow.pattern,
          publicKey = nodeRow.publicKey,
          registeredServices =
              (if (nodeRow.regServices.nonEmpty) {
                implicit val jsonFormats: Formats = DefaultFormats
                read[List[RegService]](nodeRow.regServices)
              }
              else
                  List[RegService]()).map(rs => RegService(rs.url, rs.numAgreements, rs.configState.orElse(Option("active")), rs.policy, rs.properties, rs.version.orElse(Some("")))),
          softwareVersions =
              if (nodeRow.softwareVersions.nonEmpty) {
                implicit val jsonFormats: Formats = DefaultFormats
                read[Map[String, String]](nodeRow.softwareVersions)
              }
              else
                  Map[String, String](),
          token = nodeRow.token,
          userInput =
              (if (nodeRow.userInput.nonEmpty) {
                implicit val jsonFormats: Formats = DefaultFormats
                read[List[OneUserInputService]](nodeRow.userInput)
              }
              else
                  List[OneUserInputService]()))
    
    // Basic Tuple Constructor. When dealing with non-proven shapes from Slick.
    def this(node: (String,          // arch
                    Option[String],  // clusterNamespace
                    Option[String],  // ha_group
                    String,          // heartbeatIntervals
                    Boolean,         // isNamespaceScoped
                    Option[String],  // lastHeartbeat
                    String,          // lastUpdated
                    String,          // msgEndPoint
                    String,          // name
                    String,          // nodeType
                    String,          // owner
                    String,          // pattern
                    String,          // publicKey
                    String,          // regServices
                    String,          // softwareVersions
                    String,          // token
                    String)) =       // userInput
        this(arch = node._1,
             clusterNamespace = node._2.getOrElse(""),
             ha_group = node._3,
             heartbeatIntervals =
                 if (node._4.nonEmpty) {
                   implicit val jsonFormats: Formats = DefaultFormats
                   read[NodeHeartbeatIntervals](node._4)
                 }
                 else
                     NodeHeartbeatIntervals(0, 0, 0),
             isNamespaceScoped = node._5,
             lastHeartbeat = node._6.orNull,
             lastUpdated = node._7,
             msgEndPoint = node._8,
             name = node._9,
             nodeType =
                 if (node._10.isEmpty)
                     NodeType.DEVICE.toString
                  else
                     node._10,
             owner = node._11,
             pattern = node._12,
             publicKey = node._13,
             registeredServices =
                  (if (node._14.nonEmpty) {
                    implicit val jsonFormats: Formats = DefaultFormats
                    read[List[RegService]](node._14)
                  }
                 else
                     List[RegService]()).map(rs => RegService(rs.url, rs.numAgreements, rs.configState.orElse(Option("active")), rs.policy, rs.properties, rs.version.orElse(Some("")))),
             softwareVersions =
                 if (node._15.nonEmpty) {
                   implicit val jsonFormats: Formats = DefaultFormats
                   read[Map[String, String]](node._15)
                 }
                 else
                     Map[String, String](),
             token = node._16,
             userInput =
                 if (node._17.nonEmpty) {
                   implicit val jsonFormats: Formats = DefaultFormats
                   read[List[OneUserInputService]](node._17)
                 }
                 else
                     List[OneUserInputService]())
    
    // Values Constructor. Will convert strings to JSON.
    def this(arch: String,
             clusterNamespace: Option[String],
             ha_group: Option[String],
             heartbeatIntervals: String,
             isNamespaceScoped: Boolean,
             lastHeartbeat: Option[String],
             lastUpdated: String,
             msgEndPoint: String,
             name: String,
             nodeType: String,
             owner: String,
             pattern: String,
             publicKey: String,
             regServices: String,
             softwareVersions: String,
             token: String,
             userInput: String) =
        this(arch = arch,
             clusterNamespace = clusterNamespace.getOrElse(""),
             ha_group = ha_group,
             heartbeatIntervals =
               if (heartbeatIntervals.nonEmpty) {
                 implicit val jsonFormats: Formats = DefaultFormats
                 read[NodeHeartbeatIntervals](heartbeatIntervals)
               }
               else
                 NodeHeartbeatIntervals(0, 0, 0),
             isNamespaceScoped = isNamespaceScoped,
             lastHeartbeat = lastHeartbeat.orNull,
             lastUpdated = lastUpdated,
             msgEndPoint = msgEndPoint,
             name = name,
              nodeType =
                  if (nodeType.isEmpty)
                      NodeType.DEVICE.toString
                  else
                      nodeType,
              owner = owner,
              pattern = pattern,
              publicKey = publicKey,
              registeredServices =
                  (if (regServices.nonEmpty) {
                    implicit val jsonFormats: Formats = DefaultFormats
                    read[List[RegService]](regServices)
                  }
                  else
                      List[RegService]()).map(rs => RegService(rs.url, rs.numAgreements, rs.configState.orElse(Option("active")), rs.policy, rs.properties, rs.version.orElse(Some("")))),
              softwareVersions =
                  if (softwareVersions.nonEmpty) {
                    implicit val jsonFormats: Formats = DefaultFormats
                    read[Map[String, String]](softwareVersions)
                  }
                  else
                      Map[String, String](),
              token =
                if(token.isEmpty)
                  "***************"
                else
                  token,
              userInput =
                  if (userInput.nonEmpty) {
                    implicit val jsonFormats: Formats = DefaultFormats
                    read[List[OneUserInputService]](userInput)
                  }
                  else
                      List[OneUserInputService]())
}
