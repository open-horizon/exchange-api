package org.openhorizon.exchangeapi.route.node

final case class UpgradedVersions(softwareVersion: Option[String],
                                  certVersion: Option[String],
                                  configVersion: Option[String])
