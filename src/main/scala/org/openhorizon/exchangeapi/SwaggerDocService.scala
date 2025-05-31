package org.openhorizon.exchangeapi

import org.openhorizon.exchangeapi.route.administration.{ClearAuthCache, Configuration, DropDatabase, InitializeDatabase, OrganizationStatus, Reload, Status, Version}
import org.apache.pekko.event.Logging.Info
import org.apache.pekko.http.scaladsl.model.headers.LinkParams.title
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import com.github.swagger.pekko.SwaggerHttpService
import com.github.swagger.pekko.model.{Info, License}
import org.openhorizon.exchangeapi.route.administration.{DropDatabase, InitializeDatabase, OrganizationStatus, Reload, Status, Version}
import io.swagger.v3.oas.models.ExternalDocumentation
import org.openhorizon.exchangeapi.route.administration.dropdatabase.Token
import org.openhorizon.exchangeapi.route.agent.AgentConfigurationManagement
import org.openhorizon.exchangeapi.route.agreement.Confirm
import org.openhorizon.exchangeapi.route.agreementbot.agreement.{Agreement, Agreements}
import org.openhorizon.exchangeapi.route.agreementbot.message.{Message, Messages}
import org.openhorizon.exchangeapi.route.agreementbot.{AgreementBot, AgreementBots, DeploymentPattern, DeploymentPatterns, DeploymentPolicies, DeploymentPolicy, Heartbeat}
//import org.openhorizon.exchangeapi.route.catalog.OrganizationDeploymentPatterns
import org.openhorizon.exchangeapi.route.deploymentpattern.{DeploymentPatterns, Search}
import org.openhorizon.exchangeapi.route.deploymentpolicy.{DeploymentPolicy, DeploymentPolicySearch}
import org.openhorizon.exchangeapi.route.managementpolicy.{ManagementPolicies, ManagementPolicy}
import org.openhorizon.exchangeapi.route.node.managementpolicy.Statuses
import org.openhorizon.exchangeapi.route.node.{ConfigurationState, Details, Errors, Node, NodeDetails, Nodes}
import org.openhorizon.exchangeapi.route.nodegroup.{NodeGroup, NodeGroups}
import org.openhorizon.exchangeapi.route.organization.{Changes, Cleanup, MaxChangeId, MyOrganizations, Organization, Organizations}
import org.openhorizon.exchangeapi.route.search.{NodeError, NodeErrors, NodeHealth, NodeService}
import org.openhorizon.exchangeapi.route.service.dockerauth.{DockerAuth, DockerAuths}
import org.openhorizon.exchangeapi.route.service.key.{Key, Keys}
import org.openhorizon.exchangeapi.route.service.{Policy, Service, Services}
import org.openhorizon.exchangeapi.route.user.{ChangePassword, Confirm, User, Users}
import org.openhorizon.exchangeapi.utility.Configuration

/*Swagger references:
  - Swagger with pekko-http: https://github.com/swagger-pekko-http/swagger-pekko-http
  - Swagger annotations: https://github.com/swagger-api/swagger-core/wiki/Swagger-2.X---Annotations
 */

//someday: disable the Try It Out button via the plugin suggestion: https://github.com/swagger-api/swagger-ui/issues/3725

