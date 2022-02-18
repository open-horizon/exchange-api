/** Helper classes for the exchange api rest methods, including some of the common case classes used by the api. */
package com.horizon.exchangeapi

import java.io.File
import java.lang.management.{ManagementFactory, RuntimeMXBean}
import java.time._
import java.util
import akka.event.LoggingAdapter

import scala.jdk.CollectionConverters.{CollectionHasAsScala, MapHasAsJava, MapHasAsScala}
import scala.util.matching.Regex
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server._
import com.horizon.exchangeapi.tables.{OrgRow, UserRow}
import com.osinka.i18n.{Lang, Messages}
import com.typesafe.config._
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.util._
import java.util.{Base64, Properties}
import scala.concurrent.ExecutionContext
import com.horizon.exchangeapi.auth.AuthException
import org.json4s.JsonAST.JValue
import org.json4s._
import org.json4s.jackson.Serialization.write

import scala.collection.mutable.{HashMap => MutableHashMap}

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
  val OK: StatusCodes.Success = StatusCodes.OK
  val PUT_OK: StatusCodes.Success = StatusCodes.Created
  val POST_OK: StatusCodes.Success = StatusCodes.Created
  val DELETED: StatusCodes.Success = StatusCodes.NoContent // technically means no content, but usually used for DELETE
  val BAD_INPUT: StatusCodes.ClientError = StatusCodes.BadRequest // invalid user input, usually in the params or json body
  val BADCREDS: StatusCodes.ClientError = StatusCodes.Unauthorized // user/pw or id/token is wrong (they call it unauthorized, but it is really unauthenticated)
  val ACCESS_DENIED: StatusCodes.ClientError = StatusCodes.Forbidden // do not have authorization to access this resource
  val ALREADY_EXISTS: StatusCodes.ClientError = StatusCodes.Forbidden // trying to create a resource that already exists. For now using 403 (forbidden), but could also use 409 (conflict)
  val ALREADY_EXISTS2: StatusCodes.ClientError = StatusCodes.Conflict // trying to create a resource that already exists (409 means conflict)
  val NOT_FOUND: StatusCodes.ClientError = StatusCodes.NotFound // resource not found
  val INTERNAL_ERROR: StatusCodes.ServerError = StatusCodes.InternalServerError
  val NOT_IMPLEMENTED: StatusCodes.ServerError = StatusCodes.NotImplemented
  val BAD_GW: StatusCodes.ServerError = StatusCodes.BadGateway // bad gateway, which for us means db connection error or IAM API problem
  val GW_TIMEOUT: StatusCodes.ServerError = StatusCodes.GatewayTimeout // gateway timeout, which for us means db timeout
}

/** These are used as the response structure for most PUTs, POSTs, and DELETEs. */
final case class ApiResponse(code: String, msg: String)
object ApiRespType {
  val BADCREDS: String = ExchMsg.translate("api.bad.creds")
  val ACCESS_DENIED: String = ExchMsg.translate("api.access.denied")
  val ALREADY_EXISTS: String = ExchMsg.translate("api.already.exists")
  val BAD_INPUT: String = ExchMsg.translate("api.invalid.input")
  val NOT_FOUND: String = ExchMsg.translate("api.not.found")
  val INTERNAL_ERROR: String = ExchMsg.translate("api.internal.error")
  val NOT_IMPLEMENTED: String = ExchMsg.translate("api.not.implemented")
  val BAD_GW: String = ExchMsg.translate("api.db.connection.error")
  val GW_TIMEOUT: String = ExchMsg.translate("api.db.timeout")
  val ERROR: String = ExchMsg.translate("error")
  val WARNING: String = ExchMsg.translate("warning")
  val INFO: String = ExchMsg.translate("info")
  val OK: String = ExchMsg.translate("ok")
  val TOO_BUSY: String = ExchMsg.translate("too.busy")
}

trait ExchangeRejection extends Rejection {
  private implicit val formats: DefaultFormats.type = DefaultFormats

  def httpCode: StatusCode
  def apiRespCode: String
  def apiRespMsg: String
  def toApiResp: ApiResponse = ApiResponse(apiRespCode, apiRespMsg)
  def toJsonStr: String = write(ApiResponse(apiRespCode, apiRespMsg))
  override def toString : String = s"Rejection http code: $httpCode, message: $apiRespMsg"
}

