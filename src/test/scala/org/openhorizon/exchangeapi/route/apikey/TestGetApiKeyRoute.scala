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
import _root_.org.openhorizon.exchangeapi.utility.{HttpCode,ApiTime}
import java.sql.Timestamp
import java.util.UUID

class TestGetApiKeyRoute extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
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
    createdAt = ApiTime.nowUTCTimestamp,
    email = Some("admin0@example.com"),
    identityProvider = "Open Horizon",
    isHubAdmin = false,
    isOrgAdmin = true,
    modifiedAt = ApiTime.nowUTCTimestamp,
    modified_by = None,
    organization = "testGetUserApiKeyOrg0",
    password = Some(Password.hash(PASSWORD)),
    user = UUID.randomUUID(),
    username = "testGetUserApiKeyAdmin0"
  ),
    UserRow(
    createdAt = ApiTime.nowUTCTimestamp,
    email = Some("user0@example.com"),
    identityProvider = "Open Horizon",
    isHubAdmin = false,
    isOrgAdmin = false,
    modifiedAt = ApiTime.nowUTCTimestamp,
    modified_by = None,
    organization = "testGetUserApiKeyOrg0",
    password = Some(Password.hash(PASSWORD)),
    user = UUID.randomUUID(),
    username = "testGetUserApiKeyUser0"
  ),
    UserRow(
    createdAt = ApiTime.nowUTCTimestamp,
    email = Some("user1@example.com"),
    identityProvider = "Open Horizon",
    isHubAdmin = false,
    isOrgAdmin = false,
    modifiedAt = ApiTime.nowUTCTimestamp,
    modified_by = None,
    organization = "testGetUserApiKeyOrg1",
    password = Some(Password.hash(PASSWORD)),
    user = UUID.randomUUID(),
    username = "testGetUserApiKeyUser1"
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
    username = "testGetUserApiKeyHubAdmin0"
  )
)

  private val ROOTUSERID: UUID = {
    val rootUserQuery = UsersTQ.filter((u => u.username === "root" && u.organization === "root")).result.headOption
    Await.result(DBCONNECTION.run(rootUserQuery), AWAITDURATION).get.user
  }

  private val TESTAPIKEYS = Seq(
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(0).user,
      description = "TestGetUserApiKeyRoute Test API Key 1",
      hashedKey = "hash1",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(0).user,
      orgid = "testGetUserApiKeyOrg0",
      user = TESTUSERS(0).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(1).user,
      description = "TestGetUserApiKeyRoute Test API Key 2",
      hashedKey = "hash2",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(1).user,
      orgid = "testGetUserApiKeyOrg0",
      user = TESTUSERS(1).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(1).user,
      description = "TestGetUserApiKeyRoute Test API Key 3",
      hashedKey = "hash3",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(1).user,
      orgid = "testGetUserApiKeyOrg0",
      user = TESTUSERS(1).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(2).user,
      description = "TestGetUserApiKeyRoute Test API Key 4",
      hashedKey = "hash4",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(2).user,
      orgid = "testGetUserApiKeyOrg1",
      user = TESTUSERS(2).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(3).user,
      description = "TestGetUserApiKeyRoute Test API Key HubAdmin",
      hashedKey = "hashHubAdmin",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(3).user,
      orgid = "root",
      user = TESTUSERS(3).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = ROOTUSERID,
      description = "TestGetUserApiKeyRoute Root Own",
      hashedKey = "hash5",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = ROOTUSERID,
      orgid = "root",
      user = ROOTUSERID
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(0).user,
      description = "TestGetUserApiKeyRoute For Root Get Admin",
      hashedKey = "hash6",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(0).user,
      orgid = "testGetUserApiKeyOrg0",
      user = TESTUSERS(0).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(1).user,
      description = "TestGetUserApiKeyRoute For Root Get User",
      hashedKey = "hash7",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(1).user,
      orgid = "testGetUserApiKeyOrg0",
      user = TESTUSERS(1).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(3).user,
      description = "TestGetUserApiKeyRoute For Root Get HubAdmin",
      hashedKey = "hash8",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(3).user,
      orgid = "root",
      user = TESTUSERS(3).user
    )
  )

  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("testGetUserApiKeyOrg0/testGetUserApiKeyAdmin0:password"))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode("testGetUserApiKeyOrg0/testGetUserApiKeyUser0:password"))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("root/testGetUserApiKeyHubAdmin0:password"))
  private val ROOTUSERAUTH = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))


  override def beforeAll(): Unit = {
    val setupAction = DBIO.seq(
      OrgsTQ ++= TESTORGS,
      UsersTQ ++= TESTUSERS,
      ApiKeysTQ ++= TESTAPIKEYS
    )
    Await.result(DBCONNECTION.run(setupAction.transactionally), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      ApiKeysTQ.filter(_.description.startsWith("TestGetUserApiKeyRoute")).delete
    ), AWAITDURATION)
    Await.ready(DBCONNECTION.run(
      UsersTQ.filter(_.username startsWith "testGetUserApiKey").delete
    ), AWAITDURATION)
    Await.ready(DBCONNECTION.run(
      OrgsTQ.filter(_.orgid startsWith "testGetUserApiKeyOrg").delete
    ), AWAITDURATION)

    val response: HttpResponse[String] = Http(sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/admin/clearauthcaches").method("POST").headers(ACCEPT).headers(CONTENT).headers(ROOTUSERAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
  }



