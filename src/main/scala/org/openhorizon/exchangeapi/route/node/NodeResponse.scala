package org.openhorizon.exchangeapi.route.node

import org.openhorizon.exchangeapi.table.OneUserInputService
import org.openhorizon.exchangeapi.table.node.RegService

final case class NodeResponse(id: String,
                              name: String,
                              services: List[RegService],
                              userInput: List[OneUserInputService],
                              msgEndPoint: String,
                              publicKey: String,
                              arch: String,
                              clusterNamespace: String,
                              isNamespaceScoped: Boolean = false)
