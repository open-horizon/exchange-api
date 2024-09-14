package org.openhorizon.exchangeapi.utility

import com.typesafe.config.{Config, ConfigException, ConfigFactory, ConfigParseOptions}

import scala.util.Properties

case object Configuration {
  private val configResource: String = "exchange.conf"
  private var config: Config = init()
  
  private def init(): Config = {
    config = load()
    
    // Set Java System Properties for logback.xml when the application config is initialized
    setLogBackProperties(config)
    
    config
  }
  
  /**
    * @return Config
    *
    * @throws ConfigException.BugOrBroken throws on config validation failure.
    * @throws ConfigException.NotResolved Throws on config validation failure.
    * @throws ConfigException.ValidationFailed Throws on config validation failure.
    **/
  private def load(): Config = {
    val config = ConfigFactory.load(configResource)
    
    config.checkValid(ConfigFactory.defaultReference(), "api")
    
    config
  }
  
  /**
    * @param properties Java system properties map
    *
    * @return Config
    *
    * @throws ConfigException.BugOrBroken throws on config validation failure.
    * @throws ConfigException.NotResolved Throws on config validation failure.
    * @throws ConfigException.ValidationFailed Throws on config validation failure.
    **/
  private def load(properties: java.util.Properties): Config = {
    val config = ConfigFactory.parseProperties(properties, ConfigParseOptions.defaults()).withFallback(ConfigFactory.load(configResource))
    
    config.checkValid(ConfigFactory.defaultReference(), "api")
    
    config
  }
 
  // Convert Typesafe Config to Java System Properties. These will be substituted into the logback.xml config.
  // These have to be set early enough in the application's start-up to take effect.
  private def setLogBackProperties(config: Config): Unit = {
    Properties.setProp("log.logback.appenderrefmodelhandler", config.getString("logback.core.model.processor.AppenderRefModelHandler"))
    Properties.setProp("log.logback.loggermodelhandler", config.getString("logback.classic.model.processor.LoggerModelHandler"))
    Properties.setProp("log.hikari.config", config.getString("logback.hikari.HikariConfig"))
    Properties.setProp("log.hikari.datasource", config.getString("logback.hikari.HikariDataSource"))
    Properties.setProp("log.hikari.pool", config.getString("logback.hikari.pool.HikariPool"))
    Properties.setProp("log.hikari.pool.base", config.getString("logback.hikari.pool.PoolBase"))
    Properties.setProp("log.guavacache", config.getString("logback.scalacache.guava.GuavaCache"))
    Properties.setProp("log.action", config.getString("logback.slick.basic.BasicBackend.action"))
    Properties.setProp("log.stream", config.getString("logback.slick.basic.BasicBackend.stream"))
    Properties.setProp("log.qcomp", config.getString("logback.slick.compiler-log"))
    Properties.setProp("log.qcomp.assignUniqueSymbols", config.getString("logback.slick.compiler.AssignUniqueSymbols"))
    Properties.setProp("log.qcomp.codeGen", config.getString("logback.slick.compiler.CodeGen"))
    Properties.setProp("log.qcomp.createAggregates", config.getString("logback.slick.compiler.CreateAggregates"))
    Properties.setProp("log.qcomp.createResultSetMapping", config.getString("logback.slick.compiler.CreateResultSetMapping"))
    Properties.setProp("log.qcomp.emulateOuterJoins", config.getString("logback.slick.compiler.EmulateOuterJoins"))
    Properties.setProp("log.qcomp.expandConditionals", config.getString("logback.slick.compiler.ExpandConditionals"))
    Properties.setProp("log.qcomp.expandRecords", config.getString("logback.slick.compiler.ExpandRecords"))
    Properties.setProp("log.qcomp.expandSums", config.getString("logback.slick.compiler.ExpandSums"))
    Properties.setProp("log.qcomp.expandTables", config.getString("logback.slick.compiler.ExpandTables"))
    Properties.setProp("log.qcomp.fixRowNumberOrdering", config.getString("logback.slick.compiler.FixRowNumberOrdering"))
    Properties.setProp("log.qcomp.flattenProjections", config.getString("logback.slick.compiler.FlattenProjections"))
    Properties.setProp("log.qcomp.forceOuterBinds", config.getString("logback.slick.compiler.ForceOuterBinds"))
    Properties.setProp("log.qcomp.hoistClientOps", config.getString("logback.slick.compiler.HoistClientOps"))
    Properties.setProp("log.qcomp.inferTypes", config.getString("logback.slick.compiler.InferTypes"))
    Properties.setProp("log.qcomp.inline", config.getString("logback.slick.compiler.Inline"))
    Properties.setProp("log.qcomp.insertCompiler", config.getString("logback.slick.compiler.InsertCompiler"))
    Properties.setProp("log.qcomp.mergeToComprehensions", config.getString("logback.slick.compiler.MergeToComprehensions"))
    Properties.setProp("log.qcomp.optimizeScalar", config.getString("logback.slick.compiler.OptimizeScalar"))
    Properties.setProp("log.qcomp.pruneProjections", config.getString("logback.slick.compiler.PruneProjections"))
    Properties.setProp("log.qcomp.phases", config.getString("logback.slick.compiler.QueryCompiler"))
    Properties.setProp("log.qcomp.bench", config.getString("logback.slick.compiler.QueryCompilerBenchmark"))
    Properties.setProp("log.qcomp.removeFieldNames", config.getString("logback.slick.compiler.RemoveFieldNames"))
    Properties.setProp("log.qcomp.removeMappedTypes", config.getString("logback.slick.compiler.RemoveMappedTypes"))
    Properties.setProp("log.qcomp.removeTakeDrop", config.getString("logback.slick.compiler.RemoveTakeDrop"))
    Properties.setProp("log.qcomp.reorderOperations", config.getString("logback.slick.compiler.ReorderOperations"))
    Properties.setProp("log.qcomp.resolveZipJoins", config.getString("logback.slick.compiler.ResolveZipJoins"))
    Properties.setProp("log.qcomp.rewriteBooleans", config.getString("logback.slick.compiler.RewriteBooleans"))
    Properties.setProp("log.qcomp.rewriteDistinct", config.getString("logback.slick.compiler.RewriteDistinct"))
    Properties.setProp("log.qcomp.rewriteJoins", config.getString("logback.slick.compiler.RewriteJoins"))
    Properties.setProp("log.qcomp.specializeParameters", config.getString("logback.slick.compiler.SpecializeParameters"))
    Properties.setProp("log.qcomp.verifyTypes", config.getString("logback.slick.compiler.VerifyTypes"))
    Properties.setProp("log.jdbc.driver", config.getString("logback.slick.jdbc.DriverDataSource"))
    Properties.setProp("log.jdbc.bench", config.getString("logback.slick.jdbc.JdbcBackend.benchmark"))
    Properties.setProp("log.jdbc.parameter", config.getString("logback.slick.jdbc.JdbcBackend.parameter"))
    Properties.setProp("log.jdbc.statement", config.getString("logback.slick.jdbc.JdbcBackend.statement"))
    Properties.setProp("log.jdbc.parameter", config.getString("logback.slick.jdbc.JdbcBackend.statementAndParameter"))
    Properties.setProp("log.jdbc.result", config.getString("logback.slick.jdbc.JdbcModelBuilder"))
    Properties.setProp("log.createModel", config.getString("logback.slick.jdbc.StatementInvoker.result"))
    Properties.setProp("log.heap", config.getString("logback.slick.memory.HeapBackend"))
    Properties.setProp("log.interpreter", config.getString("logback.slick.memory.QueryInterpreter"))
    Properties.setProp("log.resultConverter", config.getString("logback.slick.relational.ResultConverterCompiler"))
    Properties.setProp("log.asyncExecutor", config.getString("logback.slick.util.AsyncExecutor"))
    Properties.setProp("log.swagger.modelconvertercontextimpl", config.getString("logback.swagger.v3.core.converter.ModelConverterContextImpl"))
    Properties.setProp("log.swagger.jaxrs2.reader", config.getString("logback.swagger.v3.jaxrs2.Reader"))
  }
 
  def getConfig: Config = config
  def setConfig(config: Config): Unit =
    this.config = config
 
  // Logback can only be configured on startup.
  def reload(): Unit = {
    ConfigFactory.invalidateCaches()
    
    config = load()
  }
  
  // Logback can only be configured on startup.
  def reload(properties: java.util.Properties): Unit = {
    ConfigFactory.invalidateCaches()
    
    config = load(properties = properties)
  }
}
