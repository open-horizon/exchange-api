package com.horizon.exchangeapi

import akka.event.LoggingAdapter
import com.horizon.exchangeapi
import com.horizon.exchangeapi.auth._

import scala.util._
import scala.collection.mutable.{HashMap => MutableHashMap}
import javax.security.auth.Subject

import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.matching.Regex

/** The list of access rights. */
// Note: this list of access rights is duplicated in resources/auth.policy. Not sure how to avoid that.
object Access extends Enumeration {
  type Access = Value
  val READ: exchangeapi.Access.Value = Value("READ") // these 1st 3 are generic and will be changed to specific ones below based on the identity and target
  val WRITE: exchangeapi.Access.Value = Value("WRITE") // implies READ and includes delete
  val CREATE: exchangeapi.Access.Value = Value("CREATE")
  val READ_MYSELF: exchangeapi.Access.Value = Value("READ_MYSELF") // is used for users, nodes, agbots
  val WRITE_MYSELF: exchangeapi.Access.Value = Value("WRITE_MYSELF")
  val CREATE_NODE: exchangeapi.Access.Value = Value("CREATE_NODE") // we use WRITE_MY_NODES instead of this
  val READ_MY_NODES: exchangeapi.Access.Value = Value("READ_MY_NODES") // when an node tries to do this it means other node owned by the same user, but i do not think this works
  val WRITE_MY_NODES: exchangeapi.Access.Value = Value("WRITE_MY_NODES")
  val READ_ALL_NODES: exchangeapi.Access.Value = Value("READ_ALL_NODES")
  val WRITE_ALL_NODES: exchangeapi.Access.Value = Value("WRITE_ALL_NODES")
  val SEND_MSG_TO_NODE: exchangeapi.Access.Value = Value("SEND_MSG_TO_NODE")
  val CREATE_AGBOT: exchangeapi.Access.Value = Value("CREATE_AGBOT") // we use WRITE_MY_AGBOTS instead of this
  val READ_MY_AGBOTS: exchangeapi.Access.Value = Value("READ_MY_AGBOTS") // when an agbot tries to do this it means other agbots owned by the same user
  val WRITE_MY_AGBOTS: exchangeapi.Access.Value = Value("WRITE_MY_AGBOTS")
  val READ_ALL_AGBOTS: exchangeapi.Access.Value = Value("READ_ALL_AGBOTS")
  val WRITE_ALL_AGBOTS: exchangeapi.Access.Value = Value("WRITE_ALL_AGBOTS")
  val DATA_HEARTBEAT_MY_AGBOTS: exchangeapi.Access.Value = Value("DATA_HEARTBEAT_MY_AGBOTS")
  val SEND_MSG_TO_AGBOT: exchangeapi.Access.Value = Value("SEND_MSG_TO_AGBOT")
  val CREATE_USER: exchangeapi.Access.Value = Value("CREATE_USER")
  val CREATE_SUPERUSER: exchangeapi.Access.Value = Value("CREATE_SUPERUSER") // currently no one is allowed to do this, because root is only initialized from the config.json file
  val READ_ALL_USERS: exchangeapi.Access.Value = Value("READ_ALL_USERS")
  val WRITE_ALL_USERS: exchangeapi.Access.Value = Value("WRITE_ALL_USERS")
  //val RESET_USER_PW = Value("RESET_USER_PW")
  val READ_MY_SERVICES: exchangeapi.Access.Value = Value("READ_MY_SERVICES")
  val WRITE_MY_SERVICES: exchangeapi.Access.Value = Value("WRITE_MY_SERVICES")
  val READ_ALL_SERVICES: exchangeapi.Access.Value = Value("READ_ALL_SERVICES")
  val WRITE_ALL_SERVICES: exchangeapi.Access.Value = Value("WRITE_ALL_SERVICES")
  val CREATE_SERVICES: exchangeapi.Access.Value = Value("CREATE_SERVICES")
  val READ_MY_PATTERNS: exchangeapi.Access.Value = Value("READ_MY_PATTERNS")
  val WRITE_MY_PATTERNS: exchangeapi.Access.Value = Value("WRITE_MY_PATTERNS")
  val READ_ALL_PATTERNS: exchangeapi.Access.Value = Value("READ_ALL_PATTERNS")
  val WRITE_ALL_PATTERNS: exchangeapi.Access.Value = Value("WRITE_ALL_PATTERNS")
  val CREATE_PATTERNS: exchangeapi.Access.Value = Value("CREATE_PATTERNS")
  val READ_MY_BUSINESS: exchangeapi.Access.Value = Value("READ_MY_BUSINESS")
  val WRITE_MY_BUSINESS: exchangeapi.Access.Value = Value("WRITE_MY_BUSINESS")
  val READ_ALL_BUSINESS: exchangeapi.Access.Value = Value("READ_ALL_BUSINESS")
  val WRITE_ALL_BUSINESS: exchangeapi.Access.Value = Value("WRITE_ALL_BUSINESS")
  val CREATE_BUSINESS: exchangeapi.Access.Value = Value("CREATE_BUSINESS")
  val READ_MY_ORG: exchangeapi.Access.Value = Value("READ_MY_ORG")
  val WRITE_MY_ORG: exchangeapi.Access.Value = Value("WRITE_MY_ORG")
  val SET_IBM_ORG_TYPE: exchangeapi.Access.Value = Value("SET_IBM_ORG_TYPE")
  val STATUS: exchangeapi.Access.Value = Value("STATUS")
  val ORGSTATUS: exchangeapi.Access.Value = Value("ORGSTATUS")
  val UTILITIES: exchangeapi.Access.Value = Value("UTILITIES")
  val MAXCHANGEID: exchangeapi.Access.Value = Value("MAXCHANGEID")
  val CREATE_ORGS: exchangeapi.Access.Value = Value("CREATE_ORGS")
  val READ_OTHER_ORGS: exchangeapi.Access.Value = Value("READ_OTHER_ORGS")
  val READ_IBM_ORGS: exchangeapi.Access.Value = Value("READ_IBM_ORGS")
  val WRITE_OTHER_ORGS: exchangeapi.Access.Value = Value("WRITE_OTHER_ORGS")
  val CREATE_IN_OTHER_ORGS: exchangeapi.Access.Value = Value("CREATE_IN_OTHER_ORGS")
  val ADMIN: exchangeapi.Access.Value = Value("ADMIN")
  // Hub Admin Permissions
  val READ_MY_USERS: exchangeapi.Access.Value = Value("READ_MY_USERS")
  val WRITE_MY_USERS: exchangeapi.Access.Value = Value("WRITE_MY_USERS")
  val WRITE_ALL_ORGS: exchangeapi.Access.Value = Value("WRITE_ALL_ORGS")

