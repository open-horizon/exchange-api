/** Helper classes for the exchange api rest methods, including some of the common case classes used by the api. */
package com.horizon.exchangeapi

import java.io.File
import java.time._

import akka.event.LoggingAdapter
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server._
import com.horizon.exchangeapi.tables.{OrgRow, UserRow}
import com.osinka.i18n.{Lang, Messages}
import com.typesafe.config._
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.util._
import java.util.{Base64, Properties}

//import ch.qos.logback.classic.Level
import com.horizon.exchangeapi.auth.AuthException

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import org.json4s._
//import org.json4s.{DefaultFormats, JValue}
//import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write

/** HTTP codes, taken from https://en.wikipedia.org/wiki/List_of_HTTP_status_codes and https://www.restapitutorial.com/httpstatuscodes.html */
object HttpCode {
  /* Now using the akka StatusCodes instead
  val OK = 200
  val PUT_OK = 201
  val POST_OK = 201
  val DELETED = 204 // technically means no content, but usually used for DELETE
  val BAD_INPUT = 400 // invalid user input, usually in the params or json body
  val BADCREDS = 401 // user/pw or id/token is wrong (they call it unauthorized, but it is really unauthenticated)
  val ACCESS_DENIED = 403 // do not have authorization to access this resource
  val ALREADY_EXISTS = 403 // trying to create a resource that already exists. For now using 403 (forbidden), but could also use 409 (conflict)
  val ALREADY_EXISTS2 = 409 // trying to create a resource that already exists (409 means conflict)
  val NOT_FOUND = 404 // resource not found
  val INTERNAL_ERROR = 500
  val NOT_IMPLEMENTED = 501
  val BAD_GW = 502 // bad gateway, which for us means db connection error or jetty refused connection
  val GW_TIMEOUT = 504 */ // gateway timeout, which for us means db timeout
  val OK = StatusCodes.OK
  val PUT_OK = StatusCodes.Created
  val POST_OK = StatusCodes.Created
  val DELETED = StatusCodes.NoContent // technically means no content, but usually used for DELETE
  val BAD_INPUT = StatusCodes.BadRequest // invalid user input, usually in the params or json body
  val BADCREDS = StatusCodes.Unauthorized // user/pw or id/token is wrong (they call it unauthorized, but it is really unauthenticated)
  val ACCESS_DENIED = StatusCodes.Forbidden // do not have authorization to access this resource
  val ALREADY_EXISTS = StatusCodes.Forbidden // trying to create a resource that already exists. For now using 403 (forbidden), but could also use 409 (conflict)
  val ALREADY_EXISTS2 = StatusCodes.Conflict // trying to create a resource that already exists (409 means conflict)
  val NOT_FOUND = StatusCodes.NotFound // resource not found
  val INTERNAL_ERROR = StatusCodes.InternalServerError
  val NOT_IMPLEMENTED = StatusCodes.NotImplemented
  val BAD_GW = StatusCodes.BadGateway // bad gateway, which for us means db connection error or jetty refused connection
  val GW_TIMEOUT = StatusCodes.GatewayTimeout // gateway timeout, which for us means db timeout
}

/** These are used as the response structure for most PUTs, POSTs, and DELETEs. */
case class ApiResponse(code: String, msg: String)
object ApiRespType {
  val BADCREDS = ExchMsg.translate("api.bad.creds")
  val ACCESS_DENIED = ExchMsg.translate("api.access.denied")
  val ALREADY_EXISTS = ExchMsg.translate("api.already.exists")
  val BAD_INPUT = ExchMsg.translate("api.invalid.input")
  val NOT_FOUND = ExchMsg.translate("api.not.found")
  val INTERNAL_ERROR = ExchMsg.translate("api.internal.error")
  val NOT_IMPLEMENTED = ExchMsg.translate("api.not.implemented")
  val BAD_GW = ExchMsg.translate("api.db.connection.error")
  val GW_TIMEOUT = ExchMsg.translate("api.db.timeout")
  val ERROR = ExchMsg.translate("error")
  val WARNING = ExchMsg.translate("warning")
  val INFO = ExchMsg.translate("info")
  val OK = ExchMsg.translate("ok")
  val TOO_BUSY = ExchMsg.translate("too.busy")
}

