package org.openhorizon.exchangeapi.table.node.deploymentpolicy

import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.node.deploymentpolicy
import org.openhorizon.exchangeapi.table.OneProperty
import slick.jdbc.PostgresProfile.api._
import slick.dbio.DBIO

final case class NodePolicyRow(nodeId: String,
                               label: String,
                               description: String,
                               properties: String,
                               constraints: String,
                               deployment: String,
                               management: String,
                               nodePolicyVersion: String,
                               lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  
  def toNodePolicy: NodePolicy = {
    val prop: List[OneProperty] = if (properties != "") read[List[OneProperty]](properties) else List[OneProperty]()
    val con: List[String] = if (constraints != "") read[List[String]](constraints) else List[String]()
    val dep: PropertiesAndConstraints = if (deployment != "") read[PropertiesAndConstraints](deployment) else PropertiesAndConstraints(None, None)
    val mgmt: PropertiesAndConstraints = if (management != "") read[PropertiesAndConstraints](management) else PropertiesAndConstraints(None, None)
    deploymentpolicy.NodePolicy(label, description, prop, con, dep, mgmt, nodePolicyVersion, lastUpdated)
  }
  
  def upsert: DBIO[_] = NodePolicyTQ.insertOrUpdate(this)
}
