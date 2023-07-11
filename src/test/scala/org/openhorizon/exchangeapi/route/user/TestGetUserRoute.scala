package org.openhorizon.exchangeapi.route.user

import org.openhorizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Password, Role, StrConstants, TestDBConnection}
import org.openhorizon.exchangeapi.table.{AgbotRow, AgbotsTQ, OrgRow, OrgsTQ, ResourceChangesTQ, User, UserRow, UsersTQ}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestGetUserRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/users/"

  private implicit val formats = DefaultFormats

  private val HUBADMINPASSWORD = "hubadminpassword"
  private val ORG1ADMINPASSWORD = "org1adminpassword"
  private val ORG1USERPASSWORD = "org1userpassword"
  private val NODETOKEN = "nodetoken"
  private val AGBOTTOKEN = "agbottoken"

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow( //main test org
        heartbeatIntervals = "",
        description        = "Test Organization 1",
        label              = "testGetUser",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testGetUserRouteOrg1",
        orgType            = "",
        tags               = None
      ),
      OrgRow( //to have user in other org
        heartbeatIntervals = "",
        description        = "Test Organization 2",
        label              = "testGetUser",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testGetUserRouteOrg2",
        orgType            = "",
        tags               = None
      )
    )

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestGetUserRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestGetUserRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/orgAdmin",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORG1ADMINPASSWORD),
        admin       = true,
        hubAdmin    = false,
        email       = "orgAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/orgUser",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORG1USERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "orgUser@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow( //to be referenced but not authenticated as (hence no pw)
        username    = TESTORGS(1).orgId + "/orgAdmin",
        orgid       = TESTORGS(1).orgId,
        hashedPw    = "",
        admin       = true,
        hubAdmin    = false,
        email       = "orgAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      )
    )

  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(
      AgbotRow(
        id = TESTORGS(0).orgId + "/agbot",
        orgid = TESTORGS(0).orgId,
        token = Password.hash(AGBOTTOKEN),
        name = "",
        owner = TESTUSERS(2).username, //org 1 user
        msgEndPoint = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey = ""
      )
    )

  private val TESTNODES: Seq[NodeRow] =
    Seq(
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/node",
        heartbeatIntervals = "",
        lastHeartbeat      = None,
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(2).username, //org 1 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = Password.hash(NODETOKEN),
        userInput          = ""
      )
    )

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORG1ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + ORG1ADMINPASSWORD))
  private val ORG1USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + ORG1USERPASSWORD))
  private val AGBOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + AGBOTTOKEN))
  private val NODEAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + NODETOKEN))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      (OrgsTQ ++= TESTORGS) andThen
      (UsersTQ ++= TESTUSERS) andThen
      (AgbotsTQ ++= TESTAGBOTS) andThen
      (NodesTQ ++= TESTNODES)
    ), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      ResourceChangesTQ.filter(_.orgId startsWith "testGetUserRoute").delete andThen
        OrgsTQ.filter(_.orgid startsWith "testGetUserRoute").delete andThen
        UsersTQ.filter(_.username startsWith "root/TestGetUserRouteHubAdmin").delete
    ), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  def assertUsersEqual(user1: User, user2: UserRow): Unit = {
    assert(user1.password === user2.hashedPw)
    assert(user1.admin === user2.admin)
    assert(user1.hubAdmin === user2.hubAdmin)
    assert(user1.email === user2.email)
    assert(user1.lastUpdated === user2.lastUpdated)
    assert(user1.updatedBy === user2.updatedBy)
  }

  def assertUsersEqualNoPass(user1: User, user2: UserRow): Unit = {
    assert(user1.password === StrConstants.hiddenPw)
    assert(user1.admin === user2.admin)
    assert(user1.hubAdmin === user2.hubAdmin)
    assert(user1.email === user2.email)
    assert(user1.lastUpdated === user2.lastUpdated)
    assert(user1.updatedBy === user2.updatedBy)
  }

  test("GET /orgs/doesNotExist" + ROUTE + TESTUSERS(2).username.split("/")(1) + " -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE + TESTUSERS(2).username.split("/")(1)).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val responseBody: GetUsersResponse = JsonMethods.parse(response.body).extract[GetUsersResponse]
    assert(responseBody.users.isEmpty)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + "doesNotExist -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "doesNotExist").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val responseBody: GetUsersResponse = JsonMethods.parse(response.body).extract[GetUsersResponse]
    assert(responseBody.users.isEmpty)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + TESTUSERS(2).username.split("/")(1) + " -- as hub admin -- 404 not found, doesn't return normal user") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTUSERS(2).username.split("/")(1)).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val responseBody: GetUsersResponse = JsonMethods.parse(response.body).extract[GetUsersResponse]
    assert(responseBody.users.isEmpty)
  }

  test("GET /orgs/root" + ROUTE + "root -- as root -- 200 success, returns self w/ hashed password") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "root").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: GetUsersResponse = JsonMethods.parse(response.body).extract[GetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains("root/root"))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + TESTUSERS(2).username.split("/")(1) + " -- as root -- 200 success, returns user w/ hashed passwords") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTUSERS(2).username.split("/")(1)).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: GetUsersResponse = JsonMethods.parse(response.body).extract[GetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains(TESTUSERS(2).username))
    assertUsersEqual(responseBody.users(TESTUSERS(2).username), TESTUSERS(2))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + TESTUSERS(1).username.split("/")(1) + " -- as hub admin -- 200 success, returns admin user w/ hashed passwords") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTUSERS(1).username.split("/")(1)).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: GetUsersResponse = JsonMethods.parse(response.body).extract[GetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains(TESTUSERS(1).username))
    assertUsersEqual(responseBody.users(TESTUSERS(1).username), TESTUSERS(1))
  }

  test("GET /orgs/root" + ROUTE + "root -- as hub admin -- 200 success, returns root user w/ hashed password") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "root").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: GetUsersResponse = JsonMethods.parse(response.body).extract[GetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains("root/root"))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + TESTUSERS(2).username.split("/")(1) + " -- as org admin -- 200 success, returns user w/o password") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTUSERS(2).username.split("/")(1)).headers(ACCEPT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: GetUsersResponse = JsonMethods.parse(response.body).extract[GetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains(TESTUSERS(2).username))
    assertUsersEqualNoPass(responseBody.users(TESTUSERS(2).username), TESTUSERS(2))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + TESTUSERS(2).username.split("/")(1) + " -- as user -- 200 success, retuns self w/o password") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTUSERS(2).username.split("/")(1)).headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: GetUsersResponse = JsonMethods.parse(response.body).extract[GetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains(TESTUSERS(2).username))
    assertUsersEqualNoPass(responseBody.users(TESTUSERS(2).username), TESTUSERS(2))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + TESTUSERS(1).username.split("/")(1) + " -- as user -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTUSERS(1).username.split("/")(1)).headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + TESTORGS(1).orgId + ROUTE + TESTUSERS(3).username.split("/")(1) + " -- as org admin in other org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + TESTUSERS(3).username.split("/")(1)).headers(ACCEPT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + TESTUSERS(2).username.split("/")(1) + " -- as node -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTUSERS(2).username.split("/")(1)).headers(ACCEPT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + TESTUSERS(2).username.split("/")(1) + " -- as agbot -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTUSERS(2).username.split("/")(1)).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + "iamapikey -- as user -- 200 success, retuns self w/o password") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "iamapikey").headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: GetUsersResponse = JsonMethods.parse(response.body).extract[GetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains(TESTUSERS(2).username))
    assertUsersEqualNoPass(responseBody.users(TESTUSERS(2).username), TESTUSERS(2))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + "iamtoken -- as user -- 200 success, retuns self w/o password") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "iamtoken").headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: GetUsersResponse = JsonMethods.parse(response.body).extract[GetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains(TESTUSERS(2).username))
    assertUsersEqualNoPass(responseBody.users(TESTUSERS(2).username), TESTUSERS(2))
  }

}

