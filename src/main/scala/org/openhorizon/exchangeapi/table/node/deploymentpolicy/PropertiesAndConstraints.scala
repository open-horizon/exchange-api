package org.openhorizon.exchangeapi.table.node.deploymentpolicy

import org.openhorizon.exchangeapi.table.service.OneProperty

final case class PropertiesAndConstraints(properties: Option[List[OneProperty]],
                                          constraints: Option[List[String]])