  val ALL_IN_ORG: exchangeapi.Access.Value = Value("ALL_IN_ORG")
  val ALL: exchangeapi.Access.Value = Value("ALL")
  val NONE: exchangeapi.Access.Value = Value("NONE") // should not be put in any role below

  val NOT_FOUND: exchangeapi.Access.Value = Value("NOT_FOUND") // special case where the target we are trying to determine access to does not exist
}
import com.horizon.exchangeapi.Access._

object AccessGroups {
  val CROSS_ORG_ACCESS = Set(CREATE_ORGS, READ_OTHER_ORGS, WRITE_OTHER_ORGS, CREATE_IN_OTHER_ORGS, SET_IBM_ORG_TYPE, ADMIN)
}

// These are default roles (always defined). More can be added in the config file
object AuthRoles {
  val SuperUser = "SuperUser"
  val AdminUser = "AdminUser"
  val HubAdmin = "HubAdmin"
  val User = "User"
  val Node = "Node"
  val Agbot = "Agbot"
  val Anonymous = "Anonymous"
  val requiredRoles = Set(Anonymous, User, AdminUser, HubAdmin, SuperUser, Node, Agbot)
}

/* Not using the java authorization framework anymore, because it doesn't add any value for us and adds complexity
// Authorization and its subclasses are used by the authorizeTo() methods in Identity subclasses
sealed trait Authorization {
  def as(subject: Subject): Unit
  def specificAccessRequired: Access
}

final case class RequiresAccess(specificAccess: Access) extends Authorization {
  override def as(subject: Subject): Unit = {
    Subject.doAsPrivileged(subject, PermissionCheck(specificAccess.toString), null)
  }

  override def specificAccessRequired = specificAccess
}
*/

