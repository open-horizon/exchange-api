package com.horizon.exchangeapi

import java.util.Base64

//import com.horizon.exchangeapi.auth.{AuthErrors, ExchCallbackHandler, PermissionCheck}
import com.horizon.exchangeapi.auth.PermissionCheck
//import com.horizon.exchangeapi.tables._
import javax.security.auth.Subject
//import javax.security.auth.login.LoginContext
import javax.servlet.http.HttpServletRequest
//import org.mindrot.jbcrypt.BCrypt
import org.scalatra.servlet.ServletApiImplicits
import org.scalatra.{Control, Params /* , ScalatraBase */ }
import org.slf4j.Logger
//import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
//import slick.jdbc.PostgresProfile.api._

import scala.collection.JavaConverters._
//import scala.collection.mutable.{HashMap => MutableHashMap /* , Set => MutableSet */ }
//import scala.concurrent.Await
//import scala.concurrent.ExecutionContext.Implicits.global
//import scala.concurrent.duration._
//import scala.util._
//import scala.util.control.NonFatal

/** The list of access rights. */
// Note: this list of access rights is duplicated in resources/auth.policy. Not sure how to avoid that.
object Access extends Enumeration {
  type Access = Value
  val READ = Value("READ")       // these 1st 3 are generic and will be changed to specific ones below based on the identity and target
  val WRITE = Value("WRITE")       // implies READ and includes delete
  val CREATE = Value("CREATE")
  val READ_MYSELF = Value("READ_MYSELF")      // is used for users, nodes, agbots
  val WRITE_MYSELF = Value("WRITE_MYSELF")
  val CREATE_NODE = Value("CREATE_NODE")       // we use WRITE_MY_NODES instead of this
  val READ_MY_NODES = Value("READ_MY_NODES")     // when an node tries to do this it means other node owned by the same user
  val WRITE_MY_NODES = Value("WRITE_MY_NODES")
  val READ_ALL_NODES = Value("READ_ALL_NODES")
  val WRITE_ALL_NODES = Value("WRITE_ALL_NODES")
  val SEND_MSG_TO_NODE = Value("SEND_MSG_TO_NODE")
  val CREATE_AGBOT = Value("CREATE_AGBOT")       // we use WRITE_MY_AGBOTS instead of this
  val READ_MY_AGBOTS = Value("READ_MY_AGBOTS")     // when an agbot tries to do this it means other agbots owned by the same user
  val WRITE_MY_AGBOTS = Value("WRITE_MY_AGBOTS")
  val READ_ALL_AGBOTS = Value("READ_ALL_AGBOTS")
  val WRITE_ALL_AGBOTS = Value("WRITE_ALL_AGBOTS")
  val DATA_HEARTBEAT_MY_AGBOTS = Value("DATA_HEARTBEAT_MY_AGBOTS")
  val SEND_MSG_TO_AGBOT = Value("SEND_MSG_TO_AGBOT")
  val CREATE_USER = Value("CREATE_USER")
  val CREATE_SUPERUSER = Value("CREATE_SUPERUSER")       // currently no one is allowed to do this, because root is only initialized from the config.json file
  val READ_ALL_USERS = Value("READ_ALL_USERS")
  val WRITE_ALL_USERS = Value("WRITE_ALL_USERS")
  val RESET_USER_PW = Value("RESET_USER_PW")
  val READ_MY_RESOURCES = Value("READ_MY_RESOURCES")
  val WRITE_MY_RESOURCES = Value("WRITE_MY_RESOURCES")
  val READ_ALL_RESOURCES = Value("READ_ALL_RESOURCES")
  val WRITE_ALL_RESOURCES = Value("WRITE_ALL_RESOURCES")
  val CREATE_RESOURCES = Value("CREATE_RESOURCES")
  val READ_MY_SERVICES = Value("READ_MY_SERVICES")
  val WRITE_MY_SERVICES = Value("WRITE_MY_SERVICES")
  val READ_ALL_SERVICES = Value("READ_ALL_SERVICES")
  val WRITE_ALL_SERVICES = Value("WRITE_ALL_SERVICES")
  val CREATE_SERVICES = Value("CREATE_SERVICES")
  val READ_MY_PATTERNS = Value("READ_MY_PATTERNS")
  val WRITE_MY_PATTERNS = Value("WRITE_MY_PATTERNS")
  val READ_ALL_PATTERNS = Value("READ_ALL_PATTERNS")
  val WRITE_ALL_PATTERNS = Value("WRITE_ALL_PATTERNS")
  val CREATE_PATTERNS = Value("CREATE_PATTERNS")
  val READ_MY_ORG = Value("READ_MY_ORG")
  val WRITE_MY_ORG = Value("WRITE_MY_ORG")
  val STATUS = Value("STATUS")
  val CREATE_ORGS = Value("CREATE_ORGS")
  val READ_OTHER_ORGS = Value("READ_OTHER_ORGS")
  val READ_IBM_ORGS = Value("READ_IBM_ORGS")
  val WRITE_OTHER_ORGS = Value("WRITE_OTHER_ORGS")
  val CREATE_IN_OTHER_ORGS = Value("CREATE_IN_OTHER_ORGS")
  val ADMIN = Value("ADMIN")

