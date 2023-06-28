package org.openhorizon.exchangeapi.route.node

final case class NodeMangementPolicyStatus(scheduledTime: String,
                                           startTime: Option[String],
                                           endTime: Option[String],
                                           upgradedVersions: Option[UpgradedVersions],
                                           status: Option[String],
                                           errorMessage: Option[String])
