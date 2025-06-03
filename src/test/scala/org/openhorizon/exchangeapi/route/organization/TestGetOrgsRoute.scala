package org.openhorizon.exchangeapi.route.organization

import org.json4s.jackson.JsonMethods
import org.json4s.jackson.JsonMethods.parse
import org.json4s.DefaultFormats
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeHeartbeatIntervals, NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{Org, OrgLimits, OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, HttpCode}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestGetOrgsRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs"

  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: java.sql.Timestamp = ApiTime.nowUTCTimestamp

  private val AGBOTPASSWORD = "agbotpassword"
  private val HUBADMINPASSWORD = "adminpassword"
  private val NODEPASSWORD = "nodepassword"
  private val USERPASSWORD = "userpassword"

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
        label              = "testGetOrgs",
        lastUpdated        = ApiTime.nowUTC,
        limits             =
          """
            |{
            | "maxNodes": 100
            |}
            |""".stripMargin,
        orgId              = "testGetOrgsRoute1",
        orgType            = "testGetOrgs",
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
        label              = "GetOrgs2Label",
        lastUpdated        = ApiTime.nowUTC,
        limits             =
          """
            |{
            | "maxNodes": 5
            |}
            |""".stripMargin,
        orgId              = "testGetOrgsRoute2",
        orgType            = "testGetOrgs",
        tags               = Some(JsonMethods.parse(
          """
            |{
            | "tagName": "tagValue"
            |}
            |""".stripMargin
        ))))
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "TestGetOrgsRouteHubAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(USERPASSWORD)),
                username     = "TestGetOrgsRouteUser"))
  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(AgbotRow(id            = TESTORGS(1).orgId + "/a1",
                 lastHeartbeat = ApiTime.nowUTC,
                 msgEndPoint   = "",
                 name          = "",
                 orgid         = TESTORGS(1).orgId,
                 owner         = TESTUSERS(1).user,
                 publicKey     = "",
                 token         = Password.hash(AGBOTPASSWORD)),
        AgbotRow(id            = "IBM/TestGetOrgsRoute-a1",    // Multi-tenant Agbot
                 lastHeartbeat = ApiTime.nowUTC,
                 msgEndPoint   = "",
                 name          = "",
                 orgid         = "IBM",
                 owner         = TESTUSERS(1).user,
                 publicKey     = "",
                 token         = Password.hash(AGBOTPASSWORD)))
  private val TESTNODES: Seq[NodeRow] =
    Seq(NodeRow(arch               = "",
                id                 = TESTORGS(0).orgId + "/node1",
                heartbeatIntervals = "",
                lastHeartbeat      = None,
                lastUpdated        = ApiTime.nowUTC,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS(0).orgId,
                owner              = TESTUSERS(1).user, //org 1 user
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = Password.hash(NODEPASSWORD),
                userInput          = ""))
  
  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val AGBOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + AGBOTPASSWORD))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).organization + "/" + TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val MULTITENANTAGBOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(1).id + ":" + AGBOTPASSWORD))
  private val NODEAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + NODEPASSWORD))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + USERPASSWORD))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGS) andThen
                                 (UsersTQ ++= TESTUSERS) andThen
                                 (AgbotsTQ ++= TESTAGBOTS) andThen
                                 (NodesTQ ++= TESTNODES)), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "testGetOrgsRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testGetOrgsRoute").delete andThen
      UsersTQ.filter(_.organization === "root")
             .filter(_.username startsWith "TestGetOrgsRouteHubAdmin").delete andThen
      AgbotsTQ.filter(agbots => agbots.orgid === TESTAGBOTS(1).orgid && agbots.id === TESTAGBOTS(1).id).delete), AWAITDURATION)
  }

  def assertOrgsEqual(org1: Org, org2: OrgRow): Unit = {
    assert(org1.heartbeatIntervals === JsonMethods.parse(org2.heartbeatIntervals).extract[NodeHeartbeatIntervals]) //convert json string to NodeHeartbeatIntervals object
    assert(org1.description === org2.description)
    assert(org1.label === org2.label)
    assert(org1.lastUpdated === org2.lastUpdated)
    assert(org1.limits === JsonMethods.parse(org2.limits).extract[OrgLimits]) //convert json string to orglimits object
    assert(org1.orgType === org2.orgType)
    assert(org1.tags === org2.tags || org1.tags === Some(org2.tags.get.extract[Map[String, String]])) //first check if both None, second check if both Some
  }

  test("GET /orgs -- as root -- should see root and IBM orgs in addition to test orgs") {
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.size >= 4) //AT LEAST the ors created by this test suite, but may also be orgs created by other test suite
    assert(orgsList.orgs.contains("root"))
    assert(orgsList.orgs.contains("IBM"))
    for (testOrg <- TESTORGS) {
      assert(orgsList.orgs.contains(testOrg.orgId))
      assertOrgsEqual(orgsList.orgs(testOrg.orgId), testOrg)
    }
  }

  test("GET /orgs -- as hub admin -- should see root and IBM orgs in addition to test orgs") {
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.size >= 4) //AT LEAST the orgs created by this test suite, but may also be orgs created by other test suite
    assert(orgsList.orgs.contains("root"))
    assert(orgsList.orgs.contains("IBM"))
    for (testOrg <- TESTORGS) {
      assert(orgsList.orgs.contains(testOrg.orgId))
      assertOrgsEqual(orgsList.orgs(testOrg.orgId), testOrg)
    }
  }

  test("GET /orgs -- as regular user -- should see my org and the IBM org.") {
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.size >= 2) //AT LEAST the orgs created by this test suite, but may also be orgs created by other test suite
    assert(!orgsList.orgs.contains("root"))
    assert(orgsList.orgs.contains("IBM"))
    assert(orgsList.orgs.contains(TESTORGS(0).orgId))
    assertOrgsEqual(orgsList.orgs(TESTORGS(0).orgId), TESTORGS(0))
    assert(!orgsList.orgs.contains(TESTORGS(1).orgId))
  }
  
  test("GET /orgs -- as an agbot -- should only see my org.") {
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.nonEmpty) //AT LEAST the orgs created by this test suite, but may also be orgs created by other test suite
    assert(!orgsList.orgs.contains("root"))
    assert(!orgsList.orgs.contains("IBM"))
    assert(!orgsList.orgs.contains(TESTORGS(0).orgId))
    assert(orgsList.orgs.contains(TESTORGS(1).orgId))
    assertOrgsEqual(orgsList.orgs(TESTORGS(1).orgId), TESTORGS(1))
  }
  
  test("GET /orgs -- as multitenant agbot -- should only see my org (IBM).") {
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(MULTITENANTAGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.nonEmpty) //AT LEAST the orgs created by this test suite, but may also be orgs created by other test suite
    assert(!orgsList.orgs.contains("root"))
    assert(orgsList.orgs.contains("IBM"))
    for (testOrg <- TESTORGS) {
      assert(!orgsList.orgs.contains(testOrg.orgId))
    }
  }
  
  test("GET /orgs -- as node -- should only see my org.") {
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.nonEmpty) //AT LEAST the orgs created by this test suite, but may also be orgs created by other test suite
    assert(!orgsList.orgs.contains("root"))
    assert(!orgsList.orgs.contains("IBM"))
    assert(orgsList.orgs.contains(TESTORGS(0).orgId))
    assertOrgsEqual(orgsList.orgs(TESTORGS(0).orgId), TESTORGS(0))
    assert(!orgsList.orgs.contains(TESTORGS(1).orgId))
  }

  test("GET /orgs -- orgType = IBM as root -- returns IBM org") {
    val response: HttpResponse[String] = Http(URL).param("orgtype", "IBM").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.nonEmpty) //may be more than one org with type IBM due to other tests
    assert(orgsList.orgs.contains("IBM"))
  }

  test("GET /orgs -- orgType = IBM as regular user -- returns IBM org") {
    val response: HttpResponse[String] = Http(URL).param("orgtype", "IBM").headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.nonEmpty) //may be more than one org with type IBM due to other tests
    assert(orgsList.orgs.contains("IBM"))
  }

  test("GET /orgs -- orgType = testGetOrgs -- failure because orgType is not IBM") {
    val response: HttpResponse[String] = Http(URL).param("orgtype", "testGetOrgs").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("GET /orgs -- label = testGetOrgs -- returns just testGetOrgsRoute1") {
    val response: HttpResponse[String] = Http(URL).param("label", TESTORGS(0).label).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.size === 1)
    assert(orgsList.orgs.contains(TESTORGS(0).orgId))
  }

  test("GET /orgs -- label = GetOrgs2% -- returns just testGetOrgsRoute2") {
    val response: HttpResponse[String] = Http(URL).param("label", "GetOrgs2%").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.size === 1)
    assert(orgsList.orgs.contains(TESTORGS(1).orgId))
  }

  test("GET /orgs -- label = doesNotExist -- 404 not found") {
    val response: HttpResponse[String] = Http(URL).param("label", "doesNotExist").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("GET /orgs -- orgType = IBM & label = IBM Org -- returns IBM Org") {
    val response: HttpResponse[String] = Http(URL).param("orgtype", "IBM").param("label", "IBM Org").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.size === 1)
    assert(orgsList.orgs.contains("IBM"))
  }

}
