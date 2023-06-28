package org.openhorizon.exchangeapi.route.node

import org.openhorizon.exchangeapi.table.{OneUserInputService, RegService}

final case class NodeResponse(id: String,
                              name: String,
                              services: List[RegService],
                              userInput: List[OneUserInputService],
                              msgEndPoint: String,
                              publicKey: String,
                              arch: String,
                              clusterNamespace: String)
