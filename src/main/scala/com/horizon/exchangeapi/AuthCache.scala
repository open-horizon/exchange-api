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
import scala.concurrent.duration._
import com.horizon.exchangeapi.auth._
import com.google.common.cache.CacheBuilder
import scalacache._
import scalacache.guava.GuavaCache
import scalacache.modes.try_._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.util.{Failure, Success, Try}

// Enum for type of id in CacheId class
object CacheIdType extends Enumeration {
  type CacheIdType = Value
  val User = Value("User")
  val Node = Value("Node")
  val Agbot = Value("Agbot")
  val None = Value("None")
}

/** In-memory cache of the user/pw, node id/token, and agbot id/token, where the pw and tokens are not hashed to speed up validation */
object AuthCache extends Control with ServletApiImplicits {
  val logger = LoggerFactory.getLogger(ExchConfig.LOGGER)

  var cacheType = ""    // set from the config file by ExchConfig.load()

  // The unhashed and hashed values of the token are not always both set, but if they are they are in sync.
  case class Tokens(unhashed: String, hashed: String)

  /* Cache todo:
  - scale test
  - if cache value results in invalid creds or access denied, remove cache entry and try again
   */

  /** Holds recently authenticated users, node ids, agbot ids */
  class CacheId() {
    // For this cache the key is the id (already prefixed with the org) and the value is this class
    // Note: unhashedToken isn't really unhashed, it is just bcrypted with less rounds for speed
    case class CacheVal(hashedToken: String, unhashedToken: String = "", idType: CacheIdType = CacheIdType.None)

    private val guavaCache = CacheBuilder.newBuilder()
      .maximumSize(ExchConfig.getInt("api.cache.idsMaxSize"))
      .expireAfterWrite(ExchConfig.getInt("api.cache.idsTtlSeconds"), TimeUnit.SECONDS)
      .build[String, Entry[CacheVal]]     // the cache key is org/id, and the value is CacheVal
    implicit val userCache = GuavaCache(guavaCache)   // needed so ScalaCache API can find it. Another effect of this is that these methods don't need to be qualified
    private var db: Database = _

    def init(db: Database): Unit = { this.db = db }   // we intentionally don't prime the cache. We let it build on every access so we can add the unhashed token

    // Try to authenticate the creds and return the type (user/node/agbot) it is, or None
    def getValidType(creds: Creds, retry: Boolean = false): CacheIdType = {
      logger.debug("CacheId:getValidType(): attempting to authenticate to the exchange with "+creds)
      val cacheValue = getCacheValue(creds)
      logger.trace("cacheValue: "+cacheValue)
      if (cacheValue.isFailure) return CacheIdType.None
      // we got the hashed token from the cache or db, now verify the token passed in
      val cacheVal = cacheValue.get
      if (cacheVal.unhashedToken != "" && Password.check(creds.token, cacheVal.unhashedToken)) {    // much faster than the bcrypt check below
        logger.debug("CacheId:getValidType(): successfully quick-validated "+creds.id+" and its pw using the cache/db")
        return cacheVal.idType
      } else if (Password.check(creds.token, cacheVal.hashedToken)) {
        logger.debug("CacheId:getValidType(): successfully validated "+creds.id+" and its pw using the cache/db")
        return cacheVal.idType
      } else {
        // the creds were invalid
        if (retry) {
          // we already tried clearing the cache and retrying, so give up and return that they were bad creds
          logger.debug("CacheId:getValidType(): user " + creds.id + " not authenticated in the exchange")
          return CacheIdType.None
        } else {
          // If we only used a non-expired cache entry to get here, the cache entry could be stale (e.g. they recently changed their pw/token via a different instance of the exchange).
          // So delete the cache entry from the db and try 1 more time
          logger.debug("CacheId:getValidType(): user " + creds.id + " was not authenticated successfully, removing cache entry in case it was stale, and trying 1 more time")
          removeOne(creds.id)
          return getValidType(creds, retry = true)
        }
      }
    }

