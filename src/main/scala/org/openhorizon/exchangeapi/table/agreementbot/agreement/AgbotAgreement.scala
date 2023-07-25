package org.openhorizon.exchangeapi.table.agreementbot.agreement

import org.openhorizon.exchangeapi.table.agreementbot.AAService


final case class AgbotAgreement(service: AAService,
                                state: String,
                                lastUpdated: String,
                                dataLastReceived: String)
