package org.openhorizon.exchangeapi.auth

case class TUser(id: String) extends Target {
  override def isOwner(user: IUser): Boolean = id == user.creds.id
  override def isThere: Boolean = all || mine || AuthCache.getUserIsAdmin(id).nonEmpty
  //override def isAdmin: Boolean = AuthCache.getUserIsAdmin(id).getOrElse(false) // <- these 2 are reliable on every exchange instance
  //override def isHubAdmin: Boolean = AuthCache.getUserIsHubAdmin(id).getOrElse(false)
  override def isSuperUser: Boolean = Role.isSuperUser(id)
  override def label = "user"
}
