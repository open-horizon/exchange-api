package org.openhorizon.exchangeapi.route.agreement

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.route.agreementbot.PostAgreementsConfirmRequest
import org.openhorizon.exchangeapi.table.agreementbot.agreement.{AgbotAgreementRow, AgbotAgreementsTQ}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiResponse, ApiTime, ApiUtils, Configuration, DatabaseConnection, ExchMsg}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostAgreementConfirmRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/agreements/confirm"

  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp

  private val HUBADMINPASSWORD = "adminpassword"
  private val ORG1USERPASSWORD = "org1userpassword"
  private val ORG2USERPASSWORD = "org2userpassword"
  private val NODE1TOKEN = "node1token"
  private val AGBOT1TOKEN = "agbot1token"
  private val AGBOT2TOKEN = "agbot2token"

  val rootUser: UUID = Await.result(DBCONNECTION.run(Compiled(UsersTQ.filter(users => users.organization === "root" && users.username === "root").map(_.user)).result.head), AWAITDURATION)
  
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

  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "TestPostAgreementConfirmRouteHubAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(ORG1USERPASSWORD)),
                username     = "org1user"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(1).orgId,
                password     = Option(Password.hash(ORG2USERPASSWORD)),
                username     = "org2user"))
  }
  
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
        owner              = TESTUSERS(1).user, //org 1 user
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
        owner         = TESTUSERS(1).user, //org 1 user
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      ),
      AgbotRow(
        id            = TESTORGS(1).orgId + "/org2agbot1",
        orgid         = TESTORGS(1).orgId,
        token         = Password.hash(AGBOT2TOKEN),
        name          = "",
        owner         = TESTUSERS(2).user, //org 2 user
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      ),
      AgbotRow(
        id            = TESTORGS(0).orgId + "/org1agbot2",
        orgid         = TESTORGS(0).orgId,
        token         = "",
        name          = "",
        owner         = TESTUSERS(1).user, //org 1 user
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      ),
      AgbotRow(
        id            = TESTORGS(0).orgId + "/org1agbot3",
        orgid         = TESTORGS(0).orgId,
        token         = "",
        name          = "",
        owner         = rootUser,
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      ),
      AgbotRow(
        id            = TESTORGS(0).orgId + "/org1agbot4",
        orgid         = TESTORGS(0).orgId,
        token         = "",
        name          = "",
        owner         = TESTUSERS(0).user, //hub admin
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

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).organization + "/" + TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORG1USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + ORG1USERPASSWORD))
  private val ORG2USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).organization + "/" + TESTUSERS(2).username + ":" + ORG2USERPASSWORD))
  private val NODE1AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + NODE1TOKEN))
  private val AGBOT1AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + AGBOT1TOKEN))
  private val AGBOT2AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(1).id + ":" + AGBOT2TOKEN))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      (OrgsTQ ++= TESTORGS) andThen
      (UsersTQ ++= TESTUSERS) andThen
      (AgbotsTQ ++= TESTAGBOTS) andThen
      (NodesTQ ++= TESTNODES) andThen
      (AgbotAgreementsTQ ++= TESTAGREEMENTS)
    ), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPostAgreementConfirmRouteOrg").delete andThen
      OrgsTQ.filter(_.orgid startsWith "TestPostAgreementConfirmRouteOrg").delete andThen
      UsersTQ.filter(_.organization === "root")
             .filter(_.username startsWith "TestPostAgreementConfirmRouteHubAdmin").delete
    ), AWAITDURATION)
  }

  //I don't think it really makes sense for this to be in the "orgs" route, or even take in the orgId parameter at all. orgId is only used to check if the caller has access to
  //that org, and then isn't used. the agbots/agreements that are checked don't have to be in that org at all, just be owned by the caller.
  //for instance, the root user will get the same results regardless of which orgid they input, and a regular user will only get results if they enter their own orgid

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- user: agreementId doesn't exist -- 404 not found") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = "doesNotExist")
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NotFound.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.not.found.not.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- user: caller isn't the owner -- 404 not found") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(1).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NotFound.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.not.found.not.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- user: agreement not active -- 404 not found") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(0).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NotFound.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.not.found.not.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- user: success -- 201 success") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(1).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- root: success -- 201 success") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(4).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- hub admin: success -- 201 success") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(5).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- user in other org -- 403 access denied") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(2).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG2USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- agbot: agreementId doesn't exist -- 404 not found") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = "doesNotExist")
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NotFound.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.not.found.not.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- agbot: agbot's owner doesn't own agreement's agbot -- 404 not found") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(2).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NotFound.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.not.found.not.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- agbot: agreement not active -- 404 not found") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(0).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NotFound.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.not.found.not.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- agbot: get its own agreement -- 201 success") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(1).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- agbot: get agreement of other agbot with same owner -- 201 success") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(3).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    assert(JsonMethods.parse(response.body).extract[ApiResponse].msg === ExchMsg.translate("agreement.active"))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- agbot in other org -- 403 access denied") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(2).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT2AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as node -- 403 access denied") {
    val requestBody: PostAgreementsConfirmRequest = PostAgreementsConfirmRequest(agreementId = TESTAGREEMENTS(1).agrId)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(NODE1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
  }

}