    // I currently don't know how to make the cachingF function run and get its value w/o putting it in a separate method
    private def getCacheValue(creds: Creds): Try[CacheVal] = {
      cachingF(creds.id)(ttl = None) {
        for {
          userVal <- getId(creds, UsersTQ.getPassword(creds.id).result, CacheIdType.User, None)
          nodeVal <- getId(creds, NodesTQ.getToken(creds.id).result, CacheIdType.Node, userVal)
          cacheVal <- getId(creds, AgbotsTQ.getToken(creds.id).result, CacheIdType.Agbot, nodeVal, last = true)
        } yield cacheVal.get
      }
    }

    // Get the id of this type from the db, if there
    private def getId(creds: Creds, dbAction: DBIO[Seq[String]], idType: CacheIdType, cacheVal: Option[CacheVal], last: Boolean = false): Try[Option[CacheVal]] = {
      if (cacheVal.isDefined) return Success(cacheVal)
      logger.debug("CacheId:getId(): "+creds.id+" was not in the cache, so attempting to get it from the db")
      //val dbAction = NodesTQ.getToken(id).result
      val dbHashedTok: String = try {
        //logger.trace("awaiting for DB query of local exchange creds for "+id+"...")
        val respVector = Await.result(db.run(dbAction), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
        //logger.trace("...back from awaiting for DB query of local exchange creds for "+id+".")
        if (respVector.nonEmpty) respVector.head else ""
      } catch {
        // Handle db problems
        case timeout: java.util.concurrent.TimeoutException => logger.error("db timed out getting pw/token for '"+creds.id+"' . "+timeout.getMessage)
          throw new DbTimeoutException(ExchangeMessage.translateMessage("db.timeout.getting.token", creds.id, timeout.getMessage))
        case other: Throwable => logger.error("db connection error getting pw/token for '"+creds.id+"': "+other.getMessage)
          throw new DbConnectionException(ExchangeMessage.translateMessage("db.threw.exception", other.getMessage))
      }   // end of getting dbHashedTok

      if (dbHashedTok == "") {
        if (last) return Failure(new IdNotFoundException)
        else return Success(None) // not finding it isn't an error, try the next id type
      }
      // We found this id in the db. If the user-specified creds are valid, add the unhashed token to the cache entry
      if (creds.token!="" && Password.check(creds.token, dbHashedTok)) {
        logger.debug("CacheId:getId(): " + creds.id + " found in the db and creds are valid, adding both to the cache")
        // fast-bcrypt the token
        Success(Some(CacheVal(dbHashedTok, Password.fastHash(creds.token), idType))) // we only get the hashed tok from the db, the unhashed will be added by getValidType()
      } else {
        logger.debug("CacheId:getId(): " + creds.id + " found in the db (but creds are not valid), adding db entry to the cache")
        Success(Some(CacheVal(dbHashedTok, "", idType))) // we only get the hashed token from the db
        // In this case the cache value won't have the unhashed token, because the client didn't give us the right one. Until this entry
        // expires from the cache, we will have to do the slower bcrypt check against this entry up in getValidType()
      }
    }

    // Called for temp token creation/validation. Note: this method just gets the hashed pw, it doesn't check it against provided creds
    def getOne(id: String): Option[String] = {
      val cacheValue = getCacheValue(Creds(id,""))
      if (cacheValue.isSuccess) Some(cacheValue.get.hashedToken)
      else None
    }

    // we need these for the test suites, but in production it will only help in this 1 exchange instance
    def putUser(id: String, hashedPw: String, unhashedPw: String): Unit = {
      val fastHash = if (unhashedPw != "") Password.fastHash(unhashedPw) else ""
      put(id)(CacheVal(hashedPw, fastHash, CacheIdType.User))
    }
    def putNode(id: String, hashedTok: String, unhashedTok: String): Unit = {
      val fastHash = if (unhashedTok != "") Password.fastHash(unhashedTok) else ""
      put(id)(CacheVal(hashedTok, fastHash, CacheIdType.Node))
    }
    def putAgbot(id: String, hashedTok: String, unhashedTok: String): Unit = {
      val fastHash = if (unhashedTok != "") Password.fastHash(unhashedTok) else ""
      put(id)(CacheVal(hashedTok, fastHash, CacheIdType.Agbot))
    }

    def removeOne(id: String): Try[Any] = { remove(id) }

    def clearCache(): Try[Unit] = {
      logger.debug("Clearing the id cache")
      removeAll().map(_ => ())

      // Put the root id/pw back in the cache, so we are never left not being able to do anything to the exchange

    }
  }   // end of class CacheId


