/** Helper classes for the exchange api rest methods, including some of the common case classes used by the api. */
package com.horizon.exchangeapi

import slick.jdbc.PostgresProfile.api._
import Access._
import scala.util.control.Breaks._
// import scala.collection.mutable._
import scala.collection.immutable._
import scala.collection.mutable.{HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection
import java.time._
import scala.util._
import com.typesafe.config._
import java.io.File
import java.util.Properties
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.{Logger, Level}     // unfortunately, the slf4j abstraction does not include setting the log level
import scala.concurrent.ExecutionContext.Implicits.global
import com.horizon.exchangeapi.tables._

/** Temp data structure mimicking our real db for the rest api methods i am still developing. */
object TempDb {
  def init = {}    // called from ScalatraBootstrap just to force this object to be instantiated

  var devices = new MutableHashMap[String,Device]() /* += (("1",
    new Device(Password.hash("abc123"),"rpi1","bp",List(Microservice("https://bluehorizon.network/documentation/sdr-device-api",1,"{json policy for rpi1 sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","300","int",">="),
      Prop("version","1.0.0","version","in"),
      Prop("dataVerification","true","boolean","=")))),
      "whisper id", Map("horizon"->"1.2.3"), "2016-09-19T13:04:56.850Z[UTC]")
    )) */
  PutDevicesRequest("abc123", "rpi1",
    List(
      Microservice("https://bluehorizon.network/documentation/sdr-device-api",1,"{json policy for rpi1 sdr}",List(
        Prop("arch","arm","string","in"),
        Prop("memory","300","int",">="),
        Prop("version","1.0.0","version","in"),
        Prop("agreementProtocols","ExchangeManualTest","list","in"),
        Prop("dataVerification","true","boolean","="))),
      Microservice("https://bluehorizon.network/documentation/netspeed-device-api",1,"{json policy for rpi1 netspeed}",List(
        Prop("arch","arm","string","in"),
        Prop("agreementProtocols","ExchangeManualTest","list","in"),
        Prop("version","1.0.0","version","in")))
    ),
    "whisper id", Map("horizon"->"1.2.3"), "ABC").copyToTempDb("1", "bp")

  var agbots = new MutableHashMap[String,Agbot]()      // key is agbot id

  var devicesAgreements = MutableHashMap[String,MutableHashMap[String,DeviceAgreement]]()    // the 1st level key is the device id, the 2nd level key is the agreement id
  var agbotsAgreements = MutableHashMap[String,MutableHashMap[String,AgbotAgreement]]()    // the 1st level key is the agbot id, the 2nd level key is the agreement id

  var users = new MutableHashMap[String,User]() // += (("bp", new User(Password.hash("mypw"),"bruceandml@gmail.com","2016-09-19T13:04:56.850Z[UTC]")))
  PutUsersRequest("mypw", "bruceandml@gmail.com").copyToTempDb("bp", true)
}

/** Global config parameters for the exchange. See typesafe config classes: http://typesafehub.github.io/config/latest/api/ */
object ExchConfig {
  val configResourceName = "config.json"
  val configFileName = "/etc/horizon/exchange/"+configResourceName
  // The syntax called CONF is typesafe's superset of json that allows comments, etc. See https://github.com/typesafehub/config#using-hocon-the-json-superset. Strict json would be ConfigSyntax.JSON.
  val configOpts = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF).setAllowMissing(false)
  var config = ConfigFactory.parseResources(configResourceName, configOpts)    // these are the default values, this file is bundled in the jar
  val LOGGER = "EXCHANGE"    //or could use org.slf4j.Logger.ROOT_LOGGER_NAME
  val logger: Logger = LoggerFactory.getLogger(LOGGER).asInstanceOf[Logger]     //todo: maybe add a custom layout that includes the date: http://logback.qos.ch/manual/layouts.html
  // Maps log levels expressed as strings in the config file to the slf4j log level enums
  val levels: Map[String,Level] = Map("OFF"->Level.OFF, "ERROR"->Level.ERROR, "WARN"->Level.WARN, "INFO"->Level.INFO, "DEBUG"->Level.DEBUG, "TRACE"->Level.TRACE, "ALL"->Level.ALL)

  /** Tries to load the user's external config file */
  def load: Unit = {
    val f = new File(configFileName)
    if (f.isFile()) {     // checks if it exists and is a regular file
      config = ConfigFactory.parseFile(f, configOpts).withFallback(config)    // uses the defaults for anything not specified in the external config file
      logger.info("Using config file "+configFileName)
    } else {
      logger.info("Config file "+configFileName+" not found. Running with defaults suitable for local development.")
    }

    // Set the logging level if specified
    val loglev = config.getString("api.logging.level")
    if (loglev != "") {
      levels.get(loglev) match {
        case Some(level) => logger.setLevel(level)
        case None => logger.error("Invalid logging level '"+loglev+"' specified in config.json. Continuing with the default logging level.")
      }
    }

    // Let them know if they are running with the in-memory db
    if (getBoolean("api.db.memoryDb")) logger.info("Running with the in-memory DB (not the persistent postgresql DB).")
    else logger.info("Running with the persistent postgresql DB.")
  }

  def reload: Unit = load

  /** Set a few values on top of the current config. These values are not save persistently. Used mostly for automated testing. */
  def mod(props: Properties): Unit = { config = ConfigFactory.parseProperties(props).withFallback(config) }

  /** This is done separately from load() because we need the db execution context */
  def createRoot(db: Database): Unit = {
    // If the root pw is set in the config file, create or update the root user in the db to match
    val rootpw = config.getString("api.root.password")
    // if (rootpw != "") AuthCache.users.put(Creds("root", rootpw))    // do not actually put the root user in the db, just in our cache
    if (rootpw != "") {
      val rootemail = config.getString("api.root.email")
      AuthCache.users.put(Creds("root", rootpw))    // put it in AuthCache even if it does not get successfully written to the db, so we have a chance to fix it
      db.run(UserRow("root", rootpw, rootemail, ApiTime.nowUTC).upsertUser.asTry).map({ xs =>
        logger.debug("PUT /users/root (root) result: "+xs.toString)
        xs match {
          case Success(v) => logger.info("Root user from config.json was successfully created/updated in the DB")
          case Failure(t) => logger.error("Failed to write the root user from config.json to the DB: "+t.toString)
        }
      })
    }
  }

  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getString(key: String): String = { return config.getString(key) }

  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getInt(key: String): Int = { return config.getInt(key) }

  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getBoolean(key: String): Boolean = {
    if (key == "api.db.memoryDb") return false
    else return config.getBoolean(key)
  }
}

