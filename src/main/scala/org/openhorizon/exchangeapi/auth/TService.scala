package org.openhorizon.exchangeapi.auth

case class TService(id: String) extends Target {      // for services only the user that created it can update/delete it
  override def isOwner(user: IUser): Boolean = {
    AuthCache.getServiceOwner(id) match {
      case Some(owner) => if (owner == user.creds.id) true else false
      case None => true    // if we did not find it, we consider that as owning it because we will create it
    }
  }
  override def isPublic: Boolean = if (all) true else AuthCache.getServiceIsPublic(id).getOrElse(false)
  override def isThere: Boolean = all || mine || AuthCache.getServiceOwner(id).nonEmpty
  override def label = "service"
}
