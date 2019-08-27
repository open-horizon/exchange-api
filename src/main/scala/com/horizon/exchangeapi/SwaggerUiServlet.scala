package com.horizon.exchangeapi

import org.scalatra._
import java.io.File

import org.slf4j.LoggerFactory

class SwaggerUiServlet extends ScalatraServlet {
  val logger = LoggerFactory.getLogger(ExchConfig.LOGGER)
  val sortParams = "apisSorter=alpha&operationsSorter=alpha"

	/** Returns the swagger-ui index page. This route is so users can browse, e.g., https://exchange.bluehorizon.network/api/api and will be redirected to the swagger page. */
	get("/") {
    request.queryString.split("&").find(x => x.startsWith("url=")) match {
      // They specified the "url=" param, so pass it along to the swagger-ui
      case Some(_) => contentType = "text/html"
        // This index.html is part of the dist subdir of https://github.com/swagger-api/swagger-ui.git which was copied into src/main/webapp, which sbt copies to target/webapp
        // It takes a param called url which tells it where to get the api-docs data from. This url is from the perspective of the client (browser).
        new File(servletContext.getResource("/swagger-index.html").getFile)

      // This is the normal case, put all of the params on the url that are needed for swagger
      case _ => redirectToOurSwagger
    }
	}

	/** Different URL from above so we can redirect to pass the url param into the swagger page to have it display our api info. */
	get("/exchange") {
    redirectToOurSwagger
	}

  /** Redirects to the / or /api url with the url= param pointed to our swagger info */
  def redirectToOurSwagger = {
    contentType = "text/html"
    logger.info(request.headers.toString)
    // logger.info(request.uri.toString)
    request.header("X-API-Request") match {
      // Staging or prod environment, Haproxy will pass header X-API-Request -> https://exchange.staging.bluehorizon.network/api or https://exchange.bluehorizon.network/api
      // In staging want to produce:  https://exchange.staging.bluehorizon.network/api/api?url=https://exchange.staging.bluehorizon.network/api/api-docs
      // In prod want to produce:  https://exchange.bluehorizon.network/api/api?url=https://exchange.bluehorizon.network/api/api-docs
      // (FYI: params that can be passed into the swagger-ui js (have to edit index.html to do it): https://github.com/swagger-api/swagger-ui#parameters )
      case Some(url) => redirect(url+"/api?"+sortParams+"&url="+url+"/api-docs/swagger.json#/default")

      // Local development environment, we need to change http://localhost:8080/api/exchange to http://localhost:8080/api?url=http://localhost:8080/api-docs
      case None => val R1 = "^(.*)/api$".r
        val R2 = "^(.*)/([^/]*)/exchange$".r
        request.uri.toString match {
          case R1(first) => redirect(first+"/api?"+sortParams+"&url="+first+"/api-docs/swagger.json#/default")
          case R2(first,second) => redirect(first+"/"+second+"?"+sortParams+"&url="+first+"/api-docs/swagger.json#/default")
          case _ => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("unexpected.uri")))
        }
      }
  }

}
