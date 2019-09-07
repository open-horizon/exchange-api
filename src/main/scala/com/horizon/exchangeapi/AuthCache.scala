package com.horizon.exchangeapi

import java.util.concurrent.TimeUnit

import com.horizon.exchangeapi.CacheIdType.CacheIdType
import com.horizon.exchangeapi.tables._
import org.scalatra.servlet.ServletApiImplicits
import org.scalatra.Control
import org.slf4j.LoggerFactory
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
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
  val None = Value("None")
}

/** In-memory cache of the user/pw, node id/token, and agbot id/token, where the pw and tokens are not hashed to speed up validation */
object AuthCache extends Control with ServletApiImplicits {
  val logger = LoggerFactory.getLogger(ExchConfig.LOGGER)

  // The unhashed and hashed values of the token are not always both set, but if they are they are in sync.
  case class Tokens(unhashed: String, hashed: String)

  /* Cache todo:
  - scale test
  - if cache value results in invalid creds or access denied, remove cache entry and try again
   */

  /** Holds recently authenticated users, node ids, agbot ids */
  class CacheId() {
    // For this cache the key is the id (already prefixed with the org) and the value is this class
    case class CacheVal(hashedToken: String, idType: CacheIdType = CacheIdType.None)

    private val guavaCache = CacheBuilder.newBuilder()
      .maximumSize(ExchConfig.getInt("api.cache.idsSize"))
      .expireAfterWrite(ExchConfig.getInt("api.cache.idsTtlSeconds"), TimeUnit.SECONDS)
      .build[String, Entry[CacheVal]]     // the cache key is org/id, and the value is CacheVal
    implicit val userCache = GuavaCache(guavaCache)   // needed so ScalaCache API can find it. Another effect of this is that these methods don't need to be qualified
    private var db: Database = _

    def init(db: Database): Unit = { this.db = db }

    // Try to authenticate the creds and return the type (user/node/agbot) it is, or None
    def getValidType(creds: Creds): CacheIdType = {
      logger.debug("CacheId:getValidType(): attempting to authenticate to the exchange with "+creds)
      val cacheValue = getCacheValue(creds.id)
      if (cacheValue.isFailure) return CacheIdType.None
      // we got the hashed token from the cache or db, now verify the token passed in
      if (Password.check(creds.token, cacheValue.get.hashedToken)) {
        logger.debug("CacheId:getValidType(): successfully matched "+creds.id+" and its pw in the cache/db")
        return cacheValue.get.idType
      } else {
        logger.debug("CacheId:getValidType(): user "+creds.id+" not authenticated in the exchange")
        return CacheIdType.None
      }
    }

    // I currently don't know how to make the cachingF function run and get its value w/o putting it in a separate method
    private def getCacheValue(id: String): Try[CacheVal] = {
      cachingF(id)(ttl = None) {
        for {
          userVal <- getId(id, UsersTQ.getPassword(id).result, CacheIdType.User, None)
          nodeVal <- getId(id, NodesTQ.getToken(id).result, CacheIdType.Node, userVal)
          cacheVal <- getId(id, AgbotsTQ.getToken(id).result, CacheIdType.Agbot, nodeVal, last = true)
        } yield cacheVal.get
      }
    }

