package org.openhorizon.exchangeapi.route.organization

final case class GetOrgStatusResponse(msg: String,
                                      numberOfUsers: Int,
                                      numberOfNodes: Int,
                                      numberOfNodeAgreements: Int,
                                      numberOfRegisteredNodes: Int,
                                      numberOfNodeMsgs: Int,
                                      SchemaVersion: Int)
