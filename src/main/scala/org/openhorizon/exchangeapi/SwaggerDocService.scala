package org.openhorizon.exchangeapi

import akka.event.Logging.Info
import akka.http.scaladsl.model.headers.LinkParams.title
import akka.http.scaladsl.server.{Directives, Route}
import com.github.swagger.akka.SwaggerHttpService
import com.github.swagger.akka.model.{Info, License}
import org.openhorizon.exchangeapi.route.admin.AdminRoutes
import io.swagger.v3.oas.models.ExternalDocumentation
import org.openhorizon.exchangeapi.route.agreementbot.AgbotsRoutes
import org.openhorizon.exchangeapi.route.deploymentpattern.PatternsRoutes
import org.openhorizon.exchangeapi.route.deploymentpolicy.BusinessRoutes
import org.openhorizon.exchangeapi.route.managementpolicy.ManagementPoliciesRoutes
import org.openhorizon.exchangeapi.route.node.NodesRoutes
import org.openhorizon.exchangeapi.route.nodegroup.NodeGroupRoutes
import org.openhorizon.exchangeapi.route.organization.OrgsRoutes
import org.openhorizon.exchangeapi.route.service.ServicesRoutes
import org.openhorizon.exchangeapi.route.user.UsersRoutes

/*Swagger references:
  - Swagger with akka-http: https://github.com/swagger-akka-http/swagger-akka-http
  - Swagger annotations: https://github.com/swagger-api/swagger-core/wiki/Swagger-2.X---Annotations
 */

//someday: disable the Try It Out button via the plugin suggestion: https://github.com/swagger-api/swagger-ui/issues/3725

object SwaggerDocService extends SwaggerHttpService {
  //override implicit val actorSystem: ActorSystem = system
  //override implicit val materializer: ActorMaterializer = ActorMaterializer()
  override def apiClasses = Set(
      classOf[AgentConfigurationManagementRoutes],
      classOf[AdminRoutes],
      classOf[AgbotsRoutes], 
      classOf[BusinessRoutes], 
      classOf[CatalogRoutes], 
      classOf[ManagementPoliciesRoutes],
      classOf[NodesRoutes],
      classOf[NodeGroupRoutes],
      classOf[OrgsRoutes], 
      classOf[PatternsRoutes], 
      classOf[ServicesRoutes], 
      classOf[UsersRoutes]
      )
  override def apiDocsPath: String = "api-docs" //where you want the swagger-json endpoint exposed
  // override def basePath: String = ""
  override def host: String = (if(ExchangeApi.serviceHost.equals("0.0.0.0")) "localhost" else ExchangeApi.serviceHost) + ":" + ExchangeApi.servicePortEncrypted.getOrElse(ExchangeApi.servicePortUnencrypted) //the url of your api, not swagger's json endpoint
  override def info: com.github.swagger.akka.model.Info = com.github.swagger.akka.model.Info(
    description = "<b>Note:</b> Test the API with curl:<br><br><code>curl -sS -u &lt;org&gt;/iamapikey:&lt;key&gt; https://&lt;host&gt;:&lt;port&gt;/edge-exchange/v1/orgs/... | jq</code></br></br>This API specification is intended to be used by developers",
    version = ExchangeApi.versionText,
    title = "Exchange API",
    license = Option(License("Apache License Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0")))

  override def externalDocs: Option[ExternalDocumentation] = Option(new ExternalDocumentation().description("Open-horizon ExchangeAPI").url("https://github.com/open-horizon/exchange-api"))
  override def schemes: List[String] = if(ExchangeApi.servicePortEncrypted.isDefined) List("http", "https") else List("http")
  //override val securitySchemeDefinitions = Map("basicAuth" -> new BasicAuthDefinition())
  override def unwantedDefinitions = Seq("Function1", "Function1RequestContextFutureRouteResult")
}

/* Defines a route (that can be used in a browser) to return the swagger.json file that is built by SwaggerDocService.
  - Swagger UI static files come from: https://github.com/swagger-api/swagger-ui/dist
  - The Makefile target sync-swagger-ui puts those files under src/main/resources/swagger and modifies the url value in index.html to be /v1/api-docs/swagger.json
  - Configuration of the UI display: https://github.com/swagger-api/swagger-ui/blob/master/docs/usage/configuration.md
  - Maybe explore using https://github.com/pragmatico/swagger-ui-akka-http to run the swagger ui
 */
trait SwaggerUiService extends Directives {
  val swaggerUiRoutes: Route = path("swagger") {getFromResource("swagger/index.html") } ~ getFromResourceDirectory("swagger")
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
