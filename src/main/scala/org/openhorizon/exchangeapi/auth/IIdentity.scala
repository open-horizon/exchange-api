package org.openhorizon.exchangeapi.auth

import akka.event.LoggingAdapter
import org.openhorizon.exchangeapi.auth.Access.Access

import scala.util.{Failure, Try}

/** A generic identity before we have run authenticate to figure out what type of credentials this is */
case class IIdentity(creds: Creds) extends Identity {
  def authorizeTo(target: Target, access: Access)(implicit logger: LoggingAdapter): Try[Identity] = {
    // should never be called because authenticate() will return a real resource
    Failure(new AuthInternalErrorException("Not Implemented"))
  }
}
