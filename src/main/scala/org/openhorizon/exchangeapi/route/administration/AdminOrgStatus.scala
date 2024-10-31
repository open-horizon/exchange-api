package org.openhorizon.exchangeapi.route.administration

import io.swagger.v3.oas.annotations.media.Schema

case class AdminOrgStatus(msg: String = "",
                          @Schema(implementation = classOf[Map[String, Int]]) nodes: Map[String, Int]){
  
}
