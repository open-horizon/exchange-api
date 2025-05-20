package org.openhorizon.exchangeapi.auth

import org.openhorizon.exchangeapi.ExchangeApiApp.getOwnerOfResource

case class TNode(id: String) extends Target {
  override def isOwner(user: IUser): Boolean = {
    getOwnerOfResource(organization = getOrg, resource = id, something = "node") match {
      case Some(owner) =>
        if (owner == user.identity.identifier.get)
          true
        else
          false
      case None => true    // if we did not find it, we consider that as owning it because we will create it
    }
  }
  override def isThere: Boolean = all || mine || getOwnerOfResource(organization = getOrg, resource = id, something = "node").nonEmpty
  override def label = "node"
}
