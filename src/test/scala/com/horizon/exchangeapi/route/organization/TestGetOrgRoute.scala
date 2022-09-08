package com.horizon.exchangeapi.route.organization

import com.horizon.exchangeapi.tables.{NodeHeartbeatIntervals, Org, OrgLimits, OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, GetOrgsResponse, HttpCode, Password, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc.PostgresProfile.api._

class TestGetOrgRoute extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"

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
        label              = "testGetOrg",
        lastUpdated        = ApiTime.nowUTC,
        limits             =
          """
            |{
            | "maxNodes": 100
            |}
            |""".stripMargin,
        orgId              = "testGetOrgRoute1",
        orgType            = "testGetOrg",
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
        label              = "sampleGetOrg",
        lastUpdated        = ApiTime.nowUTC,
        limits             =
          """
            |{
            | "maxNodes": 5
            |}
            |""".stripMargin,
        orgId              = "testGetOrgRoute2",
        orgType            = "testGetOrg",
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
        username    = "root/TestGetOrgRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestGetOrgRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/TestGetOrgRouteUser1",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(USER1PASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "TestGetOrgRouteUser1@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(1).orgId + "/TestGetOrgRouteUser2",
        orgid       = TESTORGS(1).orgId,
        hashedPw    = Password.hash(USER2PASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "TestGetOrgRouteUser2@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      )
    )

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val USER1AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + USER1PASSWORD))
  private val USER2AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + USER2PASSWORD))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      (OrgsTQ ++= TESTORGS) andThen
        (UsersTQ ++= TESTUSERS)), AWAITDURATION
    )
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "testGetOrgRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testGetOrgRoute").delete andThen
      UsersTQ.filter(_.username startsWith "root/TestGetOrgRouteHubAdmin").delete), AWAITDURATION)
    DBCONNECTION.getDb.close()
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

  test("GET /orgs/doesNotExist -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + " -- as root -- success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.size === 1)
    assert(orgsList.orgs.contains(TESTORGS(0).orgId))
    assertOrgsEqual(orgsList.orgs(TESTORGS(0).orgId), TESTORGS(0))
    }

  test("GET /orgs/ + " + TESTORGS(0).orgId + " -- as hub admin -- success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.size === 1)
    assert(orgsList.orgs.contains(TESTORGS(0).orgId))
    assertOrgsEqual(orgsList.orgs(TESTORGS(0).orgId), TESTORGS(0))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + " -- as user in org -- success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).headers(ACCEPT).headers(USER1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.size === 1)
    assert(orgsList.orgs.contains(TESTORGS(0).orgId))
    assertOrgsEqual(orgsList.orgs(TESTORGS(0).orgId), TESTORGS(0))
  }

  test("GET /orgs/testGetOrgRoute1 -- as user in testGetOrgRoute2 -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + "testGetOrgRoute1").headers(ACCEPT).headers(USER2AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/IBM -- return IBM Org") {
    val response: HttpResponse[String] = Http(URL + "IBM").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.size === 1)
    assert(orgsList.orgs.contains("IBM"))
  }

  test("GET /orgs/root -- return root Org") {
    val response: HttpResponse[String] = Http(URL + "root").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.size === 1)
    assert(orgsList.orgs.contains("root"))
  }

  test("GET /orgs/testGetOrgRoute1 -- attribute = doesNotExist -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL + "testGetOrgRoute1").param("attribute", "doesNotExist").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("GET /orgs/testGetOrgRoute1 -- attribute = description -- return description of testGetOrgRoute1") {
    val response: HttpResponse[String] = Http(URL + "testGetOrgRoute1").param("attribute", "description").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val body = parse(response.body).extract[Map[String, String]]
    assert(body("attribute") === "description")
    assert(body("value") === TESTORGS(0).description)
  }
}
