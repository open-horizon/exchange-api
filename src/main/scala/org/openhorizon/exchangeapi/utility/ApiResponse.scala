package org.openhorizon.exchangeapi.utility

/** These are used as the response structure for most PUTs, POSTs, and DELETEs. */
final case class ApiResponse(code: String,
                             msg: String)
