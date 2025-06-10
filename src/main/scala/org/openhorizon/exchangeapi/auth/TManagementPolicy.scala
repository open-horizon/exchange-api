package org.openhorizon.exchangeapi.auth

import org.openhorizon.exchangeapi.ExchangeApiApp.getOwnerOfResource

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

case class TManagementPolicy(id: String, owner: Option[UUID] = None) extends Target {      // for management policies only the user that created it can update/delete it
  override def isOwner(user: IUser): Boolean = {
    if (owner.isEmpty || owner.get == user.identity.identifier.get)
      true
    else
      false
  }
  // management policies can never be public, so no need to override isPublic
  override def isThere: Boolean = all || mine || owner.isDefined
  override def label = "management policy"
}