    // Get the id of this type from the db, if there
    private def getId(id: String, dbAction: DBIO[Seq[String]], idType: CacheIdType, cacheVal: Option[CacheVal], last: Boolean = false): Try[Option[CacheVal]] = {
      if (cacheVal.isDefined) return Success(cacheVal)
      logger.debug("CacheId:getId(): "+id+" was not in the cache, so attempting to get it from the db")
      //val dbAction = NodesTQ.getToken(id).result
      val dbHashedTok: String = try {
        //logger.trace("awaiting for DB query of local exchange creds for "+id+"...")
        val respVector = Await.result(db.run(dbAction), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
        //logger.trace("...back from awaiting for DB query of local exchange creds for "+id+".")
        if (respVector.nonEmpty) respVector.head else ""
      } catch {
        // Handle db problems
        case timeout: java.util.concurrent.TimeoutException => logger.error("db timed out getting pw/token for '"+id+"' . "+timeout.getMessage)
          throw new DbTimeoutException(ExchangeMessage.translateMessage("db.timeout.getting.token", id, timeout.getMessage))
        case other: Throwable => logger.error("db connection error getting pw/token for '"+id+"': "+other.getMessage)
          throw new DbConnectionException(ExchangeMessage.translateMessage("db.threw.exception", other.getMessage))
      }   // end of getting dbHashedTok

      if (dbHashedTok == "") {
        if (last) return Failure(new IdNotFoundException(ExchangeMessage.translateMessage("id.notfound.db", id)))
        else return Success(None) // not finding it isn't an error, try the next id type
      }
      logger.debug("CacheId:getId(): "+id+" found in the db, adding it to the cache")
      Success(Some(CacheVal(dbHashedTok, idType)))
    }

    // Called for temp token creation/validation
    def getOne(id: String): Option[String] = {
      val cacheValue = getCacheValue(id)
      if (cacheValue.isSuccess) Some(cacheValue.get.hashedToken)
      else None
    }

    // The token passed in is already hashed.
    def putUser(creds: Creds): Unit = { put(creds.id)(CacheVal(creds.token, CacheIdType.User)) }    // we need these for the test suites, but in production it will only help in this 1 exchange instance
    def putNode(creds: Creds): Unit = { put(creds.id)(CacheVal(creds.token, CacheIdType.Node)) }
    def putAgbot(creds: Creds): Unit = { put(creds.id)(CacheVal(creds.token, CacheIdType.Agbot)) }

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
      .expireAfterWrite(ExchConfig.getInt("api.cache.resourceOwnersTtlSeconds"), TimeUnit.SECONDS)
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
        else Failure(new IdNotFoundException(ExchangeMessage.translateMessage("id.notfound.db", id)))
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

  class CacheAdmin() extends CacheBoolean("admin", ExchConfig.getInt("api.cache.resourceOwnersSize")) {
    def getDbAction(id: String): DBIO[Seq[Boolean]] = UsersTQ.getAdmin(id).result
  }

  class CachePublicService() extends CacheBoolean("public", ExchConfig.getInt("api.cache.resourceOwnersSize")) {
    def getDbAction(id: String): DBIO[Seq[Boolean]] = ServicesTQ.getPublic(id).result
  }

  class CachePublicPattern() extends CacheBoolean("public", ExchConfig.getInt("api.cache.resourceOwnersSize")) {
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
      .expireAfterWrite(ExchConfig.getInt("api.cache.resourceOwnersTtlSeconds"), TimeUnit.SECONDS)
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
        else Failure(new IdNotFoundException(ExchangeMessage.translateMessage("id.notfound.db", id)))
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

  class CacheOwnerNode() extends CacheOwner(ExchConfig.getInt("api.cache.idsSize")) {
    def getDbAction(id: String): DBIO[Seq[String]] = NodesTQ.getOwner(id).result
  }

  class CacheOwnerAgbot() extends CacheOwner(ExchConfig.getInt("api.cache.resourceOwnersSize")) {
    def getDbAction(id: String): DBIO[Seq[String]] = AgbotsTQ.getOwner(id).result
  }

  class CacheOwnerService() extends CacheOwner(ExchConfig.getInt("api.cache.resourceOwnersSize")) {
    def getDbAction(id: String): DBIO[Seq[String]] = ServicesTQ.getOwner(id).result
  }

  class CacheOwnerPattern() extends CacheOwner(ExchConfig.getInt("api.cache.resourceOwnersSize")) {
    def getDbAction(id: String): DBIO[Seq[String]] = PatternsTQ.getOwner(id).result
  }

  class CacheOwnerBusiness() extends CacheOwner(ExchConfig.getInt("api.cache.resourceOwnersSize")) {
    def getDbAction(id: String): DBIO[Seq[String]] = BusinessPoliciesTQ.getOwner(id).result
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

/*
  def clearAllCaches(includingIbmAuth: Boolean): Unit = {
    users.removeAll()
    nodes.removeAll()
    agbots.removeAll()
    services.removeAll()
    patterns.removeAll()
    business.removeAll()
    if (includingIbmAuth) IbmCloudAuth.clearCache()
  }

  def initAllCaches(db: Database, includingIbmAuth: Boolean): Unit = {
    ExchConfig.createRoot(db)
    users.init(db)
    nodes.init(db)
    agbots.init(db)
    services.init(db)
    patterns.init(db)
    business.init(db)
    if (includingIbmAuth) IbmCloudAuth.init(db)
  }

  // Note: when you add a cache here, also add it to the 2 methods above
  val users = new Cache("users")
  val nodes = new Cache("nodes")
  val agbots = new Cache("agbots")
  val services = new Cache("services")
  val patterns = new Cache("patterns")
  val business = new Cache("business")
 */
