package org.openhorizon.exchangeapi.route.deploymentpolicy

final case class PolicySearchResponseDesync(agbot: String,
                                            offset: Option[String],
                                            session: Option[String]) extends Throwable