object StrConstants {
  val hiddenPw = "********"
}

/** HTTP codes, taken from https://en.wikipedia.org/wiki/List_of_HTTP_status_codes */
object HttpCode {
  val OK = 200
  val PUT_OK = 201
  val POST_OK = 201
  val DELETED = 204     // technically means no content, but usually used for DELETE
  val BAD_INPUT = 400     // invalid user input, usually in the params or json body
  val BADCREDS = 401    // user/pw or id/token is wrong (they call it unauthorized, but it is really unauthenticated)
  val ACCESS_DENIED = 403   // do not have authorization to access this resource
  val NOT_FOUND = 404   // resource not found
  val INTERNAL_ERROR = 500
  val NOT_IMPLEMENTED = 501
}

/** These are used as the response structure for most PUTs, POSTs, and DELETEs. */
case class ApiResponse(code: String, msg: String)
object ApiResponseType {
  val BADCREDS = "invalid-credentials"
  val ACCESS_DENIED = "access-denied"
  val BAD_INPUT = "invalid-input"
  val NOT_FOUND = "not-found"
  val INTERNAL_ERROR = "internal_error"
  val NOT_IMPLEMENTED = "not-implemented"
  val ERROR = "error"
  val WARNING = "warning"
  val INFO = "info"
  val OK = "ok"
  val TOO_BUSY = "too busy"
}

/** Convenience methods for setting and comparing lastUpdated and lastHeartbeat attributes */
object ApiTime {
  /** Returns now in UTC string format */
  def nowUTC = ZonedDateTime.now.withZoneSameInstant(ZoneId.of("UTC")).toString

