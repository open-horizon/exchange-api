package com.horizon.exchangeapi

import akka.http.scaladsl.server.Directives
import com.github.swagger.akka.SwaggerHttpService
import com.github.swagger.akka.model.{Info, License}
import io.swagger.v3.oas.models.ExternalDocumentation

/*Swagger references:
  - Swagger with akka-http: https://github.com/swagger-akka-http/swagger-akka-http
  - Swagger annotations: https://github.com/swagger-api/swagger-core/wiki/Swagger-2.X---Annotations
 */

//someday: disable the Try It Out button via the plugin suggestion: https://github.com/swagger-api/swagger-ui/issues/3725

object SwaggerDocService extends SwaggerHttpService {
  //override implicit val actorSystem: ActorSystem = system
  //override implicit val materializer: ActorMaterializer = ActorMaterializer()
  override def apiClasses = Set(
      classOf[AdminRoutes], 
      classOf[AgbotsRoutes], 
      classOf[BusinessRoutes], 
      classOf[CatalogRoutes], 
      classOf[NodesRoutes], 
      classOf[OrgsRoutes], 
      classOf[PatternsRoutes], 
      classOf[ServicesRoutes], 
      classOf[UsersRoutes]
      )
  override def host = s"${ExchangeApi.serviceHost}:${ExchangeApi.servicePortEncrypted}" //the url of your api, not swagger's json endpoint
  override def apiDocsPath = "api-docs" //where you want the swagger-json endpoint exposed

  override def info = Info(
    description = "<b>Note:</b> Test the API with curl:<br><br><code>curl -sS -u &lt;org&gt;/iamapikey:&lt;key&gt; https://&lt;host&gt;:&lt;port&gt;/ec-exchange/v1/orgs/... | jq</code>",
    version = "1.0",
    title = "Exchange API",
    license = Some(License("Apache License Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0")))

  override def externalDocs = Some(new ExternalDocumentation().description("Open-horizon ExchangeAPI").url("https://github.com/open-horizon/exchange-api"))
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
  val swaggerUiRoutes = path("swagger") { getFromResource("swagger/index.html") } ~ getFromResourceDirectory("swagger")
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
