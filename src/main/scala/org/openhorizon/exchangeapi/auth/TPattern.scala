package org.openhorizon.exchangeapi.auth

import org.openhorizon.exchangeapi.ExchangeApiApp.getOwnerOfResource

case class TPattern(id: String) extends Target {      // for patterns only the user that created it can update/delete it
  override def isOwner(user: IUser): Boolean = {
    getOwnerOfResource(organization = getOrg, resource = id, something = "deployment_pattern") match {
      case Some(owner) =>
        if (owner == user.identity.identifier.get)
          true
        else
          false
      case None => true    // if we did not find it, we consider that as owning it because we will create it
    }
  }
  override def isPublic: Boolean = if (all) true else AuthCache.getPatternIsPublic(id).getOrElse(false)
  override def isThere: Boolean = all || mine || getOwnerOfResource(organization = getOrg, resource = id, something = "deployment_pattern").nonEmpty
  override def label = "pattern"
}