// Converts an exception into an auth rejection
final case class AuthRejection(t: Throwable) extends ExchangeRejection {
  //todo: if a generic Throwable is passed in, maybe use something other than invalid creds
  def httpCode: StatusCode = t match {
    case e: AuthException => e.httpCode
    case _ => StatusCodes.Unauthorized // should never get here
  }
  def apiRespCode: String = t match {
    case e: AuthException => e.apiResponse
    case _ => "invalid-credentials" // should never get here
  }
  def apiRespMsg: String = t match {
    case e: AuthException => e.getMessage
    case _ => "invalid credentials" // should never get here
  }
}

final case class NotFoundRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ClientError = StatusCodes.NotFound
  def apiRespCode: String = ApiRespType.NOT_FOUND
}

//someday: the rest of these rejections are not currently used. Instead the route implementations either do the complete() directly,
//  or turn an AuthException into a complete() using its toComplete method. But maybe it is better for the akka framework to know it is a rejection.
final case class BadCredsRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ClientError = StatusCodes.Unauthorized
  def apiRespCode: String = ApiRespType.BADCREDS
}

final case class BadInputRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ClientError = StatusCodes.BadRequest
  def apiRespCode: String = ApiRespType.BAD_INPUT
}

final case class AccessDeniedRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ClientError = StatusCodes.Forbidden
  def apiRespCode: String = ApiRespType.ACCESS_DENIED
}

final case class AlreadyExistsRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ClientError = StatusCodes.Forbidden
  def apiRespCode: String = ApiRespType.ALREADY_EXISTS
}

final case class AlreadyExists2Rejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ClientError = StatusCodes.Conflict
  def apiRespCode: String = ApiRespType.ALREADY_EXISTS
}

final case class BadGwRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ServerError = StatusCodes.BadGateway
  def apiRespCode: String = ApiRespType.BAD_GW
}

final case class GwTimeoutRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ServerError = StatusCodes.GatewayTimeout
  def apiRespCode: String = ApiRespType.GW_TIMEOUT
}

final case class InternalErrorRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ServerError = HttpCode.INTERNAL_ERROR
  def apiRespCode: String = ApiRespType.INTERNAL_ERROR
}

object ExchangePosgtresErrorHandling {
  def isDuplicateKeyError(serverError: org.postgresql.util.PSQLException): Boolean = {serverError.getServerErrorMessage.getMessage.contains("duplicate key") || serverError.getServerErrorMessage.getRoutine.contains("_bt_check_unique")}
  def isAccessDeniedError(serverError: org.postgresql.util.PSQLException): Boolean = {serverError.getMessage.startsWith("Access Denied:")}
  def isKeyNotFoundError(serverError: org.postgresql.util.PSQLException): Boolean = {serverError.getServerErrorMessage.getDetail.contains("is not present in table") || serverError.getServerErrorMessage.getRoutine.contains("ri_ReportViolation")}
  def ioProblemError(serverError: org.postgresql.util.PSQLException, response: String): (StatusCode, ApiResponse) = {
    if (serverError.getMessage.contains("An I/O error occurred")) (HttpCode.BAD_GW, ApiResponse(ApiRespType.BAD_GW, response))
    else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, response))
  }
}


// Returns a msg from the translated files, with the args substituted
object ExchMsg {
  def translate(key: String, args: Any*): String = {
    try {
      //todo: remove these 2 debug statements
      val exchLang: String = sys.env.getOrElse("HZN_EXCHANGE_LANG", sys.env.getOrElse("LANG", "en"))
      if (exchLang.startsWith("zh") || exchLang.startsWith("pt")) println("using lang for msgs: "+exchLang)

      implicit val userLang: Lang = Lang(sys.env.getOrElse("HZN_EXCHANGE_LANG", sys.env.getOrElse("LANG", "en")))
      if (args.nonEmpty) {
        return Messages(key, args: _*)
      }
      Messages(key)
    } catch {
      case e: Exception => s"message key '$key' not found in the messages file: ${e.getMessage}"
    }
  }

  def getLang: String = sys.env.getOrElse("HZN_EXCHANGE_LANG", sys.env.getOrElse("LANG", "en"))
}

