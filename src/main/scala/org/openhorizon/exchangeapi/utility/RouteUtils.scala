package org.openhorizon.exchangeapi.utility

import org.openhorizon.exchangeapi.route.organization.{NodeHealthAgreementElement, NodeHealthHashElement}

import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}


object RouteUtils {
  
  /** From the given db joined node/agreement rows, build the output node health hash and return it.
    * This is shared between POST /org/{orgid}/patterns/{pat-id}/nodehealth and POST /org/{orgid}/search/nodehealth */
  def buildNodeHealthHash(list: scala.Seq[(String, Option[String], Option[String], Option[String])]): Map[String, NodeHealthHashElement] = {
    // Go thru the rows and build a hash of the nodes, adding the agreement to its value as we encounter them
    val nodeHash = new MutableHashMap[String, NodeHealthHashElement] // key is node id, value has lastHeartbeat and the agreements map
    for ((nodeId, lastHeartbeat, agrId, agrLastUpdated) <- list) {
      nodeHash.get(nodeId) match {
        case Some(nodeElement) => agrId match { // this node is already in the hash, add the agreement if it's there
          case Some(agId) => nodeElement.agreements = nodeElement.agreements + ((agId, NodeHealthAgreementElement(agrLastUpdated.getOrElse("")))) // if we are here, lastHeartbeat is already set and the agreement Map is already created
          case None => ; // no agreement to add to the agreement hash
        }
        case None => agrId match { // this node id not in the hash yet, add it
          case Some(agId) => nodeHash.put(nodeId, new NodeHealthHashElement(lastHeartbeat, Map(agId -> NodeHealthAgreementElement(agrLastUpdated.getOrElse("")))))
          case None => nodeHash.put(nodeId, new NodeHealthHashElement(lastHeartbeat, Map()))
        }
      }
    }
    nodeHash.toMap
  }
  
}
