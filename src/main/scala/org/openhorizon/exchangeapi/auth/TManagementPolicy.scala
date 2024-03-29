package org.openhorizon.exchangeapi.auth

case class TManagementPolicy(id: String) extends Target {      // for management policies only the user that created it can update/delete it
  override def isOwner(user: IUser): Boolean = {
    AuthCache.getManagementPolicyOwner(id) match {
      case Some(owner) => if (owner == user.creds.id) true else false
      case None => true    // if we did not find it, we consider that as owning it because we will create it
    }
  }
  // management policies can never be public, so no need to override isPublic
  override def isThere: Boolean = all || mine || AuthCache.getManagementPolicyOwner(id).nonEmpty
  override def label = "management policy"
}
