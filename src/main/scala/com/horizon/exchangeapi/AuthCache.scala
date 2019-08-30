package com.horizon.exchangeapi

import java.util.concurrent.TimeUnit

import com.horizon.exchangeapi.CacheIdType.CacheIdType
import com.horizon.exchangeapi.tables._
import org.scalatra.servlet.ServletApiImplicits
import org.scalatra.Control
import org.slf4j.LoggerFactory
import slick.jdbc.PostgresProfile.api._

import scala.collection.mutable.{HashMap => MutableHashMap}
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import com.horizon.exchangeapi.auth._
import com.google.common.cache.CacheBuilder
import scalacache._
import scalacache.guava.GuavaCache
import scalacache.modes.try_._

import scala.util.{Failure, Success, Try}

// Enum for type of id in CacheId class
object CacheIdType extends Enumeration {
  type CacheIdType = Value
  val User = Value("User")
  val Node = Value("Node")
  val Agbot = Value("Agbot")
  val Unknown = Value("Unknown")
}

/** In-memory cache of the user/pw, node id/token, and agbot id/token, where the pw and tokens are not hashed to speed up validation */
object AuthCache extends Control with ServletApiImplicits {
  val logger = LoggerFactory.getLogger(ExchConfig.LOGGER)

  // The unhashed and hashed values of the token are not always both set, but if they are they are in sync.
  case class Tokens(unhashed: String, hashed: String)

  /* Cache todo:
  - add node and agbot ids to CacheId
  - add owner caches (including a base class)
  - put new strings in 2nd msg file
   */

  /** Holds recently authenticated users, node ids, agbot ids */
  class CacheId() {
    // For this cache the key is the id (already prefixed with the org) and the value is this class
    case class CacheVal(hashedToken: String, idType: CacheIdType = CacheIdType.Unknown) /*{
      // Generate the key that this should be stored under in the cache
      def cacheKey = id + ":" + hashedToken     // there is probably a better way to combine these values for the key

      // The value we store in the cache for the above key is the type (User, Node, Agbot, Unknown), because once we've authenticated it
      // we need to know what type it is because that affects authorization (what it is allowed to do)
      def cacheValue = idType.toString
    } */

    private val guavaCache = CacheBuilder.newBuilder()
      .maximumSize(ExchConfig.getInt("api.cache.idsSize"))
      .expireAfterWrite(ExchConfig.getInt("api.cache.idsTtlSeconds"), TimeUnit.SECONDS)
      .build[String, Entry[CacheVal]]     // the cache key is org/id, and the value is CacheVal
    implicit val userCache = GuavaCache(guavaCache)   // needed so ScalaCache API can find it. Another effect of this is that these methods don't need to be qualified
    private var db: Database = _

    def init(db: Database): Unit = { this.db = db }

    def isValid(creds: Creds): Boolean = {
      logger.debug("CacheId:isValid(): attempting to authenticate to the exchange with "+creds)
      val cacheValue = getCacheValue(creds.id)
      if (cacheValue.isFailure) return false
      // we got the hashed token from the cache or db, now verify the token passed in
      if (Password.check(creds.token, cacheValue.get.hashedToken)) {
        logger.debug("CacheId:isValid(): successfully matched "+creds.id+" and its pw in the cache/db")
        return true
      } else {
        logger.debug("CacheId:isValid(): user "+creds.id+" not authenticated in the exchange")
        return false
      }
    }

    // I currently don't know how to make the cachingF function run and get its value w/o putting it in a separate method
    private def getCacheValue(id: String): Try[CacheVal] = {
      cachingF(id)(ttl = None) {
        for {
          userVal <- getUser(id)
        } yield userVal
      }
    }