  /** Holds isAdmin or isPublic, or maybe other single boolean values */
  abstract class CacheBoolean(val attrName: String, val maxSize: Int) {
    // For this cache the key is the id (already prefixed with the org) and the value is a boolean

    private val guavaCache = CacheBuilder.newBuilder()
      .maximumSize(maxSize)
      .expireAfterWrite(ExchConfig.getInt("api.cache.resourcesTtlSeconds"), TimeUnit.SECONDS)
      .build[String, Entry[Boolean]]     // the cache key is org/id, and the value is admin priv or isPublic
    implicit val userCache = GuavaCache(guavaCache)   // needed so ScalaCache API can find it. Another effect of this is that these methods don't need to be qualified
    private var db: Database = _

    def init(db: Database): Unit = { this.db = db }

    def getDbAction(id: String): DBIO[Seq[Boolean]]

    // I currently don't know how to make the cachingF function run and get its value w/o putting it in a separate method
    private def getCacheValue(id: String): Try[Boolean] = {
      cachingF(id)(ttl = None) {
        for {
          userVal <- getId(id)
        } yield userVal
      }
    }

    // Called when this user id isn't in the cache. Gets the user from the db and puts the boolean value in the cache.
    private def getId(id: String): Try[Boolean] = {
      logger.debug("CacheBoolean:getId(): "+id+" was not in the cache, so attempting to get it from the db")
      //val dbAction = UsersTQ.getAdmin(id).result
      try {
        //logger.trace("CacheBoolean:getId(): awaiting for DB query of local exchange bool value for "+id+"...")
        val respVector = Await.result(db.run(getDbAction(id)), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
        //logger.trace("CacheBoolean:getId(): ...back from awaiting for DB query of local exchange bool value for "+id+".")
        if (respVector.nonEmpty) {
          val isValue = respVector.head
          logger.debug("CacheBoolean:getId(): "+id+" found in the db, adding it with value "+isValue+" to the cache")
          Success(isValue)
        }
        else Failure(new IdNotFoundException)
      } catch {
        // Handle db problems
        case timeout: java.util.concurrent.TimeoutException => logger.error("db timed out getting "+attrName+" boolean for '"+id+"' . "+timeout.getMessage)
          throw new DbTimeoutException(ExchangeMessage.translateMessage("db.timeout.getting.bool", attrName, id, timeout.getMessage))
        case other: Throwable => logger.error("db connection error getting "+attrName+" boolean for '"+id+"': "+other.getMessage)
          throw new DbConnectionException(ExchangeMessage.translateMessage("db.threw.exception", other.getMessage))
      }
    }

    def getOne(id: String): Option[Boolean] = {
      val cacheValue = getCacheValue(id)
      if (cacheValue.isSuccess) Some(cacheValue.get)
      else None
    }

    def putOne(id: String, isValue: Boolean): Unit = { put(id)(isValue) }    // we need this for the test suites, but in production it will only help in this 1 exchange instance

    def removeOne(id: String): Try[Any] = { remove(id) }

    def clearCache(): Try[Unit] = {
      logger.debug("Clearing the "+attrName+" cache")
      removeAll().map(_ => ())
    }
  }   // end of class CacheBoolean

  class CacheAdmin() extends CacheBoolean("admin", ExchConfig.getInt("api.cache.resourcesMaxSize")) {
    def getDbAction(id: String): DBIO[Seq[Boolean]] = UsersTQ.getAdmin(id).result
  }

  class CachePublicService() extends CacheBoolean("public", ExchConfig.getInt("api.cache.resourcesMaxSize")) {
    def getDbAction(id: String): DBIO[Seq[Boolean]] = ServicesTQ.getPublic(id).result
  }

  class CachePublicPattern() extends CacheBoolean("public", ExchConfig.getInt("api.cache.resourcesMaxSize")) {
    def getDbAction(id: String): DBIO[Seq[Boolean]] = PatternsTQ.getPublic(id).result
  }

