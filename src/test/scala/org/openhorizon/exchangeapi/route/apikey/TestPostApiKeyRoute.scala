package org.openhorizon.exchangeapi.route.apikey

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.openhorizon.exchangeapi.auth.{Identity, Role}
import org.openhorizon.exchangeapi.table.apikey.{ApiKeyMetadata, ApiKeyRow, ApiKeysTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
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
class TestPostApiKeyRoute extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/apikeys/"
  private val PASSWORD = "password"
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  private val TESTORGS = Seq(
    OrgRow(
      description = "",
      heartbeatIntervals = "",
      label = "",
      lastUpdated = "",
      limits = "",
      orgId = "testPostApiKeyRouteOrg0",
      orgType = "",
      tags = None
    ),
    OrgRow(
      description = "",
      heartbeatIntervals = "",
      label = "",
      lastUpdated = "",
      limits = "",
      orgId = "testPostApiKeyRouteOrg1",
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
      orgid = "testPostApiKeyRouteOrg0",
      updatedBy = "",
      username = "testPostApiKeyRouteOrg0/admin0"
    ),
    UserRow(
      admin = false,
      email = "",
      hashedPw = Password.hash(PASSWORD),
      hubAdmin = false,
      lastUpdated = "",
      orgid = "testPostApiKeyRouteOrg0",
      updatedBy = "",
      username = "testPostApiKeyRouteOrg0/user0"
    ),
    UserRow(
      admin = false,
      email = "",
      hashedPw = Password.hash(PASSWORD),
      hubAdmin = false,
      lastUpdated = "",
      orgid = "testPostApiKeyRouteOrg1",
      updatedBy = "",
      username = "testPostApiKeyRouteOrg1/user1"
    ),

  )


  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("testPostApiKeyRouteOrg0/admin0:password"))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode("testPostApiKeyRouteOrg0/user0:password"))


override def beforeAll(): Unit = {
  val setupAction = DBIO.seq(
    OrgsTQ ++= TESTORGS,
    UsersTQ ++= TESTUSERS
  )
  Await.result(DBCONNECTION.run(setupAction.transactionally), AWAITDURATION)
}

  override def afterAll(): Unit = {
        Await.ready(DBCONNECTION.run(
      ResourceChangesTQ.filter(_.orgId startsWith "testPostApiKeyRouteOrg").delete
    ), AWAITDURATION)
    Await.ready(DBCONNECTION.run(
      ApiKeysTQ.filter(_.orgid startsWith "testPostApiKeyRouteOrg").delete
    ), AWAITDURATION)
    Await.ready(DBCONNECTION.run(
      UsersTQ.filter(_.username startsWith "testPostApiKeyRouteOrg").delete 
    ), AWAITDURATION)
    Await.ready(DBCONNECTION.run(
      OrgsTQ.filter(_.orgid startsWith "testPostApiKeyRouteOrg").delete
    ), AWAITDURATION)
  }


  // POST /v1/orgs/{orgid}/users/{username}/apikeys 
test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE + " -- user creates apikey") {
  val response = Http(
    URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE.dropRight(1)
  ).headers(ACCEPT)
   .headers(USERAUTH)
   .header("Content-Type", "application/json")
   .postData("""{"description": "Test API Key"}""")
   .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
   .asString

   info("Code: " + response.code)
   info("Body: " + response.body)
  assert(response.code === HttpCode.POST_OK.intValue)
  val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]
  assert(responseBody.user === TESTUSERS(1).username.split("/")(1))
  assert(responseBody.description === "Test API Key")
  assert(responseBody.id.nonEmpty)
  assert(responseBody.value.nonEmpty)
}


test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username.split("/")(1) + ROUTE + " -- org admin creates apikey") {
  val response = Http(
    URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username.split("/")(1) + ROUTE.dropRight(1)
  ).headers(ACCEPT)
   .headers(ORGADMINAUTH)
   .header("Content-Type", "application/json")
   .postData("""{"description": "Admin Created Key"}""")
   .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
   .asString

   info("Code: " + response.code)
   info("Body: " + response.body)

   assert(response.code === HttpCode.POST_OK.intValue)
   val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]
   assert(responseBody.user === TESTUSERS(0).username.split("/")(1))
   assert(responseBody.description === "Admin Created Key")
   assert(responseBody.id.nonEmpty)
   assert(responseBody.value.nonEmpty)
}

test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE + " -- org admin creates apikey for user") {
  val response = Http(
    URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE.dropRight(1)
  ).headers(ACCEPT)
   .headers(ORGADMINAUTH)
   .header("Content-Type", "application/json")
   .postData("""{"description": "Admin Created Key for a user"}""")
   .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
   .asString

   info("Code: " + response.code)
   info("Body: " + response.body)

  assert(response.code === HttpCode.POST_OK.intValue)
  val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]
  assert(responseBody.user === TESTUSERS(1).username.split("/")(1))
  assert(responseBody.description === "Admin Created Key for a user")
  assert(responseBody.id.nonEmpty)
  assert(responseBody.value.nonEmpty)
}

test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE + " -- without auth should return 401") {
  val response = Http(
    URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE.dropRight(1)
  ).headers(ACCEPT)
   .header("Content-Type", "application/json")
   .postData("""{"description": "No Auth"}""")
   .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
   .asString

   info("Code: " + response.code)
   info("Body: " + response.body)

   assert(response.code === HttpCode.BADCREDS.intValue)
}
test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE + " -- invalid JSON should return 400") {
  val response = Http(
    URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username.split("/")(1) + ROUTE.dropRight(1)
  ).headers(ACCEPT)
   .headers(USERAUTH)
   .header("Content-Type", "application/json")
   .postData("""{"desc": "missing required 'description'"}""")
   .asString

   info("Code: " + response.code)
   info("Body: " + response.body)

  assert(response.code === HttpCode.BAD_INPUT.intValue)
}

test("POST /orgs/" + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username.split("/")(1) + ROUTE + " -- org admin cannot create apikey for user in another org") {
  val response = Http(
    URL + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username.split("/")(1) + ROUTE.dropRight(1)
  ).headers(ACCEPT)
   .headers(ORGADMINAUTH)
   .header("Content-Type", "application/json")
   .postData("""{"description": "Cross-org Admin Creation Attempt"}""")
   .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
   .asString

   info("Code: " + response.code)
   info("Body: " + response.body)

  assert(response.code === HttpCode.ACCESS_DENIED.intValue)
}



}