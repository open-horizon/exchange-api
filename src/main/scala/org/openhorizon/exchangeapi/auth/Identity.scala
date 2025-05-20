package org.openhorizon.exchangeapi.auth

import org.apache.pekko.event.LoggingAdapter
import org.openhorizon.exchangeapi.auth.Access.Access
import org.openhorizon.exchangeapi.utility.ExchMsg

import java.sql.Timestamp
import java.util.UUID
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}




case class Identity2(identifier: Option[UUID] = None,  // Only used by Users currently
                     organization: String,
                     owner: Option[UUID] = None,       // Used by Agbots and Nodes.
                     role: String,
                     username: String) {
  /**
    * If resource identity is an Agreement Bot, then true. Otherwise, false.
    * Multi-tenant Agreement Bots also return true.
    *
    * @return Boolean
    */
  def isAgbot: Boolean = (role == AuthRoles.Agbot)
  
   /**
    * If resource identity is a Hub Administrator, then true. Otherwise, false.
    * Returns false for the Root User.
    * Hub Admins may only exist in the `root` Organization. This may change in the future.
    *
    * @return Boolean
    */
  def isHubAdmin: Boolean = (role == AuthRoles.HubAdmin && organization == "root")
  
  /**
    * If resource identity is a Multi-Tenant Agreement Bot, then true. Otherwise, false.
    * Multi-tenant Agreement Bots are general purpose Agreement Bots that exist exclusively in the `IBM` Organization.
    * They can work any Organization in Open Horizon, and by default are used for the published example services.
    *
    * @return Boolean
    */
  def isMultiTenantAgbot: Boolean = (role == AuthRoles.Agbot && organization == "IBM")
  
  /**
    * If resource identity is a Node, then true. Otherwise, false.
    *
    * @return Boolean
    */
  def isNode: Boolean = (role == AuthRoles.Node)
  
  /**
    * If resource identity is an Organization Administrator, then true. Otherwise, false.
    * Returns false for the Root User.
    *
    * @return Boolean
    */
  def isOrgAdmin: Boolean = (role == AuthRoles.AdminUser)
  
  /**
    * If resource identity is a User, then true. Otherwise, false.
    *
    * @return Boolean
    */
  def isStandardUser: Boolean = (role == AuthRoles.User)
  
  /**
   * If resource identity is the Root User (`root/root`), then true. Otherwise, false.
   *
   * @return Boolean
   */
  def isSuperUser: Boolean = (role == AuthRoles.SuperUser && resource == Role.superUser)
  
  /**
    * If resource identity is any User archetype, then true. Otherwise, false.
    * Hub Administrator.
    * Organization Administrator.
    * Root User.
    * User.
    *
    * @return Boolean
    */
  def isUser: Boolean =
    (isHubAdmin ||
     isOrgAdmin ||
     isSuperUser ||
     isStandardUser)
  
  /**
    * Returns a concatenated representation of a Resource by Organization.
    * (`organization/resource-identifier`)
    * (`organization/username`)
    * @return String
    */
  def resource: String = s"${organization}/${username}"
}

/** This class and its subclasses represent the identity that is used as credentials to run rest api methods */
abstract class Identity {
  def creds: Creds
  def role: String = ""   // set in the subclasses to a role defined in Role
  def toIUser: IUser = IUser(creds, Identity2(identifier = None, organization = getOrg, owner = None, role = AuthRoles.User, username = getIdentity))
  def toINode: INode = INode(creds, Identity2(identifier = None, organization = getOrg, owner = None, role = AuthRoles.Node, username = getIdentity))
  def toIAgbot: IAgbot = IAgbot(creds, Identity2(identifier = None, organization = getOrg, owner = None, role = AuthRoles.Agbot, username = getIdentity))
  def isSuperUser = false       // IUser overrides this
  def isAdmin = false
  def isOrgAdmin = false       // IUser overrides this
  def isHubAdmin = false       // IUser overrides this
  def isAnonymous = false // = creds.isAnonymous
  def identityString: String = creds.id     // for error msgs
  def accessDeniedMsg(access: Access, target: Target): String = ExchMsg.translate("access.denied.no.auth", identityString, access, target.toAccessMsg)
  //var hasFrontEndAuthority = false   // true if this identity was already vetted by the front end
  def isMultiTenantAgbot: Boolean = false

/** Returns true if the token is correct for this user and not expired */
def isTokenValid(token: String, username: String): Boolean = {
  // Get their current hashed pw to use as the secret
  AuthCache.getUser(username) match {
    case Some(userHashedTok) => Token.isValid(token, userHashedTok)
    case None => throw new InvalidCredentialsException(ExchMsg.translate("invalid.credentials"))
  }
}

  // Called by auth/Module.login() to authenticate a local user/node/agbot
  // Note: this is defined here, instead of AuthenticationSupport, because this class is already set up to use AuthCache
  def authenticate(hint: String = ""): Identity = {
    //if (hasFrontEndAuthority) return this       // it is already a specific subclass
    //if (creds.isAnonymous) return toIAnonymous
    if (hint == "token") {
      if (isTokenValid(creds.token, creds.id)) return toIUser
      //else throw new InvalidCredentialsException("invalid token")  <- hint==token means it *could* be a token, not that it *must* be
    }
    
    AuthCache.ids.getValidType(creds) match {
      case Success(cacheIdType) =>
        cacheIdType match {
          case CacheIdType.User => toIUser
          case CacheIdType.Node => toINode
          case CacheIdType.Agbot => toIAgbot
          case CacheIdType.None => throw new InvalidCredentialsException() // will be caught by AuthenticationSupport.authenticate()
        }
      case Failure(t) => throw t  // this is usually 1 of our exceptions - will be caught by AuthenticationSupport.authenticate()
    }
  }

  def authorizeTo(target: Target, access: Access)(implicit logger: LoggingAdapter): Try[Identity]

  def getOrg: String = {
    val reg: Regex = """^(\S+?)/.*""".r
    creds.id match {
      case reg(org) => org
      case _ => ""
    }
  }

  def getIdentity: String = {
    val reg: Regex = """^\S+?/(\S+)$""".r
    creds.id match {
      case reg(id) => id
      case _ => ""
    }
  }

  def isMyOrg(target: Target): Boolean = {
    target.getOrg == getOrg
  }
}