  // Currently, business policies are never allowd to be public, so always return false
  class CachePublicBusiness() extends CacheBoolean("public", 1) {
    def getDbAction(id: String): DBIO[Seq[Boolean]] = DBIO.successful(Seq())
    override def getOne(id: String): Option[Boolean] = Some(false)
    override def putOne(id: String, isValue: Boolean): Unit = {}
    override def removeOne(id: String): Try[Any] = Try(true)
  }


  /** Holds the owner for this resource */
  abstract class CacheOwner(val maxSize: Int) {
    // For this cache the key is the id (already prefixed with the org) and the value is the owner

    private val guavaCache = CacheBuilder.newBuilder()
      .maximumSize(maxSize)
      .expireAfterWrite(ExchConfig.getInt("api.cache.resourcesTtlSeconds"), TimeUnit.SECONDS)
      .build[String, Entry[String]]     // the cache key is org/id, and the value is the owner
    implicit val userCache = GuavaCache(guavaCache)   // needed so ScalaCache API can find it. Another effect of this is that these methods don't need to be qualified
    private var db: Database = _

    def init(db: Database): Unit = { this.db = db }

    def getDbAction(id: String): DBIO[Seq[String]]

    // I currently don't know how to make the cachingF function run and get its value w/o putting it in a separate method
    private def getCacheValue(id: String): Try[String] = {
      cachingF(id)(ttl = None) {
        for {
          userVal <- getId(id)
        } yield userVal
      }
    }

    // Called when this id isn't in the cache. Gets the id from the db and puts the owner in the cache.
    private def getId(id: String): Try[String] = {
      logger.debug("CacheOwner:getId(): "+id+" was not in the cache, so attempting to get it from the db")
      try {
        //logger.trace("CacheOwner:getId(): awaiting for DB query of local exchange admin value for "+id+"...")
        val respVector = Await.result(db.run(getDbAction(id)), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
        //logger.trace("CacheOwner:getId(): ...back from awaiting for DB query of local exchange admin value for "+id+".")
        if (respVector.nonEmpty) {
          val owner = respVector.head
          logger.debug("CacheOwner:getId(): "+id+" found in the db, adding it with value "+owner+" to the cache")
          Success(owner)
        }
        else Failure(new IdNotFoundException)
      } catch {
        // Handle db problems
        case timeout: java.util.concurrent.TimeoutException => logger.error("db timed out getting owner for '"+id+"' . "+timeout.getMessage)
          throw new DbTimeoutException(ExchangeMessage.translateMessage("db.timeout.getting.owner", id, timeout.getMessage))
        case other: Throwable => logger.error("db connection error getting owner for '"+id+"': "+other.getMessage)
          throw new DbConnectionException(ExchangeMessage.translateMessage("db.threw.exception", other.getMessage))
      }
    }

    def getOne(id: String): Option[String] = {
      val cacheValue = getCacheValue(id)
      if (cacheValue.isSuccess) Some(cacheValue.get)
      else None
    }

    def putOne(id: String, owner: String): Unit = { if (owner != "") put(id)(owner) }    // we need this for the test suites, but in production it will only help in this 1 exchange instance

    def removeOne(id: String): Try[Any] = { remove(id) }

    def clearCache(): Try[Unit] = {
      logger.debug("Clearing the admin cache")
      removeAll().map(_ => ())
    }
  }   // end of class CacheOwner

  class CacheOwnerNode() extends CacheOwner(ExchConfig.getInt("api.cache.idsMaxSize")) {
    def getDbAction(id: String): DBIO[Seq[String]] = NodesTQ.getOwner(id).result
  }

  class CacheOwnerAgbot() extends CacheOwner(ExchConfig.getInt("api.cache.resourcesMaxSize")) {
    def getDbAction(id: String): DBIO[Seq[String]] = AgbotsTQ.getOwner(id).result
  }

  class CacheOwnerService() extends CacheOwner(ExchConfig.getInt("api.cache.resourcesMaxSize")) {
    def getDbAction(id: String): DBIO[Seq[String]] = ServicesTQ.getOwner(id).result
  }

