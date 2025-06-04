package org.openhorizon.exchangeapi.auth

import org.openhorizon.exchangeapi.ExchangeApiApp.{getOwnerOfResource, logger}

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

case class TNode(id: String, owner: Option[UUID] = None) extends Target {
  override def isOwner(user: IUser): Boolean =
    if (owner.isEmpty || owner.get == user.identity.identifier.get)
      true
    else
      false
  override def isThere: Boolean = all || mine || owner.isDefined
  override def label = "node"
}