    // Called when this user id isn't in the cache. Gets the user from the db and puts the hashed token and type in the cache.
    private def getUser(id: String): Try[CacheVal] = {
      logger.debug("CacheId:getUser(): "+id+" was not in the cache, so attempting to get it from the db")
      val dbAction = UsersTQ.getPassword(id).result
      val dbHashedTok: String = try {
        //logger.trace("awaiting for DB query of local exchange creds for "+id+"...")
        val respVector = Await.result(db.run(dbAction), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
        //logger.trace("...back from awaiting for DB query of local exchange creds for "+id+".")
        if (respVector.nonEmpty) respVector.head else ""
      } catch {
        // Handle db problems
        case timeout: java.util.concurrent.TimeoutException => logger.error("db timed out getting pw/token for '"+id+"' . "+timeout.getMessage)
          throw new DbTimeoutException(ExchangeMessage.translateMessage("db.timeout.getting.token2", id, timeout.getMessage))
        case other: Throwable => logger.error("db connection error getting pw/token for '"+id+"': "+other.getMessage)
          throw new DbConnectionException(ExchangeMessage.translateMessage("db.threw.exception", other.getMessage))
      }   // end of getting dbHashedTok

      if (dbHashedTok == "") return Failure(new UserPwNotFoundException(ExchangeMessage.translateMessage("user.notfound.db", id)))
      logger.debug("CacheId:getUser(): "+id+" found in the db, adding it to the cache")
      Success(CacheVal(dbHashedTok, CacheIdType.User))
    }

    // Called for temp token creation/validation
    def getOne(id: String): Option[String] = {
      val cacheValue = getCacheValue(id)
      if (cacheValue.isSuccess) Some(cacheValue.get.hashedToken)
      else None
    }

    // The token passed in is already hashed.
    def putOne(creds: Creds): Unit = { put(creds.id)(CacheVal(creds.token, CacheIdType.User)) }    // we need this for the test suites, but in production it will only help in this 1 exchange instance

    def removeOne(id: String): Try[Any] = { remove(id) }

    def clearCache(): Try[Unit] = {
      logger.debug("Clearing the id cache")
      removeAll().map(_ => ())
    }
  }

  /** Holds whether a user has admin privilege or not */
  class CacheAdmin() {
    // For this cache the key is the id (already prefixed with the org) and the value is a boolean

    private val guavaCache = CacheBuilder.newBuilder()
      .maximumSize(ExchConfig.getInt("api.cache.resourceOwnersSize"))
      .expireAfterWrite(ExchConfig.getInt("api.cache.resourceOwnersTtlSeconds"), TimeUnit.SECONDS)
      .build[String, Entry[Boolean]]     // the cache key is org/id, and the value is whether it has admin priv
    implicit val userCache = GuavaCache(guavaCache)   // needed so ScalaCache API can find it. Another effect of this is that these methods don't need to be qualified
    private var db: Database = _

    def init(db: Database): Unit = { this.db = db }

    def isAdmin(id: String): Boolean = {
      logger.debug("CacheAdmin:isAdmin(): querying whether "+id+" has admin privilege")
      val cacheValue = getCacheValue(id)
      if (cacheValue.isFailure) return false
      // we got the answer from the cache or db, return it
      cacheValue.get
    }

    // I currently don't know how to make the cachingF function run and get its value w/o putting it in a separate method
    private def getCacheValue(id: String): Try[Boolean] = {
      cachingF(id)(ttl = None) {
        for {
          userVal <- getUser(id)
        } yield userVal
      }
    }

    // Called when this user id isn't in the cache. Gets the user from the db and puts the admin boolean in the cache.
    private def getUser(id: String): Try[Boolean] = {
      logger.debug("CacheAdmin:getUser(): "+id+" was not in the cache, so attempting to get it from the db")
      val dbAction = UsersTQ.getAdmin(id).result
      try {
        //logger.trace("CacheAdmin:getUser(): awaiting for DB query of local exchange admin value for "+id+"...")
        val respVector = Await.result(db.run(dbAction), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
        //logger.trace("CacheAdmin:getUser(): ...back from awaiting for DB query of local exchange admin value for "+id+".")
        if (respVector.nonEmpty) {
          val isAdmin = respVector.head
          logger.debug("CacheAdmin:getUser(): "+id+" found in the db, adding it with value "+isAdmin+" to the cache")
          Success(isAdmin)
        }
        else Failure(new UserPwNotFoundException(ExchangeMessage.translateMessage("user.notfound.db", id)))
      } catch {
        // Handle db problems
        case timeout: java.util.concurrent.TimeoutException => logger.error("db timed out getting admin boolean for '"+id+"' . "+timeout.getMessage)
          throw new DbTimeoutException(ExchangeMessage.translateMessage("db.timeout.getting.admin", id, timeout.getMessage))
        case other: Throwable => logger.error("db connection error getting admin boolean for '"+id+"': "+other.getMessage)
          throw new DbConnectionException(ExchangeMessage.translateMessage("db.threw.exception", other.getMessage))
      }
    }

    // Called for temp token creation/validation
    def getOne(id: String): Option[Boolean] = {
      val cacheValue = getCacheValue(id)
      if (cacheValue.isSuccess) Some(cacheValue.get)
      else None
    }

    def putOne(id: String, isAdmin: Boolean): Unit = { put(id)(isAdmin) }    // we need this for the test suites, but in production it will only help in this 1 exchange instance

    def removeOne(id: String): Try[Any] = { remove(id) }

    def clearCache(): Try[Unit] = {
      logger.debug("Clearing the admin cache")
      removeAll().map(_ => ())
    }
  }


