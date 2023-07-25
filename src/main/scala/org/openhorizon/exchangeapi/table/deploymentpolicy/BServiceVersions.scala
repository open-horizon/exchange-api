package org.openhorizon.exchangeapi.table.deploymentpolicy

final case class BServiceVersions(version: String,
                                  priority: Option[Map[String,Int]],
                                  upgradePolicy: Option[Map[String,String]])
