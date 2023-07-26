package org.openhorizon.exchangeapi.auth

case class TAgbot(id: String) extends Target {
  override def isOwner(user: IUser): Boolean = {
    AuthCache.getAgbotOwner(id) match {
      case Some(owner) => if (owner == user.creds.id) true else false
      case None => true    // if we did not find it, we consider that as owning it because we will create it
    }
  }
  override def isThere: Boolean = all || mine || AuthCache.getAgbotOwner(id).nonEmpty
  override def label = "agbot"
}