  /** 1 set of things (user/pw, node id/token, agbot id/token, service/owner, pattern/owner) */
  class Cache(val whichTab: String) {     // i am sure there is a better way to handle the different tables
    // Throughout the implementation of this class, id and token are used generically, meaning in the case of users they are user and pw.
    // Our goal is for the token to be unhashed, but we have to handle the case where the user gives us an already hashed token.
    // In this case, turn it into an unhashed token the 1st time they have a successful check against it with an unhashed token.

    // The in-memory cache
    val things = new MutableHashMap[String,Tokens]()     // key is username or id, value is the unhashed and hashed pw's or token's
    val owners = new MutableHashMap[String,String]()     // key is node or agbot id, value is the username that owns it. For users, key is username, value is "admin" if this is an admin user.
    val isPublic = new MutableHashMap[String,Boolean]()     // key is id, value is whether or not its public attribute is true
    val whichTable = whichTab

    var db: Database = _       // filled in my init() below

    /** Initializes the cache with all of the things currently in the persistent db */
    def init(db: Database): Unit = {
      this.db = db      // store for later use
      whichTable match {
        case "users" => db.run(UsersTQ.rows.map(x => (x.username, x.password, x.admin)).result).map({ list => this._initUsers(list, skipRoot = true) })
        case "nodes" => db.run(NodesTQ.rows.map(x => (x.id, x.token, x.owner)).result).map({ list => this._initIds(list) })
        case "agbots" => db.run(AgbotsTQ.rows.map(x => (x.id, x.token, x.owner)).result).map({ list => this._initIds(list) })
        case "services" => db.run(ServicesTQ.rows.map(x => (x.service, x.owner, x.public)).result).map({ list => this._initServices(list) })
        case "patterns" => db.run(PatternsTQ.rows.map(x => (x.pattern, x.owner, x.public)).result).map({ list => this._initPatterns(list) })
        case "business" => db.run(BusinessPoliciesTQ.rows.map(x => (x.businessPolicy, x.owner)).result).map({ list => this._initBusiness(list) })
      }
    }

    /** Put all of the nodes or agbots in the cache */
    def _initIds(credList: Seq[(String,String,String)]): Unit = {
      for ((id,token,owner) <- credList) {
        val tokens: Tokens = if (Password.isHashed(token)) Tokens("", token) else Tokens(token, Password.hash(token))
        _put(id, tokens)     // Note: ExchConfig.createRoot(db) already puts root in the auth cache and we do not want a race condition
        if (owner != "") _putOwner(id, owner)
      }
    }

    /** Put all of the users in the cache */
    def _initUsers(credList: Seq[(String,String,Boolean)], skipRoot: Boolean = false): Unit = {
      for ((username,password,admin) <- credList) {
        val tokens: Tokens = if (Password.isHashed(password)) Tokens("", password) else Tokens(password, Password.hash(password))
        if (!(skipRoot && Role.isSuperUser(username))) _put(username, tokens)     // Note: ExchConfig.createRoot(db) already puts root in the auth cache and we do not want a race condition
        if (admin) _putOwner(username, "admin")
      }
    }

    /** Put owners of services in the cache */
    def _initServices(credList: Seq[(String,String,Boolean)]): Unit = {
      for ((service,owner,isPub) <- credList) {
        if (owner != "") _putOwner(service, owner)
        _putIsPublic(service, isPub)
      }
    }

    /** Put owners of patterns in the cache */
    def _initPatterns(credList: Seq[(String,String,Boolean)]): Unit = {
      for ((pattern,owner,isPub) <- credList) {
        if (owner != "") _putOwner(pattern, owner)
        _putIsPublic(pattern, isPub)
      }
    }

