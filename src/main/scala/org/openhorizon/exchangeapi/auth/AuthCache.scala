package org.openhorizon.exchangeapi.auth

import com.google.common.cache
import com.google.common.cache.CacheBuilder
import org.apache.pekko.event.LoggingAdapter
import slick.dbio.Effect
import slick.sql.FixedSqlStreamingAction

import scala.util.matching.Regex
import org.openhorizon.exchangeapi.ExchangeApi
import org.openhorizon.exchangeapi.ExchangeApiApp.{complete, system}
import org.openhorizon.exchangeapi.auth.CacheIdType.CacheIdType
import org.openhorizon.exchangeapi.auth.cloud.IbmCloudAuth
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.deploymentpattern.PatternsTQ
import org.openhorizon.exchangeapi.table.deploymentpolicy.BusinessPoliciesTQ
import org.openhorizon.exchangeapi.table.managementpolicy.ManagementPoliciesTQ
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.service.ServicesTQ
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{Configuration, DatabaseConnection, ExchMsg}
import scalacache._
import scalacache.modes.try_._
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

/** In-memory cache of the user/pw, node id/token, and agbot id/token, where the pw and tokens are not hashed to speed up validation */
object AuthCache /* extends Control with ServletApiImplicits */ {
  def logger: LoggingAdapter = ExchangeApi.defaultLogger
  implicit def executionContext: ExecutionContext = ExchangeApi.defaultExecutionContext
  

  /** Holds recently authenticated users, node ids, agbot ids */
  class CacheId() {
  
  // Put the root user in the auth cache in case the db has not been inited yet and they need to be able to run POST /admin/initdb
  def createRootInCache(): Unit = {
    
    //Identity2(identifier = None, organization = "root", owner = None, role = AuthRoles.SuperUser, username = "root")
    
    val configRootPasswdHashed = {
      try {
        if(Configuration.getConfig.getBoolean("api.root.enabled"))
          Password.hashIfNot(Configuration.getConfig.getString("api.root.password"))
        else
          ""
      }
      catch {
        case _: Exception => ""
      }
    }
    
    // TODO: putUser(Role.superUser, configRootPasswdHashed, "")
    
    logger.info("Root user from config.json added to the in-memory authentication cache")
  }
}}
