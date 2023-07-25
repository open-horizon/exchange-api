package org.openhorizon.exchangeapi.table.deploymentpolicy

final case class BService(name: String,
                          org: String,
                          arch: String,
                          serviceVersions: List[BServiceVersions],
                          nodeHealth: Option[Map[String,Int]],
                          clusterNamespace: Option[String] = None)
