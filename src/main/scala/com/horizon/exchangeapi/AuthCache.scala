package com.horizon.exchangeapi

//import java.util.Base64

//import com.horizon.exchangeapi.auth.{AuthErrors, ExchCallbackHandler, PermissionCheck}
//import com.horizon.exchangeapi.auth.PermissionCheck
import com.horizon.exchangeapi.tables._
//import javax.security.auth.Subject
//import javax.security.auth.login.LoginContext
//import javax.servlet.http.HttpServletRequest
//import org.mindrot.jbcrypt.BCrypt
//import org.scalatra.servlet.ServletApiImplicits
//import org.scalatra.{Control, Params /* , ScalatraBase */ }
import org.slf4j.LoggerFactory
//import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import slick.jdbc.PostgresProfile.api._

//import scala.collection.JavaConverters._
import scala.collection.mutable.{HashMap => MutableHashMap /* , Set => MutableSet */ }
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
//import scala.util._
//import scala.util.control.NonFatal

/** In-memory cache of the user/pw, node id/token, and agbot id/token, where the pw and tokens are not hashed to speed up validation */
object AuthCache {
  val logger = LoggerFactory.getLogger(ExchConfig.LOGGER)

  /** 1 set of things (user/pw, node id/token, agbot id/token, service/owner, pattern/owner) */
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
        case "resources" => db.run(ResourcesTQ.rows.map(x => (x.resource, x.owner, x.public)).result).map({ list => this._initResources(list) })
        case "services" => db.run(ServicesTQ.rows.map(x => (x.service, x.owner, x.public)).result).map({ list => this._initServices(list) })
        case "patterns" => db.run(PatternsTQ.rows.map(x => (x.pattern, x.owner, x.public)).result).map({ list => this._initPatterns(list) })
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

    /** Put owners of resources in the cache */
    def _initResources(credList: Seq[(String,String,Boolean)]): Unit = {
      for ((resource,owner,isPub) <- credList) {
        if (owner != "") _putOwner(resource, owner)
        _putIsPublic(resource, isPub)
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
      //todo: this db access should go at the beginning of every rest api db access, using flatmap to move on to the db access the rest api is really for
      val dbHashedTok: String = try {
        val tokVector = Await.result(db.run(a), Duration(3000, MILLISECONDS))
        if (tokVector.nonEmpty) tokVector.head else ""
      } catch {
        //TODO: this seems to happen sometimes when my laptop has been asleep. Maybe it has to reconnect to the db.
        //      Until i get a better handle on this, use the cache token when this happens.
        case _: java.util.concurrent.TimeoutException => logger.error("getting hashed pw/token for '"+id+"' timed out. Using the cache for now.")
          return _get(id)
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
                  //logger.debug("updating auth cache with unhashed pw/token for '"+creds.id+"'")
                  _put(creds.id, Tokens(creds.token, tokens.hashed))
                  true
                } else false
              }
            }
          } catch { case _: Exception => logger.error("Invalid salt version error from Password.check()"); false }   // can throw IllegalArgumentException: Invalid salt version
        case None => false
      }
    }

    /** Returns Some(owner) from the cache for this id (but verifies with the db 1st), or None if does not exist */
    def getOwner(id: String): Option[String] = {
      // logger.trace("getOwners owners: "+owners.toString)
      //if (whichTable == "users") return None      // we never actually call this

      // Even though we try to put every new/updated owner in our cache, when this server runs in multi-node mode,
      // an update could have come to 1 of the other nodes. The db is our sync point, so always verify our cached owner with the db owner.
      // We are doing this only so we can fall back to the cache's last known owner if the Await.result() times out.
      try {
        if (whichTable == "users") {
          val ownerVector = Await.result(db.run(UsersTQ.getAdmin(id).result), Duration(3000, MILLISECONDS))
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
            case "resources" => ResourcesTQ.getOwner(id).result
            case "services" => ServicesTQ.getOwner(id).result
            case "patterns" => PatternsTQ.getOwner(id).result
          }
          val ownerVector = Await.result(db.run(a), Duration(3000, MILLISECONDS))
          if (ownerVector.nonEmpty) /*{ logger.trace("getOwner return: "+ownerVector.head);*/ return Some(ownerVector.head) else /*{ logger.trace("getOwner return: None");*/ return None
        }
      } catch {
        //todo: this seems to happen sometimes when the exchange svr has been idle for a while. Or maybe it is just when i'm running local and my laptop has been asleep.
        //      Until i get a better handle on this, use the cache owner when this happens.
        case _: java.util.concurrent.TimeoutException => logger.error("getting owner for '"+id+"' timed out. Using the cache for now.")
          return _getOwner(id)
      }
    }

    /** Returns Some(isPub) from the cache for this id (but verifies with the db 1st), or None if does not exist */
    def getIsPublic(id: String): Option[Boolean] = {
      // We are doing this only so we can fall back to the cache's last known owner if the Await.result() times out.
      try {
        // For the all the others, we are looking for the traditional owner
        val a = whichTable match {
          case "resources" => ResourcesTQ.getPublic(id).result
          case "services" => ServicesTQ.getPublic(id).result
          case "patterns" => PatternsTQ.getPublic(id).result
          case _ => return Some(false)      // should never get here
        }
        val publicVector = Await.result(db.run(a), Duration(3000, MILLISECONDS))
        if (publicVector.nonEmpty) /*{ logger.trace("getIsPublic return: "+publicVector.head);*/ return Some(publicVector.head) else /*{ logger.trace("getIsPublic return: None");*/ return None
      } catch {
        //      Until i get a better handle on this, use the cache owner when this happens.
        case _: java.util.concurrent.TimeoutException => logger.error("getting public for '"+id+"' timed out. Using the cache for now.")
          return _getIsPublic(id)
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

  val users = new Cache("users")
  val nodes = new Cache("nodes")
  val agbots = new Cache("agbots")
  val resources = new Cache("resources")
  val services = new Cache("services")
  val patterns = new Cache("patterns")
}
