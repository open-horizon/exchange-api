package org.openhorizon.exchangeapi.route.user

import org.openhorizon.exchangeapi.utility.ExchMsg

final case class MethodNotAllowedException(override val getMessage: String) 
  extends RuntimeException(getMessage)