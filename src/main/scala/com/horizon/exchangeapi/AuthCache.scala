package com.horizon.exchangeapi

import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext

import com.horizon.exchangeapi.CacheIdType.CacheIdType
import com.horizon.exchangeapi.tables._
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration._
import com.horizon.exchangeapi.auth._
import com.google.common.cache.CacheBuilder
import scalacache._
import scalacache.guava.GuavaCache
import scalacache.modes.try_._

import scala.util.{ Failure, Success, Try }

// Enum for type of id in CacheId class
object CacheIdType extends Enumeration {
  type CacheIdType = Value
  val User = Value("User")
  val Node = Value("Node")
  val Agbot = Value("Agbot")
  val None = Value("None")
}

/** In-memory cache of the user/pw, node id/token, and agbot id/token, where the pw and tokens are not hashed to speed up validation */
object AuthCache /* extends Control with ServletApiImplicits */ {
  def logger = ExchangeApi.defaultLogger
  implicit def executionContext: ExecutionContext = ExchangeApi.defaultExecutionContext

  var cacheType = "" // set from the config file by ExchConfig.load(). Note: currently there is no other value besides guava

  // The unhashed and hashed values of the token are not always both set, but if they are they are in sync.
  final case class Tokens(unhashed: String, hashed: String)

  /** Holds recently authenticated users, node ids, agbot ids */
  class CacheId() {
    // For this cache the key is the id (already prefixed with the org) and the value is this class
    // Note: unhashedToken isn't really unhashed, it is just bcrypted with less rounds for speed
    case class CacheVal(hashedToken: String, unhashedToken: String = "", idType: CacheIdType = CacheIdType.None)

    private val guavaCache = CacheBuilder.newBuilder()
      .maximumSize(ExchConfig.getInt("api.cache.idsMaxSize"))
      .expireAfterWrite(ExchConfig.getInt("api.cache.idsTtlSeconds"), TimeUnit.SECONDS)
      .build[String, Entry[CacheVal]] // the cache key is org/id, and the value is CacheVal
    implicit val userCache = GuavaCache(guavaCache) // needed so ScalaCache API can find it. Another effect of this is that these methods don't need to be qualified
    private var db: Database = _

    def init(db: Database): Unit = { this.db = db } // we intentionally don't prime the cache. We let it build on every access so we can add the unhashed token

