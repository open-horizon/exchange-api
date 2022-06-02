package com.horizon.exchangeapi.route.organization

import com.horizon.exchangeapi.tables.{OrgLimits, OrgsTQ}
import com.horizon.exchangeapi.{ApiUtils, ExchConfig, HttpCode, PostPutOrgRequest, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.native.Serialization
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostOrgRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINPASSWORD = "adminpassword"
  private val USER1PASSWORD = "user1password"
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("root/TestPostOrgRouteRouteHubAdmin:" + HUBADMINPASSWORD))
  private val USER1AUTH = ("Authorization", "Basic " + ApiUtils.encode("testPostOrgRoute1/TestPostOrgRouteUser1:" + USER1PASSWORD))

  private implicit val formats = DefaultFormats

  test("POST /orgs/testPostOrgRoute1 -- invalid body -- 400 bad input") {
    val requestBody: Map[String, String] = Map("invalidKey" -> "invalidValue")
    val request: HttpResponse[String] = Http(URL + "testPostOrgRoute1").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.BAD_INPUT.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.getDb.run(OrgsTQ.getOrgid("testPostOrgRoute1").result), AWAITDURATION).length
    assert(numOrgs === 0)
  }

  //error message "requirement failed" isn't very descriptive here
  test("POST /orgs/testPostOrgRoute1 -- null label -- 400 bad input") {
    val requestBody: Map[String, String] = Map( //can't use PostPutOrgRequest here because it throws an exception if it's improperly created
      "orgType" -> null,
      "label" -> null,
      "description" -> "description",
      "tags" -> null,
      "limits" -> null,
      "heartbeatIntervals" -> null
    )
    val request: HttpResponse[String] = Http(URL + "testPostOrgRoute1").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.BAD_INPUT.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.getDb.run(OrgsTQ.getOrgid("testPostOrgRoute1").result), AWAITDURATION).length
    assert(numOrgs === 0)
  }

  //error message "requirement failed" isn't very descriptive here
  test("POST /orgs/testPostOrgRoute1 -- null description -- 400 bad input") {
    val requestBody: Map[String, String] = Map( //can't use PostPutOrgRequest here because it throws an exception if it's improperly created
      "orgType" -> null,
      "label" -> "label",
      "description" -> null,
      "tags" -> null,
      "limits" -> null,
      "heartbeatIntervals" -> null
    )
    val request: HttpResponse[String] = Http(URL + "testPostOrgRoute1").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.BAD_INPUT.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.getDb.run(OrgsTQ.getOrgid("testPostOrgRoute1").result), AWAITDURATION).length
    assert(numOrgs === 0)
  }

  test("POST /orgs/testPostOrgRoute1 -- max nodes too large -- 400 bad input") {
    val exchangeMaxNodes: Int = ExchConfig.getInt("api.limits.maxNodes")
    val requestBody: PostPutOrgRequest = PostPutOrgRequest( //can't use PostPutOrgRequest here because it throws an exception if it's improperly created
      orgType = None,
      label = "label",
      description = "description",
      tags = None,
      limits = Some(OrgLimits(exchangeMaxNodes + 1)),
      heartbeatIntervals = None
    )
    val request: HttpResponse[String] = Http(URL + "testPostOrgRoute1").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.BAD_INPUT.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.getDb.run(OrgsTQ.getOrgid("testPostOrgRoute1").result), AWAITDURATION).length
    assert(numOrgs === 0)
  }

  /*test("POST /orgs/testPostOrgRoute1 as root -- normal success") {
    val requestBody: PostPutOrgRequest = PostPutOrgRequest( //can't use PostPutOrgRequest here because it throws an exception if it's improperly created
      orgType = None,
      label = "label",
      description = "description",
      tags = None,
      limits = None,
      heartbeatIntervals = None
    )
  }*/

}
