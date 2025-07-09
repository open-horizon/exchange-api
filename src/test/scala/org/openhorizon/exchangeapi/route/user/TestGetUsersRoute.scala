package org.openhorizon.exchangeapi.route.user

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{User, UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, HttpCode, StrConstants}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestGetUsersRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/users"
  
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: java.sql.Timestamp = ApiTime.nowUTCTimestamp

  private val HUBADMINPASSWORD = "hubadminpassword"
  private val ORG1ADMINPASSWORD = "org1adminpassword"
  private val ORG2ADMINPASSWORD = "org2adminpassword"
  private val ORG1USERPASSWORD = "org1userpassword"
  private val NODETOKEN = "nodetoken"
  private val AGBOTTOKEN = "agbottoken"

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow( //main test org
        heartbeatIntervals = "",
        description        = "Test Organization 1",
        label              = "testGetUsers",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testGetUsersRouteOrg1",
        orgType            = "",
        tags               = None
      ),
      OrgRow( //to have user in other org
        heartbeatIntervals = "",
        description        = "Test Organization 2",
        label              = "testGetUsers",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testGetUsersRouteOrg2",
        orgType            = "",
        tags               = None
      ),
      OrgRow( //org with no users
        heartbeatIntervals = "",
        description        = "Test Organization 3",
        label              = "testGetUsers",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testGetUsersRouteOrg3",
        orgType            = "",
        tags               = None
      )
    )

  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "TestGetUsersRouteHubAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(ORG1ADMINPASSWORD)),
                username     = "orgAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(ORG1USERPASSWORD)),
                username     = "orgUser"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = None,
                username     = "orgUser2"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(1).orgId,
                password     = Option(Password.hash(ORG2ADMINPASSWORD)),
                username     = "orgAdmin"))
  }
  
  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(
      AgbotRow(
        id = TESTORGS(0).orgId + "/agbot",
        orgid = TESTORGS(0).orgId,
        token = Password.hash(AGBOTTOKEN),
        name = "",
        owner = TESTUSERS(2).user, //org 1 user
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
        owner              = TESTUSERS(2).user, //org 1 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = Password.hash(NODETOKEN),
        userInput          = ""
      )
    )

  private val ROOTAUTH      = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val HUBADMINAUTH  = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).organization + "/" + TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORG1ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + ORG1ADMINPASSWORD))
  private val ORG1USERAUTH  = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).organization + "/" + TESTUSERS(2).username + ":" + ORG1USERPASSWORD))
  private val ORG2ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(4).organization + "/" + TESTUSERS(4).username + ":" + ORG2ADMINPASSWORD))
  private val AGBOTAUTH     = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + AGBOTTOKEN))
  private val NODEAUTH      = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + NODETOKEN))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      ((OrgsTQ ++= TESTORGS) andThen
       (UsersTQ ++= TESTUSERS) andThen
       (AgbotsTQ ++= TESTAGBOTS) andThen
       (NodesTQ ++= TESTNODES)).transactionally
    ), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      (ResourceChangesTQ.filter(_.orgId startsWith "testGetUsersRoute").delete andThen
       OrgsTQ.filter(_.orgid startsWith "testGetUsersRoute").delete andThen
       UsersTQ.filter(_.organization === "root")
              .filter(_.username startsWith "TestGetUsersRouteHubAdmin").delete).transactionally
    ), AWAITDURATION)

    val response: HttpResponse[String] = Http(sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/admin/clearauthcaches").method("POST").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
  }

  def assertUsersEqual(user1: TestUser, user2: UserRow): Unit = {
    assert(user1.password === StrConstants.hiddenPw)
    assert(user1.admin === user2.isOrgAdmin)
    assert(user1.hubAdmin === user2.isHubAdmin)
    assert(user1.email === user2.email.getOrElse(""))
    assert(user1.lastUpdated.nonEmpty)
    assert(user1.updatedBy === user2.modified_by.getOrElse(""))
  }

  def assertUsersEqualNoPass(user1: TestUser, user2: UserRow): Unit = {
    assert(user1.password === "")
    assert(user1.admin === user2.isOrgAdmin)
    assert(user1.hubAdmin === user2.isHubAdmin)
    assert(user1.email === user2.email.getOrElse(""))
    assert(user1.lastUpdated.nonEmpty)
    assert(user1.updatedBy === user2.modified_by.getOrElse(""))
  }

  test("GET /orgs/doesNotExist" + ROUTE + " -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.isEmpty)
  }

  test("GET /orgs/" + TESTORGS(2).orgId + ROUTE + " -- no users in org -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(2).orgId + ROUTE).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.isEmpty)
  }

  test("GET /orgs/root" + ROUTE + " -- as root user -- 200 success, all users in root org returned") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.size >= 2) //may be more due to concurrent tests
    assert(responseBody.users.contains("root/root"))
    assert(responseBody.users.contains((TESTUSERS(0).organization + "/" + TESTUSERS(0).username)))
    assertUsersEqual(responseBody.users((TESTUSERS(0).organization + "/" + TESTUSERS(0).username)), TESTUSERS(0))
  }

  test("GET /orgs/root" + ROUTE + " -- as hub admin -- 200 success, all admins in root org returned") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.size >= 1) //may be more due to concurrent tests
    assert(responseBody.users.contains((TESTUSERS(0).organization + "/" + TESTUSERS(0).username)))
    assertUsersEqual(responseBody.users((TESTUSERS(0).organization + "/" + TESTUSERS(0).username)), TESTUSERS(0))
  }

  test("GET /orgs/root" + ROUTE + " -- as org admin -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE).headers(ACCEPT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as root -- 200 success, all users in org returned") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.size == 3)
    assert(responseBody.users.contains((TESTUSERS(1).organization + "/" + TESTUSERS(1).username)))
    assert(responseBody.users.contains((TESTUSERS(2).organization + "/" + TESTUSERS(2).username)))
    assert(responseBody.users.contains((TESTUSERS(3).organization + "/" + TESTUSERS(3).username)))
    assertUsersEqual(responseBody.users((TESTUSERS(1).organization + "/" + TESTUSERS(1).username)), TESTUSERS(1))
    assertUsersEqual(responseBody.users((TESTUSERS(2).organization + "/" + TESTUSERS(2).username)), TESTUSERS(2))
    assertUsersEqualNoPass(responseBody.users((TESTUSERS(3).organization + "/" + TESTUSERS(3).username)), TESTUSERS(3))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as hub admin -- 200 success, only admins in org returned w/ hashed passwords") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains((TESTUSERS(1).organization + "/" + TESTUSERS(1).username)))
    assertUsersEqual(responseBody.users((TESTUSERS(1).organization + "/" + TESTUSERS(1).username)), TESTUSERS(1))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as org admin -- 200 success, all users in org returned") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.size === 3)
    assert(responseBody.users.contains((TESTUSERS(1).organization + "/" + TESTUSERS(1).username)))
    assert(responseBody.users.contains((TESTUSERS(2).organization + "/" + TESTUSERS(2).username)))
    assert(responseBody.users.contains((TESTUSERS(3).organization + "/" + TESTUSERS(3).username)))
    assertUsersEqual(responseBody.users((TESTUSERS(1).organization + "/" + TESTUSERS(1).username)), TESTUSERS(1))
    assertUsersEqual(responseBody.users((TESTUSERS(2).organization + "/" + TESTUSERS(2).username)), TESTUSERS(2))
    assertUsersEqualNoPass(responseBody.users((TESTUSERS(3).organization + "/" + TESTUSERS(3).username)), TESTUSERS(3))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as user -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as admin in other org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(ORG2ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as node -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as agbot -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

    // GET /orgs/{org}/users/apikey tests
  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + "/apikey -- as org user in correct org -- 200 success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "/apikey").headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains((TESTUSERS(2).organization + "/" + TESTUSERS(2).username)))
    assertUsersEqual(responseBody.users((TESTUSERS(2).organization + "/" + TESTUSERS(2).username)), TESTUSERS(2))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + "/apikey -- as org admin in correct org -- 200 success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "/apikey").headers(ACCEPT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains((TESTUSERS(1).organization + "/" + TESTUSERS(1).username)))
    assertUsersEqual(responseBody.users((TESTUSERS(1).organization + "/" + TESTUSERS(1).username)), TESTUSERS(1))
  }

  test("GET /orgs/root" + ROUTE + "/apikey -- as root user -- 200 success") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "/apikey").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains("root/root"))
  }

  test("GET /orgs/root" + ROUTE + "/apikey -- as hub admin -- 200 success") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "/apikey").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains((TESTUSERS(0).organization + "/" + TESTUSERS(0).username)))
    assertUsersEqual(responseBody.users((TESTUSERS(0).organization + "/" + TESTUSERS(0).username)), TESTUSERS(0))
  }

  test("GET /orgs/" + TESTORGS(1).orgId + ROUTE + "/apikey -- as user in different org -- 403 Forbidden") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + "/apikey").headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + TESTORGS(1).orgId + ROUTE + "/apikey -- as admin in different org -- 403 Forbidden") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + "/apikey").headers(ACCEPT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }


  // GET /orgs/{org}/users/iamapikey tests (identical to apikey tests)
  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + "/iamapikey -- as org user in correct org -- 200 success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "/iamapikey").headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains((TESTUSERS(2).organization + "/" + TESTUSERS(2).username)))
    assertUsersEqual(responseBody.users((TESTUSERS(2).organization + "/" + TESTUSERS(2).username)), TESTUSERS(2))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + "/iamapikey -- as org admin in correct org -- 200 success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "/iamapikey").headers(ACCEPT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains((TESTUSERS(1).organization + "/" + TESTUSERS(1).username)))
    assertUsersEqual(responseBody.users((TESTUSERS(1).organization + "/" + TESTUSERS(1).username)), TESTUSERS(1))
  }

  test("GET /orgs/root" + ROUTE + "/iamapikey -- as root user -- 200 success") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "/iamapikey").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains("root/root"))
  }

  test("GET /orgs/root" + ROUTE + "/iamapikey -- as hub admin -- 200 success") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "/iamapikey").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: TestGetUsersResponse = JsonMethods.parse(response.body).extract[TestGetUsersResponse]
    assert(responseBody.users.size === 1)
    assert(responseBody.users.contains((TESTUSERS(0).organization + "/" + TESTUSERS(0).username)))
    assertUsersEqual(responseBody.users((TESTUSERS(0).organization + "/" + TESTUSERS(0).username)), TESTUSERS(0))
  }

  test("GET /orgs/" + TESTORGS(1).orgId + ROUTE + "/iamapikey -- as user in different org -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + "/iamapikey").headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("GET /orgs/" + TESTORGS(1).orgId + ROUTE + "/iamapikey -- as admin in different org -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + "/iamapikey").headers(ACCEPT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

}
