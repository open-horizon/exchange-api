package org.openhorizon.exchangeapi.route.administration

import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization.write
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.Http
import org.openhorizon.exchangeapi.utility.{ApiUtils, HttpCode}
import org.openhorizon.exchangeapi.auth.Role
import org.openhorizon.exchangeapi.utility.Configuration

class TestDeleteOrgChanges extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept", "application/json")
  private val CONTENT = ("Content-Type", "application/json")
  private val ROOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val API_URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private implicit val formats: DefaultFormats.type = DefaultFormats
  // have to insert some data first, will update later
  // test("DELETE /orgs/root/changes/cleanup - should return 204 NO CONTENT") {
  //   val input = DeleteOrgChangesRequest(List("admin", "user", "agbot", "pattern", "node", "service", "hubadmin"))
  //   val response = Http(API_URL + "root/changes/cleanup").postData(write(input))
  //     .method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString

  //   info("http status code: " + response.code)
  //   info("response body: " + response.body)
  //   assert(response.code === HttpCode.DELETED.intValue)
  // }

  // test("DELETE /orgs/{org}/changes/cleanup - should return 204 NO CONTENT for specific org") {
  //   val input = DeleteOrgChangesRequest(List())
  //   val response = Http(API_URL + "someOrg/changes/cleanup").postData(write(input))
  //     .method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString

  //   info("http status code: " + response.code)
  //   info("response body: " + response.body)
  //   assert(response.code === HttpCode.DELETED.intValue)
  // }
}