  val ALL_IN_ORG = Value("ALL_IN_ORG")
  val ALL = Value("ALL")
  val NONE = Value("NONE")        // should not be put in any role below
}
import com.horizon.exchangeapi.Access._

// I think this is an enum for values that get stuffed into Module.ExchangeRole
object AuthRoles {
  val SuperUser = "SuperUser"
  val AdminUser = "AdminUser"
  val User = "User"
  val Node = "Node"
  val Agbot = "Agbot"
  val Anonymous = "Anonymous"
}

// Authorization and its subclasses are used by the authorizeTo() methods in Identity subclasses
sealed trait Authorization {
  def as(subject: Subject): Unit
  def specificAccessRequired: Access
}

case object FrontendAuth extends Authorization {
  override def as(subject: Subject): Unit = {}

  override def specificAccessRequired = Access.NONE   // i think this should never be called
}

case class RequiresAccess(specificAccess: Access) extends Authorization {
  override def as(subject: Subject): Unit = {
    Subject.doAsPrivileged(subject, PermissionCheck(specificAccess.toString), null)
  }

  override def specificAccessRequired = specificAccess
}

/** Who is allowed to do what. */
object Role {
  /* this is now in resources/auth.policy
  val ANONYMOUS = Set(Access.RESET_USER_PW)
  val USER = Set(Access.READ_MYSELF, Access.WRITE_MYSELF, Access.RESET_USER_PW, Access.CREATE_NODE, Access.READ_MY_NODES, Access.WRITE_MY_NODES, Access.READ_ALL_NODES, Access.CREATE_AGBOT, Access.READ_MY_AGBOTS, Access.WRITE_MY_AGBOTS, Access.DATA_HEARTBEAT_MY_AGBOTS, Access.READ_ALL_AGBOTS, Access.STATUS)
  val SUPERUSER = Set(Access.ALL)
  val NODE = Set(Access.READ_MYSELF, Access.WRITE_MYSELF, Access.READ_MY_NODES, Access.SEND_MSG_TO_AGBOT)
  val AGBOT = Set(Access.READ_MYSELF, Access.WRITE_MYSELF, Access.DATA_HEARTBEAT_MY_AGBOTS, Access.READ_MY_AGBOTS, Access.READ_ALL_NODES, Access.SEND_MSG_TO_NODE)
  def hasAuthorization(role: Set[Access], access: Access): Boolean = { role.contains(Access.ALL) || role.contains(access) }

  var ANONYMOUS = Set[String]()
  var USER = Set[String]()
  var ADMINUSER = Set[String]()
  var SUPERUSER = Set[String]()
  var NODE = Set[String]()
  var AGBOT = Set[String]()

  // Sets the set of access values to the specified role. Not used, now done in resources/auth.policy
  def setRole(role: String, accessValues: Set[String]): Try[String] = {
    role match {
      case "ANONYMOUS" => ANONYMOUS = accessValues
      case "USER" => USER = accessValues
      case "ADMINUSER" => ADMINUSER = accessValues
      case "SUPERUSER" => SUPERUSER = accessValues
      case "NODE" => NODE = accessValues
      case "AGBOT" => AGBOT = accessValues
      case _ => return Failure(new Exception("invalid role"))
    }
    return Success("role set successfuly")
  }

  val allAccessValues = getAllAccessValues

  // Returns a set of all of the Access enum toString values
  def getAllAccessValues: Set[String] = {
    val accessSet = MutableSet[String]()
    for (a <- Access.values) { accessSet += a.toString}
    accessSet.toSet
  }

  // Returns true if the specified access string is valid. Used to check input from config.json.
  def isValidAcessValues(accessValues: Set[String]): Boolean = {
    for (a <- accessValues) if (!allAccessValues.contains(a)) return false
    return true
  }

  // Returns true if the role has the specified access
  def hasAuthorization(role: Set[String], access: Access): Boolean = {
    if (role.contains(Access.ALL.toString)) return true
    if (role.contains(Access.ALL_IN_ORG.toString) && !(access == CREATE_ORGS || access == READ_OTHER_ORGS || access == WRITE_OTHER_ORGS || access == CREATE_IN_OTHER_ORGS || access == ADMIN)) return true
    return role.contains(access.toString)
  }

  def publicOrg = "public"
  */

