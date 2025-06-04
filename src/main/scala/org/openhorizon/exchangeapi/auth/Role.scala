package org.openhorizon.exchangeapi.auth

import com.typesafe.config.ConfigValue
import org.apache.pekko.event.LoggingAdapter
import org.openhorizon.exchangeapi.auth.Access.Access
import org.openhorizon.exchangeapi.utility.Configuration

import scala.collection.mutable.{HashMap => MutableHashMap}
import scala.jdk.CollectionConverters.{CollectionHasAsScala, MapHasAsScala}
import scala.language.postfixOps

/** Who is allowed to do what. */
object Role {
  // the list of roles and their permissions are in config.json
  type AccessList = Set[String]
  //case class AccessList extends Set[String]

  // Sets the access list to the specified role
  def setRole(role: String, accessValues: AccessList): Option[AccessList] = roles.put(role, accessValues)

  // we need at least these roles set in the config file
  //someday: if/when we support adding user-defined roles, we need to enhance this check
  def haveRequiredRoles: Boolean = roles.keySet == AuthRoles.requiredRoles   // will return true even if the elements are in a different order

  val allAccessValues: Set[String] = Access.values.map(_.toString)
  
  // Making the roles and their ACLs a map, so it is more flexible at runtime
  var roles: MutableHashMap[String, AccessList] = {
    val roles: MutableHashMap[String, AccessList] = loadRoles()
    
    if (!AuthRoles.requiredRoles.subsetOf(roles.keySet))
      println("Error: at least these roles must be set in the config file: " + AuthRoles.requiredRoles.mkString(", "))
    
    roles
  }

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
  
  def loadRoles(): MutableHashMap[String, AccessList] = {
    // Read the ACLs and set them in our Role object
    val RoleMap = new MutableHashMap[String, AccessList]
    for ((role, _) <- Configuration.getConfig.getObject("api.acls").asScala.toMap) {
      val accessSet: Set[String] = Configuration.getConfig.getStringList("api.acls." + role).asScala.toSet
      if (!isValidAcessValues(accessSet))
        println("Error: invalid value in ACLs in config file for role " + role)
      else
        RoleMap.put(role, accessSet)
    }
    
    println(s"Roles: ${RoleMap}")
    
    RoleMap
  }

  def superUser = "root/root"
  def isSuperUser(username: String): Boolean = username == superUser // only checks the username, does not verify the pw
  def isHubAdmin(username: String): Boolean = username.startsWith("root/") // only checks that user is in root org, doesn't verify anything else
}