object NodeAgbotTokenValidation {
  def isValid(token: String): Boolean = {
    // Check if token is valid
    // (?=.*[0-9]) digit must occur at least once
    // (?=.*[a-z]) lowercase letter must occur at least once
    // (?=.*[A-Z]) uppercase letter must occur at least once
    // .{15,} minimum 15 chars
    val exchLang: String = sys.env.getOrElse("HZN_EXCHANGE_LANG", sys.env.getOrElse("LANG", "en"))
    val pwRegex: Regex = if (exchLang.contains("ja") || exchLang.contains("ko") || exchLang.contains("zh")) """^(?=.*[0-9]).{15,}$""".r else """^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z]).{15,}$""".r
    val valid : Boolean = token match {
      case pwRegex(_*) => true
      case _ => false
    }
    valid
  }
}

object LogLevel {
  val OFF = "OFF"
  val ERROR = "ERROR"
  val WARN = "WARN"
  val INFO = "INFO"
  val DEBUG = "DEBUG"
  val validLevels: Set[String] = Set(OFF, ERROR, WARN, INFO, DEBUG)
}

/** Global config parameters for the exchange. See typesafe config classes: http://typesafehub.github.io/config/latest/api/ */
object ExchConfig {
  val configResourceName = "config.json"
  val configFileName: String = "/etc/horizon/exchange/" + configResourceName
  // The syntax called CONF is typesafe's superset of json that allows comments, etc. See https://github.com/typesafehub/config#using-hocon-the-json-superset. Strict json would be ConfigSyntax.JSON.
  val configOpts: ConfigParseOptions = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF).setAllowMissing(false)
  var config: Config = ConfigFactory.parseResources(configResourceName, configOpts) // these are the default values, this file is bundled in the jar

  //var defaultExecutionContext: ExecutionContext = _ // this gets set early by ExchangeApiApp
  implicit def executionContext: ExecutionContext = ExchangeApi.defaultExecutionContext
  def logger: LoggingAdapter = ExchangeApi.defaultLogger

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
    val roles: Map[String, ConfigValue] = config.getObject("api.acls").asScala.toMap
    for ((role, _) <- roles) {
      val accessSet: Set[String] = getStringList("api.acls." + role).toSet
      if (!Role.isValidAcessValues(accessSet)) println("Error: invalid value in ACLs in config file for role " + role)
      else Role.setRole(role, accessSet)
    }
    println(s"Roles: ${Role.roles}")
    if (!Role.haveRequiredRoles) println("Error: at least these roles must be set in the config file: " + AuthRoles.requiredRoles.mkString(", "))

    // Note: currently there is no other value besides guava
    AuthCache.cacheType = config.getString("api.cache.type") // need to do this before using the cache in the next step
  }

  def getLogLevel: String = {
    val loglev: String = config.getString("api.logging.level")
    if (loglev == "") LogLevel.INFO // default
    else if (LogLevel.validLevels.contains(loglev)) loglev
    else {
      println("Invalid logging level '" + loglev + "' specified in config.json. Continuing with the default logging level " + LogLevel.INFO + ".")
      LogLevel.INFO // fallback
    }
  }

  def getHostAndPort: (String, Option[Int], Option[Int]) = {
    var host: String = config.getString("api.service.host")
    if (host.isEmpty) host = "0.0.0.0"
    
    val portEncrypted: Option[Int] = {
      try {
        Option(config.getInt("api.service.portEncrypted"))
      }
      catch {
        case _: Exception => None
      }
    }
    val portUnencrypted: Option[Int] = {
      try {
        Option(config.getInt("api.service.port"))
      }
      catch {
        case _: Exception => None
      }
    }
    
    (portEncrypted, portUnencrypted) match {
      case (None, None) => (host, None, Option(8080))
      case (a, b) if (a == b) => (host, None, portUnencrypted)
      case _ => (host, portEncrypted, portUnencrypted)
    }
  }

  // Get relevant values from our config file to create the akka config
  def getAkkaConfig: Config = {
    var akkaConfig: Map[String, ConfigValue] = config.getObject("api.akka").asScala.toMap
    akkaConfig = akkaConfig ++ Map[scala.Predef.String,ConfigValue]("akka.loglevel" -> ConfigValueFactory.fromAnyRef(ExchConfig.getLogLevel))
    val secondsToWait: Int = ExchConfig.getInt("api.service.shutdownWaitForRequestsToComplete")
    akkaConfig = akkaConfig ++ Map[scala.Predef.String,ConfigValue]("akka.coordinated-shutdown.phases.service-unbind.timeout" -> ConfigValueFactory.fromAnyRef(s"${secondsToWait}s"))
    printf("Running with akka config: %s\n", akkaConfig.toString())
    //ConfigFactory.parseMap(Map("akka.loglevel" -> ExchConfig.getLogLevel).asJava, "akka overrides")
    ConfigFactory.parseMap(akkaConfig.asJava, "akka overrides")
  }

  // Put the root user in the auth cache in case the db has not been inited yet and they need to be able to run POST /admin/initdb
  def createRootInCache(): Unit = {
    val rootpw: String = config.getString("api.root.password")
    val rootIsEnabled: Boolean = config.getBoolean("api.root.enabled")
    if (rootpw == "" || !rootIsEnabled) {
      logger.warning("Root password is not specified in config.json or is not enabled. You will not be able to do exchange operations that require root privilege.")
      return
    }
    if (rootHashedPw == "") {
      // this is the 1st time, we need to hash and save it
      rootHashedPw = Password.hashIfNot(rootpw)
    }
    val rootUnhashedPw: String = if (Password.isHashed(rootpw)) "" else rootpw // this is the 1 case in which an id cache entry could end up with a blank unhashed pw/tok
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
    val rootpw: String = config.getString("api.root.password")
    val rootIsEnabled: Boolean = config.getBoolean("api.root.enabled")
    if (rootpw == "" || !rootIsEnabled) {
      rootHashedPw = "" // this should already be true, but just make sure
    } else { // there is a real, enabled root pw
      //val hashedPw = Password.hashIfNot(rootpw)  <- can't hash this again, because it would be different
      if (rootHashedPw == "") logger.error("Internal Error: rootHashedPw not already set")
      val rootUnhashedPw: String = if (Password.isHashed(rootpw)) "" else rootpw // this is the 1 case in which an id cache entry could not have an unhashed pw/tok
      AuthCache.putUser(Role.superUser, rootHashedPw, rootUnhashedPw) // put it in AuthCache even if it does not get successfully written to the db, so we have a chance to fix it
    }
    // Put the root org and user in the db, even if root is disabled (because in that case we want all exchange instances to know the root pw is blank
    //val rootemail = config.getString("api.root.email")
    val rootemail = ""
    // Create the root org, create the IBM org, and create the root user (all only if necessary)
    db.run(OrgRow("root", "", "Root Org", "Organization for the root user only", ApiTime.nowUTC, None, "", "").upsert.asTry.flatMap({ xs =>
      logger.debug("Upsert /orgs/root result: " + xs.toString)
      xs match {
        case Success(_) => UserRow(Role.superUser, "root", rootHashedPw, admin = true, hubAdmin = true, rootemail, ApiTime.nowUTC, Role.superUser).upsertUser.asTry // next action
        case Failure(t) => DBIO.failed(t).asTry // rethrow the error to the next step
      }
    }).flatMap({ xs =>
      logger.debug("Upsert /orgs/root/users/root (root) result: " + xs.toString)
      xs match {
        case Success(_) => OrgRow("IBM", "IBM", "IBM Org", "Organization containing IBM services", ApiTime.nowUTC, None, "", "").upsert.asTry // next action
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

  //someday: we should catch ConfigException.Missing and ConfigException.WrongType, but they are always set by the built-in config.json
  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getString(key: String): String = { config.getString(key) }

  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getStringList(key: String): List[String] = { config.getStringList(key).asScala.toList }

  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getInt(key: String): Int = { config.getInt(key) }

  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getBoolean(key: String): Boolean = { config.getBoolean(key) }
}

object StrConstants {
  val hiddenPw = "********"
}

/** Convenience methods for setting and comparing lastUpdated and lastHeartbeat attributes */
object ApiTime {
  /** Returns now in UTC string format */
  def nowUTC: String = fixFormatting(ZonedDateTime.now.withZoneSameInstant(ZoneId.of("UTC")).toString)

  /** Returns now in UTC in java.sql.Timestamp type */
  def nowUTCTimestamp: java.sql.Timestamp = java.sql.Timestamp.from(ZonedDateTime.now.withZoneSameInstant(ZoneId.of("UTC")).toInstant)

  /** Return UTC format of the time specified in seconds */
  def thenUTC(seconds: Long): String = fixFormatting(ZonedDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.of("UTC")).toString)

  /** Return UTC format of the time n seconds ago */
  def pastUTC(secondsAgo: Int): String = fixFormatting(ZonedDateTime.now.minusSeconds(secondsAgo).withZoneSameInstant(ZoneId.of("UTC")).toString)

  /** Return UTC format of the time n seconds ago in java.sql.Timestamp type */
  def pastUTCTimestamp(secondsAgo: Int): java.sql.Timestamp = java.sql.Timestamp.from(ZonedDateTime.now.minusSeconds(secondsAgo).withZoneSameInstant(ZoneId.of("UTC")).toInstant)

  /** Return UTC format of the time n seconds from now */
  def futureUTC(secondsFromNow: Int): String = fixFormatting(ZonedDateTime.now.plusSeconds(secondsFromNow).withZoneSameInstant(ZoneId.of("UTC")).toString)

  /** Return UTC format of unix begin time */
  def beginningUTC: String = fixFormatting(ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC")).toString)

  /** Returns now in epoch seconds */
  def nowSeconds: Long = System.currentTimeMillis / 1000 // seconds since 1/1/1970

  /** Returns now as a java.sql.Timestamp */
  def nowTimestamp = new java.sql.Timestamp(System.currentTimeMillis())

  /** Determines if the given UTC time is more than daysStale old */
  def isDaysStale(UTC: String, daysStale: Int): Boolean = {
    if (daysStale <= 0) return false // they did not specify what was too stale
    if (UTC == "") return true // assume an empty UTC is the beginning of time
    val secondsInDay = 86400
    val thenTime: Long = ZonedDateTime.parse(UTC).toEpochSecond
    (nowSeconds - thenTime >= daysStale * secondsInDay)
  }

  /** Determines if the given UTC time is more than secondsStale old */
  def isSecondsStale(UTC: String, secondsStale: Int): Boolean = {
    if (secondsStale <= 0) return false // they did not specify what was too stale
    if (UTC == "") return true // assume an empty UTC is the beginning of time
    val thenTime: Long = ZonedDateTime.parse(UTC).toEpochSecond
    (nowSeconds - thenTime >= secondsStale)
  }

  def fixFormatting(time: String): String ={
    val timeLength: Int = time.length
    /*
    This implementation uses length of the string instead of a regex to make it as fast as possible
    The problem that was happening is described here: https://bugs.openjdk.java.net/browse/JDK-8193307
    Essentially the returned string would truncate milliseconds or seconds and milliseconds if those values happened to be 0
    So we would be getting:
    uuuu-MM-dd'T'HH:mm (Ex: "2020-02-05T20:28Z[UTC]")
    uuuu-MM-dd'T'HH:mm:ss (Ex: "2020-02-05T20:28:14Z[UTC]")
    Instead of what we want : uuuu-MM-dd'T'HH:mm:ss.SSS  (Ex: "2020-02-05T20:28:14.469Z[UTC]")
    This implementation serves to ensure we always get time in the format we expect
    This is explained in the docs here: https://docs.oracle.com/javase/9/docs/api/java/time/LocalDateTime.html#toString--
    length when time is fully filled out is 29
    length when time has no milliseconds 25
    length when time has no seconds and no milliseconds is 22
    */
    if(timeLength >= 29){ // if its the correct length just return it
      time
    } else if (timeLength == 25){ // need to add milliseconds on
      time.substring(0, 19) + ".000Z[UTC]"
    } else if (timeLength == 22) { // need to add seconds and milliseconds on
      time.substring(0, 16) + ":00.000Z[UTC]"
    } else time // On the off chance its some weird length
  }
}

/** Parse a version string like 1.2.3 into its parts and define >(), in(), etc. */
final case class Version(version: String) {
  val R3: Regex = """(\d+)\.(\d+)\.(\d+)""".r
  val R2: Regex = """(\d+)\.(\d+)""".r
  val R1: Regex = """(\d+)""".r
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
    case that: Version => if (!isValid || !that.isValid) false
    else if (that.isInfinity && isInfinity) true
    else if (that.isInfinity || isInfinity) false
    else that.major == major && that.minor == minor && that.mod == mod
    case _ => false
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

  def in(range: VersionRange): Boolean = if (!isValid || !range.isValid) false else range includes this

  def notIn(range: VersionRange): Boolean = if (!isValid || !range.isValid) true else !(range includes this)

  override def toString: String = {
    if (isInfinity) "infinity"
    else "" + major + "." + minor + "." + mod
  }
}

/** Parse an osgi version range string and define includes() to test if a Version is in a VersionRange */
final case class VersionRange(range: String) {
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
  val R1: Regex = """([\[(]?)(\d.*)""".r
  val (floorInclusive, floor) = firstPart match {
    case "" => (true, Version("0.0.0"))
    case R1(i, f) => ((i != "("), Version(f))
    case _ => (false, Version("x")) // Version("x") is just an invalid version object
  }
  // separate the version number from the trailing ] or )
  val R2: Regex = """(.*\d)([\])]?)""".r
  val R3: Regex = """(infinity)([\])]?)""".r
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
    true
  }

  // If this range is a single version (e.g. [1.2.3,1.2.3] ) return that version, otherwise None
  def singleVersion: Option[Version] = {
    if (floor == ceiling) Option(floor)
    else None
  }

  override def toString: String = {
    (if (floorInclusive) "[" else "(") + floor + "," + ceiling + (if (ceilingInclusive) "]" else ")")
  }
}

/** Depending on the given int, returns 1st, 2nd, 3rd, 4th, ... */
final case class Nth(n: Int) {
  override def toString: String = {
    n match {
      case 1 => s"${n}st"
      case 2 => s"${n}nd"
      case 3 => s"${n}rd"
      case _ => s"${n}th"
    }
  }
}

object ApiUtils {
  def encode(unencodedCredStr: String): String = Base64.getEncoder.encodeToString(unencodedCredStr.getBytes("utf-8"))

  // Convert an AnyRef to JValue
  def asJValue(src: AnyRef): JValue = {
    import org.json4s.{ Extraction, NoTypeHints }
    import org.json4s.jackson.Serialization
    implicit val formats: AnyRef with Formats = Serialization.formats(NoTypeHints)

    Extraction.decompose(src)
  }

  // Get the JVM arguments that were passed to it
  def getJvmArgs: util.List[String] = {
    val runtimeMXBean: RuntimeMXBean = ManagementFactory.getRuntimeMXBean
    val jvmArgs: util.List[String] = runtimeMXBean.getInputArguments
    jvmArgs
  }

  /* This apparently ony works when run from a war file, not straight from sbt. Get our version from build.sbt
  def getAppVersion = {
    val p = getClass.getPackage
    p.getImplementationVersion
  }
  */
}

object RouteUtils {

  /** From the given db joined node/agreement rows, build the output node health hash and return it.
     This is shared between POST /org/{orgid}/patterns/{pat-id}/nodehealth and POST /org/{orgid}/search/nodehealth
    */
  def buildNodeHealthHash(list: scala.Seq[(String, Option[String], Option[String], Option[String])]): Map[String,NodeHealthHashElement] = {
    // Go thru the rows and build a hash of the nodes, adding the agreement to its value as we encounter them
    val nodeHash = new MutableHashMap[String,NodeHealthHashElement]     // key is node id, value has lastHeartbeat and the agreements map
    for ( (nodeId, lastHeartbeat, agrId, agrLastUpdated) <- list ) {
      nodeHash.get(nodeId) match {
        case Some(nodeElement) => agrId match {    // this node is already in the hash, add the agreement if it's there
          case Some(agId) => nodeElement.agreements = nodeElement.agreements + ((agId, NodeHealthAgreementElement(agrLastUpdated.getOrElse(""))))    // if we are here, lastHeartbeat is already set and the agreement Map is already created
          case None => ;      // no agreement to add to the agreement hash
        }
        case None => agrId match {      // this node id not in the hash yet, add it
          case Some(agId) => nodeHash.put(nodeId, new NodeHealthHashElement(lastHeartbeat, Map(agId -> NodeHealthAgreementElement(agrLastUpdated.getOrElse("")))))
          case None => nodeHash.put(nodeId, new NodeHealthHashElement(lastHeartbeat, Map()))
        }
      }
    }
    nodeHash.toMap
  }

}
