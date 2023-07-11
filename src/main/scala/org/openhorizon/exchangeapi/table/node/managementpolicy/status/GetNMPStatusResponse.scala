package org.openhorizon.exchangeapi.table.node.managementpolicy.status

import org.openhorizon.exchangeapi.table.node.managementpolicy.status

object GetNMPStatusResponse{
  def apply(managementStatus: Seq[NodeMgmtPolStatusRow], lastIndex: Int = 0): GetNMPStatusResponse = {
    new GetNMPStatusResponse(
      managementStatus =
        managementStatus.map(
          e => e.policy -> status.NMPStatus(agentUpgradePolicyStatus =
                                       PolicyStatus(scheduledTime = e.scheduledStartTime,
                                                    startTime = e.actualStartTime.getOrElse(""),
                                                    endTime = e.endTime.getOrElse(""),
                                                    upgradedVersions =
                                                      UpgradedVersions(softwareVersion = e.softwareVersion.getOrElse(""),
                                                                       certVersion = e.certificateVersion.getOrElse(""),
                                                                       configVersion = e.configurationVersion.getOrElse("")),
                                                    status = e.status.getOrElse(""),
                                                    errorMessage = e.errorMessage.getOrElse(""),
                                                    lastUpdated = e.updated))
        ).toMap,
      lastIndex = lastIndex)
  }

  def unapply(response: GetNMPStatusResponse): Option[(Map[String,NMPStatus], Int)] = {
    Option((response.managementStatus, response.lastIndex))
  }
}

final case class GetNMPStatusResponse(managementStatus: Map[String,NMPStatus], lastIndex: Int)