  def superUser = "root/root"
  def isSuperUser(username: String): Boolean = return username == superUser    // only checks the username, does not verify the pw
}

case class Creds(id: String, token: String) {     // id and token are generic names and their values can actually be username and password
  def isAnonymous: Boolean = (id == "" && token == "")
  //todo: add an optional hint to this so when they specify creds as username/password we know to try to authenticate as a user 1st
}

case class OrgAndId(org: String, id: String) {
  override def toString = if (org == "" || id.startsWith(org + "/") || id.startsWith(Role.superUser)) id else org + "/" + id
}

// This class is separate from the one above, because when the id is for a cred, we want automatically add the org only when a different org is not there
case class OrgAndIdCred(org: String, id: String) {
  override def toString = if (org == "" || id.contains("/") || id.startsWith(Role.superUser)) id else org + "/" + id    // we only check for slash, because they could already have on a different org
}

case class CompositeId(compositeId: String) {
  def getOrg: String = {
    val reg = """^(\S+?)/.*""".r
    compositeId match {
      case reg(org) => return org
      case _ => return ""
    }
  }

  def getId: String = {
    val reg = """^\S+?/(\S+)$""".r
    compositeId match {
      case reg(id) => return id
      case _ => return ""
    }
  }

  def split: (String, String) = {
    val reg = """^(\S*?)/(\S*)$""".r
    compositeId match {
      case reg(org,id) => return (org,id)
      case reg(org,_) => return (org,"")
      case reg(_,id) => return ("",id)
      case _ => return ("", "")
    }
  }
}

case class RequestInfo(
  request: HttpServletRequest,
  params: Params,
  dbMigration: Boolean,
  anonymousOk: Boolean,
  hint: String,
)

/*
AuthorizationSupport is used by AuthenticationSupport, auth/Module, and auth/IbmCloudModule.
It contains several authentication utilities:
  - getCredentials (pulls the creds from the request)
  - AuthenticatedIdentity
  - all the Identity subclasses (used for local authentication)
  - all the Target subclasses (used for authorization)
 */
trait AuthorizationSupport extends Control with ServletApiImplicits {
  implicit def logger: Logger

  /** Returns true if the token is correct for this user and not expired */
  def isTokenValid(token: String, username: String): Boolean = {
    // Get their current pw to use as the secret
    // Use the users hashed pw because that is consistently there, whereas the clear pw is not
    AuthCache.users.get(username) match {
      // case Some(userTok) => if (userTok.unhashed != "") Token.isValid(token, userTok.unhashed) else Token.isValid(token, userTok.hashed)
      case Some(userTok) => Token.isValid(token, userTok.hashed)
      case None => halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
    }
  }

