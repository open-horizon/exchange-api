package com.horizon.exchangeapi

import org.scalatra.swagger.{NativeSwaggerBase, Swagger, ApiInfo}
import org.scalatra.ScalatraServlet

/** The servlet for swagger api doc info.
 *
 *  @constructor create the swagger servlet.
 *  @param swagger instance of ExchangeApiSwagger created in ScalatraBootstrap
 */
class ResourcesApp(implicit val swagger: Swagger) extends ScalatraServlet with NativeSwaggerBase // {
// 	get("/foo") { <p>Hello</p> }
// }

/** Exchange API swagger instance, passed implicitly to ResourcesApp in ScalatraBootstrap. */
//class ExchangeApiSwagger extends Swagger(Swagger.SpecVersion, "1", ApiInfo("The Data Exchange API","Docs for the data exchange","https://termsofservice.bluehorizon.network/","ibm","apache2","http://www.apache.org/licenses/LICENSE-2.0"))
