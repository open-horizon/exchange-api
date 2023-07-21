package org.openhorizon.exchangeapi.table.node

import org.openhorizon.exchangeapi.table.OneUserInputService

// This is the node table minus the key - used as the data structure to return to the REST clients
class Node(var token: String,
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
  def copy =
    new Node(arch = arch,
             clusterNamespace = clusterNamespace,
             ha_group = ha_group,
             heartbeatIntervals = heartbeatIntervals,
             isNamespaceScoped = isNamespaceScoped,
             lastHeartbeat = lastHeartbeat,
             lastUpdated = lastUpdated,
             msgEndPoint = msgEndPoint,
             name = name,
             nodeType = nodeType,
             owner = owner,
             pattern = pattern,
             publicKey = publicKey,
             registeredServices = registeredServices,
             softwareVersions = softwareVersions,
             token = token,
             userInput = userInput)
}