  class CacheOwnerPattern() extends CacheOwner(ExchConfig.getInt("api.cache.resourcesMaxSize")) {
    def getDbAction(id: String): DBIO[Seq[String]] = PatternsTQ.getOwner(id).result
  }

  class CacheOwnerBusiness() extends CacheOwner(ExchConfig.getInt("api.cache.resourcesMaxSize")) {
    def getDbAction(id: String): DBIO[Seq[String]] = BusinessPoliciesTQ.getOwner(id).result
  }



  // These methods allow us to use either the new or old cache
  def useNew: Boolean = {
    if (cacheType == "") logger.error("Using the cache before the type has been initialized. Defaulting to guava.")
    return cacheType != "custom"
  }

  def getUser(id: String): Option[String] = {
    if (useNew) {
      ids.getOne(id)
    } else {
      val tokens = users.get(id)
      if (tokens.isDefined) Some(tokens.get.hashed) else None
    }
  }

  def putUser(id: String, hashedPw: String, unhashedPw: String): Unit = {
    if (useNew) ids.putUser(id, hashedPw, unhashedPw)
    else users.put(Creds(id, unhashedPw), hashedPw)
  }

  def getUserIsAdmin(id: String): Option[Boolean] = {
    if (useNew) {
      usersAdmin.getOne(id)
    } else {
      users.getOwner(id) match {
        case Some(s) => if (s == "admin") return Some(true) else return Some(false)
        case None => return None
      }
    }
  }

  def putUserIsAdmin(id: String, isAdmin: Boolean): Unit = {
    if (useNew) {
      usersAdmin.putOne(id, isAdmin)
    } else {
      val adminVal = if (isAdmin) "admin" else ""
      users.putOwner(id, adminVal)
    }
  }

  def putUserAndIsAdmin(id: String, hashedPw: String, unhashedPw: String, isAdmin: Boolean): Unit = {
    if (useNew) {
      ids.putUser(id, hashedPw, unhashedPw)
      usersAdmin.putOne(id, isAdmin)
    } else {
      val adminVal = if (isAdmin) "admin" else ""
      users.putBoth(Creds(id, unhashedPw), hashedPw, adminVal)
    }
  }

  def removeUserAndIsAdmin(id: String): Unit = {
    if (useNew) {
      ids.removeOne(id)
      usersAdmin.removeOne(id)
    } else {
      users.removeBoth(id)
    }
  }

  def getNodeOwner(id: String) = {
    if (useNew) nodesOwner.getOne(id)
    else nodes.getOwner(id)
  }

  def putNode(id: String, hashedTok: String, unhashedTok: String): Unit = {
    if (useNew) ids.putNode(id, hashedTok, unhashedTok)
    else nodes.put(Creds(id, unhashedTok), hashedTok)
  }

  def putNodeAndOwner(id: String, hashedTok: String, unhashedTok: String, owner: String): Unit = {
    if (useNew) {
      ids.putNode(id, hashedTok, unhashedTok)
      nodesOwner.putOne(id, owner)
    } else {
      nodes.putBoth(Creds(id, unhashedTok), hashedTok, owner)
    }
  }

  def removeNodeAndOwner(id: String): Unit = {
    if (useNew) {
      ids.removeOne(id)
      nodesOwner.removeOne(id)
    } else {
      nodes.removeBoth(id)
    }
  }

  def getAgbotOwner(id: String) = {
    if (useNew) agbotsOwner.getOne(id)
    else agbots.getOwner(id)
  }

  def putAgbot(id: String, hashedTok: String, unhashedTok: String): Unit = {
    if (useNew) ids.putAgbot(id, hashedTok, unhashedTok)
    else agbots.put(Creds(id, unhashedTok), hashedTok)
  }

  def putAgbotAndOwner(id: String, hashedTok: String, unhashedTok: String, owner: String): Unit = {
    if (useNew) {
      ids.putAgbot(id, hashedTok, unhashedTok)
      agbotsOwner.putOne(id, owner)
    } else {
      agbots.putBoth(Creds(id, unhashedTok), hashedTok, owner)
    }
  }

  def removeAgbotAndOwner(id: String): Unit = {
    if (useNew) {
      ids.removeOne(id)
      agbotsOwner.removeOne(id)
    } else {
      agbots.removeBoth(id)
    }
  }

