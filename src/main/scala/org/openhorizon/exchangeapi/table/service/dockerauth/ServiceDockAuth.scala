package org.openhorizon.exchangeapi.table.service.dockerauth

final case class ServiceDockAuth(dockAuthId: Int,
                                 registry: String,
                                 username: String,
                                 token: String,
                                 lastUpdated: String)
