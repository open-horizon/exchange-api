package org.openhorizon.exchangeapi.table.deploymentpattern

final case class PServiceVersions(version: String,
                                  deployment_overrides: Option[String],
                                  deployment_overrides_signature: Option[String],
                                  priority: Option[Map[String,Int]],
                                  upgradePolicy: Option[Map[String,String]])
