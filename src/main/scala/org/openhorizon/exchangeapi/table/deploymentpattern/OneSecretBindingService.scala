package org.openhorizon.exchangeapi.table.deploymentpattern

final case class OneSecretBindingService(serviceOrgid: String,
                                         serviceUrl: String,
                                         serviceArch: Option[String],
                                         serviceVersionRange: Option[String],
                                         secrets: List[Map[String, String]])