  /** Return UTC format of the time n seconds ago */
  def pastUTC(secondsAgo: Int) = ZonedDateTime.now.minusSeconds(secondsAgo).withZoneSameInstant(ZoneId.of("UTC")).toString

  /** Return UTC format of the time n seconds from now */
  def futureUTC(secondsFromNow: Int) = ZonedDateTime.now.plusSeconds(secondsFromNow).withZoneSameInstant(ZoneId.of("UTC")).toString

  /** Returns now in epoch seconds */
  def nowSeconds: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970

  /** Returns now as a java.sql.Timestamp */
  def nowTimestamp = new java.sql.Timestamp(System.currentTimeMillis())

  /** Determines if the given UTC time is more than daysStale old */
  def isDaysStale(UTC: String, daysStale: Int): Boolean = {
    if (daysStale <= 0) return false      // they did not specify what was too stale
    if (UTC == "") return true       // assume an empty UTC is the beginning of time
    val secondsInDay = 86400
    val thenTime = ZonedDateTime.parse(UTC).toEpochSecond
    return (nowSeconds - thenTime >= daysStale * secondsInDay)
  }

  /** Determines if the given UTC time is more than secondsStale old */
  def isSecondsStale(UTC: String, secondsStale: Int): Boolean = {
    if (secondsStale <= 0) return false      // they did not specify what was too stale
    if (UTC == "") return true       // assume an empty UTC is the beginning of time
    val thenTime = ZonedDateTime.parse(UTC).toEpochSecond
    return (nowSeconds - thenTime >= secondsStale)
  }
}

/** Parse a version string like 1.2.3 into its parts and define equals() and toString() */
case class Version(version: String) {
  val R3 = """(\d+)\.(\d+)\.(\d+)""".r
  val R2 = """(\d+)\.(\d+)""".r
  val R1 = """(\d+)""".r
  val (major, minor, mod, isInfinity) = version.trim() match {
    case "infinity" => (0, 0, 0, true)
    case R3(major, minor, mod) => (major.toInt, minor.toInt, mod.toInt, false)
    case R2(major, minor) => (major.toInt, minor.toInt, 0, false)
    case R1(major) => (major.toInt, 0, 0, false)
    case _ => (-1, -1, -1, false)
  }

  def isValid: Boolean = { isInfinity || (major != -1 && minor != -1 && mod != -1) }

  // the == operator calls equals()
  override def equals(that: Any): Boolean = that match {
    case that: Version => if (that.isInfinity && isInfinity) return true
      else if (that.isInfinity || isInfinity) return false
      else return that.major==major && that.minor==minor && that.mod==mod
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

  def in(range: VersionRange): Boolean = range includes this

  def notIn(range: VersionRange): Boolean = !(range includes this)

  override def toString: String = {
    if (isInfinity) "infinity"
    else ""+major+"."+minor+"."+mod
  }
}

/** Parse an osgi version range string and define in() to test if a Version is in a VersionRange */
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
  val (firstPart, secondPart) = range.trim().split("""\s*,\s*""") match {
    case Array(s) => (s, "infinity")
    case Array(s1, s2) => (s1, s2)
    case _ => ("x", "x")
  }
  // split the leading [ or ( from the version number
  val R1 = """([\[\(]?)(\d.*)""".r
  val (floorInclusive, floor) = firstPart match {
    case "" => (true, Version("0.0.0"))
    case R1(i,f) => ((i != "("), Version(f))
    case _ => (false, Version("x"))         // Version("x") is just an invalid version object
  }
  // split the version number from the trailing ] or )
  val R2 = """(.*\d)([\]\)]?)""".r
  val R3 = """(infinity)([\]\)]?)""".r
  val (ceiling, ceilingInclusive) = secondPart match {
    // case "" => (Version("infinity"), false)
    case R2(c,i) => (Version(c), (i == "]"))
    case R3(c,i) => (Version(c), (i == "]"))
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

  override def toString: String = {
    (if (floorInclusive) "[" else "(") + floor + "," + ceiling + (if (ceilingInclusive) "]" else ")")
  }
}
