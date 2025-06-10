package org.openhorizon.exchangeapi.auth

import java.util.UUID

case class TUser(id: String, owner: Option[UUID] = None) extends Target {
  override def isOwner(user: IUser): Boolean = owner == user.identity.identifier
  override def isThere: Boolean = all || mine || owner.isDefined
  override def isSuperUser: Boolean = Role.isSuperUser(id)
  override def label = "user"
}
