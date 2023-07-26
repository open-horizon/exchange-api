package org.openhorizon.exchangeapi.auth

// These are default roles (always defined). More can be added in the config file
object AuthRoles {
  val SuperUser = "SuperUser"
  val AdminUser = "AdminUser"
  val HubAdmin = "HubAdmin"
  val User = "User"
  val Node = "Node"
  val Agbot = "Agbot"
  val Anonymous = "Anonymous"
  val requiredRoles: Set[String] =
    Set(Anonymous,
        User,
        AdminUser,
        HubAdmin,
        SuperUser,
        Node,
        Agbot)
}
