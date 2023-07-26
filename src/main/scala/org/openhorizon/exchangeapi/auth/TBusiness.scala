package org.openhorizon.exchangeapi.auth

case class TBusiness(id: String) extends Target {      // for business policies only the user that created it can update/delete it
  override def isOwner(user: IUser): Boolean = {
    AuthCache.getBusinessOwner(id) match {
      case Some(owner) => if (owner == user.creds.id) true else false
      case None => true    // if we did not find it, we consider that as owning it because we will create it
    }
  }
  // business policies can never be public, so no need to override isPublic
  override def isThere: Boolean = all || mine || AuthCache.getBusinessOwner(id).nonEmpty
  override def label = "business policy"
}
