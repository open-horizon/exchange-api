package org.openhorizon.exchangeapi.auth

import org.openhorizon.exchangeapi.ExchangeApiApp.getOwnerOfResource

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

case class TService(id: String, owner: Option[UUID] = None, public: Boolean = false) extends Target {      // for services only the user that created it can update/delete it
  override def isOwner(user: IUser): Boolean = {
    if (owner.isEmpty || owner.get == user.identity.identifier.get)
      true
    else
      false
  }
  override def isPublic: Boolean = if (all) true else public
  override def isThere: Boolean = all || mine || owner.isDefined
  override def label = "service"
}
