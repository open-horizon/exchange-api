package org.openhorizon.exchangeapi.route.apikey

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.openhorizon.exchangeapi.auth.{Identity, Role}
import org.openhorizon.exchangeapi.table.apikey.{ApiKeyMetadata, ApiKeyRow, ApiKeysTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiUtils, Configuration, DatabaseConnection, ExchMsg}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, ExchMsg, HttpCode}
import scalaj.http.{Http, HttpResponse}
import scala.concurrent.Await
import org.openhorizon.exchangeapi.auth.{Password, Role}
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.ExecutionContext.Implicits.global
import org.openhorizon.exchangeapi.utility.HttpCode

class TestGetOrgApiKeyRoute extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/apikeys/"
  private val PASSWORD = "password"
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  private val TESTORGS: Seq[OrgRow] = Seq(
    OrgRow(
      description = "",
      heartbeatIntervals = "",
      label = "",
      lastUpdated = "",
      limits = "",
      orgId = "testGetOrgApiKeyRouteOrg0",
      orgType = "",
      tags = None
    ),
    OrgRow(
      description = "",
      heartbeatIntervals = "",
      label = "",
      lastUpdated = "",
      limits = "",
      orgId = "testGetOrgApiKeyRouteOrg1",
      orgType = "",
      tags = None
    )
  )
  
  private val TESTUSERS: Seq[UserRow] = Seq(
    UserRow(
      admin = true,
      email = "",
      hashedPw = Password.hash(PASSWORD),
      hubAdmin = false,
      lastUpdated = "",
      orgid = "testGetOrgApiKeyRouteOrg0",
      updatedBy = "",
      username = "testGetOrgApiKeyRouteOrg0/admin0"
    ),
    UserRow(
      admin = false,
      email = "",
      hashedPw = Password.hash(PASSWORD),
      hubAdmin = false,
      lastUpdated = "",
      orgid = "testGetOrgApiKeyRouteOrg0",
      updatedBy = "",
      username = "testGetOrgApiKeyRouteOrg0/user0"
    ),
    UserRow(
      admin = true,
      email = "",
      hashedPw = Password.hash(PASSWORD),
      hubAdmin = true,
      lastUpdated = "",
      orgid = "root",
      updatedBy = "",
      username = "root/hubadmin0"
    )
  )
  
  private val TESTAPIKEYS: Seq[ApiKeyRow] = Seq(
    ApiKeyRow(
      orgid = "testGetOrgApiKeyRouteOrg0",
      id = "key1",
      username = "testGetOrgApiKeyRouteOrg0/admin0",
      description = "Test API Key 1",
      hashedKey = "hash1"
    ),
    ApiKeyRow(
      orgid = "testGetOrgApiKeyRouteOrg0",
      id = "key2",
      username = "testGetOrgApiKeyRouteOrg0/user0",
      description = "Test API Key 2",
      hashedKey = "hash2"
    ),
    ApiKeyRow(
      orgid = "testGetOrgApiKeyRouteOrg1",
      id = "key3",
      username = "root/hubadmin0",
      description = "Test API Key 3",
      hashedKey = "hash3"
    )
  )
  
  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("testGetOrgApiKeyRouteOrg0/admin0:password"))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode("testGetOrgApiKeyRouteOrg0/user0:password"))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("root/hubadmin0:password"))
 override def beforeAll(): Unit = {
    val setupAction = DBIO.seq(
      OrgsTQ ++= TESTORGS,
      UsersTQ ++= TESTUSERS,
      ApiKeysTQ ++= TESTAPIKEYS
    ).map(_ => TESTAPIKEYS.length)
    
    val result = Await.result(DBCONNECTION.run(setupAction.transactionally), AWAITDURATION)
    assert(result == TESTAPIKEYS.length,
           s"Failed to insert all API keys: expected=${TESTAPIKEYS.length}, actual=$result")
  }
  
override def afterAll(): Unit = {
  Await.ready(DBCONNECTION.run(
    ApiKeysTQ.filter(_.orgid startsWith "testGetOrgApiKeyRouteOrg").delete
  ), AWAITDURATION)
  Await.ready(DBCONNECTION.run(
    UsersTQ.filter(_.username startsWith "testGetOrgApiKeyRouteOrg").delete andThen
    UsersTQ.filter(_.username === "root/hubadmin0").delete
  ), AWAITDURATION)
  Await.ready(DBCONNECTION.run(
    OrgsTQ.filter(_.orgid startsWith "testGetOrgApiKeyRouteOrg").delete
  ), AWAITDURATION)
}


test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- org admin - 200 ok - normal success") {
  val response = Http(URL + TESTORGS(0).orgId + ROUTE.dropRight(1))
    .headers(ACCEPT)
    .headers(ORGADMINAUTH)
    .asString
  info("Code: " + response.code)
  info("Body: " + response.body)
  assert(response.code === HttpCode.OK.intValue)
  val apikeys: GetOrgApiKeysResponse = JsonMethods.parse(response.body).extract[GetOrgApiKeysResponse]
  assert(apikeys.apikeys.length === 2)
  assert(apikeys.apikeys.exists(_.id === TESTAPIKEYS(0).id))
  assert(apikeys.apikeys.exists(_.id === TESTAPIKEYS(1).id))
}

test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- normal user - 403 forbidden") {
  val response = Http(URL + TESTORGS(0).orgId + ROUTE.dropRight(1))
    .headers(ACCEPT)
    .headers(USERAUTH)
    .asString
  info("Code: " + response.code)
  info("Body: " + response.body)
  assert(response.code === HttpCode.ACCESS_DENIED.intValue)
}

test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- hub admin - 403 forbidden for other org") {
  val response = Http(URL + TESTORGS(0).orgId + ROUTE.dropRight(1))
    .headers(ACCEPT)
    .headers(HUBADMINAUTH)
    .asString
  info("Code: " + response.code)
  info("Body: " + response.body)
  assert(response.code === HttpCode.ACCESS_DENIED.intValue)
}

test("GET /orgs/root" + ROUTE + " -- hub admin - 200 ok for root org") {
  val response = Http(URL + "root" + ROUTE.dropRight(1))
    .headers(ACCEPT)
    .headers(HUBADMINAUTH)
    .asString
  info("Code: " + response.code)
  info("Body: " + response.body)
  assert(response.code === HttpCode.OK.intValue)
}

  
test("GET /orgs/nonExistentOrg" + ROUTE + " -- non-existent org - 404 not found") {
  val response = Http(URL + "nonExistentOrg" + ROUTE.dropRight(1))
    .headers(ACCEPT)
    .headers(ORGADMINAUTH)
    .asString
  info("Code: " + response.code)
  info("Body: " + response.body)
  assert(response.code === HttpCode.NOT_FOUND.intValue)
}

test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- no auth header - should return 401") {
  val response = Http(URL + TESTORGS(0).orgId + ROUTE.dropRight(1))
    .headers(ACCEPT)
    .asString
  info("Code: " + response.code)
  info("Body: " + response.body)
  assert(response.code === HttpCode.BADCREDS.intValue)
}


}