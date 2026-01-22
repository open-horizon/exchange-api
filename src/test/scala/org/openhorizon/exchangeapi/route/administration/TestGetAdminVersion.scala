package org.openhorizon.exchangeapi.route.administration

import org.json4s.DefaultFormats
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.Http
import org.openhorizon.exchangeapi.utility.{ApiUtils, HttpCode}
import org.openhorizon.exchangeapi.utility.Configuration

class TestGetAdminVersion extends AnyFunSuite {
  private val ACCEPTTEXT = ("Accept", "text/plain")
  private val API_URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/admin/version"

  test("GET /admin/version - should return 200 OK with version info") {
    val response = Http(API_URL).headers(ACCEPTTEXT).asString

    info("http status code: " + response.code)
    info("version: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
  }
}
