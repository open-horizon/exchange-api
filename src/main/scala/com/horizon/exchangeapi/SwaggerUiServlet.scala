package com.horizon.exchangeapi

import org.scalatra._
import java.io.File
import org.slf4j.LoggerFactory

class SwaggerUiServlet extends ScalatraServlet {
  val logger = LoggerFactory.getLogger(ExchConfig.LOGGER)

	/** Returns the swagger-ui index page. */
	get("/") {
		// <p>Hello</p>
    // logger.info(request.headers.toString)
    // logger.info(request.uri.toString)
    // logger.info(request.queryString)
    request.queryString.split("&").find(x => x.startsWith("url=")) match {
      // They specified the "url=" param, so pass it along to the swagger-ui
      case Some(_) => contentType = "text/html"
        // This index.html is part of the dist subdir of https://github.com/swagger-api/swagger-ui.git which was copied into src/main/webapp, which sbt copies to target/webapp
        // It takes a param called url which tells it where to get the api-docs data from. This url is from the perspective of the client (browser).
        new File(servletContext.getResource("/swagger-index.html").getFile)
        // new File(servletContext.getResource("/index.html").getFile())
        // new File(servletContext.getResource("/index.html?url=http://localhost:8080/api-docs").getFile())  // gave null ptr exception
        // redirect(servletContext.getResource("/index.html")+"?url=http://localhost:8080/api-docs")
      case _ => redirectToOurSwagger
    }
	}

	/** Different URL from above so we can redirect to pass the url param into the swagger page to have it display our api info. */
	get("/exchange") {
    redirectToOurSwagger
	}

  /** redirects to the / or /api url with the url= param pointed to our swagger info */
  def redirectToOurSwagger = {
    contentType = "text/html"
    logger.info(request.headers.toString)
    // logger.info(request.uri.toString)
    request.header("X-API-Request") match {
      // Staging or prod environment, Haproxy will pass header X-API-Request -> https://exchange.staging.bluehorizon.network/api or https://exchange.bluehorizon.network/api
      // In staging want to produce:  https://exchange.staging.bluehorizon.network/api/api?url=https://exchange.staging.bluehorizon.network/api/api-docs
      // In prod want to produce:  https://exchange.bluehorizon.network/api/api?url=https://exchange.bluehorizon.network/api/api-docs
      // (FYI: params that can be passed into the swagger-ui js (have to edit index.html to do it): https://github.com/swagger-api/swagger-ui#parameters )
      case Some(url) => redirect(url+"/api?url="+url+"/api-docs#/v1")
      // Local development environment, we need to change http://localhost:8080/api/exchange to http://localhost:8080/api?url=http://localhost:8080/api-docs
      case None => val R1 = "^(.*)/api$".r
        val R2 = "^(.*)/([^/]*)/exchange$".r
        request.uri.toString match {
          case R1(first) => redirect(first+"/api?url="+first+"/api-docs#/v1")
          case R2(first,second) => redirect(first+"/"+second+"?url="+first+"/api-docs#/v1")
          case _ => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "unexpected uri"))
        }
      }
    // redirect("/api?url=http://localhost:8080/api-docs")
  }

}
