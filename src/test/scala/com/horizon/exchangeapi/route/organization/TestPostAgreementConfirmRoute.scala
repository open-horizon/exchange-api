package com.horizon.exchangeapi.route.organization

import com.horizon.exchangeapi.tables.{AgbotAgreementRow, AgbotAgreementsTQ, AgbotRow, AgbotsTQ, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiResponse, ApiTime, ApiUtils, ExchMsg, HttpCode, Password, PostAgreementsConfirmRequest, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostAgreementConfirmRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/agreements/confirm"

  private implicit val formats = DefaultFormats

  private val HUBADMINPASSWORD = "adminpassword"
  private val ORG1USERPASSWORD = "org1userpassword"
  private val ORG2USERPASSWORD = "org2userpassword"
  private val NODE1TOKEN = "node1token"
  private val AGBOT1TOKEN = "agbot1token"
  private val AGBOT2TOKEN = "agbot2token"

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow(
        orgId              = "TestPostAgreementConfirmRouteOrg1",
        orgType            = "",
        label              = "TestPostAgreementConfirm",
        description        = "Test Organization 1",
        lastUpdated        = ApiTime.nowUTC,
        tags               = None,
        limits             = "",
        heartbeatIntervals = ""),
      OrgRow(
        orgId              = "TestPostAgreementConfirmRouteOrg2",
        orgType            = "",
        label              = "TestPostAgreementConfirm",
        description        = "Test Organization 2",
        lastUpdated        = ApiTime.nowUTC,
        tags               = None,
        limits             = "",
        heartbeatIntervals = ""
      ))

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestPostAgreementConfirmRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestPostAgreementConfirmRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/org1user",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORG1USERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "org1user@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = TESTORGS(1).orgId + "/org2user",
        orgid       = TESTORGS(1).orgId,
        hashedPw    = Password.hash(ORG2USERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "org2user@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
    )

  private val TESTNODES: Seq[NodeRow] =
    Seq(
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/org1node1",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).username, //org 1 user
        pattern            = "hasPattern",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = Password.hash(NODE1TOKEN),
        userInput          = "")
    )

  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(
      AgbotRow(
        id            = TESTORGS(0).orgId + "/org1agbot1",
        orgid         = TESTORGS(0).orgId,
        token         = Password.hash(AGBOT1TOKEN),
        name          = "",
        owner         = TESTUSERS(1).username, //org 1 user
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      ),
      AgbotRow(
        id            = TESTORGS(1).orgId + "/org2agbot1",
        orgid         = TESTORGS(1).orgId,
        token         = Password.hash(AGBOT2TOKEN),
        name          = "",
        owner         = TESTUSERS(2).username, //org 2 user
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      ),
      AgbotRow(
        id            = TESTORGS(0).orgId + "/org1agbot2",
        orgid         = TESTORGS(0).orgId,
        token         = "",
        name          = "",
        owner         = TESTUSERS(1).username, //org 1 user
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      ),
      AgbotRow(
        id            = TESTORGS(0).orgId + "/org1agbot3",
        orgid         = TESTORGS(0).orgId,
        token         = "",
        name          = "",
        owner         = "root/root",
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      ),
      AgbotRow(
        id            = TESTORGS(0).orgId + "/org1agbot4",
        orgid         = TESTORGS(0).orgId,
        token         = "",
        name          = "",
        owner         = TESTUSERS(0).username, //hub admin
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      )
    )

  private val TESTAGREEMENTS: Seq[AgbotAgreementRow] = Seq(
    AgbotAgreementRow(
      agrId = "TestPostAgreementConfirmRouteAgr0",
      agbotId = TESTAGBOTS(0).id,
      serviceOrgid = "",
      servicePattern = "",
      serviceUrl = "",
      state = "",
      lastUpdated = ApiTime.nowUTC,
      dataLastReceived = ApiTime.nowUTC
    ),
    AgbotAgreementRow(
      agrId = "TestPostAgreementConfirmRouteAgr1",
      agbotId = TESTAGBOTS(0).id,
      serviceOrgid = "",
      servicePattern = "",
      serviceUrl = "",
      state = "active",
      lastUpdated = ApiTime.nowUTC,
      dataLastReceived = ApiTime.nowUTC
    ),
    AgbotAgreementRow(
      agrId = "TestPostAgreementConfirmRouteAgr2",
      agbotId = TESTAGBOTS(1).id,
      serviceOrgid = "",
      servicePattern = "",
      serviceUrl = "",
      state = "active",
      lastUpdated = ApiTime.nowUTC,
      dataLastReceived = ApiTime.nowUTC
    ),
    AgbotAgreementRow(
      agrId = "TestPostAgreementConfirmRouteAgr3",
      agbotId = TESTAGBOTS(2).id,
      serviceOrgid = "",
      servicePattern = "",
      serviceUrl = "",
      state = "active",
      lastUpdated = ApiTime.nowUTC,
      dataLastReceived = ApiTime.nowUTC
    ),
    AgbotAgreementRow(
      agrId = "TestPostAgreementConfirmRouteAgr4",
      agbotId = TESTAGBOTS(3).id,
      serviceOrgid = "",
      servicePattern = "",
      serviceUrl = "",
      state = "active",
      lastUpdated = ApiTime.nowUTC,
      dataLastReceived = ApiTime.nowUTC
    ),
    AgbotAgreementRow(
      agrId = "TestPostAgreementConfirmRouteAgr5",
      agbotId = TESTAGBOTS(4).id,
      serviceOrgid = "",
      servicePattern = "",
      serviceUrl = "",
      state = "active",
      lastUpdated = ApiTime.nowUTC,
      dataLastReceived = ApiTime.nowUTC
    )
  )

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORG1USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + ORG1USERPASSWORD))
  private val ORG2USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + ORG2USERPASSWORD))
  private val NODE1AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + NODE1TOKEN))
  private val AGBOT1AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + AGBOT1TOKEN))
  private val AGBOT2AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(1).id + ":" + AGBOT2TOKEN))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      (OrgsTQ ++= TESTORGS) andThen
      (UsersTQ ++= TESTUSERS) andThen
      (AgbotsTQ ++= TESTAGBOTS) andThen
      (NodesTQ ++= TESTNODES) andThen
      (AgbotAgreementsTQ ++= TESTAGREEMENTS)
    ), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPostAgreementConfirmRouteOrg").delete andThen
      OrgsTQ.filter(_.orgid startsWith "TestPostAgreementConfirmRouteOrg").delete andThen
      UsersTQ.filter(_.username startsWith "root/TestPostAgreementConfirmRouteHubAdmin").delete
    ), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  //I don't think it really makes sense for this to be in the "orgs" route, or even take in the orgId parameter at all. orgId is only used to check if the caller has access to
  //that org, and then isn't used. the agbots/agreements that are checked don't have to be in that org at all, just be owned by the caller.
  //for instance, the root user will get the same results regardless of which orgid they input, and a regular user will only get results if they enter their own orgid

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- user: agreementId doesn't exist -- 404 not found") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = "doesNotExist")
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.not.found.not.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- user: caller isn't the owner -- 404 not found") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(1).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.not.found.not.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- user: agreement not active -- 404 not found") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(0).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.not.found.not.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- user: success -- 201 success") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(1).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- root: success -- 201 success") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(4).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- hub admin: success -- 201 success") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(5).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- user in other org -- 403 access denied") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(2).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG2USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- agbot: agreementId doesn't exist -- 404 not found") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = "doesNotExist")
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.not.found.not.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- agbot: agbot's owner doesn't own agreement's agbot -- 404 not found") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(2).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.not.found.not.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- agbot: agreement not active -- 404 not found") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(0).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.not.found.not.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- agbot: get its own agreement -- 201 success") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(1).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- agbot: get agreement of other agbot with same owner -- 201 success") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(3).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- agbot in other org -- 403 access denied") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(2).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT2AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as node -- 403 access denied") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(1).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(NODE1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

}
