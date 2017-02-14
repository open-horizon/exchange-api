package com.horizon.exchangeapi

import scala.util.matching._
import org.scalatra.{ScalatraBase}
import org.mindrot.jbcrypt.BCrypt
import pdi.jwt.{Jwt, JwtAlgorithm, JwtHeader, JwtClaim, JwtOptions}
import scala.collection.mutable.{HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection
import org.slf4j.{LoggerFactory, Logger}
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.Await
import scala.concurrent.duration._
import com.horizon.exchangeapi.tables._
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.Base64
import java.nio.charset.StandardCharsets

/** The list of basic access rights.
object BaseAccess extends Enumeration {     //todo: remove this and only use Access
  type BaseAccess = Value
  val READ,
    WRITE,       // implies READ and includes delete
    CREATE,       // should we not distinguish between create and write?
    ADMIN,
    STATUS,
    RESET_PW,
    AGREEMENT_CONFIRM,
    DATA_HEARTBEAT,
    SEND_MSG,
    READ_ALL_BLOCKCHAINS,
    WRITE_ALL_BLOCKCHAINS
    = Value
}
import BaseAccess._
*/

/** The list of access rights. */
object Access extends Enumeration {
  type Access = Value
  // Note: the strings here are *not* for checking equality, they are for toString in error msgs
  val READ = Value("READ")       // these 1st 3 are generic and will be changed to specific ones below based on the identity and target
  val WRITE = Value("WRITE")       // implies READ and includes delete
  val CREATE = Value("CREATE")
  val READ_MYSELF = Value("READ_MYSELF")      // is used for users, devices, agbots
  val WRITE_MYSELF = Value("WRITE_MYSELF")
  val CREATE_DEVICE = Value("CREATE_DEVICE")       // we use WRITE_MY_DEVICES instead of this
  val READ_MY_DEVICES = Value("READ_MY_DEVICES")     // when an device tries to do this it means other device owned by the same user
  val WRITE_MY_DEVICES = Value("WRITE_MY_DEVICES")
  val READ_ALL_DEVICES = Value("READ_ALL_DEVICES")
  val WRITE_ALL_DEVICES = Value("WRITE_ALL_DEVICES")
  val SEND_MSG_TO_DEVICE = Value("SEND_MSG_TO_DEVICE")
  val CREATE_AGBOT = Value("CREATE_AGBOT")       // we use WRITE_MY_AGBOTS instead of this
  val READ_MY_AGBOTS = Value("READ_MY_AGBOTS")     // when an agbot tries to do this it means other agbots owned by the same user
  val WRITE_MY_AGBOTS = Value("WRITE_MY_AGBOTS")
  val READ_ALL_AGBOTS = Value("READ_ALL_AGBOTS")
  val WRITE_ALL_AGBOTS = Value("WRITE_ALL_AGBOTS")
  val DATA_HEARTBEAT_MY_AGBOTS = Value("DATA_HEARTBEAT_MY_AGBOTS")
  // AGBOT_AGREEMENT_DATA_HEARTBEAT,    //TODO: remove
  // AGBOT_AGREEMENT_CONFIRM,    //TODO: remove
  val SEND_MSG_TO_AGBOT = Value("SEND_MSG_TO_AGBOT")
  val CREATE_USER = Value("CREATE_USER")
  val CREATE_SUPERUSER = Value("CREATE_SUPERUSER")       // currently no one is allowed to do this, because root is only initialized from the config.json file
  val READ_ALL_USERS = Value("READ_ALL_USERS")
  val WRITE_ALL_USERS = Value("WRITE_ALL_USERS")
  val RESET_USER_PW = Value("RESET_USER_PW")
  val READ_MY_BLOCKCHAINS = Value("READ_MY_BLOCKCHAINS")
  val WRITE_MY_BLOCKCHAINS = Value("WRITE_MY_BLOCKCHAINS")
  val READ_ALL_BLOCKCHAINS = Value("READ_ALL_BLOCKCHAINS")
  val WRITE_ALL_BLOCKCHAINS = Value("WRITE_ALL_BLOCKCHAINS")
  val CREATE_BLOCKCHAINS = Value("CREATE_BLOCKCHAINS")       // we use WRITE_MY_BLOCKCHAINS instead of this
  val READ_MY_BCTYPES = Value("READ_MY_BCTYPES")
  val WRITE_MY_BCTYPES = Value("WRITE_MY_BCTYPES")
  val READ_ALL_BCTYPES = Value("READ_ALL_BCTYPES")
  val WRITE_ALL_BCTYPES = Value("WRITE_ALL_BCTYPES")
  val CREATE_BCTYPES = Value("CREATE_BCTYPES")       // we use WRITE_MY_BCTYPES instead of this
  val ADMIN = Value("ADMIN")
  val STATUS = Value("STATUS")
  val ALL = Value("ALL")
  val NONE = Value("NONE")        // should not be put in any role below
}
import Access._

/** Who is allowed to do what. */
object Role {
  val ANONYMOUS = Set(Access.CREATE_USER, Access.RESET_USER_PW)
  val USER = Set(Access.READ_MYSELF, Access.WRITE_MYSELF, Access.RESET_USER_PW, Access.CREATE_DEVICE, Access.READ_MY_DEVICES, Access.WRITE_MY_DEVICES, Access.READ_ALL_DEVICES, Access.CREATE_AGBOT, Access.READ_MY_AGBOTS, Access.WRITE_MY_AGBOTS, Access.DATA_HEARTBEAT_MY_AGBOTS, Access.READ_ALL_AGBOTS, Access.STATUS, Access.READ_MY_BLOCKCHAINS, Access.READ_ALL_BLOCKCHAINS, Access.WRITE_MY_BLOCKCHAINS, Access.CREATE_BLOCKCHAINS, Access.READ_MY_BCTYPES, Access.READ_ALL_BCTYPES, Access.WRITE_MY_BCTYPES, Access.CREATE_BCTYPES)
  val SUPERUSER = Set(Access.ALL)
  val DEVICE = Set(Access.READ_MYSELF, Access.WRITE_MYSELF, Access.READ_MY_DEVICES, Access.SEND_MSG_TO_AGBOT, Access.READ_ALL_BLOCKCHAINS, Access.READ_ALL_BCTYPES)
  val AGBOT = Set(Access.READ_MYSELF, Access.WRITE_MYSELF, Access.DATA_HEARTBEAT_MY_AGBOTS, Access.READ_MY_AGBOTS, Access.READ_ALL_DEVICES, Access.SEND_MSG_TO_DEVICE, Access.READ_ALL_BLOCKCHAINS, Access.READ_ALL_BCTYPES)
  def hasAuthorization(role: Set[Access], access: Access): Boolean = { role.contains(Access.ALL) || role.contains(access) }
  def superUser = "root"
  def isSuperUser(username: String): Boolean = return username == superUser    // only checks the username, does not verify the pw
}

case class Creds(id: String, token: String) {     // id and token are generic names and their values can actually be username and password
  def isAnonymous: Boolean = (id == "" && token == "")
  //todo: add an optional hint to this so when they specify creds as username/password we know to try to authenticate as a user 1st
}

/** In-memory cache of the user/pw, device id/token, and agbot id/token, where the pw and tokens are not hashed to speed up validation */
object AuthCache {
  val logger = LoggerFactory.getLogger(ExchConfig.LOGGER)

  /** 1 set of things (user/pw, device id/token, or agbot id/token) */
  class Cache(val whichTab: String) {     //TODO: i am sure there is a better way to handle the different tables
    // Throughout the implementation of this class, id and token are used generically, meaning in the case of users they are user and pw.
    // Our goal is for the token to be unhashed, but we have to handle the case where the user gives us an already hashed token.
    // In this case, turn it into an unhashed token the 1st time they have a successful check against it with an unhashed token.

    // The unhashed and hashed values of the token are not always both set, but if they are they are in sync.
    case class Tokens(unhashed: String, hashed: String)

    // The in-memory cache
    val things = new MutableHashMap[String,Tokens]()     // key is username or id, value is the unhashed and hashed pw's or token's
    val owners = new MutableHashMap[String,String]()     // key is device or agbot id, value is the username that owns it. (Not used for users)
    val whichTable = whichTab

    var db: Database = null       // filled in my init() below

    /** Initializes the cache with all of the things currently in the persistent db */
    def init(db: Database): Unit = {
      this.db = db      // store for later use
      whichTable match {
        case "users" => db.run(UsersTQ.rows.map(x => (x.username, x.password)).result).map({ list => this._initUsers(list, true) })
        case "devices" => db.run(DevicesTQ.rows.map(x => (x.id, x.token, x.owner)).result).map({ list => this._initIds(list) })
        case "agbots" => db.run(AgbotsTQ.rows.map(x => (x.id, x.token, x.owner)).result).map({ list => this._initIds(list) })
        case "bctypes" => db.run(BctypesTQ.rows.map(x => (x.bctype, x.definedBy)).result).map({ list => this._initBctypes(list) })
        case "blockchains" => db.run(BlockchainsTQ.rows.map(x => (x.name, x.bctype, x.definedBy)).result).map({ list => this._initBCs(list) })
      }
    }

    /** Put all of the devices or agbots in the cache */
    def _initIds(credList: Seq[(String,String,String)]): Unit = {
      for ((id,token,owner) <- credList) {
        val tokens: Tokens = if (Password.isHashed(token)) Tokens("", token) else Tokens(token, Password.hash(token))
        _put(id, tokens)     // Note: ExchConfig.createRoot(db) already puts root in the auth cache and we do not want a race condition
        if (owner != "") _putOwner(id, owner)
      }
    }

    /** Put all of the users in the cache */
    def _initUsers(credList: Seq[(String,String)], skipRoot: Boolean = false): Unit = {
      for ((username,password) <- credList) {
        val tokens: Tokens = if (Password.isHashed(password)) Tokens("", password) else Tokens(password, Password.hash(password))
        if (!(skipRoot && Role.isSuperUser(username))) _put(username, tokens)     // Note: ExchConfig.createRoot(db) already puts root in the auth cache and we do not want a race condition
      }
    }

    /** Put owners of bctypes in the cache */
    def _initBctypes(credList: Seq[(String,String)]): Unit = {
      for ((bctype,definedBy) <- credList) {
        if (definedBy != "") _putOwner(bctype, definedBy)
      }
    }

    /** Put owners of bc instances in the cache */
    def _initBCs(credList: Seq[(String,String,String)]): Unit = {
      for ((name,bctype,definedBy) <- credList) {
        val key = name+"|"+bctype
        if (definedBy != "") _putOwner(key, definedBy)
      }
    }

    /** Returns Some(Tokens) from the cache for this user/id (but verifies with the db 1st), or None if does not exist */
    def get(id: String): Option[Tokens] = {
      if (Role.isSuperUser(id)) return _get(id)     // root is always initialized from config.json and put in the cache, and should not be changed at runtime

      // Even though we try to put every new/updated id/token or user/pw in our cache, when this server runs in multi-node mode,
      // an update could have come to 1 of the other nodes. The db is our sync point, so always verify our cached hash with the db hash.
      // This at least saves us from having to check a clear token against a hashed token most of the time (which is time consuming).
      // The db is the source of truth, so get that pw 1st. It should always be the hashed pw/token.
      val a = whichTable match {
        case "users" => UsersTQ.getPassword(id).result
        case "devices" => DevicesTQ.getToken(id).result
        case "agbots" => AgbotsTQ.getToken(id).result
      }
      //TODO: this db access should go at the beginning of every rest api db access, using flatmap to move on to the db access the rest api is really for
      val dbHashedTok: String = try {
        val tokVector = Await.result(db.run(a), Duration(3000, MILLISECONDS))
        if (tokVector.size > 0) tokVector.head else ""
      } catch {
        //TODO: this seems to happen sometimes when my laptop has been asleep. Maybe it has to reconnect to the db.
        //      Until i get a better handle on this, use the cache token when this happens.
        case e: java.util.concurrent.TimeoutException => logger.error("getting hashed pw/token for '"+id+"' timed out. Using the cache for now.")
          return _get(id)
      }

      // Now get it from the cache and compare/sync the 2
      _get(id) match {
        case Some(cacheTok) => if (dbHashedTok == "" && whichTable == "users" && Role.isSuperUser(id)) { return Some(cacheTok) }  // we never want to get rid of the cache in root, or we have no way to repair things
          else if (dbHashedTok == "") {   //not in db, remove it from cache, unless it is root
            remove(id);
            return None
          } else {    // in both db and cache, verify hashed values match, or update
            if (dbHashedTok == cacheTok.hashed) return Some(cacheTok)       // all good
            else {
              val tokens = Tokens("", dbHashedTok)
              logger.debug("pw/token for '"+id+"' in cache is out of date, updating the hashed value in the cache")
              _put(id, tokens)
              return Some(tokens)
            }
          }
        case None => if (dbHashedTok == "") return None     // did not find it either place
          else {      // it was in the db, but not in the cache. Add it, then return it
            val tokens = Tokens("", dbHashedTok)
            logger.debug("pw/token for '"+id+"' is in db, but not cache, adding hashed value to cache")
            _put(id, tokens)
            return Some(tokens)
          }
      }
    }

    /** Check these creds using our cache, confirming with the db. */
    def isValid(creds: Creds): Boolean = {
      get(creds.id) match {      // Note: get() will verify the cache with the db before returning
        // We have this id in the cache, but the unhashed token in the cache could be blank, or the cache could be out of date
        case Some(tokens) => ;
          try {
            if (Password.isHashed(creds.token)) return creds.token == tokens.hashed
            else {      // specified token is unhashed
              if (tokens.unhashed != "") return creds.token == tokens.unhashed
              else {    // the specified token is unhashed, but we do not have the unhashed token in our cache yet
                if (Password.check(creds.token, tokens.hashed)) {
                  // now we have the unhashed version of the token so updated our cache with that
                  logger.debug("updating auth cache with unhashed pw/token for '"+creds.id+"'")
                  _put(creds.id, Tokens(creds.token, tokens.hashed))
                  true
                } else false
              }
            }
          } catch { case e: Exception => logger.error("Invalid salt version error from Password.check()"); false }   // can throw IllegalArgumentException: Invalid salt version 
        case None => false
      }
    }

    /** Returns Some(owner) from the cache for this id (but verifies with the db 1st), or None if does not exist */
    def getOwner(id: String): Option[String] = {
      // logger.trace("getOwners owners: "+owners.toString)
      if (whichTable == "users") return None      // we never actually call this

      // Even though we try to put every new/updated owner in our cache, when this server runs in multi-node mode,
      // an update could have come to 1 of the other nodes. The db is our sync point, so always verify our cached owner with the db owner.
      // We are doing this only so we can fall back to the cache's last known owner if the Await.result() times out.
      val a = whichTable match {
        case "users" => return None
        case "devices" => DevicesTQ.getOwner(id).result
        case "agbots" => AgbotsTQ.getOwner(id).result
        case "bctypes" => BctypesTQ.getOwner(id).result
        case "blockchains" => BlockchainsTQ.getOwner2(id).result
      }
      try {
        val ownerVector = Await.result(db.run(a), Duration(3000, MILLISECONDS))
        if (ownerVector.size > 0) /*{ logger.trace("getOwner return: "+ownerVector.head);*/ return Some(ownerVector.head) else /*{ logger.trace("getOwner return: None");*/ return None
      } catch {
        //todo: this seems to happen sometimes when the exchange svr has been idle for a while. Or maybe it is just when i'm running local and my laptop has been asleep.
        //      Until i get a better handle on this, use the cache owner when this happens.
        case e: java.util.concurrent.TimeoutException => logger.error("getting owner for '"+id+"' timed out. Using the cache for now.")
          return _getOwner(id)
      }
    }

    /** Cache this id/token (or user/pw) pair in unhashed (usually) form */
    def put(creds: Creds): Unit = {
      // Normally overwrite the current cached pw with this new one, unless the new one is blank.
      // But we need to handle the special case: the new pw is hashed, the old one is not, and they match
      // Note: Password.check() can throw 'IllegalArgumentException: Invalid salt version', but we intentially let that bubble up
      if (creds.token == "") return
      _get(creds.id) match {
        // case Some(token) =>  if (Password.isHashed(creds.token) && !Password.isHashed(token) && Password.check(token, creds.token)) return    // do not overwrite our good plain pw with the equivalent hashed one
        case Some(tokens) =>  if (Password.isHashed(creds.token)) {
            val unhashed = if (Password.check(tokens.unhashed, creds.token)) tokens.unhashed else ""    // if our clear tok is wrong, blank it out
            if (creds.token != tokens.hashed) _put(creds.id, Tokens(unhashed, creds.token))
          } else {      // they gave us a clear token
            val hashed = if (Password.check(creds.token, tokens.hashed)) tokens.hashed else Password.hash(creds.token)    // if our hashed tok is wrong, blank it out
            if (creds.token != tokens.unhashed) _put(creds.id, Tokens(creds.token, hashed))
          }
        case None => val tokens: Tokens = if (Password.isHashed(creds.token)) Tokens("", creds.token) else Tokens(creds.token, Password.hash(creds.token))
          _put(creds.id, tokens)
      }
    }

    def putOwner(id: String, owner: String): Unit = { /*logger.trace("putOwner for "+id+": "+owner); */ if (owner != "") _putOwner(id, owner) }
    def putBoth(creds: Creds, owner: String): Unit = { put(creds); putOwner(creds.id, owner) }

    /** Removes the user/id and pw/token pair from the cache. If it does not exist, no error is returned */
    def remove(id: String) = { _remove(id) }
    def removeOwner(id: String) = { _removeOwner(id) }
    def removeBoth(id: String) = { _removeBoth(id) }

    /** Removes all user/id, pw/token pairs from this cache. */
    def removeAll = {
      val rootTokens = if (whichTable == "users") _get(Role.superUser) else None     // have to preserve the root user or they can not do anything after this
      _clear
      rootTokens match {
        case Some(tokens) => _put(Role.superUser, tokens)
        case None => ;
      }
      removeAllOwners
    }
    def removeAllOwners = { _clearOwners }

    /** Low-level functions to lock on the hashmap */
    private def _get(id: String) = synchronized { things.get(id) }
    private def _put(id: String, tokens: Tokens) = synchronized { things.put(id, tokens) }
    private def _remove(id: String) = synchronized { things.remove(id) }
    private def _clear = synchronized { things.clear }

    private def _getOwner(id: String) = synchronized { owners.get(id) }
    private def _putOwner(id: String, owner: String) = synchronized { owners.put(id, owner) }
    private def _removeOwner(id: String) = synchronized { owners.remove(id) }
    private def _clearOwners = synchronized { owners.clear }

    private def _removeBoth(id: String) = synchronized { things.remove(id); owners.remove(id) }
  }     // end of Cache class

  val users = new Cache("users")
  val devices = new Cache("devices")
  val agbots = new Cache("agbots")
  val bctypes = new Cache("bctypes")
  val blockchains = new Cache("blockchains")
}

/** Authenticates the client credentials and then checks the ACLs for authorization. */
trait AuthenticationSupport extends ScalatraBase {
  // We could add a before action with befor() {}, but sometimes they need to pass in user/pw, and sometimes id/token
  // I tried using code from http://www.scalatra.org/2.4/guides/http/authentication.html, but it throws an exception.

  def db: Database      // get access to the db object in ExchangeApiApp
  implicit def logger: Logger    // get access to the logger object in ExchangeApiApp

  var migratingDb = false     // used to lock everyone out during db migration
  def isDbMigration = migratingDb
  // def setDbMigration(dbMigration: Boolean): Unit = { migratingDb = dbMigration }

  /** This class and its subclasses represent the identity that is used as credentials to run rest api methods */
  abstract class Identity {
    def creds: Creds
    def toIUser = IUser(creds)
    def toIDevice = IDevice(creds)
    def toIAgbot = IAgbot(creds)
    def toIAnonymous = IAnonymous(Creds("",""))
    def isSuperUser = false       // IUser overrides this
    def identityString = creds.id     // for error msgs
    def accessDeniedMsg(access: Access) = "Access denied: '"+identityString+"' does not have authorization: "+access

    def authenticate(hint: String = ""): Identity = {
      if (creds.isAnonymous) return toIAnonymous
      if (hint == "token") {
        if (isTokenValid(creds.token, creds.id)) return toIUser
        else halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
      }
      if (AuthCache.users.isValid(creds)) return toIUser
      if (AuthCache.devices.isValid(creds)) return toIDevice
      if (AuthCache.agbots.isValid(creds)) return toIAgbot
      halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
    }

    def authorizeTo(target: Target, access: Access): Identity
  }

  /** A generic identity before we have run authenticate to figure out what type of credentials this is */
  case class IIdentity(creds: Creds) extends Identity {
    def authorizeTo(target: Target, access: Access): Identity = this      // should never be called because authenticate() will return a real resource
  }

  case class IUser(creds: Creds) extends Identity {
    override def isSuperUser = Role.isSuperUser(creds.id)

    def authorizeTo(target: Target, access: Access): Identity = {
      val role = if (isSuperUser) Role.SUPERUSER else Role.USER
      // Transform any generic access into specific access
      val access2 = target match {
        case TUser(id) => access match {     // a user accessing a user
            case Access.READ => if (id == creds.id) Access.READ_MYSELF else Access.READ_ALL_USERS
            case Access.WRITE => if (id == creds.id) Access.WRITE_MYSELF else Access.WRITE_ALL_USERS
            case Access.CREATE => if (Role.isSuperUser(id)) Access.CREATE_SUPERUSER else Access.CREATE_USER
            case _ => access
          }
        case TDevice(id) => access match {     // a user accessing a device
            case Access.READ => if (iOwnTarget(target)) Access.READ_MY_DEVICES else Access.READ_ALL_DEVICES
            case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_DEVICES else Access.WRITE_ALL_DEVICES
            case Access.CREATE => Access.CREATE_DEVICE    // not used, because WRITE is used for create also
            case _ => access
          }
        case TAgbot(id) => access match {     // a user accessing a agbot
            case Access.READ => if (iOwnTarget(target)) Access.READ_MY_AGBOTS else Access.READ_ALL_AGBOTS
            case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_AGBOTS else Access.WRITE_ALL_AGBOTS
            case Access.CREATE => Access.CREATE_AGBOT
            case _ => access
          }
        case TBctype(id) => access match {     // a user accessing a bctype
            case Access.READ => if (iOwnTarget(target)) Access.READ_MY_BCTYPES else Access.READ_ALL_BCTYPES
            case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_BCTYPES else Access.WRITE_ALL_BCTYPES
            case Access.CREATE => Access.CREATE_BCTYPES
            case _ => access
          }
        case TBlockchain(id) => access match {     // a user accessing a blockchain
            case Access.READ => if (iOwnTarget(target)) Access.READ_MY_BLOCKCHAINS else Access.READ_ALL_BLOCKCHAINS
            case Access.WRITE => if (iOwnTarget(target)) Access.WRITE_MY_BLOCKCHAINS else Access.WRITE_ALL_BLOCKCHAINS
            case Access.CREATE => Access.CREATE_BLOCKCHAINS
            case _ => access
          }
        case TAction(id) => access      // a user running an action
      }
      if (Role.hasAuthorization(role, access2)) return this else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, accessDeniedMsg(access2)))
    }

    def iOwnTarget(target: Target): Boolean = {
      if (target.mine) return true
      else if (target.all) return false
      else {
        val owner = target match {
          case TUser(id) => return id == creds.id
          case TDevice(id) => AuthCache.devices.getOwner(id)
          case TAgbot(id) => AuthCache.agbots.getOwner(id)
          case TBctype(id) => AuthCache.bctypes.getOwner(id)
          case TBlockchain(id) => AuthCache.blockchains.getOwner(id)
          case _ => return false
        }
        owner match {
          case Some(owner) => if (owner == creds.id) return true else return false
          case None => return true    // if we did not find it, we consider that as owning it because we will create it
        }
      }
    }
  }

  case class IDevice(creds: Creds) extends Identity {
    def authorizeTo(target: Target, access: Access): Identity = {
      // Transform any generic access into specific access
      val access2 = target match {
        case TUser(id) => access match {     // a device accessing a user
            case Access.READ => Access.READ_ALL_USERS
            case Access.WRITE => Access.WRITE_ALL_USERS
            case Access.CREATE => if (Role.isSuperUser(id)) Access.CREATE_SUPERUSER else Access.CREATE_USER
            case _ => access
          }
        case TDevice(id) => access match {     // a device accessing a device
            case Access.READ => if (id == creds.id) Access.READ_MYSELF else if (target.mine) Access.READ_MY_DEVICES else Access.READ_ALL_DEVICES
            case Access.WRITE => if (id == creds.id) Access.WRITE_MYSELF else if (target.mine) Access.WRITE_MY_DEVICES else Access.WRITE_ALL_DEVICES
            case Access.CREATE => Access.CREATE_DEVICE
            case _ => access
          }
        case TAgbot(id) => access match {     // a device accessing a agbot
            case Access.READ => Access.READ_ALL_AGBOTS
            case Access.WRITE => Access.WRITE_ALL_AGBOTS
            case Access.CREATE => Access.CREATE_AGBOT
            case _ => access
          }
        case TBctype(id) => access match {     // a device accessing a bctype
            case Access.READ => Access.READ_ALL_BCTYPES
            case Access.WRITE => Access.WRITE_ALL_BCTYPES
            case Access.CREATE => Access.CREATE_BCTYPES
            case _ => access
          }
        case TBlockchain(id) => access match {     // a device accessing a blockchain
            case Access.READ => Access.READ_ALL_BLOCKCHAINS
            case Access.WRITE => Access.WRITE_ALL_BLOCKCHAINS
            case Access.CREATE => Access.CREATE_BLOCKCHAINS
            case _ => access
          }
        case TAction(id) => access      // a device running an action
      }
      if (Role.hasAuthorization(Role.DEVICE, access2)) return this else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, accessDeniedMsg(access2)))
    }    
  }

  case class IAgbot(creds: Creds) extends Identity {
    def authorizeTo(target: Target, access: Access): Identity = {
      // Transform any generic access into specific access
      val access2 = target match {
        case TUser(id) => access match {     // a agbot accessing a user
            case Access.READ => Access.READ_ALL_USERS
            case Access.WRITE => Access.WRITE_ALL_USERS
            case Access.CREATE => if (Role.isSuperUser(id)) Access.CREATE_SUPERUSER else Access.CREATE_USER
            case _ => access
          }
        case TDevice(id) => access match {     // a agbot accessing a device
            case Access.READ => Access.READ_ALL_DEVICES
            case Access.WRITE => Access.WRITE_ALL_DEVICES
            case Access.CREATE => Access.CREATE_DEVICE
            case _ => access
          }
        case TAgbot(id) => access match {     // a agbot accessing a agbot
            case Access.READ => if (id == creds.id) Access.READ_MYSELF else if (target.mine) Access.READ_MY_AGBOTS else Access.READ_ALL_AGBOTS
            case Access.WRITE => if (id == creds.id) Access.WRITE_MYSELF else if (target.mine) Access.WRITE_MY_AGBOTS else Access.WRITE_ALL_AGBOTS
            case Access.CREATE => Access.CREATE_AGBOT
            case _ => access
          }
        case TBctype(id) => access match {     // a agbot accessing a bctype
            case Access.READ => Access.READ_ALL_BCTYPES
            case Access.WRITE => Access.WRITE_ALL_BCTYPES
            case Access.CREATE => Access.CREATE_BCTYPES
            case _ => access
          }
        case TBlockchain(id) => access match {     // a agbot accessing a blockchain
            case Access.READ => Access.READ_ALL_BLOCKCHAINS
            case Access.WRITE => Access.WRITE_ALL_BLOCKCHAINS
            case Access.CREATE => Access.CREATE_BLOCKCHAINS
            case _ => access
          }
        case TAction(id) => access      // a agbot running an action
      }
      if (Role.hasAuthorization(Role.AGBOT, access2)) return this else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, accessDeniedMsg(access2)))
    }    
  }

  case class IAnonymous(creds: Creds) extends Identity {
    def authorizeTo(target: Target, access: Access): Identity = {
      // Transform any generic access into specific access
      val access2 = target match {
        case TUser(id) => access match {     // a anonymous accessing a user
            case Access.READ => Access.READ_ALL_USERS
            case Access.WRITE => Access.WRITE_ALL_USERS
            case Access.CREATE => if (Role.isSuperUser(id)) Access.CREATE_SUPERUSER else Access.CREATE_USER
            case _ => access
          }
        case TDevice(id) => access match {     // a anonymous accessing a device
            case Access.READ => Access.READ_ALL_DEVICES
            case Access.WRITE => Access.WRITE_ALL_DEVICES
            case Access.CREATE => Access.CREATE_DEVICE
            case _ => access
          }
        case TAgbot(id) => access match {     // a anonymous accessing a agbot
            case Access.READ => Access.READ_ALL_AGBOTS
            case Access.WRITE => Access.WRITE_ALL_AGBOTS
            case Access.CREATE => Access.CREATE_AGBOT
            case _ => access
          }
        case TBctype(id) => access match {     // a anonymous accessing a bctype
            case Access.READ => Access.READ_ALL_BCTYPES
            case Access.WRITE => Access.WRITE_ALL_BCTYPES
            case Access.CREATE => Access.CREATE_BCTYPES
            case _ => access
          }
        case TBlockchain(id) => access match {     // a anonymous accessing a blockchain
            case Access.READ => Access.READ_ALL_BLOCKCHAINS
            case Access.WRITE => Access.WRITE_ALL_BLOCKCHAINS
            case Access.CREATE => Access.CREATE_BLOCKCHAINS
            case _ => access
          }
        case TAction(id) => access      // a anonymous running an action
      }
      if (Role.hasAuthorization(Role.ANONYMOUS, access2)) return this else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, accessDeniedMsg(access2)))
    }    
  }

  /** This and its subclasses are used to identify the target resource the rest api method goes after */
  abstract class Target {
    def id: String
    def all: Boolean = return id == "*"
    def mine: Boolean = return id == "#"
  }

  case class TUser(id: String) extends Target
  case class TDevice(id: String) extends Target
  case class TAgbot(id: String) extends Target
  case class TBctype(id: String) extends Target      // for bctypes and blockchains only the user that created it can update/delete it
  case class TBlockchain(id: String) extends Target   // this id is a composite of the bc name and bctype
  case class TAction(id: String = "") extends Target    // for post rest api methods that do not target any specific resource (e.g. admin operations)


  def credsAndLog(anonymousOk: Boolean = false): Identity = {
    val creds = credentials(anonymousOk)
    val userOrId = if (creds.isAnonymous) "(anonymous)" else creds.id
    val clientIp = request.header("X-Forwarded-For").orElse(Option(request.getRemoteAddr)).get      // haproxy inserts the real client ip into the header for us
    logger.info("User or id "+userOrId+" from "+clientIp+" running "+request.getMethod+" "+request.getPathInfo)
    if (isDbMigration && !Role.isSuperUser(creds.id)) halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied - in the process of DB migration"))
    return IIdentity(creds)
  }
  // def credentialsAndLog(anonymousOk: Boolean = false): Creds = credsAndLog(anonymousOk).creds

  /** Looks in the http header and url params for credentials and returns them. Supported:
   * Basic auth in header in clear text: Authorization:Basic <user-or-id>:<pw-or-token>
   * Basic auth in header base64 encoded: Authorization:Basic <base64-encoded-of-above>
   * URL params: username=<user>&password=<pw>
   * URL params: id=<id>&token=<token>
   * @param anonymousOk True means this method will not halt with error msg if no credentials are found
   */
  def credentials(anonymousOk: Boolean = false): Creds = {
    val auth = Option(request.getHeader("Authorization"))
    auth match {
      case Some(authStr) => val R1 = "^Basic *(.*)$".r
        authStr match {
          case R1(basicAuthStr) => val basicAuthStr2 = if (basicAuthStr.contains(":")) basicAuthStr else new String(Base64.getDecoder.decode(basicAuthStr), "utf-8")
            val R2 = """^\s*(\S*):(\S*)\s*$""".r      // decode() seems to add a newline at the end
            basicAuthStr2 match {
              case R2(id,tok) => /*logger.trace("id="+id+",tok="+tok+".");*/ Creds(id,tok)
              case _ => halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials format: "+basicAuthStr2))
            }
          case _ => halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "if the Authorization field in the header is specified, only Basic auth is currently supported"))
        }
      // Not in the header, look in the url query string. Parameters() gives you the params ater "?". Params() gives you the routes variables (if they have same name)
      case None => (request.parameters.get("id").orElse(params.get("id")), request.parameters.get("token")) match {
        case (Some(id), Some(tok)) => if (id == "{id}") Creds(swaggerHack("id"),tok) else Creds(id,tok)
        case _ => (request.parameters.get("username").orElse(params.get("username")), request.parameters.get("password")) match {
          case (Some(user), Some(pw)) => if (user == "{username}") Creds(swaggerHack("username"),pw) else Creds(user,pw)
          case _ => if (anonymousOk) Creds("","")
            else halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "no credentials given"))
        }
      }
    }
  }

