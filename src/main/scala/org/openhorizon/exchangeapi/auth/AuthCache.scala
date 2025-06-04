package org.openhorizon.exchangeapi.auth


import org.apache.pekko.event.LoggingAdapter
import org.openhorizon.exchangeapi.ExchangeApi
import org.openhorizon.exchangeapi.utility.Configuration

import scala.concurrent.ExecutionContext


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
          Password.hash(Configuration.getConfig.getString("api.root.password"))
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