object SwaggerDocService extends SwaggerHttpService {
  //override implicit val actorSystem: ActorSystem = system
  //override implicit val materializer: ActorMaterializer = ActorMaterializer()
  override def apiClasses: Set[Class[_]] =
    Set(classOf[org.openhorizon.exchangeapi.route.administration.ClearAuthCache],
        classOf[org.openhorizon.exchangeapi.route.administration.Configuration],
        classOf[org.openhorizon.exchangeapi.route.administration.DropDatabase],
        classOf[org.openhorizon.exchangeapi.route.administration.dropdatabase.Token],
        classOf[org.openhorizon.exchangeapi.route.administration.InitializeDatabase],
        classOf[org.openhorizon.exchangeapi.route.administration.OrganizationStatus],
        classOf[org.openhorizon.exchangeapi.route.administration.Reload],
        classOf[org.openhorizon.exchangeapi.route.administration.Status],
        classOf[org.openhorizon.exchangeapi.route.administration.Version],
        classOf[org.openhorizon.exchangeapi.route.agent.AgentConfigurationManagement],
        classOf[org.openhorizon.exchangeapi.route.agreement.Confirm],
        classOf[Agreement],
        classOf[org.openhorizon.exchangeapi.route.agreementbot.AgreementBot],
        classOf[org.openhorizon.exchangeapi.route.agreementbot.AgreementBots],
        classOf[Agreements],
        classOf[org.openhorizon.exchangeapi.route.agreementbot.DeploymentPattern],
        classOf[org.openhorizon.exchangeapi.route.agreementbot.DeploymentPatterns],
        classOf[org.openhorizon.exchangeapi.route.agreementbot.DeploymentPolicies],
        classOf[org.openhorizon.exchangeapi.route.agreementbot.DeploymentPolicy],
        classOf[org.openhorizon.exchangeapi.route.agreementbot.Heartbeat],
        classOf[Message],
        classOf[Messages],
        classOf[org.openhorizon.exchangeapi.route.catalog.DeploymentPatterns],
        //classOf[org.openhorizon.exchangeapi.route.catalog.OrganizationDeploymentPatterns],
        // classOf[org.openhorizon.exchangeapi.route.catalog.OrganizationServices],
        classOf[org.openhorizon.exchangeapi.route.catalog.Services],
        classOf[org.openhorizon.exchangeapi.route.deploymentpattern.DeploymentPattern],
        classOf[org.openhorizon.exchangeapi.route.deploymentpattern.DeploymentPatterns],
        classOf[org.openhorizon.exchangeapi.route.deploymentpattern.key.Key],
        classOf[org.openhorizon.exchangeapi.route.deploymentpattern.key.Keys],
        classOf[org.openhorizon.exchangeapi.route.deploymentpattern.NodeHealth],
        classOf[org.openhorizon.exchangeapi.route.deploymentpattern.Search],
        classOf[org.openhorizon.exchangeapi.route.deploymentpolicy.DeploymentPolicies],
        classOf[org.openhorizon.exchangeapi.route.deploymentpolicy.DeploymentPolicy],
        classOf[org.openhorizon.exchangeapi.route.deploymentpolicy.DeploymentPolicySearch],
        classOf[org.openhorizon.exchangeapi.route.managementpolicy.ManagementPolicies],
        classOf[org.openhorizon.exchangeapi.route.managementpolicy.ManagementPolicy],
        classOf[org.openhorizon.exchangeapi.route.node.agreement.Agreement],
        classOf[org.openhorizon.exchangeapi.route.node.agreement.Agreements],
        classOf[org.openhorizon.exchangeapi.route.node.ConfigurationState],
        classOf[org.openhorizon.exchangeapi.route.node.Details],
        classOf[org.openhorizon.exchangeapi.route.node.Errors],
        classOf[org.openhorizon.exchangeapi.route.node.Heartbeat],
        classOf[org.openhorizon.exchangeapi.route.node.managementpolicy.Status],
        classOf[org.openhorizon.exchangeapi.route.node.managementpolicy.Statuses],
        classOf[org.openhorizon.exchangeapi.route.node.message.Message],
        classOf[org.openhorizon.exchangeapi.route.node.message.Messages],
        classOf[org.openhorizon.exchangeapi.route.node.Node],
        classOf[org.openhorizon.exchangeapi.route.node.Nodes],
        classOf[org.openhorizon.exchangeapi.route.node.Policy],
        classOf[org.openhorizon.exchangeapi.route.node.Status],
        classOf[org.openhorizon.exchangeapi.route.nodegroup.node.Node],
        classOf[org.openhorizon.exchangeapi.route.nodegroup.NodeGroup],
        classOf[org.openhorizon.exchangeapi.route.nodegroup.NodeGroups],
        classOf[org.openhorizon.exchangeapi.route.organization.Cleanup],
        classOf[org.openhorizon.exchangeapi.route.organization.Changes],
        classOf[org.openhorizon.exchangeapi.route.organization.Cleanup],
        classOf[org.openhorizon.exchangeapi.route.organization.MaxChangeId],
        classOf[org.openhorizon.exchangeapi.route.organization.MyOrganizations],
        classOf[org.openhorizon.exchangeapi.route.organization.Organization],
        classOf[org.openhorizon.exchangeapi.route.organization.Organizations],
        classOf[org.openhorizon.exchangeapi.route.search.NodeError],
        classOf[org.openhorizon.exchangeapi.route.search.NodeErrors],
        classOf[org.openhorizon.exchangeapi.route.search.NodeHealth],
        classOf[org.openhorizon.exchangeapi.route.search.NodeService],
        classOf[org.openhorizon.exchangeapi.route.service.dockerauth.DockerAuth],
        classOf[org.openhorizon.exchangeapi.route.service.dockerauth.DockerAuths],
        classOf[org.openhorizon.exchangeapi.route.service.key.Key],
        classOf[org.openhorizon.exchangeapi.route.service.key.Keys],
        classOf[org.openhorizon.exchangeapi.route.service.Policy],
        classOf[org.openhorizon.exchangeapi.route.service.Service],
        classOf[org.openhorizon.exchangeapi.route.service.Services],
        classOf[org.openhorizon.exchangeapi.route.user.ChangePassword],
        classOf[org.openhorizon.exchangeapi.route.user.Confirm],
        classOf[org.openhorizon.exchangeapi.route.user.User],
        classOf[org.openhorizon.exchangeapi.route.user.Users])
  override def apiDocsPath: String = "api-docs" //where you want the swagger-json endpoint exposed
  // override def basePath: String = ""
  private def domain: String = {
    val configServiceHost = Configuration.getConfig.getString("api.service.host")
    if (configServiceHost.equals("0.0.0.0"))
      "localhost"
    else
      configServiceHost
  }
  