  def getServiceOwner(id: String) = {
    if (useNew) servicesOwner.getOne(id)
    else services.getOwner(id)
  }

  def putServiceOwner(id: String, owner: String) = {
    if (useNew) servicesOwner.putOne(id, owner)
    else services.putOwner(id, owner)
  }

  def removeServiceOwner(id: String) = {
    if (useNew) servicesOwner.removeOne(id)
    else services.removeOwner(id)
  }

  def getServiceIsPublic(id: String) = {
    if (useNew) servicesPublic.getOne(id)
    else services.getIsPublic(id)
  }

  def putServiceIsPublic(id: String, isPublic: Boolean) = {
    if (useNew) servicesPublic.putOne(id, isPublic)
    else services.putIsPublic(id, isPublic)
  }

  def removeServiceIsPublic(id: String) = {
    if (useNew) servicesPublic.removeOne(id)
    else services.removeIsPublic(id)
  }

  def getPatternOwner(id: String) = {
    if (useNew) patternsOwner.getOne(id)
    else patterns.getOwner(id)
  }

  def putPatternOwner(id: String, owner: String) = {
    if (useNew) patternsOwner.putOne(id, owner)
    else patterns.putOwner(id, owner)
  }

  def removePatternOwner(id: String) = {
    if (useNew) patternsOwner.removeOne(id)
    else patterns.removeOwner(id)
  }

  def getPatternIsPublic(id: String) = {
    if (useNew) patternsPublic.getOne(id)
    else patterns.getIsPublic(id)
  }

  def putPatternIsPublic(id: String, isPublic: Boolean) = {
    if (useNew) patternsPublic.putOne(id, isPublic)
    else patterns.putIsPublic(id, isPublic)
  }

  def removePatternIsPublic(id: String) = {
    if (useNew) patternsPublic.removeOne(id)
    else patterns.removeIsPublic(id)
  }

  def getBusinessOwner(id: String) = {
    if (useNew) businessOwner.getOne(id)
    else business.getOwner(id)
  }

  def putBusinessOwner(id: String, owner: String) = {
    if (useNew) businessOwner.putOne(id, owner)
    else business.putOwner(id, owner)
  }

  def removeBusinessOwner(id: String) = {
    if (useNew) businessOwner.removeOne(id)
    else business.removeOwner(id)
  }

  def getBusinessIsPublic(id: String) = {
    if (useNew) businessPublic.getOne(id)
    else business.getIsPublic(id)
  }

  def putBusinessIsPublic(id: String, isPublic: Boolean) = {
    if (useNew) businessPublic.putOne(id, isPublic)
    else business.putIsPublic(id, isPublic)
  }

  def removeBusinessIsPublic(id: String) = {
    if (useNew) businessPublic.removeOne(id)
    else business.removeIsPublic(id)
  }

  def clearAllCaches(includingIbmAuth: Boolean): Unit = {
    if (useNew) {
      ids.clearCache()
      usersAdmin.clearCache()
      nodesOwner.clearCache()
      agbotsOwner.clearCache()
      servicesOwner.clearCache()
      patternsOwner.clearCache()
      businessOwner.clearCache()
      servicesPublic.clearCache()
      patternsPublic.clearCache()
      businessPublic.clearCache()
      if (includingIbmAuth) IbmCloudAuth.clearCache()
    } else {
      users.removeAll()
      nodes.removeAll()
      agbots.removeAll()
      services.removeAll()
      patterns.removeAll()
      business.removeAll()
      if (includingIbmAuth) IbmCloudAuth.clearCache()
    }
  }

  def initAllCaches(db: Database, includingIbmAuth: Boolean): Unit = {
    if (useNew) {
      ExchConfig.createRoot(db)
      ids.init(db)
      usersAdmin.init(db)
      nodesOwner.init(db)
      agbotsOwner.init(db)
      servicesOwner.init(db)
      patternsOwner.init(db)
      businessOwner.init(db)
      servicesPublic.init(db)
      patternsPublic.init(db)
      businessPublic.init(db)
      if (includingIbmAuth) IbmCloudAuth.init(db)
    } else {
      ExchConfig.createRoot(db)
      users.init(db)
      nodes.init(db)
      agbots.init(db)
      services.init(db)
      patterns.init(db)
      business.init(db)
      if (includingIbmAuth) IbmCloudAuth.init(db)
    }
  }