/** Who is allowed to do what. */
object Role {
  /* this is now in config.json
  val ANONYMOUS = Set(Access.RESET_USER_PW)
  val USER = Set(Access.READ_MYSELF, Access.WRITE_MYSELF, Access.RESET_USER_PW, Access.CREATE_NODE, Access.READ_MY_NODES, Access.WRITE_MY_NODES, Access.READ_ALL_NODES, Access.CREATE_AGBOT, Access.READ_MY_AGBOTS, Access.WRITE_MY_AGBOTS, Access.DATA_HEARTBEAT_MY_AGBOTS, Access.READ_ALL_AGBOTS, Access.STATUS)
  val SUPERUSER = Set(Access.ALL)
  val NODE = Set(Access.READ_MYSELF, Access.WRITE_MYSELF, Access.READ_MY_NODES, Access.SEND_MSG_TO_AGBOT)
  val AGBOT = Set(Access.READ_MYSELF, Access.WRITE_MYSELF, Access.DATA_HEARTBEAT_MY_AGBOTS, Access.READ_MY_AGBOTS, Access.READ_ALL_NODES, Access.SEND_MSG_TO_NODE)
  */

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
    roles.get(role) match {
      case Some(accessList) =>
        if (accessList.contains(Access.ALL.toString) ) return true
        if (accessList.contains(Access.ALL_IN_ORG.toString) && !AccessGroups.CROSS_ORG_ACCESS.contains(access) ) return true
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

final case class Creds(id: String, token: String) { // id and token are generic names and their values can actually be username and password
  //def isAnonymous: Boolean = (id == "" && token == "")
  //someday: maybe add an optional hint to this so when they specify creds as username/password we know to try to authenticate as a user 1st
}

final case class OrgAndId(org: String, id: String) {
  override def toString: String = if (org == "" || id.startsWith(org + "/") || id.startsWith(Role.superUser)) id.trim else org.trim + "/" + id.trim
}

// This class is separate from the one above, because when the id is for a cred, we want automatically add the org only when a different org is not there
final case class OrgAndIdCred(org: String, id: String) {
  override def toString: String = if (org == "" || id.contains("/") || id.startsWith(Role.superUser)) id.trim else org.trim + "/" + id.trim    // we only check for slash, because they could already have on a different org
}

final case class CompositeId(compositeId: String) {
  def getOrg: String = {
    val reg: Regex = """^(\S+?)/.*""".r
    compositeId match {
      case reg(org) => org
      case _ => ""
    }
  }

  def getId: String = {
    val reg: Regex = """^\S+?/(\S+)$""".r
    compositeId match {
      case reg(id) => id
      case _ => ""
    }
  }

  def split: (String, String) = {
    val reg: Regex = """^(\S*?)/(\S*)$""".r
    compositeId match {
      case reg(org, id) => (org, id)
      // These 2 lines never get run, and aren't needed. If we really want to handle a special, put something like this as the 1st case above: case reg(org, "") => return (org, "")
      //case reg(org, _) => return (org, "")
      //case reg(_, id) => return ("", id)
      case _ => ("", "")
    }
  }
}

// The context info about the request passed into the login() methods
final case class RequestInfo(creds: Creds, isDbMigration: Boolean, hint: String)

/*
AuthorizationSupport is used by AuthenticationSupport, auth/Module, and auth/IbmCloudModule.
It contains several authentication utilities:
  - AuthenticatedIdentity
  - all the Identity subclasses (used for local authentication)
  - all the Target subclasses (used for authorization)
 */
trait AuthorizationSupport {
  implicit def logger: LoggingAdapter

  /** Returns true if the token is correct for this user and not expired */
  def isTokenValid(token: String, username: String): Boolean = {
    // Get their current hashed pw to use as the secret
    AuthCache.getUser(username) match {
      case Some(userHashedTok) => Token.isValid(token, userHashedTok)
      case None => throw new InvalidCredentialsException(ExchMsg.translate("invalid.credentials"))
    }
  }

  // Embodies both the exchange-specific Identity, and the JAAS/javax.security.auth Subject
  // Note: this is defined here, instead of AuthenticationSupport, because its authorizeTo() method uses this class extensively
  case class AuthenticatedIdentity(identity: Identity, subject: Subject) {

    // Determines if this authenticated identity has the specified access to the specified target
    def authorizeTo(target: Target, access: Access): Try[Identity] = {
      try {
        identity match {
          case IUser(_) =>
            if (target.getId == "iamapikey" || target.getId == "iamtoken") {
              // This is a cloud IAM user. Get the actual username before continuing.
              val authenticatedIdentity: Creds = subject.getPrivateCredentials(classOf[IUser]).asScala.head.creds
              identity.authorizeTo(TUser(authenticatedIdentity.id), access)
            } else {
              println("authorizeTo(): calling authorizeTo on "+identity)
              identity.authorizeTo(target, access)
            }
          case _ =>
            // This is an exchange node or agbot
            identity.authorizeTo(target, access)
        }
      } catch {
        case _: Exception =>
          Failure(new AccessDeniedException(identity.accessDeniedMsg(access, target)))
      }
    }
  }

  /** This class and its subclasses represent the identity that is used as credentials to run rest api methods */
  abstract class Identity {
    def creds: Creds
    def role: String = ""   // set in the subclasses to a role defined in Role
    def toIUser: IUser = IUser(creds)
    def toINode: INode = INode(creds)
    def toIAgbot: IAgbot = IAgbot(creds)
    def toIAnonymous: IAnonymous = IAnonymous(Creds("",""))
    def isSuperUser = false       // IUser overrides this
    def isAdmin = false       // IUser overrides this
    def isHubAdmin = false       // IUser overrides this
    def isAnonymous = false // = creds.isAnonymous
    def identityString: String = creds.id     // for error msgs
    def accessDeniedMsg(access: Access, target: Target): String = ExchMsg.translate("access.denied.no.auth", identityString, access, target.toAccessMsg)
    //var hasFrontEndAuthority = false   // true if this identity was already vetted by the front end
    def isMultiTenantAgbot: Boolean = false

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

    def authorizeTo(target: Target, access: Access): Try[Identity]

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

  /** A generic identity before we have run authenticate to figure out what type of credentials this is */
  case class IIdentity(creds: Creds) extends Identity {
    def authorizeTo(target: Target, access: Access): Try[Identity] = {
      // should never be called because authenticate() will return a real resource
      Failure(new AuthInternalErrorException("Not Implemented"))
    }
  }

  case class IUser(creds: Creds) extends Identity {
    override def isSuperUser: Boolean = Role.isSuperUser(creds.id)

    override lazy val role: String =
      if (isSuperUser) AuthRoles.SuperUser
      else if (isAdmin) AuthRoles.AdminUser
      else if (isHubAdmin) AuthRoles.HubAdmin
      else AuthRoles.User

    override def authorizeTo(target: Target, access: Access): Try[Identity] = {
      val requiredAccess: Access =
        // Transform any generic access into specific access
        if (isMyOrg(target) || target.isPublic) {
          target match {
            case TUser(id) => access match { // a user accessing a user
              case Access.READ => logger.debug(s"authorizeTo(): target id=$id, identity creds.id=${creds.id}")
                if (id == creds.id) Access.READ_MYSELF
                // since we are in the section in which identity and target are in the same org, if identity is a hub admin we are viewing the users in the root org. Note: the root user is also an hub admin and org admin.
                else if (isHubAdmin && (target.isAdmin || target.isHubAdmin || target.mine)) Access.READ_MY_USERS
                else Access.READ_ALL_USERS
              case Access.WRITE => if (id == creds.id) Access.WRITE_MYSELF
                else if (isHubAdmin && !target.isSuperUser && (target.isAdmin || target.isHubAdmin)) Access.WRITE_MY_USERS
                else Access.WRITE_ALL_USERS
              case Access.CREATE => if (target.isSuperUser) Access.CREATE_SUPERUSER
                else Access.CREATE_USER // this also applies to a hub admin creating another hub admin
              case _ => access
            }
            case TNode(_) => access match { // a user accessing a node
              case Access.READ => if (iOwnTarget(target)) Access.READ_MY_NODES else Access.READ_ALL_NODES
              case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_NODES else Access.WRITE_ALL_NODES
              case Access.CREATE => Access.CREATE_NODE // not used, because WRITE is used for create also
              case _ => access
            }
            case TAgbot(_) => access match { // a user accessing a agbot
              case Access.READ => if (iOwnTarget(target)) Access.READ_MY_AGBOTS else Access.READ_ALL_AGBOTS
              case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_AGBOTS else Access.WRITE_ALL_AGBOTS
              case Access.CREATE => Access.CREATE_AGBOT
              case _ => access
            }
            case TService(_) => access match { // a user accessing a service
              case Access.READ => if (iOwnTarget(target)) Access.READ_MY_SERVICES else Access.READ_ALL_SERVICES
              case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_SERVICES else Access.WRITE_ALL_SERVICES
              case Access.CREATE => Access.CREATE_SERVICES
              case _ => access
            }
            case TPattern(_) => access match { // a user accessing a pattern
              case Access.READ => if (iOwnTarget(target)) Access.READ_MY_PATTERNS else Access.READ_ALL_PATTERNS
              case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_PATTERNS else Access.WRITE_ALL_PATTERNS
              case Access.CREATE => Access.CREATE_PATTERNS
              case _ => access
            }
            case TBusiness(_) => access match { // a user accessing a business policy
              case Access.READ => if (iOwnTarget(target)) Access.READ_MY_BUSINESS else Access.READ_ALL_BUSINESS
              case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_BUSINESS else Access.WRITE_ALL_BUSINESS
              case Access.CREATE => Access.CREATE_BUSINESS
              case _ => access
            }
            case TOrg(_) => access match {    // a user accessing an org resource
              case Access.READ => Access.READ_MY_ORG
              case Access.READ_IBM_ORGS => Access.READ_IBM_ORGS
              case Access.WRITE => if (Role.isHubAdmin(creds.id)) Access.WRITE_ALL_ORGS else Access.WRITE_MY_ORG
              case Access.CREATE => Access.CREATE_ORGS
              case _ => access
            }
            case TAction(_) => access // a user running an action
          }
        } else if (isHubAdmin) { // cross-org access is "normal" for a hub admin, because the hub admin is defined in the root org
          // since we are in the cross-org section, the target will never be itself or root
          target match {
            case TUser(id) => access match { // a user accessing a user
              case Access.READ => logger.debug(s"authorizeTo(): target id=$id, identity creds.id=${creds.id}")
                if (target.isAdmin || target.isHubAdmin || target.mine) Access.READ_MY_USERS else Access.READ_ALL_USERS
              case Access.WRITE => Access.WRITE_MY_USERS // we don't know the content of the request body here, the actual request will have to prevent updating regular users
              case Access.CREATE => Access.CREATE_USER // we don't know the content of the request body here, the actual request will have to prevent updating regular users
              case _ => access
            }
          case _ => access // a user running an action
          }
        } else if (!target.isThere && access == Access.READ) {  // not my org, not public, not there, and we are trying to read it
          Access.NOT_FOUND
        } else {  // not my org and not public, but is there or we might create it
          println("IUser.authorizeTo(): determining access to other orgs...")
          access match {
            case Access.READ => Access.READ_OTHER_ORGS
            case Access.WRITE => Access.WRITE_OTHER_ORGS
            case Access.CREATE => Access.CREATE_IN_OTHER_ORGS
            case _ => access
          }
        }
      if (requiredAccess == Access.NOT_FOUND) Failure(new ResourceNotFoundException(ExchMsg.translate("resource.not.found", target.id)))
      else if (Role.hasAuthorization(role, requiredAccess)) Success(this)
      else Failure(new AccessDeniedException(accessDeniedMsg(requiredAccess, target)))
    }

    override def isAdmin: Boolean = {
      if (isSuperUser) return true
      //println("getting admin for "+creds.id+" ...")
      val resp: Boolean = AuthCache.getUserIsAdmin(creds.id).getOrElse(false)
      //println("... back from getting admin for "+creds.id+": "+resp)
      resp
    }

    override def isHubAdmin: Boolean = {
      if (isSuperUser) return true
      //println("getting admin for "+creds.id+" ...")
      val resp: Boolean = AuthCache.getUserIsHubAdmin(creds.id).getOrElse(false)
      //println("... back from getting admin for "+creds.id+": "+resp)
      resp
    }

    def iOwnTarget(target: Target): Boolean = {
      if (target.mine) true
      else if (target.all) false
      else target.isOwner(this)
    }
  }

  case class INode(creds: Creds) extends Identity {
    override lazy val role: String = AuthRoles.Node

    def authorizeTo(target: Target, access: Access): Try[Identity] = {
      // Transform any generic access into specific access
      val requiredAccess: Access =
        if (isMyOrg(target) || target.isPublic || isMsgToMultiTenantAgbot(target,access)) {
          target match {
            case TUser(id) => access match { // a node accessing a user
              case Access.READ => Access.READ_ALL_USERS
              case Access.WRITE => Access.WRITE_ALL_USERS
              case Access.CREATE => if (Role.isSuperUser(id)) Access.CREATE_SUPERUSER else Access.CREATE_USER
              case _ => access
            }
            case TNode(id) => access match { // a node accessing a node
              case Access.READ => if (id == creds.id) Access.READ_MYSELF else if (target.mine) Access.READ_MY_NODES else Access.READ_ALL_NODES
              case Access.WRITE => if (id == creds.id) Access.WRITE_MYSELF else if (target.mine) Access.WRITE_MY_NODES else Access.WRITE_ALL_NODES
              case Access.CREATE => Access.CREATE_NODE
              case _ => access
            }
            case TAgbot(_) => access match { // a node accessing a agbot
              case Access.READ => Access.READ_ALL_AGBOTS
              case Access.WRITE => Access.WRITE_ALL_AGBOTS
              case Access.CREATE => Access.CREATE_AGBOT
              case _ => access
            }
            case TService(_) => access match { // a node accessing a service
              case Access.READ => Access.READ_ALL_SERVICES
              case Access.WRITE => Access.WRITE_ALL_SERVICES
              case Access.CREATE => Access.CREATE_SERVICES
              case _ => access
            }
            case TPattern(_) => access match { // a user accessing a pattern
              case Access.READ => Access.READ_ALL_PATTERNS
              case Access.WRITE => Access.WRITE_ALL_PATTERNS
              case Access.CREATE => Access.CREATE_PATTERNS
              case _ => access
            }
            case TBusiness(_) => access match { // a user accessing a business policy
              case Access.READ => Access.READ_ALL_BUSINESS
              case Access.WRITE => Access.WRITE_ALL_BUSINESS
              case Access.CREATE => Access.CREATE_BUSINESS
              case _ => access
            }
            case TOrg(_) => access match { // a node accessing his org resource
              case Access.READ => Access.READ_MY_ORG
              case Access.WRITE => Access.WRITE_MY_ORG
              case Access.CREATE => Access.CREATE_ORGS
              case _ => access
            }
            case TAction(_) => access // a node running an action
          }
        } else if (!target.isThere && access == Access.READ) {  // not my org, not public, not there, and we are trying to read it
          Access.NOT_FOUND
        } else {  // not my org and not public, not a msg send to agbot, but is there or we might create it
          access match {
            case Access.READ => Access.READ_OTHER_ORGS
            case Access.WRITE => Access.WRITE_OTHER_ORGS
            case Access.CREATE => Access.CREATE_IN_OTHER_ORGS
            case _ => access
          }
        }
      if (requiredAccess == Access.NOT_FOUND) Failure(new ResourceNotFoundException(ExchMsg.translate("resource.not.found", target.id)))
      else if (Role.hasAuthorization(role, requiredAccess)) Success(this)
      else Failure(new AccessDeniedException(accessDeniedMsg(requiredAccess, target)))

    }

    def isMsgToMultiTenantAgbot(target: Target, access: Access): Boolean = {
      target.getOrg == "IBM" && (access == Access.SEND_MSG_TO_AGBOT || access == Access.READ)    //someday: implement instance-level ACLs instead of hardcoding this
    }
  }

  case class IAgbot(creds: Creds) extends Identity {
    override lazy val role: String = AuthRoles.Agbot

    def authorizeTo(target: Target, access: Access): Try[Identity] = {
      // Transform any generic access into specific access
      val requiredAccess: Access =
        if (isMyOrg(target) || target.isPublic || isMultiTenantAgbot) {
          target match {
            case TUser(id) => access match { // a agbot accessing a user
              case Access.READ => Access.READ_ALL_USERS
              case Access.WRITE => Access.WRITE_ALL_USERS
              case Access.CREATE => if (Role.isSuperUser(id)) Access.CREATE_SUPERUSER else Access.CREATE_USER
              case _ => access
            }
            case TNode(_) => access match { // a agbot accessing a node
              case Access.READ => Access.READ_ALL_NODES
              case Access.WRITE => Access.WRITE_ALL_NODES
              case Access.CREATE => Access.CREATE_NODE
              case _ => access
            }
            case TAgbot(id) => access match { // a agbot accessing a agbot
              case Access.READ => if (id == creds.id) Access.READ_MYSELF else if (target.mine) Access.READ_MY_AGBOTS else Access.READ_ALL_AGBOTS
              case Access.WRITE => if (id == creds.id) Access.WRITE_MYSELF else if (target.mine) Access.WRITE_MY_AGBOTS else Access.WRITE_ALL_AGBOTS
              case Access.CREATE => Access.CREATE_AGBOT
              case _ => access
            }
            case TService(_) => access match { // a agbot accessing a service
              case Access.READ => Access.READ_ALL_SERVICES
              case Access.WRITE => Access.WRITE_ALL_SERVICES
              case Access.CREATE => Access.CREATE_SERVICES
              case _ => access
            }
            case TPattern(_) => access match { // a user accessing a pattern
              case Access.READ => Access.READ_ALL_PATTERNS
              case Access.WRITE => Access.WRITE_ALL_PATTERNS
              case Access.CREATE => Access.CREATE_PATTERNS
              case _ => access
            }
            case TBusiness(_) => access match { // a user accessing a business policy
              case Access.READ => Access.READ_ALL_BUSINESS
              case Access.WRITE => Access.WRITE_ALL_BUSINESS
              case Access.CREATE => Access.CREATE_BUSINESS
              case _ => access
            }
            case TOrg(_) => access match { // a agbot accessing his org resource
              case Access.READ => Access.READ_MY_ORG
              case Access.WRITE => Access.WRITE_MY_ORG
              case Access.CREATE => Access.CREATE_ORGS
              case _ => access
            }
            case TAction(_) => access // a agbot running an action
          }
        } else if (!target.isThere && access == Access.READ) {  // not my org, not public, not there, and we are trying to read it
          Access.NOT_FOUND
        } else {  // not my org and not public, not a msg send to node, but is there or we might create it
          access match {
            case Access.READ => Access.READ_OTHER_ORGS
            case Access.WRITE => Access.WRITE_OTHER_ORGS
            case Access.CREATE => Access.CREATE_IN_OTHER_ORGS
            case _ => access
          }
        }
      if (requiredAccess == Access.NOT_FOUND) Failure(new ResourceNotFoundException(ExchMsg.translate("resource.not.found", target.id)))
      else if (Role.hasAuthorization(role, requiredAccess)) Success(this)
      else Failure(new AccessDeniedException(accessDeniedMsg(requiredAccess, target)))
    }

    override def isMultiTenantAgbot: Boolean = getOrg == "IBM"    //someday: implement instance-level ACLs instead of hardcoding this
  }

  // Note: i don't think this is used now
  case class IAnonymous(creds: Creds) extends Identity {
    override lazy val role: String = AuthRoles.Anonymous

    def authorizeTo(target: Target, access: Access): Try[Identity] = {
      // Transform any generic access into specific access
      val requiredAccess: Access =
        // Note: IAnonymous.isMyOrg() is always false (except for admin operations), so if we had another route that should work for anonymous, so something like:
        //if (access == Access.RESET_USER_PW) Access.RESET_USER_PW else
        if (isMyOrg(target) || target.isPublic) {
          target match {
            case TUser(id) => access match { // a anonymous accessing a user
              case Access.READ => Access.READ_ALL_USERS
              case Access.WRITE => Access.WRITE_ALL_USERS
              case Access.CREATE => if (Role.isSuperUser(id)) Access.CREATE_SUPERUSER else Access.CREATE_USER
              case _ => access
            }
            case TNode(_) => access match { // a anonymous accessing a node
              case Access.READ => Access.READ_ALL_NODES
              case Access.WRITE => Access.WRITE_ALL_NODES
              case Access.CREATE => Access.CREATE_NODE
              case _ => access
            }
            case TAgbot(_) => access match { // a anonymous accessing a agbot
              case Access.READ => Access.READ_ALL_AGBOTS
              case Access.WRITE => Access.WRITE_ALL_AGBOTS
              case Access.CREATE => Access.CREATE_AGBOT
              case _ => access
            }
            case TService(_) => access match { // a anonymous accessing a service
              case Access.READ => Access.READ_ALL_SERVICES
              case Access.WRITE => Access.WRITE_ALL_SERVICES
              case Access.CREATE => Access.CREATE_SERVICES
              case _ => access
            }
            case TPattern(_) => access match { // a user accessing a pattern
              case Access.READ => Access.READ_ALL_PATTERNS
              case Access.WRITE => Access.WRITE_ALL_PATTERNS
              case Access.CREATE => Access.CREATE_PATTERNS
              case _ => access
            }
            case TBusiness(_) => access match { // a user accessing a business policy
              case Access.READ => Access.READ_ALL_BUSINESS
              case Access.WRITE => Access.WRITE_ALL_BUSINESS
              case Access.CREATE => Access.CREATE_BUSINESS
              case _ => access
            }
            case TOrg(_) => access match { // a anonymous accessing his org resource
              case Access.READ => Access.READ_MY_ORG
              case Access.WRITE => Access.WRITE_MY_ORG
              case Access.CREATE => Access.CREATE_ORGS
              case _ => access
            }
            case TAction(_) => access // a anonymous running an action
          }
        } else if (!target.isThere && access == Access.READ) {  // not my org, not public, not there, and we are trying to read it
          Access.NOT_FOUND
        } else {  // not my org and not public, but is there or we might create it
          access match {
            case Access.READ => Access.READ_OTHER_ORGS
            case Access.WRITE => Access.WRITE_OTHER_ORGS
            case Access.CREATE => Access.CREATE_IN_OTHER_ORGS
            case _ => access
          }
        }
      if (requiredAccess == Access.NOT_FOUND) Failure(new ResourceNotFoundException(ExchMsg.translate("resource.not.found", target.id)))
      else if (Role.hasAuthorization(role, requiredAccess)) Success(this)
      else Failure(new AccessDeniedException(accessDeniedMsg(requiredAccess, target)))
    }
  }

  /** This and its subclasses are used to identify the target resource the rest api method goes after */
  abstract class Target {
    def id: String    // this is the composite id, e.g. orgid/username
    def all: Boolean = getId == "*"
    def mine: Boolean = getId == "#"
    def isPublic: Boolean = false    // is overridden by some subclasses
    def isThere: Boolean = false    // is overridden by some subclasses
    def isOwner(user: IUser): Boolean = false    // is overridden by some subclasses
    def isAdmin = false       // TUser overrides this
    def isHubAdmin = false       // TUser overrides this
    def isSuperUser = false       // TUser overrides this
    def label: String = ""    // overridden by subclasses. This should be the exchange resource type
    def toAccessMsg = s"$label=$id"  // the way the target should be described in access denied msgs

    // Returns just the orgid part of the resource
    def getOrg: String = {
      val reg: Regex = """^(\S+?)/.*""".r
      id match {
        case reg(org) => org
        case _ => ""
      }
    }

    // Returns just the id or username part of the resource
    def getId: String = {
      val reg: Regex = """^\S+?/(\S+)$""".r
      id match {
        case reg(id) => id
        case _ => ""
      }
    }
  }

  case class TOrg(id: String) extends Target {
    override def getOrg: String = id    // otherwise the regex in the base class will return blank because there is no /
    override def getId = ""
    override def isThere: Boolean = true   // we don't have a cache to quickly tell if this org exists, so return true and let the db access sort it out
    override def label = "org"
  }

  case class TUser(id: String) extends Target {
    override def isOwner(user: IUser): Boolean = id == user.creds.id
    override def isThere: Boolean = all || mine || AuthCache.getUserIsAdmin(id).nonEmpty
    override def isAdmin: Boolean = AuthCache.getUserIsAdmin(id).getOrElse(false)
    override def isHubAdmin: Boolean = AuthCache.getUserIsHubAdmin(id).getOrElse(false)
    override def isSuperUser: Boolean = Role.isSuperUser(id)
    override def label = "user"
  }

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

  case class TPattern(id: String) extends Target {      // for patterns only the user that created it can update/delete it
    override def isOwner(user: IUser): Boolean = {
      AuthCache.getPatternOwner(id) match {
        case Some(owner) => if (owner == user.creds.id) true else false
        case None => true    // if we did not find it, we consider that as owning it because we will create it
      }
    }
    override def isPublic: Boolean = if (all) true else AuthCache.getPatternIsPublic(id).getOrElse(false)
    override def isThere: Boolean = all || mine || AuthCache.getPatternOwner(id).nonEmpty
    override def label = "pattern"
  }

  case class TBusiness(id: String) extends Target {      // for business policies only the user that created it can update/delete it
    override def isOwner(user: IUser): Boolean = {
      AuthCache.getBusinessOwner(id) match {
        case Some(owner) => if (owner == user.creds.id) true else false
        case None => true    // if we did not find it, we consider that as owning it because we will create it
      }
    }
    // business policies can never be public, so no need to override isPublic
    override def isThere: Boolean = all || mine || AuthCache.getBusinessOwner(id).nonEmpty
    override def label = "business policy"
  }

  case class TAction(id: String = "") extends Target { // for post rest api methods that do not target any specific resource (e.g. admin operations)
    override def label = "action"
  }
}
