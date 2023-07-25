package org.openhorizon.exchangeapi.table.deploymentpattern

final case class PDataVerification(enabled: Boolean,
                                   URL: String,
                                   user: String,
                                   password: String,
                                   interval: Int,
                                   check_rate: Int,
                                   metering: Map[String,Any])