  // Note: when you add a cache here, also add it to the 2 methods above
  val ids = new CacheId()
  val usersAdmin = new CacheAdmin()
  val nodesOwner = new CacheOwnerNode()
  val agbotsOwner = new CacheOwnerAgbot()
  val servicesOwner = new CacheOwnerService()
  val patternsOwner = new CacheOwnerPattern()
  val businessOwner = new CacheOwnerBusiness()
  val servicesPublic = new CachePublicService()
  val patternsPublic = new CachePublicPattern()
  val businessPublic = new CachePublicBusiness()

  // Old home-grown cache
  val users = new Cache("users")
  val nodes = new Cache("nodes")
  val agbots = new Cache("agbots")
  val services = new Cache("services")
  val patterns = new Cache("patterns")
  val business = new Cache("business")

  /** Old home-grown cache. 1 set of things (user/pw, node id/token, agbot id/token, service/owner, pattern/owner) */
  class Cache(val whichTab: String) {     //TODO: i am sure there is a better way to handle the different tables
    // Throughout the implementation of this class, id and token are used generically, meaning in the case of users they are user and pw.
    // Our goal is for the token to be unhashed, but we have to handle the case where the user gives us an already hashed token.
    // In this case, turn it into an unhashed token the 1st time they have a successful check against it with an unhashed token.

    // The unhashed and hashed values of the token are not always both set, but if they are they are in sync.
    case class Tokens(unhashed: String, hashed: String)

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
        case Some(cacheToks) => ;
          try {
            /*if (Password.isHashed(creds.token)) return creds.token == cacheToks.hashed
            else { */     // specified token is unhashed
              if (cacheToks.unhashed != "") return creds.token == cacheToks.unhashed
              else {    // the specified token is unhashed, but we do not have the unhashed token in our cache yet
                if (Password.check(creds.token, cacheToks.hashed)) {
                  // now we have the unhashed version of the token so update our cache with that
                  //logger.debug("updating auth cache with unhashed pw/token for '"+creds.id+"'")
                  _put(creds.id, Tokens(creds.token, cacheToks.hashed))
                  true
                } else false
              }
            //}
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

    /** Cache this id/token (or user/pw) pair. Token in creds is unhashed */
    def put(creds: Creds, hashedTok: String): Unit = {
      // Normally overwrite the current cached pw with this new one, unless the new one is blank.
      // But we need to handle the special case: the new pw is hashed, the old one is not, and they match
      // Note: Password.check() can throw 'IllegalArgumentException: Invalid salt version', but we intentially let that bubble up
      if (creds.token == "" && hashedTok == "") return
      _put(creds.id, Tokens(creds.token, hashedTok))
      /* _get(creds.id) match {
        case Some(cacheToks) =>  if (Password.isHashed(creds.token)) {
          val unhashed = if (Password.check(cacheToks.unhashed, creds.token)) cacheToks.unhashed else ""    // if our clear tok is wrong, blank it out
          if (creds.token != cacheToks.hashed) _put(creds.id, Tokens(unhashed, creds.token))
        } else {      // they gave us a clear token
          val hashed = if (Password.check(creds.token, cacheToks.hashed)) cacheToks.hashed else Password.hash(creds.token)    // if our hashed tok is wrong, blank it out
          if (creds.token != cacheToks.unhashed) _put(creds.id, Tokens(creds.token, hashed))
        }
        case None => val tokens: Tokens = if (Password.isHashed(creds.token)) Tokens("", creds.token) else Tokens(creds.token, hashedTok)
          _put(creds.id, tokens)
      } */
    }

    def putOwner(id: String, owner: String): Unit = { /*logger.trace("putOwner for "+id+": "+owner); */ if (owner != "") _putOwner(id, owner) }
    def putBoth(creds: Creds, hashedTok: String, owner: String): Unit = { put(creds, hashedTok); putOwner(creds.id, owner) }
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
}
