package org.openhorizon.exchangeapi.route.organization

final case class ResourceChangesRespObject(changes: List[ChangeEntry],
                                           mostRecentChangeId: Long,
                                           hitMaxRecords: Boolean,
                                           exchangeVersion: String)