// GET /users/{username}/apikeys/{keyid}
// User get own key with key id - Expected: 200
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id + " -- get existing apikey by id - 200") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id
    ).headers(ACCEPT).headers(USERAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)

    val responseBody = JsonMethods.parse(response.body)
    assert((responseBody \ "description").extract[String] === "TestGetUserApiKeyRoute Test API Key 2")
    assert((responseBody \ "id").extract[String] === TESTAPIKEYS(1).id.toString)
  }

  // User tried to get non existent key - Expected: 404
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + "nonexistentkeyid -- 404") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + UUID.randomUUID()
    ).headers(ACCEPT).headers(USERAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  // Org admin tries to access another user's API key - Expected: 200
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id + " -- org admin should be allowed - 200") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id
    ).headers(ACCEPT).headers(ORGADMINAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)

    val responseBody = JsonMethods.parse(response.body)
    assert((responseBody \ "description").extract[String] === "TestGetUserApiKeyRoute Test API Key 2")
    assert((responseBody \ "id").extract[String] === TESTAPIKEYS(1).id.toString)
  }

  // User tries to access someone else's API key (same org) -- should fail - Expected: 403
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(1).id + " -- user should be forbidden - 403") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(1).id
    ).headers(ACCEPT).headers(USERAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  // Org Admin tries to access API key from another org - Expected: 403
  test("GET /orgs/" + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username + ROUTE + TESTAPIKEYS(3).id + " -- cross-org access should be forbidden - 403") {
    val response = Http(
      URL + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username + ROUTE + TESTAPIKEYS(3).id
    ).headers(ACCEPT).headers(ORGADMINAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  // No auth header provided - Expected: 401
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id + " -- unauthorized access without credentials - 401") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id
    ).headers(ACCEPT).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
  }

  // Hub admin gets their own API key - Expected: 200
  test("GET /orgs/root/users/" + TESTUSERS(3).username + ROUTE + TESTAPIKEYS(4).id + " -- hub admin gets own apikey - 200") {
    val response = Http(
      URL + "root/users/" + TESTUSERS(3).username + ROUTE + TESTAPIKEYS(4).id
    ).headers(ACCEPT).headers(HUBADMINAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)

    val responseBody = JsonMethods.parse(response.body)
    assert((responseBody \ "description").extract[String] === "TestGetUserApiKeyRoute Test API Key HubAdmin")
    assert((responseBody \ "id").extract[String] === TESTAPIKEYS(4).id.toString)
  }

  // Hub admin gets org admin's API key - Expected: 200
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(0).id + " -- hub admin gets org admin apikey - 200") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(0).id
    ).headers(ACCEPT).headers(HUBADMINAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)

    val responseBody = JsonMethods.parse(response.body)
    assert((responseBody \ "description").extract[String] === "TestGetUserApiKeyRoute Test API Key 1")
    assert((responseBody \ "id").extract[String] === TESTAPIKEYS(0).id.toString)
  }

  // Hub admin tries to get regular user's API key (should fail) - Expected: 404
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id + " -- hub admin tries to get user apikey - 404") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id
    ).headers(ACCEPT).headers(HUBADMINAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  // Root user gets their own API key - Expected: 200
  test("GET /orgs/" + "root" + "/users/" + "root" + ROUTE + TESTAPIKEYS(5).id + " -- root user gets own apikey - 200") {
    val response = Http(
      URL + "root" + "/users/" + "root" + ROUTE + TESTAPIKEYS(5).id
    ).headers(ACCEPT).headers(ROOTUSERAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)

    val responseBody = JsonMethods.parse(response.body)
    assert((responseBody \ "description").extract[String] === "TestGetUserApiKeyRoute Root Own")
    assert((responseBody \ "id").extract[String] === TESTAPIKEYS(5).id.toString)
  }

  // Root user gets org admin's API key - Expected: 200
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(6).id + " -- root user gets org admin apikey - 200") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(6).id
    ).headers(ACCEPT).headers(ROOTUSERAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)

    val responseBody = JsonMethods.parse(response.body)
    assert((responseBody \ "description").extract[String] === "TestGetUserApiKeyRoute For Root Get Admin")
    assert((responseBody \ "id").extract[String] === TESTAPIKEYS(6).id.toString)
  }

  // Root user gets regular user's API key - Expected: 200
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(7).id + " -- root user gets regular user apikey - 200") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(7).id
    ).headers(ACCEPT).headers(ROOTUSERAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)

    val responseBody = JsonMethods.parse(response.body)
    assert((responseBody \ "description").extract[String] === "TestGetUserApiKeyRoute For Root Get User")
    assert((responseBody \ "id").extract[String] === TESTAPIKEYS(7).id.toString)
  }

  // Root user gets hub admin's API key - Expected: 200
  test("GET /orgs/" + "root" + "/users/" + TESTUSERS(3).username + ROUTE + TESTAPIKEYS(8).id + " -- root user gets hub admin apikey - 200") {
    val response = Http(
      URL + "root" + "/users/" + TESTUSERS(3).username + ROUTE + TESTAPIKEYS(8).id
    ).headers(ACCEPT).headers(ROOTUSERAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)

    val responseBody = JsonMethods.parse(response.body)
    assert((responseBody \ "description").extract[String] === "TestGetUserApiKeyRoute For Root Get HubAdmin")
    assert((responseBody \ "id").extract[String] === TESTAPIKEYS(8).id.toString)
  }

}