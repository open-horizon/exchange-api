package org.openhorizon.exchangeapi.table.node.managementpolicy.status

final case class PolicyStatus(scheduledTime: String,
                              startTime: String,
                              endTime: String,
                              upgradedVersions: UpgradedVersions,
                              status: String,
                              errorMessage: String,
                              lastUpdated: String)