  private def servicePortEncrypted: Option[Int] =
    if (Configuration.getConfig.hasPath("pekko.http.server.default-https-port"))
      Option(Configuration.getConfig.getInt("pekko.http.server.default-https-port"))
    else
      None
  
  private def servicePortUnencrypted: Option[Int] =
    if (Configuration.getConfig.hasPath("pekko.http.server.default-http-port"))
      Option(Configuration.getConfig.getInt("pekko.http.server.default-http-port"))
    else
      None
  
  override def host: String = domain + ":" + servicePortEncrypted.getOrElse(servicePortUnencrypted) //the url of your api, not swagger's json endpoint
  
  override def info: com.github.swagger.pekko.model.Info =
    com.github.swagger.pekko.model.Info(
    description = "<b>Note:</b> Test the API with curl:<br><br><code>curl -sS -u &lt;org&gt;/iamapikey:&lt;key&gt; https://&lt;host&gt;:&lt;port&gt;/edge-exchange/v1/orgs/... | jq</code></br></br>This API specification is intended to be used by developers",
    version = ExchangeApi.versionText,
    title = "Exchange API",
    license = Option(License("Apache License Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0")))

  override def externalDocs: Option[ExternalDocumentation] = Option(new ExternalDocumentation().description("Open-horizon ExchangeAPI").url("https://github.com/open-horizon/exchange-api"))
  override def schemes: List[String] =
    if (servicePortEncrypted.isDefined)
      List("http", "https")
    else
      List("http")
  //override val securitySchemeDefinitions = Map("basicAuth" -> new BasicAuthDefinition())
  override def unwantedDefinitions: Seq[String] = Seq("Function1", "Function1RequestContextFutureRouteResult")
  
  
}

/* Defines a route (that can be used in a browser) to return the swagger.json file that is built by SwaggerDocService.
  - Swagger UI static files come from: https://github.com/swagger-api/swagger-ui/dist
  - The Makefile target sync-swagger-ui puts those files under src/main/resources/swagger and modifies the url value in index.html to be /v1/api-docs/swagger.json
  - Configuration of the UI display: https://github.com/swagger-api/swagger-ui/blob/master/docs/usage/configuration.md
  - Maybe explore using https://github.com/pragmatico/swagger-ui-pekko-http to run the swagger ui
 */
trait SwaggerUiService extends Directives {
  val swaggerUiRoutes: Route =
    path("swagger") {
      getFromResource("swagger/index.html")
    } ~
    getFromResourceDirectory("swagger")
}

/* this didn't work because swagger annotations need to be constants
object SwaggerUtils {
  def resp(successCode: StatusCode, responseClass: Class[_], otherCodes: StatusCode*) = {
    val r = Array(new responses.ApiResponse(responseCode = successCode.intValue.toString, description = "Response body:",
      content = Array(new Content(schema = new Schema(implementation = responseClass)))))
    r ++ otherCodes.map(c => new responses.ApiResponse(responseCode = c.intValue.toString, description = c.reason() )).toArray
  }
}
*/