object ResourceChangeConfig {
  val CREATED = "created"
  val CREATEDMODIFIED = "created/modified"
  val MODIFIED = "modified"
  val DELETED = "deleted"
}

trait ExchangeRejection extends Rejection {
  private implicit val formats = DefaultFormats

  def httpCode: StatusCode
  def apiRespCode: String
  def apiRespMsg: String
  def toApiResp = ApiResponse(apiRespCode, apiRespMsg)
  def toJsonStr = write(ApiResponse(apiRespCode, apiRespMsg))
}

// Converts an exception into an auth rejection
final case class AuthRejection(t: Throwable) extends ExchangeRejection {
  def httpCode = t match {
    case e: AuthException => e.httpCode
    case _ => StatusCodes.Unauthorized // should never get here
  }
  def apiRespCode = t match {
    case e: AuthException => e.apiResponse
    case _ => "invalid-credentials" // should never get here
  }
  def apiRespMsg = t match {
    case e: AuthException => e.getMessage
    case _ => "invalid credentials" // should never get here
  }
}

final case class BadCredsRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode = StatusCodes.Unauthorized
  def apiRespCode = ApiRespType.BADCREDS
}

final case class BadInputRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode = StatusCodes.BadRequest
  def apiRespCode = ApiRespType.BAD_INPUT
}

final case class AccessDeniedRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode = StatusCodes.Forbidden
  def apiRespCode = ApiRespType.ACCESS_DENIED
}

final case class AlreadyExistsRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode = StatusCodes.Forbidden
  def apiRespCode = ApiRespType.ALREADY_EXISTS
}

final case class AlreadyExists2Rejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode = StatusCodes.Conflict
  def apiRespCode = ApiRespType.ALREADY_EXISTS
}

final case class NotFoundRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode = StatusCodes.NotFound
  def apiRespCode = ApiRespType.NOT_FOUND
}

final case class BadGwRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode = StatusCodes.BadGateway
  def apiRespCode = ApiRespType.BAD_GW
}

final case class GwTimeoutRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode = StatusCodes.GatewayTimeout
  def apiRespCode = ApiRespType.GW_TIMEOUT
}

// Returns a msg from the translated files, with the args substituted
object ExchMsg {
  def translate(key: String, args: Any*): String = {
    try {
      implicit val userLang = Lang(sys.env.getOrElse("HZN_EXCHANGE_LANG", sys.env.getOrElse("LANG", "en")))
      if (args.nonEmpty) {
        return Messages(key, args: _*)
      }
      Messages(key)
    } catch {
      case e: Exception => s"message key '$key' not found in the messages file: ${e.getMessage}"
    }
  }
}

object LogLevel {
  val OFF = "OFF"
  val ERROR = "ERROR"
  val WARN = "WARN"
  val INFO = "INFO"
  val DEBUG = "DEBUG"
  val validLevels = Set(OFF, ERROR, WARN, INFO, DEBUG)
}

