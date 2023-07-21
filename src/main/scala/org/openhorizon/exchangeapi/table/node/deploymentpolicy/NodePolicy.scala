package org.openhorizon.exchangeapi.table.node.deploymentpolicy

import org.openhorizon.exchangeapi.table.OneProperty

final case class NodePolicy(label: String,
                            description: String,
                            properties: List[OneProperty],
                            constraints: List[String],
                            deployment: PropertiesAndConstraints,
                            management: PropertiesAndConstraints,
                            nodePolicyVersion: String,
                            lastUpdated: String)
