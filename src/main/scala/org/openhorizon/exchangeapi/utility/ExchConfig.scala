package org.openhorizon.exchangeapi.utility

import akka.event.LoggingAdapter
import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions, ConfigSyntax, ConfigValue, ConfigValueFactory}
import org.json4s.JObject
import org.json4s.JsonAST.JString
import org.openhorizon.exchangeapi.auth.{AuthCache, AuthRoles, Password, Role}
import org.openhorizon.exchangeapi.table.organization.OrgRow
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.{ExchangeApi}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.PostgresProfile.api.Database

import java.io.File
import java.util.Properties
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.{CollectionHasAsScala, MapHasAsJava, MapHasAsScala}
import scala.util.{Failure, Success}

/** Global config parameters for the exchange. See typesafe config classes: http://typesafehub.github.io/config/latest/api/ */
object ExchConfig {
  val configResourceName = "config.json"
  val configFileName: String = "/etc/horizon/exchange/" + configResourceName
  // The syntax called CONF is typesafe's superset of json that allows comments, etc. See https://github.com/typesafehub/config#using-hocon-the-json-superset. Strict json would be ConfigSyntax.JSON.
  val configOpts: ConfigParseOptions = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF).setAllowMissing(false)
  var config: Config = ConfigFactory.parseResources(configResourceName, configOpts) // these are the default values, this file is bundled in the jar
  var configUser: Config = _
  
  //var defaultExecutionContext: ExecutionContext = _ // this gets set early by ExchangeApiApp
  implicit def executionContext: ExecutionContext = ExchangeApi.defaultExecutionContext
  
  def logger: LoggingAdapter = ExchangeApi.defaultLogger
  
  var rootHashedPw = "" // so we can remember the hashed pw between load() and createRoot()
  
  // Tries to load the user's external config file
  // Note: logger doesn't have a valid value at the time of this call
  def load(): Unit = {
    val f = new File(configFileName)
    if (f.isFile) { // checks if it exists and is a regular file
      configUser = ConfigFactory.parseFile(f, configOpts)
      config = configUser.withFallback(config) // uses the defaults for anything not specified in the external config file
      println("Using config file " + configFileName)
    } else {
      println("Config file " + configFileName + " not found. Running with defaults suitable for local development.")
    }
    
    // Read the ACLs and set them in our Role object
    val roles: Map[String, ConfigValue] = config.getObject("api.acls").asScala.toMap
    for ((role, _) <- roles) {
      val accessSet: Set[String] = getStringList("api.acls." + role).toSet
      if (!Role.isValidAcessValues(accessSet)) println("Error: invalid value in ACLs in config file for role " + role) else Role.setRole(role, accessSet)
    }
    println(s"Roles: ${Role.roles}")
    if (!Role.haveRequiredRoles) println("Error: at least these roles must be set in the config file: " + AuthRoles.requiredRoles.mkString(", "))
    
    // Note: currently there is no other value besides guava
    AuthCache.cacheType = config.getString("api.cache.type") // need to do this before using the cache in the next step
  }
  
  def getLogLevel: String = {
    val loglev: String = config.getString("api.logging.level")
    if (loglev == "") LogLevel.INFO // default
    else if (LogLevel.validLevels.contains(loglev)) loglev else {
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
      } catch {
        case _: Exception => None
      }
    }
    val portUnencrypted: Option[Int] = {
      try {
        Option(config.getInt("api.service.port"))
      } catch {
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
  /*
   * Akka has a better way of handling application configuration, unfortunately the project also needs to be
   * backwards-compatible with this custom method of handling configuration. Primarily has an impact on the
   * configurations for akka-core and akka-http (this method).
   */ def getAkkaConfig: Config = {
    var akkaConfig: Map[String, ConfigValue] = config.getObject("api.akka").asScala.toMap
    val secondsToWait: Int = ExchConfig.getInt("api.service.shutdownWaitForRequestsToComplete")
    
    akkaConfig = akkaConfig ++ Map[scala.Predef.String, ConfigValue]("akka.coordinated-shutdown.phases.service-unbind.timeout" -> ConfigValueFactory.fromAnyRef(s"${secondsToWait}s"))
    akkaConfig = akkaConfig ++ Map[scala.Predef.String, ConfigValue]("akka.loglevel" -> ConfigValueFactory.fromAnyRef(ExchConfig.getLogLevel))
    
    // printf("Running with akka config: %s\n", akkaConfig.toString())
    
    // Highest priority to lowest priority.
    configUser.withFallback(ConfigFactory.parseMap(akkaConfig.asJava)).withFallback(ConfigFactory.parseResources("config.json"))
  }
  
  // Put the root user in the auth cache in case the db has not been inited yet and they need to be able to run POST /admin/initdb
  def createRootInCache(): Unit = {
    val rootpw: String = config.getString("api.root.password")
    val rootIsEnabled: Boolean = config.getBoolean("api.root.enabled")
    if (rootpw == "" || !rootIsEnabled) {
      logger.warning("Root password is not specified in config.json or is not enabled. You will not be able to do exchange operations that require root privilege.")
      return
    }
    if (rootHashedPw == "") { // this is the 1st time, we need to hash and save it
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
    * and therefore will *not* work when the exchange is running in multi-node config. This method is used mostly for automated testing. */
  def mod(props: Properties): Unit = {
    config = ConfigFactory.parseProperties(props).withFallback(config)
  }
  
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
    val rootemail = "" // Create the root org, create the IBM org, create the root user, and create hub admins listed in config.json (all only if necessary)
    
    val rootOrg: OrgRow = OrgRow(orgId = "root", orgType = "", label = "Root Org", description = "Organization for the root user only", lastUpdated = ApiTime.nowUTC, tags = {
      try {
        Some(JObject("ibmcloud_id" -> JString(config.getString("api.root.account_id"))))
      } catch {
        case _: Exception => None
      }
    }, limits = "", heartbeatIntervals = "")
    
    val rootUser: UserRow = UserRow(admin = true, email = rootemail, hashedPw = rootHashedPw, hubAdmin = true, lastUpdated = ApiTime.nowUTC, orgid = "root", updatedBy = Role.superUser, username = Role.superUser)
    
    val IBMOrg: OrgRow = OrgRow(description = "Organization containing IBM services", heartbeatIntervals = "", label = "IBM Org", lastUpdated = ApiTime.nowUTC, limits = "", orgId = "IBM", orgType = "IBM", tags = None)
    
    val configHubAdmins: ListBuffer[UserRow] = ListBuffer.empty[UserRow]
    config.getObjectList("api.hubadmins").asScala.foreach({ c =>
      if (c.toConfig.getString("org") == "root") {
        configHubAdmins += UserRow(hashedPw = {
          val credential: Option[String] = try {
            Option(c.toConfig.getString("password"))
          } catch {
            case _: Exception => None
          }
          if (credential.isEmpty) "" // No password, IAM User.
          else if (Password.isHashed(credential.get)) credential.get // Password is already hashed.
          else Password.hash(credential.get) // Plain-text, hash.
        }, orgid = c.toConfig.getString("org"), username = c.toConfig.getString("org") + "/" + c.toConfig.getString("user"), admin = false, hubAdmin = true, email = "", lastUpdated = ApiTime.nowUTC, updatedBy = "")
      } else {
        logger.error(s"Hub Admin '${c.toConfig.getString("user")}' not created: hub admin must be in the root org")
      }
    })
    
    val query = for {existingUsers <- UsersTQ.filter(_.username inSet configHubAdmins.map(_.username)).map(_.username).result //get all users whose usernames match a hub admin username in config.json
                     _ <- DBIO.seq(rootOrg.upsert, rootUser.upsertUser, IBMOrg.upsert, UsersTQ ++= configHubAdmins.filter(a => !existingUsers.contains(a.username)) //only insert the ones whose usernames don't already exist
                     )} yield existingUsers
    
    db.run(query.transactionally.asTry).map({ case Success(result) => for (badUser <- result) {
      logger.warning(s"Hub Admin '$badUser' not created: a user with this username already exists")
    }
      logger.info("Successfully updated/inserted root org, root user, IBM org, and hub admins from config")
    case Failure(t) => logger.error(s"Failed to update/insert root org, root user, IBM org, and hub admins from config: ${t.toString}")
    })
  }
  
  //someday: we should catch ConfigException.Missing and ConfigException.WrongType, but they are always set by the built-in config.json
  
  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getString(key: String): String = {
    config.getString(key)
  }
  
  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getStringList(key: String): List[String] = {
    config.getStringList(key).asScala.toList
  }
  
  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getInt(key: String): Int = {
    config.getInt(key)
  }
  
  /** Returns the value of the specified config variable. Throws com.typesafe.config.ConfigException.* if not found. */
  def getBoolean(key: String): Boolean = {
    config.getBoolean(key)
  }
}