    // Try to authenticate the creds and return the type (user/node/agbot) it is, or None
    def getValidType(creds: Creds, alreadyRetried: Boolean = false): Try[CacheIdType] = {
      //logger.debug("CacheId:getValidType(): attempting to authenticate to the exchange with " + creds)
      val cacheValue = getCacheValue(creds)
      logger.debug("cacheValue: " + cacheValue)
      cacheValue match {
        case Failure(t) => return Failure(t)  // bubble up the specific failure
        case Success(cacheVal) =>
          // we got the hashed token from the cache or db, now verify the token passed in
          if (cacheVal.unhashedToken != "" && Password.check(creds.token, cacheVal.unhashedToken)) { // much faster than the bcrypt check below
            //logger.debug("CacheId:getValidType(): successfully quick-validated " + creds.id + " and its pw using the cache/db")
            return Success(cacheVal.idType)
          } else if (Password.check(creds.token, cacheVal.hashedToken)) {
            //logger.debug("CacheId:getValidType(): successfully validated " + creds.id + " and its pw using the cache/db")
            return Success(cacheVal.idType)
          } else {
            // the creds were invalid
            if (alreadyRetried) {
              // we already tried clearing the cache and retrying, so give up and return that they were bad creds
              logger.debug("CacheId:getValidType(): user " + creds.id + " not authenticated in the exchange")
              return Success(CacheIdType.None)  // this is distinguished from Failure, because we didn't hit an error trying to access the db, it's just that the creds weren't value
            } else {
              // If we only used a non-expired cache entry to get here, the cache entry could be stale (e.g. they recently changed their pw/token via a different instance of the exchange).
              // So delete the cache entry from the db and try 1 more time
              logger.debug("CacheId:getValidType(): user " + creds.id + " was not authenticated successfully, removing cache entry in case it was stale, and trying 1 more time")
              removeOne(creds.id)
              return getValidType(creds, alreadyRetried = true)
            }
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
      logger.debug(s"CacheId:getId(): ${creds.id} was not in the cache, so attempting to get it from the $idType db table")
      //val dbAction = NodesTQ.getToken(id).result
      val dbHashedTok: String = try {
        //logger.debug("awaiting for DB query of local exchange creds for "+id+"...")
        val respVector = Await.result(db.run(dbAction), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
        //logger.debug("...back from awaiting for DB query of local exchange creds for "+id+".")
        if (respVector.nonEmpty) respVector.head else ""
      } catch {
        // Handle db problems
        case timeout: java.util.concurrent.TimeoutException =>
          logger.error("db timed out getting pw/token for '" + creds.id + "' . " + timeout.getMessage)
          throw new DbTimeoutException(ExchMsg.translate("db.timeout.getting.token", creds.id, timeout.getMessage))
        case other: Throwable =>
          logger.error("db connection error getting pw/token for '" + creds.id + "': " + other.getMessage)
          throw new DbConnectionException(ExchMsg.translate("db.threw.exception", other.getMessage))
      } // end of getting dbHashedTok

      if (dbHashedTok == "") {
        if (last) return Failure(new IdNotFoundException)
        else return Success(None) // not finding it isn't an error, try the next id type
      }
      // We found this id in the db. If the user-specified creds are valid, add the unhashed token to the cache entry
      if (creds.token != "" && Password.check(creds.token, dbHashedTok)) {
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
      val cacheValue = getCacheValue(Creds(id, ""))
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
      //logger.debug("Clearing the id cache")
      removeAll().map(_ => ())

      // Put the root id/pw back in the cache, so we are never left not being able to do anything to the exchange

    }
  } // end of class CacheId

  /** Holds isAdmin or isPublic, or maybe other single boolean values */
  abstract class CacheBoolean(val attrName: String, val maxSize: Int) {
    // For this cache the key is the id (already prefixed with the org) and the value is a boolean

    private val guavaCache = CacheBuilder.newBuilder()
      .maximumSize(maxSize)
      .expireAfterWrite(ExchConfig.getInt("api.cache.resourcesTtlSeconds"), TimeUnit.SECONDS)
      .build[String, Entry[Boolean]] // the cache key is org/id, and the value is admin priv or isPublic
    implicit val userCache = GuavaCache(guavaCache) // needed so ScalaCache API can find it. Another effect of this is that these methods don't need to be qualified
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
      //logger.debug("CacheBoolean:getId(): " + id + " was not in the cache, so attempting to get it from the db")
      //val dbAction = UsersTQ.getAdmin(id).result
      try {
        //logger.debug("CacheBoolean:getId(): awaiting for DB query of local exchange bool value for "+id+"...")
        val respVector = Await.result(db.run(getDbAction(id)), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
        //logger.debug("CacheBoolean:getId(): ...back from awaiting for DB query of local exchange bool value for "+id+".")
        if (respVector.nonEmpty) {
          val isValue = respVector.head
          logger.debug("CacheBoolean:getId(): " + id + " was not in the cache but found in the db, adding it with value " + isValue + " to the cache")
          Success(isValue)
        } else Failure(new IdNotFoundForAuthorizationException)
      } catch {
        // Handle db problems
        case timeout: java.util.concurrent.TimeoutException =>
          logger.error("db timed out getting " + attrName + " boolean for '" + id + "' . " + timeout.getMessage)
          throw new DbTimeoutException(ExchMsg.translate("db.timeout.getting.bool", attrName, id, timeout.getMessage))
        case other: Throwable =>
          logger.error("db connection error getting " + attrName + " boolean for '" + id + "': " + other.getMessage)
          throw new DbConnectionException(ExchMsg.translate("db.threw.exception", other.getMessage))
      }
    }

    def getOne(id: String): Option[Boolean] = {
      val cacheValue = getCacheValue(id)
      if (cacheValue.isSuccess) Some(cacheValue.get)
      else None
    }

    def putOne(id: String, isValue: Boolean): Unit = { put(id)(isValue) } // we need this for the test suites, but in production it will only help in this 1 exchange instance

    def removeOne(id: String): Try[Any] = { remove(id) }

    def clearCache(): Try[Unit] = {
      //logger.debug("Clearing the " + attrName + " cache")
      removeAll().map(_ => ())
    }
  } // end of class CacheBoolean

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
      .build[String, Entry[String]] // the cache key is org/id, and the value is the owner
    implicit val userCache = GuavaCache(guavaCache) // needed so ScalaCache API can find it. Another effect of this is that these methods don't need to be qualified
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
      logger.debug("CacheOwner:getId(): " + id + " was not in the cache, so attempting to get it from the db")
      try {
        //logger.debug("CacheOwner:getId(): awaiting for DB query of local exchange admin value for "+id+"...")
        val respVector = Await.result(db.run(getDbAction(id)), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
        //logger.debug("CacheOwner:getId(): ...back from awaiting for DB query of local exchange admin value for "+id+".")
        if (respVector.nonEmpty) {
          val owner = respVector.head
          logger.debug("CacheOwner:getId(): " + id + " found in the db, adding it with value " + owner + " to the cache")
          Success(owner)
        } else Failure(new IdNotFoundForAuthorizationException)
      } catch {
        // Handle db problems
        case timeout: java.util.concurrent.TimeoutException =>
          logger.error("db timed out getting owner for '" + id + "' . " + timeout.getMessage)
          throw new DbTimeoutException(ExchMsg.translate("db.timeout.getting.owner", id, timeout.getMessage))
        case other: Throwable =>
          logger.error("db connection error getting owner for '" + id + "': " + other.getMessage)
          throw new DbConnectionException(ExchMsg.translate("db.threw.exception", other.getMessage))
      }
    }

    def getOne(id: String): Option[String] = {
      val cacheValue = getCacheValue(id)
      if (cacheValue.isSuccess) Some(cacheValue.get)
      else None
    }

    def putOne(id: String, owner: String): Unit = { if (owner != "") put(id)(owner) } // we need this for the test suites, but in production it will only help in this 1 exchange instance

    def removeOne(id: String): Try[Any] = { remove(id) }

    def clearCache(): Try[Unit] = {
      //logger.debug("Clearing the admin cache")
      removeAll().map(_ => ())
    }
  } // end of class CacheOwner

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

  //perf: These methods were originally here to allow us to use either the new or old cache. We maybe can eliminate them now.
  def getUser(id: String): Option[String] = {
    ids.getOne(id)
  }

  def putUser(id: String, hashedPw: String, unhashedPw: String): Unit = {
    ids.putUser(id, hashedPw, unhashedPw)
  }

  def getUserIsAdmin(id: String): Option[Boolean] = {
    usersAdmin.getOne(id)
  }

  def putUserIsAdmin(id: String, isAdmin: Boolean): Unit = {
    usersAdmin.putOne(id, isAdmin)
  }

  def putUserAndIsAdmin(id: String, hashedPw: String, unhashedPw: String, isAdmin: Boolean): Unit = {
    ids.putUser(id, hashedPw, unhashedPw)
    usersAdmin.putOne(id, isAdmin)
  }

  def removeUserAndIsAdmin(id: String): Unit = {
    ids.removeOne(id)
    usersAdmin.removeOne(id)
  }

  def getNodeOwner(id: String) = {
    nodesOwner.getOne(id)
  }

  def putNode(id: String, hashedTok: String, unhashedTok: String): Unit = {
    ids.putNode(id, hashedTok, unhashedTok)
  }

  def putNodeAndOwner(id: String, hashedTok: String, unhashedTok: String, owner: String): Unit = {
    ids.putNode(id, hashedTok, unhashedTok)
    nodesOwner.putOne(id, owner)
  }

  def removeNodeAndOwner(id: String): Unit = {
    ids.removeOne(id)
    nodesOwner.removeOne(id)
  }

  def getAgbotOwner(id: String) = {
    agbotsOwner.getOne(id)
  }

  def putAgbot(id: String, hashedTok: String, unhashedTok: String): Unit = {
    ids.putAgbot(id, hashedTok, unhashedTok)
  }

  def putAgbotAndOwner(id: String, hashedTok: String, unhashedTok: String, owner: String): Unit = {
    ids.putAgbot(id, hashedTok, unhashedTok)
    agbotsOwner.putOne(id, owner)
  }

  def removeAgbotAndOwner(id: String): Unit = {
    ids.removeOne(id)
    agbotsOwner.removeOne(id)
  }

  def getServiceOwner(id: String) = {
    servicesOwner.getOne(id)
  }

  def putServiceOwner(id: String, owner: String) = {
    servicesOwner.putOne(id, owner)
  }

  def removeServiceOwner(id: String) = {
    servicesOwner.removeOne(id)
  }

  def getServiceIsPublic(id: String) = {
    servicesPublic.getOne(id)
  }

  def putServiceIsPublic(id: String, isPublic: Boolean) = {
    servicesPublic.putOne(id, isPublic)
  }

  def removeServiceIsPublic(id: String) = {
    servicesPublic.removeOne(id)
  }

  def getPatternOwner(id: String) = {
    patternsOwner.getOne(id)
  }

  def putPatternOwner(id: String, owner: String) = {
    patternsOwner.putOne(id, owner)
  }

  def removePatternOwner(id: String) = {
    patternsOwner.removeOne(id)
  }

  def getPatternIsPublic(id: String) = {
    patternsPublic.getOne(id)
  }

  def putPatternIsPublic(id: String, isPublic: Boolean) = {
    patternsPublic.putOne(id, isPublic)
  }

  def removePatternIsPublic(id: String) = {
    patternsPublic.removeOne(id)
  }

  def getBusinessOwner(id: String) = {
    businessOwner.getOne(id)
  }

  def putBusinessOwner(id: String, owner: String) = {
    businessOwner.putOne(id, owner)
  }

  def removeBusinessOwner(id: String) = {
    businessOwner.removeOne(id)
  }

  def getBusinessIsPublic(id: String) = {
    businessPublic.getOne(id)
  }

  def putBusinessIsPublic(id: String, isPublic: Boolean) = {
    businessPublic.putOne(id, isPublic)
  }

  def removeBusinessIsPublic(id: String) = {
    businessPublic.removeOne(id)
  }

  def clearAllCaches(includingIbmAuth: Boolean): Unit = {
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
  }

  def initAllCaches(db: Database, includingIbmAuth: Boolean): Unit = {
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
}