/** Global config parameters for the exchange. See typesafe config classes: http://typesafehub.github.io/config/latest/api/ */
object ExchConfig {
  val configResourceName = "config.json"
  val configFileName = "/etc/horizon/exchange/" + configResourceName
  // The syntax called CONF is typesafe's superset of json that allows comments, etc. See https://github.com/typesafehub/config#using-hocon-the-json-superset. Strict json would be ConfigSyntax.JSON.
  val configOpts = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF).setAllowMissing(false)
  var config = ConfigFactory.parseResources(configResourceName, configOpts) // these are the default values, this file is bundled in the jar

  var defaultLogger: LoggingAdapter = _ // this gets set early by ExchangeApiApp
  def logger = defaultLogger

  var rootHashedPw = "" // so we can remember the hashed pw between load() and createRoot()

  // Tries to load the user's external config file
  // Note: logger doesn't have a valid value at the time of this call
  def load(): Unit = {
    val f = new File(configFileName)
    if (f.isFile) { // checks if it exists and is a regular file
      config = ConfigFactory.parseFile(f, configOpts).withFallback(config) // uses the defaults for anything not specified in the external config file
      println("Using config file " + configFileName)
    } else {
      println("Config file " + configFileName + " not found. Running with defaults suitable for local development.")
    }

    // Read the ACLs and set them in our Role object
    val roles = config.getObject("api.acls").asScala.toMap
    for ((role, _) <- roles) {
      val accessSet = getStringList("api.acls." + role).toSet
      if (!Role.isValidAcessValues(accessSet)) println("Error: invalid value in ACLs in config file for role " + role)
      else Role.setRole(role, accessSet)
    }
    println(s"Roles: ${Role.roles}")
    if (!Role.haveRequiredRoles) println("Error: at least these roles must be set in the config file: " + AuthRoles.requiredRoles.mkString(", "))

    // Note: currently there is no other value besides guava
    AuthCache.cacheType = config.getString("api.cache.type") // need to do this before using the cache in the next step
  }

  def getLogLevel = {
    val loglev = config.getString("api.logging.level")
    if (loglev == "") LogLevel.INFO // default
    else if (LogLevel.validLevels.contains(loglev)) loglev
    else {
      println("Invalid logging level '" + loglev + "' specified in config.json. Continuing with the default logging level " + LogLevel.INFO + ".")
      LogLevel.INFO // fallback
    }
  }

  // Put the root user in the auth cache in case the db has not been inited yet and they need to be able to run POST /admin/initdb
  def createRootInCache(): Unit = {
    val rootpw = config.getString("api.root.password")
    val rootIsEnabled = config.getBoolean("api.root.enabled")
    if (rootpw == "" || !rootIsEnabled) {
      logger.warning("Root password is not specified in config.json or is not enabled. You will not be able to do exchange operations that require root privilege.")
      return
    }
    if (rootHashedPw == "") {
      // this is the 1st time, we need to hash and save it
      rootHashedPw = Password.hashIfNot(rootpw)
    }
    val rootUnhashedPw = if (Password.isHashed(rootpw)) "" else rootpw // this is the 1 case in which an id cache entry could end up with a blank unhashed pw/tok
    AuthCache.putUser(Role.superUser, rootHashedPw, rootUnhashedPw)
    logger.info("Root user from config.json added to the in-memory authentication cache")
  }

  //todo: investigate if this does the right things when called from POST /admin/reload
  def reload(): Unit = load()

  /**
   * Set a few values on top of the current config. These values are not saved persistently, and therefore will only set it in this 1 exchange instance,
   * and therefore will *not* work when the exchange is running in multi-node config. This method is used mostly for automated testing.
   */
  def mod(props: Properties): Unit = { config = ConfigFactory.parseProperties(props).withFallback(config) }

  /** Create the root user in the DB. This is done separately from load() because we need the db execution context */
  def createRoot(db: Database): Unit = {
    // If the root pw is set in the config file, create or update the root user in the db to match
    val rootpw = config.getString("api.root.password")
    val rootIsEnabled = config.getBoolean("api.root.enabled")
    if (rootpw == "" || !rootIsEnabled) {
      rootHashedPw = "" // this should already be true, but just make sure
    } else { // there is a real, enabled root pw
      //val hashedPw = Password.hashIfNot(rootpw)  <- can't hash this again, because it would be different
      if (rootHashedPw == "") logger.error("Internal Error: rootHashedPw not already set")
      val rootUnhashedPw = if (Password.isHashed(rootpw)) "" else rootpw // this is the 1 case in which an id cache entry could not have an unhashed pw/tok
      AuthCache.putUser(Role.superUser, rootHashedPw, rootUnhashedPw) // put it in AuthCache even if it does not get successfully written to the db, so we have a chance to fix it
    }
    // Put the root org and user in the db, even if root is disabled (because in that case we want all exchange instances to know the root pw is blank
    //val rootemail = config.getString("api.root.email")
    val rootemail = ""
    // Create the root org, create the IBM org, and create the root user (all only if necessary)
    db.run(OrgRow("root", "", "Root Org", "Organization for the root user only", ApiTime.nowUTC, None).upsert.asTry.flatMap({ xs =>
      logger.debug("Upsert /orgs/root result: " + xs.toString)
      xs match {
        case Success(_) => UserRow(Role.superUser, "root", rootHashedPw, admin = true, rootemail, ApiTime.nowUTC, Role.superUser).upsertUser.asTry // next action
        case Failure(t) => DBIO.failed(t).asTry // rethrow the error to the next step
      }
    }).flatMap({ xs =>
      logger.debug("Upsert /orgs/root/users/root (root) result: " + xs.toString)
      xs match {
        case Success(_) => OrgRow("IBM", "IBM", "IBM Org", "Organization containing IBM services", ApiTime.nowUTC, None).upsert.asTry // next action
        case Failure(t) => DBIO.failed(t).asTry // rethrow the error to the next step
      }
    })).map({ xs =>
      logger.debug("Upsert /orgs/IBM result: " + xs.toString)
      xs match {
        case Success(_) => logger.info("Root org and user from config.json was successfully created/updated in the DB")
        case Failure(t) => logger.error("Failed to write the root user from config.json to the DB: " + t.toString)
      }
    })
  }

  //todo: we should catch ConfigException.Missing and ConfigException.WrongType, but they are always set by the built-in config.json
  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getString(key: String): String = { return config.getString(key) }

  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getStringList(key: String): List[String] = { return config.getStringList(key).asScala.toList }

  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getInt(key: String): Int = { return config.getInt(key) }

  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getBoolean(key: String): Boolean = { return config.getBoolean(key) }
}

