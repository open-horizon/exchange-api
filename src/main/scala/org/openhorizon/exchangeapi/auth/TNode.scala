package org.openhorizon.exchangeapi.auth

case class TNode(id: String) extends Target {
  override def isOwner(user: IUser): Boolean = {
    AuthCache.getNodeOwner(id) match {
      case Some(owner) => if (owner == user.creds.id) true else false
      case None => true    // if we did not find it, we consider that as owning it because we will create it
    }
  }
  override def isThere: Boolean = all || mine || AuthCache.getNodeOwner(id).nonEmpty
  override def label = "node"
}
