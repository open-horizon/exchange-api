package org.openhorizon.exchangeapi.table.deploymentpattern

final case class PServices(serviceUrl: String,
                           serviceOrgid: String,
                           serviceArch: String,
                           agreementLess: Option[Boolean],
                           serviceVersions: List[PServiceVersions],
                           dataVerification: Option[Map[String,Any]],
                           nodeHealth: Option[Map[String,Int]])