object StrConstants {
  val hiddenPw = "********"
}

/** Convenience methods for setting and comparing lastUpdated and lastHeartbeat attributes */
object ApiTime {
  /** Returns now in UTC string format */
  def nowUTC = ZonedDateTime.now.withZoneSameInstant(ZoneId.of("UTC")).toString

  /** Return UTC format of the time specified in seconds */
  def thenUTC(seconds: Long) = ZonedDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.of("UTC")).toString

  /** Return UTC format of the time n seconds ago */
  def pastUTC(secondsAgo: Int) = ZonedDateTime.now.minusSeconds(secondsAgo).withZoneSameInstant(ZoneId.of("UTC")).toString

  /** Return UTC format of the time n seconds from now */
  def futureUTC(secondsFromNow: Int) = ZonedDateTime.now.plusSeconds(secondsFromNow).withZoneSameInstant(ZoneId.of("UTC")).toString

  /** Return UTC format of unix begin time */
  def beginningUTC = ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC")).toString

  /** Returns now in epoch seconds */
  def nowSeconds: Long = System.currentTimeMillis / 1000 // seconds since 1/1/1970

  /** Returns now as a java.sql.Timestamp */
  def nowTimestamp = new java.sql.Timestamp(System.currentTimeMillis())

  /** Determines if the given UTC time is more than daysStale old */
  def isDaysStale(UTC: String, daysStale: Int): Boolean = {
    if (daysStale <= 0) return false // they did not specify what was too stale
    if (UTC == "") return true // assume an empty UTC is the beginning of time
    val secondsInDay = 86400
    val thenTime = ZonedDateTime.parse(UTC).toEpochSecond
    return (nowSeconds - thenTime >= daysStale * secondsInDay)
  }

  /** Determines if the given UTC time is more than secondsStale old */
  def isSecondsStale(UTC: String, secondsStale: Int): Boolean = {
    if (secondsStale <= 0) return false // they did not specify what was too stale
    if (UTC == "") return true // assume an empty UTC is the beginning of time
    val thenTime = ZonedDateTime.parse(UTC).toEpochSecond
    return (nowSeconds - thenTime >= secondsStale)
  }
}

/** Parse a version string like 1.2.3 into its parts and define >(), in(), etc. */
case class Version(version: String) {
  val R3 = """(\d+)\.(\d+)\.(\d+)""".r
  val R2 = """(\d+)\.(\d+)""".r
  val R1 = """(\d+)""".r
  val (major, minor, mod, isInfinity) = version.trim().toLowerCase match {
    case "infinity" => (0, 0, 0, true)
    case R3(maj, min, mo) => (maj.toInt, min.toInt, mo.toInt, false)
    case R2(maj, min) => (maj.toInt, min.toInt, 0, false)
    case R1(maj) => (maj.toInt, 0, 0, false)
    case _ => (-1, -1, -1, false)
  }

  def isValid: Boolean = { isInfinity || (major != -1 && minor != -1 && mod != -1) }

  // the == operator calls equals()
  override def equals(that: Any): Boolean = that match {
    case that: Version => if (!isValid || !that.isValid) return false
    else if (that.isInfinity && isInfinity) return true
    else if (that.isInfinity || isInfinity) return false
    else return that.major == major && that.minor == minor && that.mod == mod
    case _ => return false
  }

  def >(that: Version): Boolean = {
    if (this.isInfinity && !that.isInfinity) true
    else if (that.isInfinity) false
    else if (this.major > that.major) true
    else if (that.major > this.major) false
    else if (this.minor > that.minor) true
    else if (that.minor > this.minor) false
    else if (this.mod > that.mod) true
    else false
  }

  def >=(that: Version): Boolean = (this > that || this == that)

  def in(range: VersionRange): Boolean = if (!isValid || !range.isValid) return false else range includes this

  def notIn(range: VersionRange): Boolean = if (!isValid || !range.isValid) return true else !(range includes this)

  override def toString: String = {
    if (isInfinity) "infinity"
    else "" + major + "." + minor + "." + mod
  }
}