    /** Put owners of business policies in the cache */
    def _initBusiness(credList: Seq[(String,String)]): Unit = {
      for ((pattern,owner) <- credList) {
        if (owner != "") _putOwner(pattern, owner)
        _putIsPublic(pattern, isPub = false)    // business policies are never public
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
        case "nodes" => NodesTQ.getToken(id).result
        case "agbots" => AgbotsTQ.getToken(id).result
      }
      // maybe this db access should go at the beginning of every rest api db access, using flatmap to move on to the db access the rest api is really for
      val dbHashedTok: String = try {
        //logger.trace("awaiting for DB query of local exchange creds for "+id+"...")
        val tokVector = Await.result(db.run(a), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
        //logger.trace("...back from awaiting for DB query of local exchange creds for "+id+".")
        if (tokVector.nonEmpty) tokVector.head else ""
      } catch {
        // Handle db problems
        case timeout: java.util.concurrent.TimeoutException => logger.error("db timed out getting pw/token for '"+id+"' . Trying to use the cache for now. "+timeout.getMessage)
          val cacheVal = _get(id)
          if (cacheVal.isEmpty) throw new DbTimeoutException(ExchangeMessage.translateMessage("db.timeout.getting.token", id, timeout.getMessage))
          return cacheVal
        case other: Throwable => logger.error("db connection error getting pw/token for '"+id+"': "+other.getMessage)
          throw new DbConnectionException(ExchangeMessage.translateMessage("db.threw.exception", other.getMessage))
      }

      // Now get it from the cache and compare/sync the 2
      _get(id) match {
        case Some(cacheTok) => if (dbHashedTok == "" && whichTable == "users" && Role.isSuperUser(id)) { return Some(cacheTok) }  // we never want to get rid of the cache in root, or we have no way to repair things
          else if (dbHashedTok == "") {   //not in db, remove it from cache, unless it is root
            remove(id)
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
      //logger.trace("in AuthCache.users.isValid(creds) calling get(creds.id)")
      val getReturn = get(creds.id)
      //logger.trace("in AuthCache.users.isValid(creds) back get(creds.id)")
      getReturn match {      // Note: get() will verify the cache with the db before returning
        // We have this id in the cache, but the unhashed token in the cache could be blank, or the cache could be out of date
        case Some(tokens) => ;
          try {
            if (Password.isHashed(creds.token)) return creds.token == tokens.hashed
            else {      // specified token is unhashed
              if (tokens.unhashed != "") return creds.token == tokens.unhashed
              else {    // the specified token is unhashed, but we do not have the unhashed token in our cache yet
                if (Password.check(creds.token, tokens.hashed)) {
                  // now we have the unhashed version of the token so update our cache with that
                  //logger.debug("updating auth cache with unhashed pw/token for '"+creds.id+"'")
                  _put(creds.id, Tokens(creds.token, tokens.hashed))
                  true
                } else false
              }
            }
          } catch { case _: Exception => logger.error("Invalid encoded version error from Password.check()"); false }   // can throw IllegalArgumentException: Invalid encoded version
        case None => false
      }
    }

    /** Returns Some(owner) from the cache for this id (but verifies with the db 1st), or None if does not exist */
    def getOwner(id: String): Option[String] = {
      // logger.trace("getOwners owners: "+owners.toString)
      //if (whichTable == "users") return None      // we never actually call this

      // Even though we try to put every new/updated owner in our cache, when this server runs in multi-node mode,
      // an update could have come to 1 of the other nodes. The db is our sync point, so always verify our cached owner with the db owner.
      // We are doing this only so we can fall back to the cache's last known owner if the db times out.
      try {
        if (whichTable == "users") {
          //logger.trace("awaiting for DB query of local exchange isAdmin for "+id+"...")
          val ownerVector = Await.result(db.run(UsersTQ.getAdmin(id).result), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
          //logger.trace("...back from awaiting for DB query of local exchange isAdmin for "+id+".")
          if (ownerVector.nonEmpty) {
            if (ownerVector.head) return Some("admin")
            else return Some("")
          }
          else return None
        } else {
          // For the all the others, we are looking for the traditional owner
          val a = whichTable match {
            //case "users" => UsersTQ.getAdminAsString(id).result
            case "nodes" => NodesTQ.getOwner(id).result
            case "agbots" => AgbotsTQ.getOwner(id).result
            case "services" => ServicesTQ.getOwner(id).result
            case "patterns" => PatternsTQ.getOwner(id).result
            case "business" => BusinessPoliciesTQ.getOwner(id).result
          }
          //logger.trace("awaiting for DB query of local exchange owner for "+id+"...")
          val ownerVector = Await.result(db.run(a), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
          //logger.trace("...back from awaiting for DB query of local exchange owner for "+id+".")
          if (ownerVector.nonEmpty) /*{ logger.trace("getOwner return: "+ownerVector.head);*/ return Some(ownerVector.head) else /*{ logger.trace("getOwner return: None");*/ return None
        }
      } catch {
        // Handle db problems
        case timeout: java.util.concurrent.TimeoutException => logger.error("db timed out getting owner or isAdmin for '"+id+"' . Trying to use the cache for now. "+timeout.getMessage)
          val cacheVal = _getOwner(id)
          if (cacheVal.isEmpty) throw new DbTimeoutException(ExchangeMessage.translateMessage("db.timeout.getting.owner", id, timeout.getMessage))
          return cacheVal
        case other: Throwable => logger.error("db connection error getting owner or isAdmin for '"+id+"': "+other.getMessage)
          throw new DbConnectionException(ExchangeMessage.translateMessage("db.threw.exception", other.getMessage))
      }
    }

    /** Returns Some(isPub) from the cache for this id (but verifies with the db 1st), or None if does not exist */
    def getIsPublic(id: String): Option[Boolean] = {
      if (whichTable == "business") return Some(false)    // business policies are never public
      // We are doing this only so we can fall back to the cache's last known owner if the db times out.
      try {
        // For the all the others, we are looking for the traditional owner
        val a = whichTable match {
          case "services" => ServicesTQ.getPublic(id).result
          case "patterns" => PatternsTQ.getPublic(id).result
          case _ => return Some(false)      // should never get here
        }
        //logger.trace("awaiting for DB query of local exchange isPublic for "+id+"...")
        val publicVector = Await.result(db.run(a), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
        //logger.trace("...back from awaiting for DB query of local exchange isPublic for "+id+".")
        if (publicVector.nonEmpty) /*{ logger.trace("getIsPublic return: "+publicVector.head);*/ return Some(publicVector.head) else /*{ logger.trace("getIsPublic return: None");*/ return None
      } catch {
        // Handle db problems
        case timeout: java.util.concurrent.TimeoutException => logger.error("db timed out getting isPublic for '"+id+"' . Trying to use the cache for now. "+timeout.getMessage)
          val cacheVal = _getIsPublic(id)
          if (cacheVal.isEmpty) throw new DbTimeoutException(ExchangeMessage.translateMessage("db.timeout.getting.ispublic", id, timeout.getMessage))
          return cacheVal
        case other: Throwable => logger.error("db connection error getting isPublic for '"+id+"': "+other.getMessage)
          throw new DbConnectionException(ExchangeMessage.translateMessage("db.threw.exception", other.getMessage))
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
    def putIsPublic(id: String, isPub: Boolean): Unit = { _putIsPublic(id, isPub)}

    /** Removes the user/id and pw/token pair from the cache. If it does not exist, no error is returned */
    def remove(id: String) = { _remove(id) }
    def removeOwner(id: String) = { _removeOwner(id) }
    def removeBoth(id: String) = { _removeBoth(id) }
    def removeIsPublic(id: String) = { _removeIsPublic(id) }

    /** Removes all user/id, pw/token pairs from this cache. */
    def removeAll() = {
      val rootTokens = if (whichTable == "users") _get(Role.superUser) else None     // have to preserve the root user or they can not do anything after this
      _clear()
      rootTokens match {
        case Some(tokens) => _put(Role.superUser, tokens)
        case None => ;
      }
      removeAllOwners()
    }
    def removeAllOwners() = { _clearOwners() }
    def removeAllIsPublic() = { _clearIsPublic() }

    /** Low-level functions to lock on the hashmap */
    private def _get(id: String) = synchronized { things.get(id) }
    private def _put(id: String, tokens: Tokens) = synchronized { things.put(id, tokens) }
    private def _remove(id: String) = synchronized { things.remove(id) }
    private def _clear() = synchronized { things.clear }

    private def _getOwner(id: String) = synchronized { owners.get(id) }
    private def _putOwner(id: String, owner: String) = synchronized { owners.put(id, owner) }
    private def _removeOwner(id: String) = synchronized { owners.remove(id) }
    private def _clearOwners() = synchronized { owners.clear }

    private def _removeBoth(id: String) = synchronized { things.remove(id); owners.remove(id) }

    private def _getIsPublic(id: String) = synchronized { isPublic.get(id) }
    private def _putIsPublic(id: String, isPub: Boolean) = synchronized { isPublic.put(id, isPub) }
    private def _removeIsPublic(id: String) = synchronized { isPublic.remove(id) }
    private def _clearIsPublic() = synchronized { isPublic.clear }
  }     // end of Cache class

  val users = new CacheId()
  val usersAdmin = new CacheAdmin()
  val nodes = new Cache("nodes")
  val agbots = new Cache("agbots")
  val services = new Cache("services")
  val patterns = new Cache("patterns")
  val business = new Cache("business")
}
