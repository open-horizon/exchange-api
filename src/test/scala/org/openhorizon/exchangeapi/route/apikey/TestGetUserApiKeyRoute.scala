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
import scalaj.http.{Http, HttpResponse}
import scala.concurrent.Await
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._
import org.openhorizon.exchangeapi.auth.{Password, Role}
import scala.concurrent.ExecutionContext.Implicits.global
import _root_.org.openhorizon.exchangeapi.utility.HttpCode
class TestGetUserApiKeyRoute extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val PASSWORD = "password"
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/apikeys/"
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  private val TESTORGS = Seq(
    OrgRow(
      description = "",
      heartbeatIntervals = "",
      label = "",
      lastUpdated = "",
      limits = "",
      orgId = "testGetUserApiKeyOrg0",
      orgType = "",
      tags = None
    ),
    OrgRow(
      description = "",
      heartbeatIntervals = "",
      label = "",
      lastUpdated = "",
      limits = "",
      orgId = "testGetUserApiKeyOrg1",
      orgType = "",
      tags = None
)
  )
  
  private val TESTUSERS = Seq(
    UserRow(
      admin = true,
      email = "",
      hashedPw = Password.hash(PASSWORD),
      hubAdmin = false,
      lastUpdated = "",
      orgid = "testGetUserApiKeyOrg0",
      updatedBy = "",
      username = "testGetUserApiKeyOrg0/admin0"
    ),
    UserRow(
      admin = false,
      email = "",
      hashedPw = Password.hash(PASSWORD),
      hubAdmin = false,
      lastUpdated = "",
      orgid = "testGetUserApiKeyOrg0",
      updatedBy = "",
      username = "testGetUserApiKeyOrg0/user0"
    ),
    UserRow(
      admin = false,
      email = "",
      hashedPw = Password.hash(PASSWORD),
      hubAdmin = false,
      lastUpdated = "",
      orgid = "testGetUserApiKeyOrg1",
      updatedBy = "",
      username = "testGetUserApiKeyOrg1/user1"
    ),

  )

  private val TESTAPIKEYS = Seq(
    ApiKeyRow(
      orgid = "testGetUserApiKeyOrg0",
      id = "key1",
      username = "testGetUserApiKeyOrg0/admin0",
      description = "Test API Key 1",
      hashedKey = "hash1"
    ),
    ApiKeyRow(
      orgid = "testGetUserApiKeyOrg0",
      id = "key2",
      username = "testGetUserApiKeyOrg0/user0",
      description = "Test API Key 2",
      hashedKey = "hash2"
    ),
      ApiKeyRow(
      orgid = "testGetUserApiKeyOrg0",
      id = "key3",
      username = "testGetUserApiKeyOrg0/user0",
      description = "Test API Key 2",
      hashedKey = "hash3"
    ),
    ApiKeyRow(
      orgid = "testGetUserApiKeyOrg1",
      id = "key4",
      username = "testGetUserApiKeyOrg1/user1",
      description = "Test API Key 3",
      hashedKey = "hash4"
    ),
  )

  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("testGetUserApiKeyOrg0/admin0:password"))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode("testGetUserApiKeyOrg0/user0:password"))


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
      ApiKeysTQ.filter(_.orgid startsWith "testGetUserApiKeyOrg").delete
    ), AWAITDURATION)
    Await.ready(DBCONNECTION.run(
      UsersTQ.filter(_.username startsWith "testGetUserApiKeyOrg").delete
    ), AWAITDURATION)
    Await.ready(DBCONNECTION.run(
      OrgsTQ.filter(_.orgid startsWith "testGetUserApiKeyOrg").delete
    ), AWAITDURATION)
  }

 

test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE + " -- user gets own key") {
  val response = Http(
    URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE.dropRight(1)
  ).headers(ACCEPT).headers(USERAUTH).asString

  info("Code: " + response.code)
  info("Body: " + response.body)
  assert(response.code === HttpCode.OK.intValue)
  val responseBody = JsonMethods.parse(response.body).extract[GetUserApiKeysResponse]
  assert(responseBody.apikeys.exists(_.id === "key2"))
}
test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE + " -- org admin gets key of a user in same org") {
  val response = Http(
    URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE.dropRight(1)
  ).headers(ACCEPT).headers(ORGADMINAUTH).asString

  info("Code: " + response.code)
  info("Body: " + response.body)
  assert(response.code === HttpCode.OK.intValue)
  val responseBody = JsonMethods.parse(response.body).extract[GetUserApiKeysResponse]
  assert(responseBody.apikeys.exists(_.id === "key2"))
}

test("GET /orgs/" + TESTORGS(0).orgId + "/users/nonexistent" + ROUTE + " -- 404 not found") {
  val response = Http(
    URL + TESTORGS(0).orgId + "/users/nonexistent" + ROUTE.dropRight(1)
  ).headers(ACCEPT).headers(ORGADMINAUTH).asString

  info("Code: " + response.code)
  info("Body: " + response.body)
  assert(response.code === HttpCode.NOT_FOUND.intValue)
}
test("GET /orgs/" + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username.split("/")(1) + ROUTE + " -- 403 forbidden") {
  val response = Http(
    URL + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username.split("/")(1) + ROUTE.dropRight(1)
  ).headers(ACCEPT).headers(ORGADMINAUTH).asString

  info("Code: " + response.code)
  info("Body: " + response.body)
  assert(response.code === HttpCode.ACCESS_DENIED.intValue)
}
test("GET /orgs/" + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username.split("/")(1) + ROUTE + " -- user tries to get another user's key") {
  val response = Http(
    URL + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username.split("/")(1) + ROUTE.dropRight(1)
  ).headers(ACCEPT).headers(USERAUTH).asString

  info("Code: " + response.code)
  info("Body: " + response.body)
  assert(response.code === HttpCode.ACCESS_DENIED.intValue)
}

// GET /users/{username}/apikeys/{keyid}
test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE + TESTAPIKEYS(1).id + " -- get existing apikey by id") {
  val response = Http(
    URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE + TESTAPIKEYS(1).id
  ).headers(ACCEPT).headers(USERAUTH).asString

  info("Code: " + response.code)
  info("Body: " + response.body)
  assert(response.code === HttpCode.OK.intValue)
}
test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE + "nonexistentkeyid -- should return 404") {
  val response = Http(
    URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE + "nonexistentkeyid"
  ).headers(ACCEPT).headers(USERAUTH).asString

  info("Code: " + response.code)
  info("Body: " + response.body)
  assert(response.code === HttpCode.NOT_FOUND.intValue)
}


}