/** Parse an osgi version range string and define includes() to test if a Version is in a VersionRange */
case class VersionRange(range: String) {
  /* The typical format of a range is like [1.2.3,4.5.6), where
  The 1st version is the lower bound (floor), if not specified 0.0.0 is the default
  The 2nd version is the upper bound (ceiling), if not specified infinity is the default
  [ or ] means inclusive on that side of the range
  ( or ) means *not* inclusive of the limit on that side of the range
  The default for the left side is [, the default for the right side is )
  For more detail, see section 3.2.6 of the OSGi Core Specification: https://www.osgi.org/developer/downloads/
  */
  // split the lower and upper bounds
  val (firstPart, secondPart) = range.trim().toLowerCase.split("""\s*,\s*""") match {
    case Array(s) => (s, "infinity")
    case Array(s1, s2) => (s1, s2)
    case _ => ("x", "x")
  }
  // split the leading [ or ( from the version number
  val R1 = """([\[(]?)(\d.*)""".r
  val (floorInclusive, floor) = firstPart match {
    case "" => (true, Version("0.0.0"))
    case R1(i, f) => ((i != "("), Version(f))
    case _ => (false, Version("x")) // Version("x") is just an invalid version object
  }
  // separate the version number from the trailing ] or )
  val R2 = """(.*\d)([\])]?)""".r
  val R3 = """(infinity)([\])]?)""".r
  val (ceiling, ceilingInclusive) = secondPart match {
    // case "" => (Version("infinity"), false)
    case R2(c, i) => (Version(c), (i == "]"))
    case R3(c, i) => (Version(c), (i == "]"))
    case _ => (Version("x"), true)
  }

  def isValid: Boolean = (floor.isValid && ceiling.isValid)

  def includes(version: Version): Boolean = {
    if (floorInclusive) { if (floor > version) return false }
    else { if (floor >= version) return false }
    if (ceilingInclusive) { if (version > ceiling) return false }
    else { if (version >= ceiling) return false }
    return true
  }

  // If this range is a single version (e.g. [1.2.3,1.2.3] ) return that version, otherwise None
  def singleVersion: Option[Version] = {
    if (floor == ceiling) Some(floor)
    else None
  }

  override def toString: String = {
    (if (floorInclusive) "[" else "(") + floor + "," + ceiling + (if (ceilingInclusive) "]" else ")")
  }
}

/** Depending on the given int, returns 1st, 2nd, 3rd, 4th, ... */
case class Nth(n: Int) {
  override def toString: String = {
    n match {
      case 1 => return n + "st"
      case 2 => return n + "nd"
      case 3 => return n + "rd"
      case _ => return n + "th"
    }
  }
}

object ApiUtils {
  def encode(unencodedCredStr: String) = Base64.getEncoder.encodeToString(unencodedCredStr.getBytes("utf-8"))

  // Convert an AnyRef to JValue
  def asJValue(src: AnyRef): JValue = {
    import org.json4s.{ Extraction, NoTypeHints }
    import org.json4s.jackson.Serialization
    implicit val formats: AnyRef with Formats = Serialization.formats(NoTypeHints)

    Extraction.decompose(src)
  }

  /* This apparently ony works when run from a war file, not straight from sbt. Get our version from build.sbt
  def getAppVersion = {
    val p = getClass.getPackage
    p.getImplementationVersion
  }
  */
}
