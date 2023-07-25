package org.openhorizon.exchangeapi.table.deploymentpattern

final case class OneUserInputService(serviceOrgid: String,
                                     serviceUrl: String,
                                     serviceArch: Option[String],
                                     serviceVersionRange: Option[String],
                                     inputs: List[OneUserInputValue])
