package com.horizon.exchangeapi.route.organization

import com.horizon.exchangeapi.tables.{NodeHeartbeatIntervals, Org, OrgLimits, OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, GetOrgsResponse, HttpCode, Password, Role, TestDBConnection}
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.JsonMethods.parse
import org.json4s.DefaultFormats
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestGetOrgsRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs"

  private implicit val formats = DefaultFormats

  private val HUBADMINPASSWORD = "adminpassword"
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
    Seq(
      UserRow(
        username    = "root/TestGetOrgsRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestGetOrgsRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/TestGetOrgsRouteUser",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(USERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "TestGetOrgsRouteUser@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      )
    )

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + USERPASSWORD))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      (OrgsTQ ++= TESTORGS) andThen
        (UsersTQ ++= TESTUSERS)), AWAITDURATION
    )
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "testGetOrgsRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testGetOrgsRoute").delete andThen
      UsersTQ.filter(_.username startsWith "root/TestGetOrgsRouteHubAdmin").delete), AWAITDURATION)
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

  test("GET /orgs -- as regular user -- should fail (403 access denied)") {
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
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
