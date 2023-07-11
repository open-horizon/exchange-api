package org.openhorizon.exchangeapi.table.node.agreement

import scala.collection.mutable.{HashMap => MutableHashMap}
import org.json4s.{DefaultFormats, Formats}

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