  // Only used from the jaas local authentication module.
  def frontEndCreds(info: RequestInfo): Identity = {
    val request = info.request
    val frontEndHeader = ExchConfig.config.getString("api.root.frontEndHeader")
    if (frontEndHeader == "" || request.getHeader(frontEndHeader) == null) return null
    logger.trace("request.headers: "+request.headers.toString())
    //todo: For now the only front end we support is data power doing the authentication and authorization. Create a plugin architecture.
    // Data power calls us similar to: curl -u '{username}:{password}' 'https://{serviceURL}' -H 'type:{subjectType}' -H 'id:{username}' -H 'orgid:{org}' -H 'issuer:IBM_ID' -H 'Content-Type: application/json'
    // type: person (user logged into the dashboard), app (API Key), or dev (device/gateway)
    val idType = request.getHeader("type")
    val orgid = request.getHeader("orgid")
    val id = request.getHeader("id")
    if (idType == null || id == null || orgid == null) halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "front end header "+frontEndHeader+" set, but not the rest of the required headers"))
    val creds = Creds(OrgAndIdCred(orgid,id).toString, "")    // we don't have a pw/token, so leave it blank
    val identity: Identity = idType match {
      case "person" => IUser(creds)
      case "app" => IApiKey(creds)
      case "dev" => INode(creds)
      case _ => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected identity type "+idType+" from front end"))
    }
    identity.hasFrontEndAuthority = true
    return identity
  }

  /** Looks in the http header and url params for credentials and returns them. Supported:
    * Basic auth in header in clear text: Authorization:Basic <user-or-id>:<pw-or-token>
    * Basic auth in header base64 encoded: Authorization:Basic <base64-encoded-of-above>
    * URL params: username=<user>&password=<pw>
    * URL params: id=<id>&token=<token>
    * param anonymousOk True means this method will not halt with error msg if no credentials are found
    */
  def credentials(info: RequestInfo): Creds = {
    val RequestInfo(request, params, _, anonymousOk, _) = info
    getCredentials(request, params, anonymousOk)
  }

  // Used by both credentials() and AuthenticationSupport:credsForAnonymous()
  def getCredentials(request: HttpServletRequest, params: Params, anonymousOk: Boolean = false): Creds = {
    val auth = Option(request.getHeader("Authorization"))
    auth match {
      case Some(authStr) => val R1 = "^Basic ?(.*)$".r
        authStr match {
          case R1(basicAuthStr) => var basicAuthStr2 = ""
            if (basicAuthStr.contains(":")) basicAuthStr2 = basicAuthStr
            else {
              try { basicAuthStr2 = new String(Base64.getDecoder.decode(basicAuthStr), "utf-8") }
              catch { case _: IllegalArgumentException => halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "Basic auth header is missing ':' or is bad encoded format")) }
            }
            val R2 = """^(.+):(.+)\s?$""".r      // decode() seems to add a newline at the end
            basicAuthStr2 match {
              case R2(id,tok) => /*logger.trace("id="+id+",tok="+tok+".");*/ Creds(id,tok)
              case _ => halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials format, either it is missing ':' or is bad encoded format: "+basicAuthStr))
            }
          case _ => halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "if the Authorization field in the header is specified, only Basic auth is currently supported"))
        }
      // Not in the header, look in the url query string. Parameters() gives you the params after "?". Params() gives you the routes variables (if they have same name)
      case None => (params.get("orgid"), request.parameters.get("id").orElse(params.get("id")), request.parameters.get("token")) match {
        case (Some(org), Some(id), Some(tok)) => Creds(OrgAndIdCred(org,id).toString,tok)
        case (None, Some(id), Some(tok)) => Creds(OrgAndIdCred("",id).toString,tok)   // this is when they are querying /orgs so there is not org
        // Did not find id/token, so look for username/password
        case _ => (params.get("orgid"), request.parameters.get("username").orElse(params.get("username")), request.parameters.get("password").orElse(request.parameters.get("token"))) match {
          case (Some(org), Some(user), Some(pw)) => Creds(OrgAndIdCred(org,user).toString,pw)
          case (None, Some(user), Some(pw)) => Creds(OrgAndIdCred("",user).toString,pw)   // this is when they are querying /orgs so there is not org
          case _ => if (anonymousOk) Creds("","")
          else halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "no credentials given"))
        }
      }
    }
  }

  // Embodies both the exchange-specific Identity, and the JAAS/javax.security.auth Subject
  case class AuthenticatedIdentity(identity: Identity, subject: Subject) {
    def authorizeTo(target: Target, access: Access): Identity = {
      var requiredAccess: Authorization = RequiresAccess(Access.NONE)
      try {
        identity match {
          case IUser(_) => if (target.getId == "iamapikey" || target.getId == "iamtoken") {
              val authenticatedIdentity = subject.getPrivateCredentials(classOf[IUser]).asScala.head.creds
              logger.debug("authenticatedIdentity=" + authenticatedIdentity.id)
              requiredAccess = identity.authorizeTo(TUser(authenticatedIdentity.id), access)
              requiredAccess.as(subject)
              IUser(authenticatedIdentity)
            } else {
              requiredAccess = identity.authorizeTo(target, access)
              requiredAccess.as(subject)
              identity
            }
          case _ =>
            requiredAccess = identity.authorizeTo(target, access)
            requiredAccess.as(subject)
            identity
        }
      } catch {
        case _: Exception => halt(
          HttpCode.ACCESS_DENIED,
          ApiResponse(ApiResponseType.ACCESS_DENIED, identity.accessDeniedMsg(requiredAccess.specificAccessRequired))
        )
      }
    }
  }

  /** This class and its subclasses represent the identity that is used as credentials to run rest api methods */
  abstract class Identity {
    def creds: Creds
    def role: String = ""   // set in the subclasses to 1 of the roles in AuthRoles
    def toIUser = IUser(creds)
    def toINode = INode(creds)
    def toIAgbot = IAgbot(creds)
    def toIAnonymous = IAnonymous(Creds("",""))
    def isSuperUser = false       // IUser overrides this
    def isAdmin = false       // IUser overrides this
    def isAnonymous = creds.isAnonymous
    def identityString = creds.id     // for error msgs
    def accessDeniedMsg(access: Access) = "Access denied: '"+identityString+"' does not have authorization: "+access
    var hasFrontEndAuthority = false   // true if this identity was already vetted by the front end
    def isMultiTenantAgbot: Boolean = return false

    def authenticate(hint: String = ""): Identity = {
      if (hasFrontEndAuthority) return this       // it is already a specific subclass
      if (creds.isAnonymous) return toIAnonymous
      if (hint == "token") {
        if (isTokenValid(creds.token, creds.id)) return toIUser
        else halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
      }
      //for ((k, v) <- AuthCache.users.things) { logger.debug("users cache entry: "+k+" "+v) }
      if (AuthCache.users.isValid(creds)) return toIUser
      if (AuthCache.nodes.isValid(creds)) return toINode
      if (AuthCache.agbots.isValid(creds)) return toIAgbot
      halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
    }

    def authorizeTo(target: Target, access: Access): Authorization

    def getOrg: String = {
      val reg = """^(\S+?)/.*""".r
      creds.id match {
        case reg(org) => return org
        case _ => return ""
      }
    }

    def getIdentity: String = {
      val reg = """^\S+?/(\S+)$""".r
      creds.id match {
        case reg(id) => return id
        case _ => return ""
      }
    }

    def isMyOrg(target: Target): Boolean = {
      return target.getOrg == getOrg
    }
  }

  /** A generic identity before we have run authenticate to figure out what type of credentials this is */
  case class IIdentity(creds: Creds) extends Identity {
    def authorizeTo(target: Target, access: Access): Authorization = {
      // should never be called because authenticate() will return a real resource
      throw new Exception("Not Implemented")
    }
  }

  case class IFrontEnd(creds: Creds) extends Identity {
    override def authenticate(hint: String) = {
      if (ExchConfig.config.getString("api.root.frontEndHeader") == creds.id) this    // let everything thru
      else halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
    }
    def authorizeTo(target: Target, access: Access): Authorization = {
      if (ExchConfig.config.getString("api.root.frontEndHeader") == creds.id) FrontendAuth    // let everything thru
      else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "Access denied: an exchange front end is not authorized in the config.json"))
    }
  }

  case class IUser(creds: Creds) extends Identity {
    override def isSuperUser = Role.isSuperUser(creds.id)

    override lazy val role =
      if (isSuperUser) AuthRoles.SuperUser
      else if (isAdmin) AuthRoles.AdminUser
      else AuthRoles.User

    override def authorizeTo(target: Target, access: Access): Authorization = {
      if (hasFrontEndAuthority) return FrontendAuth // allow whatever it wants to do
      val requiredAccess =
        // Transform any generic access into specific access
        if (!isMyOrg(target) && !target.isPublic) {
          access match {
            case Access.READ => Access.READ_OTHER_ORGS
            case Access.WRITE => Access.WRITE_OTHER_ORGS
            case Access.CREATE => Access.CREATE_IN_OTHER_ORGS
            case _ => access
          }
        } else {      // the target is in the same org as the identity
          target match {
            case TUser(id) => access match { // a user accessing a user
              case Access.READ => logger.debug("id="+id+", creds.id=",creds.id); if (id == creds.id) Access.READ_MYSELF else Access.READ_ALL_USERS
              case Access.WRITE => if (id == creds.id) Access.WRITE_MYSELF else Access.WRITE_ALL_USERS
              case Access.CREATE => if (Role.isSuperUser(id)) Access.CREATE_SUPERUSER else Access.CREATE_USER
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
            case TResource(_) => access match { // a user accessing a resource
              case Access.READ => if (iOwnTarget(target)) Access.READ_MY_RESOURCES else Access.READ_ALL_RESOURCES
              case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_RESOURCES else Access.WRITE_ALL_RESOURCES
              case Access.CREATE => Access.CREATE_RESOURCES
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
            case TOrg(_) => access match {    // a user accessing an org resource
              case Access.READ => Access.READ_MY_ORG
              case Access.READ_IBM_ORGS => Access.READ_IBM_ORGS
              case Access.WRITE => Access.WRITE_MY_ORG
              case Access.CREATE => Access.CREATE_ORGS
              case _ => access
            }
            case TAction(_) => access // a user running an action
          }
        }
      //logger.trace("IUser.authorizeTo() requiredAccess: "+requiredAccess)
      RequiresAccess(requiredAccess)
    }

    override def isAdmin: Boolean = {
      if (isSuperUser) return true
      //println("AuthCache.users.owners: "+AuthCache.users.owners.toString())
      //println("getting owner for "+creds.id+": "+AuthCache.users.getOwner(creds.id))
      AuthCache.users.getOwner(creds.id) match {
        case Some(s) => if (s == "admin") return true else return false
        case None => return false
      }
    }

    def iOwnTarget(target: Target): Boolean = {
      if (target.mine) return true
      else if (target.all) return false
      else {
        //todo: should move these into the Target subclasses
        val owner = target match {
          case TUser(id) => return id == creds.id
          case TNode(id) => AuthCache.nodes.getOwner(id)
          case TAgbot(id) => AuthCache.agbots.getOwner(id)
          case TResource(id) => AuthCache.resources.getOwner(id)
          case TService(id) => AuthCache.services.getOwner(id)
          case TPattern(id) => AuthCache.patterns.getOwner(id)
          case _ => return false
        }
        owner match {
          case Some(owner2) => if (owner2 == creds.id) return true else return false
          case None => return true    // if we did not find it, we consider that as owning it because we will create it
        }
      }
    }
  }

  case class INode(creds: Creds) extends Identity {
    override lazy val role = AuthRoles.Node

    def authorizeTo(target: Target, access: Access): Authorization = {
      if (hasFrontEndAuthority) return FrontendAuth     // allow whatever it wants to do
      // Transform any generic access into specific access
      val requiredAccess =
        if (!isMyOrg(target) && !target.isPublic && !isMsgToMultiTenantAgbot(target,access)) {
          access match {
            case Access.READ => Access.READ_OTHER_ORGS
            case Access.WRITE => Access.WRITE_OTHER_ORGS
            case Access.CREATE => Access.CREATE_IN_OTHER_ORGS
            case _ => access
          }
        } else { // the target is in the same org as the identity
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
            case TResource(_) => access match { // a user accessing a resource
              case Access.READ => Access.READ_ALL_RESOURCES
              case Access.WRITE => Access.WRITE_ALL_RESOURCES
              case Access.CREATE => Access.CREATE_RESOURCES
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
            case TOrg(_) => access match { // a node accessing his org resource
              case Access.READ => Access.READ_MY_ORG
              case Access.WRITE => Access.WRITE_MY_ORG
              case Access.CREATE => Access.CREATE_ORGS
              case _ => access
            }
            case TAction(_) => access // a node running an action
          }
        }
      RequiresAccess(requiredAccess)
    }

    def isMsgToMultiTenantAgbot(target: Target, access: Access): Boolean = {
      return target.getOrg == "IBM" && (access == Access.SEND_MSG_TO_AGBOT || access == Access.READ)    //todo: implement instance-level ACLs instead of hardcoding this
    }
  }

  case class IAgbot(creds: Creds) extends Identity {
    override lazy val role = AuthRoles.Agbot

    def authorizeTo(target: Target, access: Access): Authorization = {
      if (hasFrontEndAuthority) return FrontendAuth     // allow whatever it wants to do
      // Transform any generic access into specific access
      val requiredAccess =
        if (!isMyOrg(target) && !target.isPublic && !isMultiTenantAgbot) {
          access match {
            case Access.READ => Access.READ_OTHER_ORGS
            case Access.WRITE => Access.WRITE_OTHER_ORGS
            case Access.CREATE => Access.CREATE_IN_OTHER_ORGS
            case _ => access
          }
        } else { // the target is in the same org as the identity
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
            case TResource(_) => access match { // a user accessing a resource
              case Access.READ => Access.READ_ALL_RESOURCES
              case Access.WRITE => Access.WRITE_ALL_RESOURCES
              case Access.CREATE => Access.CREATE_RESOURCES
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
            case TOrg(_) => access match { // a agbot accessing his org resource
              case Access.READ => Access.READ_MY_ORG
              case Access.WRITE => Access.WRITE_MY_ORG
              case Access.CREATE => Access.CREATE_ORGS
              case _ => access
            }
            case TAction(_) => access // a agbot running an action
          }
        }
      RequiresAccess(requiredAccess)
    }

    override def isMultiTenantAgbot: Boolean = return getOrg == "IBM"    //todo: implement instance-level ACLs instead of hardcoding this
  }

  case class IApiKey(creds: Creds) extends Identity {
    def authorizeTo(target: Target, access: Access): Authorization = {
      if (hasFrontEndAuthority) return FrontendAuth // allow whatever it wants to do
      halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, accessDeniedMsg(access))) // should not ever get here
    }
  }

  case class IAnonymous(creds: Creds) extends Identity {
    override lazy val role = AuthRoles.Anonymous

    //override def getOrg = Role.publicOrg  // no longer support the special public org

    def authorizeTo(target: Target, access: Access): Authorization = {
      // Transform any generic access into specific access
      val requiredAccess =
        //todo: This makes anonymous never work, but we need it to work for RESET_USER_PW
        if (access == Access.RESET_USER_PW) Access.RESET_USER_PW
        else if (!isMyOrg(target) && !target.isPublic) {  // Note: IAnonymous.isMyOrg() is always false, except for admin operations
          access match {
            case Access.READ => Access.READ_OTHER_ORGS
            case Access.WRITE => Access.WRITE_OTHER_ORGS
            case Access.CREATE => Access.CREATE_IN_OTHER_ORGS
            case _ => access
          }
        } else { // the target is in the same org as the identity
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
            case TResource(_) => access match { // a user accessing a resource
              case Access.READ => Access.READ_ALL_RESOURCES
              case Access.WRITE => Access.WRITE_ALL_RESOURCES
              case Access.CREATE => Access.CREATE_RESOURCES
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
            case TOrg(_) => access match { // a anonymous accessing his org resource
              case Access.READ => Access.READ_MY_ORG
              case Access.WRITE => Access.WRITE_MY_ORG
              case Access.CREATE => Access.CREATE_ORGS
              case _ => access
            }
            case TAction(_) => access // a anonymous running an action
          }
        }
      RequiresAccess(requiredAccess)
    }
  }

  /** This and its subclasses are used to identify the target resource the rest api method goes after */
  abstract class Target {
    def id: String    // this is the composite id, e.g. orgid/username
    def all: Boolean = return getId == "*"
    def mine: Boolean = return getId == "#"
    def isPublic: Boolean = return false    // is overridden by some subclasses

    // Returns just the orgid part of the resource
    def getOrg: String = {
      val reg = """^(\S+?)/.*""".r
      id match {
        case reg(org) => return org
        case _ => return ""
      }
    }

    // Returns just the id or username part of the resource
    def getId: String = {
      val reg = """^\S+?/(\S+)$""".r
      id match {
        case reg(id) => return id
        case _ => return ""
      }
    }
  }

  case class TOrg(id: String) extends Target {
    override def getOrg = id    // otherwise the regex in the base class will return blank because there is no /
    override def getId = ""
  }
  case class TUser(id: String) extends Target
  case class TNode(id: String) extends Target
  case class TAgbot(id: String) extends Target
  case class TResource(id: String) extends Target {      // for resources only the user that created it can update/delete it
    override def isPublic: Boolean = if (all) return true else return AuthCache.resources.getIsPublic(id).getOrElse(false)
  }
  case class TService(id: String) extends Target {      // for services only the user that created it can update/delete it
    override def isPublic: Boolean = if (all) return true else return AuthCache.services.getIsPublic(id).getOrElse(false)
  }
  case class TPattern(id: String) extends Target {      // for patterns only the user that created it can update/delete it
    override def isPublic: Boolean = if (all) return true else return AuthCache.patterns.getIsPublic(id).getOrElse(false)
  }
  case class TAction(id: String = "") extends Target    // for post rest api methods that do not target any specific resource (e.g. admin operations)
}
