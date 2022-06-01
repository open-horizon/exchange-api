package com.horizon.exchangeapi.route.organization

import com.horizon.exchangeapi.tables.{OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, AuthCache, HttpCode, Password, Role, TestDBConnection}
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
  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINPASSWORD = "adminpassword"
  private val USER1PASSWORD = "user1password"
  private val USER2PASSWORD = "user2password"
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("root/TestGetOrgRouteHubAdmin:" + HUBADMINPASSWORD))
  private val USER1AUTH = ("Authorization", "Basic " + ApiUtils.encode("testGetOrgRoute1/TestGetOrgRouteUser1:" + USER1PASSWORD))
  private val USER2AUTH = ("Authorization", "Basic " + ApiUtils.encode("testGetOrgRoute2/TestGetOrgRouteUser2:" + USER2PASSWORD))

  private implicit val formats = DefaultFormats

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
        username    = "testGetOrgStatusRoute1/TestGetOrgStatusRouteUser1",
        orgid       = "testGetOrgStatusRoute1",
        hashedPw    = Password.hash(USER1PASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "TestGetOrgStatusRouteUser1@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = "testGetOrgStatusRoute2/TestGetOrgStatusRouteUser2",
        orgid       = "testGetOrgStatusRoute2",
        hashedPw    = Password.hash(USER2PASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "TestGetOrgStatusRouteUser2@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      )
    )

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

  test("GET /orgs/doesNotExist/status -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist/status").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

}
