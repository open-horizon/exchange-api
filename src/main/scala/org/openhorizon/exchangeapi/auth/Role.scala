package org.openhorizon.exchangeapi.auth

import akka.event.LoggingAdapter
import org.openhorizon.exchangeapi.auth.Access.Access

import scala.collection.mutable.{HashMap => MutableHashMap}

/** Who is allowed to do what. */
object Role {
  // the list of roles and their permissions are in config.json
  type AccessList = Set[String]
  //case class AccessList extends Set[String]

  // Making the roles and their ACLs a map, so it is more flexible at runtime
  val roles = new MutableHashMap[String, AccessList]()

  // Sets the access list to the specified role
  def setRole(role: String, accessValues: AccessList): Option[AccessList] = roles.put(role, accessValues)

  // we need at least these roles set in the config file
  //someday: if/when we support adding user-defined roles, we need to enhance this check
  def haveRequiredRoles: Boolean = roles.keySet == AuthRoles.requiredRoles   // will return true even if the elements are in a different order

  val allAccessValues: Set[String] = Access.values.map(_.toString)

  // Returns true if the specified access string is valid. Used to check input from config.json.
  def isValidAcessValues(accessValues: AccessList): Boolean = {
    for (a <- accessValues) if (!allAccessValues.contains(a)) return false
    true
  }

  // Returns true if the role has the specified access
  def hasAuthorization(role: String, access: Access)(implicit logger: LoggingAdapter): Boolean = {
    if (access == Access.NEVER_ALLOWED) return false
    roles.get(role) match {
      case Some(accessList) =>
        if (accessList.contains(Access.ALL.toString) ) return true // root user
        if (accessList.contains(Access.ALL_IN_ORG.toString) && !AccessGroups.CROSS_ORG_ACCESS.contains(access) ) return true // org admin
        accessList.contains(access.toString)
      case None =>
        logger.error (s"Role.hasAuthorization: role $role does not exist")
        false
    }
  }

  def superUser = "root/root"
  def isSuperUser(username: String): Boolean = username == superUser // only checks the username, does not verify the pw
  def isHubAdmin(username: String): Boolean = username.startsWith("root/") // only checks that user is in root org, doesn't verify anything else
}
