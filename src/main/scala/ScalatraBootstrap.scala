import com.mchange.v2.c3p0.ComboPooledDataSource
import org.slf4j.LoggerFactory
import com.horizon.exchangeapi._
import org.scalatra._
import javax.servlet.ServletContext
// import slick.driver.PostgresDriver.api._
import slick.jdbc.PostgresProfile.api._
import com.typesafe.config._
import java.io.File

/** Scalatra bootstrap file.
 *
 *  Used to mount servlets or filters, and run initialization code which needs to
 *  run at application start (e.g. database configurations, create loggers), and init params.
 */
class ScalatraBootstrap extends LifeCycle {

  implicit val swagger = new ExchangeApiSwagger     // this gets implicitly used by ExchangeApiApp and ResourcesApp

  val logger = LoggerFactory.getLogger(getClass)

  // Get config file, normally in /etc/horizon/exchange/config.json
  ExchConfig.load

  // Load the db backend. The db access info must be in config.json
  var cpds: ComboPooledDataSource = null
  cpds = new ComboPooledDataSource
  configureC3p0(cpds)
  logger.info("Created c3p0 connection pool")

  /** Initialize the main servlet.
   *
   *  Mounts the top level URLs for the REST API and swagger, and creates the db object.
   */
  override def init(context: ServletContext) {
    val db = if (cpds != null) Database.forDataSource(cpds) else null
    context.mount(new ExchangeApiApp(db), "/v1", "v1")
    context.mount(new ResourcesApp, "/api-docs", "api-docs")
    context.mount(new SwaggerUiServlet, "/api", "api")
  }

  /** Closes the db connection in destroy(). */
  private def closeDbConnection() {
    logger.info("Closing c3po connection pool")
    cpds.close
  }

  /** Closes the db connection when the servlet ends. */
  override def destroy(context: ServletContext) {
    super.destroy(context)
    closeDbConnection
  }

  /** Configure the slick data pool source using values from the exchange config.json file */
  def configureC3p0(cpds: ComboPooledDataSource): Unit = {
    cpds.setDriverClass(ExchConfig.getString("api.db.driverClass")) //loads the jdbc driver
    cpds.setJdbcUrl(ExchConfig.getString("api.db.jdbcUrl"))
    cpds.setUser(ExchConfig.getString("api.db.user"))
    cpds.setPassword(ExchConfig.getString("api.db.password"))
    // the settings below are optional -- c3p0 can work with defaults
    cpds.setMinPoolSize(ExchConfig.getInt("api.db.minPoolSize"))
    cpds.setAcquireIncrement(ExchConfig.getInt("api.db.acquireIncrement"))
    cpds.setMaxPoolSize(ExchConfig.getInt("api.db.maxPoolSize"))
  }
}