package org.openhorizon.exchangeapi.route.deploymentpattern

/**
  * Input for pattern-based search for nodes to make agreements with.
  *
  * Pattern does not use changedSince like policy search because pattern agreements either exist
  * or they do not. Pattern agreements are not time-boxed.
  **/
final case class PostPatternSearchRequest(arch: Option[String],
                                          nodeOrgids: Option[List[String]],
                                          numEntries: Option[String] = None,  // Not used.
                                          secondsStale: Option[Int],
                                          serviceUrl: String = "",
                                          startIndex: Option[String] = None)
