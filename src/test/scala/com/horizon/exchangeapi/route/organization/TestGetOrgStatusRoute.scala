package com.horizon.exchangeapi.route.organization

import com.horizon.exchangeapi.tables.{AgbotRow, AgbotsTQ, NodeAgreementRow, NodeAgreementsTQ, NodeMsgRow, NodeMsgsTQ, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResourceChangesTQ, SchemaTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, ExchMsg, GetOrgStatusResponse, HttpCode, Password, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc.PostgresProfile.api._

class TestGetOrgStatusRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/status"
  private val SCHEMAVERSION: Int = Await.result(DBCONNECTION.getDb.run(SchemaTQ.getSchemaVersion.result), AWAITDURATION).head

  private implicit val formats = DefaultFormats

  private val HUBADMINPASSWORD = "adminpassword"
  private val USER1PASSWORD = "user1password"
  private val USER2PASSWORD = "user2password"

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow(
        heartbeatIntervals =
          """
            |{
            | "minInterval": 1,
            | "maxInterval": 2,
            | "intervalAdjustment": 3
            |}
            |""".stripMargin,
        description        = "Test Organization 1",
        label              = "testGetOrgStatus",
        lastUpdated        = ApiTime.nowUTC,
        limits             =
          """
            |{
            | "maxNodes": 100
            |}
            |""".stripMargin,
        orgId              = "testGetOrgStatusRoute1",
        orgType            = "testGetOrgStatus",
        tags               = None),
      OrgRow(
        heartbeatIntervals =
          """
            |{
            | "minInterval": 4,
            | "maxInterval": 5,
            | "intervalAdjustment": 6
            |}
            |""".stripMargin,
        description        = "Test Organization 2",
        label              = "sampleGetOrgStatus",
        lastUpdated        = ApiTime.nowUTC,
        limits             =
          """
            |{
            | "maxNodes": 5
            |}
            |""".stripMargin,
        orgId              = "testGetOrgStatusRoute2",
        orgType            = "testGetOrgStatus",
        tags               = Some(JsonMethods.parse(
          """
            |{
            | "tagName": "tagValue"
            |}
            |""".stripMargin
        ))))

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestGetOrgStatusRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestGetOrgStatusRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/TestGetOrgStatusRouteUser1",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(USER1PASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "TestGetOrgStatusRouteUser1@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(1).orgId + "/TestGetOrgStatusRouteUser2",
        orgid       = TESTORGS(1).orgId,
        hashedPw    = Password.hash(USER2PASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "TestGetOrgStatusRouteUser2@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      )
    )

  private val TESTNODES: Seq[NodeRow] =
    Seq(
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/n1",
        heartbeatIntervals = "",
        lastHeartbeat      = None,
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).username,
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/n2",
        heartbeatIntervals = "",
        lastHeartbeat      = None,
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).username,
        pattern            = "",
        publicKey          = "registered",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""))

  private val TESTAGBOTS: Seq[AgbotRow] = //must have an agbot in order to create a node message
    Seq(AgbotRow(
      id            = TESTORGS(0).orgId + "/a1",
      orgid         = TESTORGS(0).orgId,
      token         = "",
      name          = "testAgbot",
      owner         = TESTUSERS(1).username,
      msgEndPoint   = "",
      lastHeartbeat = ApiTime.nowUTC,
      publicKey     = ""
    ))

  private val TESTAGREEMENTS: Seq[NodeAgreementRow] =
    Seq(NodeAgreementRow(
      agId          = TESTAGBOTS(0).id,
      nodeId        = TESTNODES(0).id,
      services      = "",
      agrSvcOrgid   = TESTORGS(0).orgId,
      agrSvcPattern = "",
      agrSvcUrl     = "",
      state         = "active",
      lastUpdated   = ApiTime.nowUTC))

  private val TESTMESSAGES: Seq[NodeMsgRow] =
    Seq(NodeMsgRow(
      msgId = 0, // this will be automatically set to a unique ID by the DB
      nodeId = TESTNODES(0).id,
      agbotId = TESTAGBOTS(0).id,
      agbotPubKey = "",
      message = "Test Message",
      timeSent = ApiTime.nowUTC,
      timeExpires = ApiTime.futureUTC(120)))

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val USER1AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + USER1PASSWORD))
  private val USER2AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + USER2PASSWORD))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      (OrgsTQ ++= TESTORGS) andThen
        (UsersTQ ++= TESTUSERS) andThen
        (AgbotsTQ ++= TESTAGBOTS) andThen
        (NodesTQ ++= TESTNODES) andThen
        (NodeMsgsTQ ++= TESTMESSAGES) andThen
        (NodeAgreementsTQ ++= TESTAGREEMENTS)), AWAITDURATION
    )
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "testGetOrgStatusRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testGetOrgStatusRoute").delete andThen
      UsersTQ.filter(_.username startsWith "root/TestGetOrgStatusRouteHubAdmin").delete), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  //is this intended? I would think this should fail with 404 not found
  test("GET /orgs/doesNotExist" + ROUTE + " -- success, but returns 0 for everything") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: GetOrgStatusResponse = JsonMethods.parse(response.body).extract[GetOrgStatusResponse]
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfNodes === 0)
    assert(status.numberOfUsers === 0)
    assert(status.numberOfNodeMsgs === 0)
    assert(status.numberOfNodeAgreements === 0)
    assert(status.numberOfRegisteredNodes === 0)
    assert(status.SchemaVersion === SCHEMAVERSION)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " as root -- normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: GetOrgStatusResponse = JsonMethods.parse(response.body).extract[GetOrgStatusResponse]
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfNodes === 2)
    assert(status.numberOfUsers === 1)
    assert(status.numberOfNodeMsgs === 1)
    assert(status.numberOfNodeAgreements === 1)
    assert(status.numberOfRegisteredNodes === 1)
    assert(status.SchemaVersion === SCHEMAVERSION)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " as hub admin -- normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: GetOrgStatusResponse = JsonMethods.parse(response.body).extract[GetOrgStatusResponse]
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfNodes === 2)
    assert(status.numberOfUsers === 1)
    assert(status.numberOfNodeMsgs === 1)
    assert(status.numberOfNodeAgreements === 1)
    assert(status.numberOfRegisteredNodes === 1)
    assert(status.SchemaVersion === SCHEMAVERSION)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " as user in org -- normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(USER1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: GetOrgStatusResponse = JsonMethods.parse(response.body).extract[GetOrgStatusResponse]
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfNodes === 2)
    assert(status.numberOfUsers === 1)
    assert(status.numberOfNodeMsgs === 1)
    assert(status.numberOfNodeAgreements === 1)
    assert(status.numberOfRegisteredNodes === 1)
    assert(status.SchemaVersion === SCHEMAVERSION)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " as user in other org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(USER2AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
}