/*

  /** Returns true if username is root and pw is correct */
  def isSuperUser(creds: Creds): Boolean = { Role.isSuperUser(creds.id) && isAuthenticatedUser(creds) }

  /** Returns true if the username and password of a User object in the db match the specified credentials */
  def isAuthenticatedUser(creds: Creds): Boolean = {
    return AuthCache.users.isValid(creds)
  }

  /** Returns if the id and token of a Device object in the db match the specified credentials */
  def isAuthenticatedDevice(creds: Creds): Boolean = {
    return AuthCache.devices.isValid(creds)
  }

  /** Returns true if the id and token of an Agbot object in the db match the specified credentials */
  def isAuthenticatedAgbot(creds: Creds): Boolean = {
    return AuthCache.agbots.isValid(creds)
  }

  /** Returns true if this authenticated user has access to the specified user object */
  def userHasAuthorization(username: String, userToAccess: String, baseAccess: BaseAccess): Boolean = {
    val role = if (Role.isSuperUser(username)) Role.SUPERUSER else if (username == "") Role.ANONYMOUS else Role.USER
    if (baseAccess == BaseAccess.ADMIN) return Role.hasAuthorization(role, Access.ADMIN)
    else if (baseAccess == BaseAccess.STATUS) return Role.hasAuthorization(role, Access.STATUS)
    else if (baseAccess == BaseAccess.RESET_PW) return Role.hasAuthorization(role, Access.RESET_USER_PW)
    else if (baseAccess == BaseAccess.READ_ALL_BLOCKCHAINS) return Role.hasAuthorization(role, Access.READ_ALL_BLOCKCHAINS)
    else if (baseAccess == BaseAccess.WRITE_ALL_BLOCKCHAINS) return Role.hasAuthorization(role, Access.WRITE_ALL_BLOCKCHAINS)
    else if (username == "" && baseAccess == BaseAccess.CREATE) {
      val acc = if (Role.isSuperUser(userToAccess)) Access.CREATE_SUPERUSER else Access.CREATE_USER
      return Role.hasAuthorization(Role.ANONYMOUS, acc)
    } else {
      var access: Access = Access.READ_MYSELF
      if (username == userToAccess) access = if (baseAccess == BaseAccess.READ) Access.READ_MYSELF else Access.WRITE_MYSELF
      else access = if (baseAccess == BaseAccess.READ) Access.READ_ALL_USERS else Access.WRITE_ALL_USERS
      return Role.hasAuthorization(role, access)
    }
  }

  /** Returns true if this authenticated user has access to the specified device object */
  def userHasAuthorizationToDevice(username: String, deviceToAccess: String, baseAccess: BaseAccess): Boolean = {
    var iOwnDevice = false
    if (deviceToAccess == "#") iOwnDevice = true        // "#" means my devices
    else if (baseAccess != BaseAccess.CREATE && deviceToAccess != "*") {
      AuthCache.devices.getOwner(deviceToAccess) match {
        case Some(owner) => if (owner == username) iOwnDevice = true else iOwnDevice = false
        case None => iOwnDevice = true    // if we did not find it, we consider that as owning it because we will create it
      }
      // iOwnDevice = try {
      //   val ownerVector = Await.result(db.run(DevicesTQ.getOwner(deviceToAccess).result), Duration(3000, MILLISECONDS))
      //   if (ownerVector.size > 0) { if (ownerVector.head == username) true else false } else true    // if we did not find it, we consider that as owning it because we will create it
      // } catch { case e: java.util.concurrent.TimeoutException => logger.error("getting device '"+deviceToAccess+"' owner timed out"); false }     // could not get it from the db, so have to assume we do not own it
    }
    var access: Access = Access.READ_MY_DEVICES
    baseAccess match {          // determine the access required, based on the baseAccess and whether i own the device or not
      case BaseAccess.READ => access = if (iOwnDevice) Access.READ_MY_DEVICES else Access.READ_ALL_DEVICES
      case BaseAccess.WRITE => access = if (iOwnDevice) Access.WRITE_MY_DEVICES else Access.WRITE_ALL_DEVICES
      case BaseAccess.CREATE => access = Access.CREATE_DEVICE
    }
    val role = if (Role.isSuperUser(username)) Role.SUPERUSER else Role.USER
    return Role.hasAuthorization(role,access)
  }

  /** Returns true if this authenticated user has access to the specified agbot object */
  def userHasAuthorizationToAgbot(username: String, agbotToAccess: String, baseAccess: BaseAccess): Boolean = {
    var iOwnAgbot = false
    if (agbotToAccess == "#") iOwnAgbot = true        // "#" means my agbots
    else if (baseAccess != BaseAccess.CREATE && agbotToAccess != "*") {
      AuthCache.agbots.getOwner(agbotToAccess) match {
        case Some(owner) => if (owner == username) iOwnAgbot = true else iOwnAgbot = false
        case None => iOwnAgbot = true    // if we did not find it, we consider that as owning it because we will create it
      }
      // iOwnAgbot = try {
      //   val ownerVector = Await.result(db.run(AgbotsTQ.getOwner(agbotToAccess).result), Duration(3000, MILLISECONDS))
      //   if (ownerVector.size > 0) { if (ownerVector.head == username) true else false } else true    // if we did not find it, we consider that as owning it because we will create it
      // } catch { case e: java.util.concurrent.TimeoutException => logger.error("getting device '"+agbotToAccess+"' owner timed out"); false }     // could not get it from the db, so have to assume we do not own it
    }
    var access: Access = Access.READ_MY_AGBOTS
    baseAccess match {          // determine the access required, based on the baseAccess and whether i own the device or not
      case BaseAccess.READ => access = if (iOwnAgbot) Access.READ_MY_AGBOTS else Access.READ_ALL_AGBOTS
      case BaseAccess.WRITE => access = if (iOwnAgbot) Access.WRITE_MY_AGBOTS else Access.WRITE_ALL_AGBOTS
      case BaseAccess.DATA_HEARTBEAT => access = if (iOwnAgbot) Access.AGBOT_AGREEMENT_DATA_HEARTBEAT else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
      case BaseAccess.AGREEMENT_CONFIRM => access = Access.AGBOT_AGREEMENT_CONFIRM       // the implementation of the rest method only searches this user's agbot agreements
      case BaseAccess.CREATE => access = Access.CREATE_AGBOT
    }
    val role = if (Role.isSuperUser(username)) Role.SUPERUSER else Role.USER
    return Role.hasAuthorization(role,access)
  }

  /** Returns true if this authenticated device id has access to the specified device object */
  def deviceHasAuthorization(id: String, idToAccess: String, baseAccess: BaseAccess): Boolean = {
    var access: Access = Access.READ_MYSELF
    if (id == idToAccess) access = if (baseAccess == BaseAccess.READ) Access.READ_MYSELF else Access.WRITE_MYSELF
    else if (baseAccess == BaseAccess.SEND_MSG) access = Access.SEND_MSG_TO_AGBOT
    else if (baseAccess == BaseAccess.READ_ALL_BLOCKCHAINS) access = Access.READ_ALL_BLOCKCHAINS
    else if (baseAccess == BaseAccess.WRITE_ALL_BLOCKCHAINS) access = Access.WRITE_ALL_BLOCKCHAINS
    else access = if (baseAccess == BaseAccess.READ) Access.READ_ALL_DEVICES else Access.WRITE_ALL_DEVICES
    return Role.hasAuthorization(Role.DEVICE, access)
  }

  /** Returns true if this authenticated agbot id has access to the specified device object */
  def agbotHasAuthorizationToDevice(id: String, idToAccess: String, baseAccess: BaseAccess): Boolean = {
    val access = if (baseAccess == BaseAccess.READ) Access.READ_ALL_DEVICES else if (baseAccess == BaseAccess.WRITE) Access.WRITE_ALL_DEVICES else Access.NONE
    return Role.hasAuthorization(Role.AGBOT, access)
  }

  /** Returns true if this authenticated agbot id has access to the specified agbot object */
  def agbotHasAuthorization(id: String, idToAccess: String, baseAccess: BaseAccess): Boolean = {
    var access: Access = Access.READ_MYSELF
    if (id == idToAccess && baseAccess == BaseAccess.DATA_HEARTBEAT) access = Access.AGBOT_AGREEMENT_DATA_HEARTBEAT
    else if (baseAccess == BaseAccess.AGREEMENT_CONFIRM) access = Access.AGBOT_AGREEMENT_CONFIRM       // the implementation of the rest method only searches the agbot agreements owned by the same user
    else if (id == idToAccess) access = if (baseAccess == BaseAccess.READ) Access.READ_MYSELF else Access.WRITE_MYSELF
    else if (baseAccess == BaseAccess.SEND_MSG) access = Access.SEND_MSG_TO_DEVICE
    else if (baseAccess == BaseAccess.READ_ALL_BLOCKCHAINS) access = Access.READ_ALL_BLOCKCHAINS
    else if (baseAccess == BaseAccess.WRITE_ALL_BLOCKCHAINS) access = Access.WRITE_ALL_BLOCKCHAINS
    else access = if (baseAccess == BaseAccess.READ) Access.READ_ALL_AGBOTS else Access.WRITE_ALL_AGBOTS
    return Role.hasAuthorization(Role.AGBOT, access)
  }

  /** Validates creds as root user/pw, and then validates access to the specified user or operation. */
  def validateRoot(baseAccess: BaseAccess, username: String = ""): Creds = {
    val creds = credentialsAndLog()
    if (isSuperUser(creds)) if (userHasAuthorization(creds.id, username, baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
  }

  /** Validates creds as user/pw, and then validates access to the specified user or operation. */
  def validateUser(baseAccess: BaseAccess, username: String): Creds = {
    val creds = credentialsAndLog(true)
    if (creds.isAnonymous) if (userHasAuthorization("", username, baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    if (isAuthenticatedUser(creds)) if (userHasAuthorization(creds.id, username, baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
  }

  /** Validates creds as user and reset token, and then validates access to the specified user or operation. */
  def validateToken(baseAccess: BaseAccess, username: String): Creds = {
    val creds = credentialsAndLog()
    if (isTokenValid(creds.token, creds.id)) if (userHasAuthorization(creds.id, username, baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid or expired token"))
  }

  /** Validates creds as either user/pw or device/agbot id/token. */
  def validateUserOrId(baseAccess: BaseAccess): Creds = {
    val creds = credentialsAndLog()
    if (isAuthenticatedUser(creds)) if (userHasAuthorization(creds.id, "", baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    if (isAuthenticatedDevice(creds)) if (deviceHasAuthorization(creds.id, "", baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    if (isAuthenticatedAgbot(creds)) if (agbotHasAuthorization(creds.id, "", baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
  }

  /** Validates creds as either user/pw or device id/token, and then validates access to the specified device. */
  def validateUserOrDeviceId(baseAccess: BaseAccess, id: String): Creds = {
    val creds = credentialsAndLog()
    if (isAuthenticatedUser(creds)) if (userHasAuthorizationToDevice(creds.id, id, baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    if (isAuthenticatedDevice(creds)) if (deviceHasAuthorization(creds.id, id, baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
  }

  /** Validates creds as device id/token. */
  def validateDeviceId(baseAccess: BaseAccess): Creds = {
    val creds = credentialsAndLog()
    if (isAuthenticatedDevice(creds)) if (deviceHasAuthorization(creds.id, "", baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
  }

  /** Validates creds, and then validates access to the specified device. */
  def validateAccessToDevice(baseAccess: BaseAccess, id: String): Creds = {
    val creds = credentialsAndLog()
    if (isAuthenticatedUser(creds)) if (userHasAuthorizationToDevice(creds.id, id, baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    if (isAuthenticatedDevice(creds)) if (deviceHasAuthorization(creds.id, id, baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    if (isAuthenticatedAgbot(creds)) if (agbotHasAuthorizationToDevice(creds.id, id, baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
  }

  /** Validates creds as either user/pw or agbot id/token, and then validates access to the specified agbot. */
  def validateUserOrAgbotId(baseAccess: BaseAccess, id: String): Creds = {
    val creds = credentialsAndLog()
    if (isAuthenticatedUser(creds)) if (userHasAuthorizationToAgbot(creds.id, id, baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    if (isAuthenticatedAgbot(creds)) if (agbotHasAuthorization(creds.id, id, baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
  }

  /** Validates creds as agbot id/token. */
  def validateAgbotId(baseAccess: BaseAccess): Creds = {
    val creds = credentialsAndLog()
    if (isAuthenticatedAgbot(creds)) if (agbotHasAuthorization(creds.id, "", baseAccess)) return creds else halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied"))
    halt(HttpCode.BADCREDS, ApiResponse(ApiResponseType.BADCREDS, "invalid credentials"))
  }
*/

  /** Work around A swagger Try It button bug that specifies id as "{id}" instead of the actual id. In this case, get the id from the query string. */
  def swaggerHack(paramName: String): String = {
    val paramsVal = params(paramName)
    if (paramsVal != "{"+paramName+"}") return paramsVal
    // val parm = request.queryString.split("&").find(x => x.startsWith(paramName+"="))
    val parm = request.parameters.get(paramName)
    parm match {
      case Some(parm) => return parm      // parm.replace(paramName+"=","")
      case _ => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "swagger specifies the "+paramName+" incorrectly in this case"))
    }
  }

  /** Returns a temporary pw reset token. */
  def createToken(username: String): String = {
    // Get their current pw to use as the secret
    AuthCache.users.get(username) match {
      // case Some(userTok) => if (userTok.unhashed != "") Token.create(userTok.unhashed) else Token.create(userTok.hashed)   // try to create the token with the unhashed pw for consistency with the rest of the code
      case Some(userTok) => Token.create(userTok.hashed)   // always create the token with the hashed pw because that will always be there during creation and validation of the token
      case None => halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "username not found"))
    }
  }

  /** Returns true if the token is correct for this user and not expired */
  def isTokenValid(token: String, username: String): Boolean = {
    // Get their current pw to use as the secret
    // Use the users hashed pw because that is consistently there, whereas the clear pw is not
    AuthCache.users.get(username) match {
      // case Some(userTok) => if (userTok.unhashed != "") Token.isValid(token, userTok.unhashed) else Token.isValid(token, userTok.hashed)
      case Some(userTok) => Token.isValid(token, userTok.hashed)
      case None => halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "username not found"))
    }
  }

}

/** Hash a password or token, and compare a pw/token to its hashed value */
object Password {
  // Using jbcrypt, see https://github.com/jeremyh/jBCrypt and http://javadox.com/org.mindrot/jbcrypt/0.3m/org/mindrot/jbcrypt/BCrypt.html
  val logRounds = 10      // hashes the pw 2**logRounds times

  /** Returns the hashed/salted value of the given password or token */
  //TODO: when we have a more reliable check() below, allow them to pass in a pw that is already hashed our way, recognize it, and do not hash it. Linux pw hash can be created using: openssl passwd -1 -salt xyz yourpass
  def hash(password: String): String = { BCrypt.hashpw(password, BCrypt.gensalt(logRounds)) }

  /** Returns true if plainPw matches hashedPw */
  def check(plainPw: String, hashedPw: String): Boolean = { BCrypt.checkpw(plainPw, hashedPw) }

  /** Returns true if this pw/token is already hashed */
  def isHashed(password: String): Boolean = { password.startsWith("""$2a$10$""") }      // is there a better way to determine this?
}

/** Create and validate web tokens that expire */
object Token {
  // From: https://github.com/pauldijou/jwt-scala
  val defaultExpiration = 600     // seconds
  val algorithm = JwtAlgorithm.HS256

  /** Returns a temporary pw reset token. */
  def create(secret: String, expiration: Int = defaultExpiration): String = { Jwt.encode(JwtClaim({"""{"user":1}"""}).issuedNow.expiresIn(defaultExpiration), secret, algorithm) }

  /** Returns true if the token is correct for this secret and not expired */
  def isValid(token: String, secret: String): Boolean = { Jwt.isValid(token, secret, Seq(algorithm)) }
}
