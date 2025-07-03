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
import _root_.org.openhorizon.exchangeapi.utility.{HttpCode,ApiTime}
import java.sql.Timestamp
import java.util.UUID

class TestPostApiKeyRoute extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
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
      createdAt = ApiTime.nowUTCTimestamp,
      email = Some("admin0@example.com"),
      identityProvider = "Open Horizon",
      isHubAdmin = false,
      isOrgAdmin = true,
      modifiedAt = ApiTime.nowUTCTimestamp,
      modified_by = None,
      organization = "testPostApiKeyRouteOrg0",
      password = Some(Password.hash(PASSWORD)),
      user = UUID.randomUUID(),
      username = "testPostApiKeyRouteAdmin0"
  ),
    UserRow(
      createdAt = ApiTime.nowUTCTimestamp,
      email = Some("user0@example.com"),
      identityProvider = "Open Horizon",
      isHubAdmin = false,
      isOrgAdmin = false,
      modifiedAt = ApiTime.nowUTCTimestamp,
      modified_by = None,
      organization = "testPostApiKeyRouteOrg0",
      password = Some(Password.hash(PASSWORD)),
      user = UUID.randomUUID(),
      username = "testPostApiKeyRouteUser0"
  ),
    UserRow(
      createdAt = ApiTime.nowUTCTimestamp,
      email = Some("user1@example.com"),
      identityProvider = "Open Horizon",
      isHubAdmin = false,
      isOrgAdmin = false,
      modifiedAt = ApiTime.nowUTCTimestamp,
      modified_by = None,
      organization = "testPostApiKeyRouteOrg1",
      password = Some(Password.hash(PASSWORD)),
      user = UUID.randomUUID(),
      username = "testPostApiKeyRouteUser1"
  ),
    UserRow(
      createdAt = ApiTime.nowUTCTimestamp,
      email = Some("hubadmin0@example.com"),
      identityProvider = "Open Horizon",
      isHubAdmin = true,
      isOrgAdmin = false,
      modifiedAt = ApiTime.nowUTCTimestamp,
      modified_by = None,
      organization = "root",
      password = Some(Password.hash(PASSWORD)),
      user = UUID.randomUUID(),
      username = "testPostApiKeyRouteHubAdmin0"
  )
)

  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("testPostApiKeyRouteOrg0/testPostApiKeyRouteAdmin0:password"))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode("testPostApiKeyRouteOrg0/testPostApiKeyRouteUser0:password"))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("root/testPostApiKeyRouteHubAdmin0:password"))
  private val ROOTUSERAUTH = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))

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
      ApiKeysTQ.filter(_.description.startsWith("TestPostApiKeyRoute")).delete
    ), AWAITDURATION)
    Await.ready(DBCONNECTION.run(
      UsersTQ.filter(_.username startsWith "testPostApiKeyRoute").delete 
    ), AWAITDURATION)
    Await.ready(DBCONNECTION.run(
      OrgsTQ.filter(_.orgid startsWith "testPostApiKeyRouteOrg").delete
    ), AWAITDURATION)

    val response: HttpResponse[String] = Http(sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/admin/clearauthcaches").method("POST").headers(ACCEPT).headers(CONTENT).headers(ROOTUSERAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
  }

  // User creates their own API key - Expected: 201
  test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + " -- user creates apikey - 201") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE.dropRight(1))
      .headers(ACCEPT)
      .headers(USERAUTH)
      .header("Content-Type", "application/json")
      .postData("""{"description": "TestPostApiKeyRoute Test API Key"}""")
      .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]

    assert(responseBody.owner === s"${TESTUSERS(1).organization}/${TESTUSERS(1).username}")
    assert(responseBody.description === "TestPostApiKeyRoute Test API Key")
    assert(responseBody.id.nonEmpty)
    assert(responseBody.value.nonEmpty)
  }

  // Org admin creates an API key for themselves - Expected: 201
  test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + " -- org admin creates apikey - 201") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE.dropRight(1))
      .headers(ACCEPT)
      .headers(ORGADMINAUTH)
      .header("Content-Type", "application/json")
      .postData("""{"description": "TestPostApiKeyRoute Admin Created Key"}""")
      .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]
    assert(responseBody.owner === s"${TESTUSERS(0).organization}/${TESTUSERS(0).username}")
    assert(responseBody.description === "TestPostApiKeyRoute Admin Created Key")
    assert(responseBody.id.nonEmpty)
    assert(responseBody.value.nonEmpty)
  }

  // Org admin creates an API key for another user in same org - Expected: 201
  test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + " -- org admin creates apikey for user - 201") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE.dropRight(1))
      .headers(ACCEPT)
      .headers(ORGADMINAUTH)
      .header("Content-Type", "application/json")
      .postData("""{"description": "TestPostApiKeyRoute Admin Created Key for a user"}""")
      .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]
    assert(responseBody.owner === s"${TESTUSERS(1).organization}/${TESTUSERS(1).username}")
    assert(responseBody.description === "TestPostApiKeyRoute Admin Created Key for a user")
    assert(responseBody.id.nonEmpty)
    assert(responseBody.value.nonEmpty)
  }

  // Missing auth header should return 401 - Expected: 401
  test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + " -- without auth - 401") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE.dropRight(1))
      .headers(ACCEPT)
      .header("Content-Type", "application/json")
      .postData("""{"description": "TestPostApiKeyRoute No Auth"}""")
      .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
  }

  // Invalid JSON body should return 400 - Expected: 400
  test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + " -- invalid JSON - 400") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE.dropRight(1))
      .headers(ACCEPT)
      .headers(USERAUTH)
      .header("Content-Type", "application/json")
      .postData("""{"desc": "missing required 'description'"}""")
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  // Org admin attempts to create an API key for a user in another org (should fail) - Expected: 403
  test("POST /orgs/" + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username + ROUTE + " -- org admin cannot create apikey for user in another org - 403") {
    val response = Http(
      URL + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username + ROUTE.dropRight(1))
      .headers(ACCEPT)
      .headers(ORGADMINAUTH)
      .header("Content-Type", "application/json")
      .postData("""{"description": "TestPostApiKeyRoute Cross-org Admin Creation Attempt"}""")
      .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  // Non-admin user tries to create API key for another user => should fail (403) - Expected: 403
  test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + " -- non-admin tries to create apikey for another user - 403") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE.dropRight(1))
      .headers(ACCEPT)
      .headers(USERAUTH)
      .header("Content-Type", "application/json")
      .postData("""{"description": "TestPostApiKeyRoute Unauthorized Attempt"}""")
      .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)

    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  // Admin tries to create key for non-existent user => should fail (400 for now) - Expected: 404
  test("POST /orgs/" + TESTORGS(0).orgId + "/users/nonexistentuser" + ROUTE + " -- admin creates apikey for non-existent user - 404") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/nonexistentuser" + ROUTE.dropRight(1))
      .headers(ACCEPT)
      .headers(ORGADMINAUTH)
      .header("Content-Type", "application/json")
      .postData("""{"description": "TestPostApiKeyRoute Non-existent user test"}""")
      .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)

    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  // Hub admin creates their own API key - Expected: 201
  test("POST /orgs/root/users/" + TESTUSERS(3).username + ROUTE + " -- hub admin creates own apikey - 201") {
    val response = Http(
      URL + "root/users/" + TESTUSERS(3).username + ROUTE.dropRight(1))
      .headers(ACCEPT)
      .headers(HUBADMINAUTH)
      .header("Content-Type", "application/json")
      .postData("""{"description": "TestPostApiKeyRoute Hub Admin API Key"}""")
      .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]
    assert(responseBody.owner === s"${TESTUSERS(3).organization}/${TESTUSERS(3).username}")
    assert(responseBody.description === "TestPostApiKeyRoute Hub Admin API Key")
    assert(responseBody.id.nonEmpty)
    assert(responseBody.value.nonEmpty)
  }

  // Hub admin creates API key for org admin - Expected: 201
  test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + " -- hub admin creates apikey for org admin - 201") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE.dropRight(1))
      .headers(ACCEPT)
      .headers(HUBADMINAUTH)
      .header("Content-Type", "application/json")
      .postData("""{"description": "TestPostApiKeyRoute Hub Admin Created Key for Org Admin"}""")
      .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]
    assert(responseBody.owner === s"${TESTUSERS(0).organization}/${TESTUSERS(0).username}")
    assert(responseBody.description === "TestPostApiKeyRoute Hub Admin Created Key for Org Admin")
    assert(responseBody.id.nonEmpty)
    assert(responseBody.value.nonEmpty)
  }

  // Hub admin tries to create API key for regular user (should fail) - Expected: 404
  test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + " -- hub admin tries to create apikey for user - 404") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE.dropRight(1))
      .headers(ACCEPT)
      .headers(HUBADMINAUTH)
      .header("Content-Type", "application/json")
      .postData("""{"description": "TestPostApiKeyRoute Hub Admin Unauthorized Attempt"}""")
      .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  // Root user creates their own API key - Expected: 201
  test("POST /orgs/root/users/root/apikeys -- root user creates own apikey - 201") {
    val response = Http(
      URL + "root/users/root/apikeys")
      .headers(ACCEPT)
      .headers(ROOTUSERAUTH)
      .header("Content-Type", "application/json")
      .postData("""{"description": "TestPostApiKeyRoute Root Created Key"}""")
      .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]
    assert(responseBody.owner === "root/root")
    assert(responseBody.description === "TestPostApiKeyRoute Root Created Key")
    assert(responseBody.id.nonEmpty)
    assert(responseBody.value.nonEmpty)
  }

  // Root user creates apikey for org admin - Expected: 201
  test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + " -- root user creates apikey for org admin - 201") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE.dropRight(1))
      .headers(ACCEPT)
      .headers(ROOTUSERAUTH)
      .header("Content-Type", "application/json")
      .postData("""{"description": "TestPostApiKeyRoute Root Created Key for OrgAdmin"}""")
      .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]
    assert(responseBody.owner === s"${TESTUSERS(0).organization}/${TESTUSERS(0).username}")
    assert(responseBody.description === "TestPostApiKeyRoute Root Created Key for OrgAdmin")
    assert(responseBody.id.nonEmpty)
    assert(responseBody.value.nonEmpty)
  }

  // Root user creates apikey for regular user - Expected: 201
  test("POST /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + " -- root user creates apikey for regular user - 201") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE.dropRight(1))
      .headers(ACCEPT)
      .headers(ROOTUSERAUTH)
      .header("Content-Type", "application/json")
      .postData("""{"description": "TestPostApiKeyRoute Root Created Key for User"}""")
      .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
      .asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]
    assert(responseBody.owner === s"${TESTUSERS(1).organization}/${TESTUSERS(1).username}")
    assert(responseBody.description === "TestPostApiKeyRoute Root Created Key for User")
    assert(responseBody.id.nonEmpty)
    assert(responseBody.value.nonEmpty)
  }
  
}