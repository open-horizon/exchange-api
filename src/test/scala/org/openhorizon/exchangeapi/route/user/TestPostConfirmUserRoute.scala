package org.openhorizon.exchangeapi.route.user

import org.openhorizon.exchangeapi.TestDBConnection
import org.json4s.DefaultFormats
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, HttpCode}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostConfirmUserRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE1 = "/users/"
  private val ROUTE2 = "/confirm"

  private implicit val formats = DefaultFormats

  private val HUBADMINPASSWORD = "hubadminpassword"
  private val ORGADMINPASSWORD = "orgadminpassword"
  private val ORGUSERPASSWORD = "orguserpassword"
  private val NODETOKEN = "nodetoken"
  private val AGBOTTOKEN = "agbottoken"

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow( //main test org
        heartbeatIntervals = "",
        description        = "Test Organization 1",
        label              = "testPostConfirmUser",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPostConfirmUserRouteOrg1",
        orgType            = "",
        tags               = None
      ),
      OrgRow( //main test org
        heartbeatIntervals = "",
        description        = "Test Organization 2",
        label              = "testPostConfirmUser",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPostConfirmUserRouteOrg2",
        orgType            = "",
        tags               = None
      )
    )

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestPostConfirmUserRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestPostConfirmUserRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/orgAdmin",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORGADMINPASSWORD),
        admin       = true,
        hubAdmin    = false,
        email       = "orgAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/orgUser",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORGUSERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "orgUser@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(1).orgId + "/orgUser",
        orgid       = TESTORGS(1).orgId,
        hashedPw    = Password.hash(ORGUSERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "orgUser@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/orgUser2",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORGUSERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "orgUser2@ibm.com",
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
  private val ORG1ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + ORGADMINPASSWORD))
  private val ORG1USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + ORGUSERPASSWORD))
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
      ResourceChangesTQ.filter(_.orgId startsWith "testPostConfirmUserRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testPostConfirmUserRoute").delete andThen
      UsersTQ.filter(_.username startsWith "root/TestPostConfirmUserRouteHubAdmin").delete
    ), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  private val defaultUsername = TESTUSERS(2).username.split("/")(1)

  test("POST /orgs/doesNotExist" + ROUTE1 + defaultUsername + ROUTE2 + " -- as org admin -- 404 NOT FOUND") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE1 + defaultUsername + ROUTE2).postForm.headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  //this should fail
  ignore("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + "doesNotExist" + ROUTE2 + " -- as org admin -- 404 NOT FOUND") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + "doesNotExist" + ROUTE2).postForm.headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("POST /orgs/" + "doesNotExist" + ROUTE1 + "doesNotExist" + ROUTE2 + " -- as org admin -- 404 NOT FOUND") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE1 + "doesNotExist" + ROUTE2).postForm.headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2 + " -- as root -- 201 OK") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2).postForm.headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2 + " -- as hub admin -- 201 OK") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2).postForm.headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2 + " -- as org admin -- 201 OK") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2).postForm.headers(ACCEPT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + "orgUser2" + ROUTE2 + " -- as user -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + "orgUser2" + ROUTE2).postForm.headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2 + " -- as self -- 201 OK") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2).postForm.headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/" + TESTORGS(1).orgId + ROUTE1 + defaultUsername + ROUTE2 + " -- as org admin -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE1 + defaultUsername + ROUTE2).postForm.headers(ACCEPT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2 + " -- as agbot -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2).postForm.headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2 + " -- as node -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2).postForm.headers(ACCEPT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2 + " -- bad auth -- 401 INVALID CREDENTIALS") {
    val auth = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":incorrectPassword"))
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + defaultUsername + ROUTE2).postForm.headers(ACCEPT).headers(auth).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
  }

}
