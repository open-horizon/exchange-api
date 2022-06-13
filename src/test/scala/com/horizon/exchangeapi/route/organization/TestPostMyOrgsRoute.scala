package com.horizon.exchangeapi.route.organization

import com.horizon.exchangeapi.auth.IamAccountInfo
import com.horizon.exchangeapi.tables.{NodeHeartbeatIntervals, OrgLimits, OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, GetOrgsResponse, HttpCode, Password, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostMyOrgsRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/myorgs"

  private implicit val formats = DefaultFormats

  private val HUBADMINPASSWORD = "adminpassword"
  private val USERPASSWORD = "userpassword"
  private val ADMINPASSWORD = "adminpassword"

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow(
        orgId              = "TestPostMyOrgsRouteOrg1",
        orgType            = "test org",
        label              = "testPostMyOrgsRoute1",
        description        = "Test Organization 1",
        lastUpdated        = ApiTime.nowUTC,
        tags               = Some(JsonMethods.parse(
          "{\"cloud_id\":\"account1\"}"
        )),
        limits             = "{\"maxNodes\":5}",
        heartbeatIntervals = "{" +
          "\"minInterval\":4," +
          "\"maxInterval\":5," +
          "\"intervalAdjustment\":6" +
          "}"
      ),
      OrgRow(
        orgId              = "TestPostMyOrgsRouteOrg2",
        orgType            = "test org",
        label              = "testPostMyOrgsRoute2",
        description        = "Test Organization 2",
        lastUpdated        = ApiTime.nowUTC,
        tags               = Some(JsonMethods.parse(
          "{\"cloud_id\":\"account2\"}"
        )),
        limits             = "{\"maxNodes\":10}",
        heartbeatIntervals = "{" +
          "\"minInterval\":7," +
          "\"maxInterval\":8," +
          "\"intervalAdjustment\":9" +
          "}"
      ),
      OrgRow(
        orgId              = "TestPostMyOrgsRouteOrg3",
        orgType            = "test org",
        label              = "testPostMyOrgsRoute3",
        description        = "Test Organization 3",
        lastUpdated        = ApiTime.nowUTC,
        tags               = Some(JsonMethods.parse(
          "{\"cloud_id\":\"account2\"}"
        )),
        limits             = "{\"maxNodes\":15}",
        heartbeatIntervals = "{" +
          "\"minInterval\":10," +
          "\"maxInterval\":11," +
          "\"intervalAdjustment\":12" +
          "}"
      )
    )

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestPostMyOrgsRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestPostMyOrgsRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = "TestPostMyOrgsRouteOrg1/TestPostMyOrgsRouteUser",
        orgid       = "TestPostMyOrgsRouteOrg1",
        hashedPw    = Password.hash(USERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "TestPostMyOrgsRouteUser@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = "TestPostMyOrgsRouteOrg1/TestPostMyOrgsRouteAdmin",
        orgid       = "TestPostMyOrgsRouteOrg1",
        hashedPw    = Password.hash(ADMINPASSWORD),
        admin       = true,
        hubAdmin    = false,
        email       = "TestPostMyOrgsRouteAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      )
    )

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + USERPASSWORD))
  private val ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + ADMINPASSWORD))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      (OrgsTQ ++= TESTORGS) andThen
      (UsersTQ ++= TESTUSERS)
    ), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPostMyOrgsRouteOrg").delete andThen
      OrgsTQ.filter(_.orgid startsWith "TestPostMyOrgsRouteOrg").delete andThen
      UsersTQ.filter(_.username startsWith "root/TestPostMyOrgsRouteHubAdmin").delete), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  test("POST /myorgs -- empty body -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL).postData("{}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("POST /myorgs -- invalid body -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL).postData("{\"invalidKey\":\"invalidValue\"}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("POST /myorgs -- nonexistent account -- 404 not found") {
    val requestBody: Seq[IamAccountInfo] = Seq(IamAccountInfo(
      id = "doesNotExist",
      name = "whatever",
      description = "bleh",
      createdOn = ApiTime.nowUTC
    ))
    val response: HttpResponse[String] = Http(URL).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.isEmpty)
  }

  //should this be code 201 instead?
  test("POST /myorgs -- as root -- 200 success") {
    val requestBody: Seq[IamAccountInfo] = Seq(IamAccountInfo(
      id = "account1",
      name = "Account 1",
      description = "The first account",
      createdOn = ApiTime.nowUTC
    ))
    val response: HttpResponse[String] = Http(URL).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.nonEmpty)
    assert(searchResponse.orgs.contains(TESTORGS(0).orgId))
    assert(searchResponse.orgs(TESTORGS(0).orgId).heartbeatIntervals === JsonMethods.parse(TESTORGS(0).heartbeatIntervals).extract[NodeHeartbeatIntervals]) //convert json string to NodeHeartbeatIntervals object
    assert(searchResponse.orgs(TESTORGS(0).orgId).description === TESTORGS(0).description)
    assert(searchResponse.orgs(TESTORGS(0).orgId).label === TESTORGS(0).label)
    assert(searchResponse.orgs(TESTORGS(0).orgId).lastUpdated === TESTORGS(0).lastUpdated)
    assert(searchResponse.orgs(TESTORGS(0).orgId).limits === JsonMethods.parse(TESTORGS(0).limits).extract[OrgLimits]) //convert json string to orglimits object
    assert(searchResponse.orgs(TESTORGS(0).orgId).orgType === TESTORGS(0).orgType)
    assert(searchResponse.orgs(TESTORGS(0).orgId).tags.get === TESTORGS(0).tags.get.extract[Map[String, String]])
  }

  test("POST /myorgs -- only id provided -- 200 success") {
    val requestBody = "[{\"id\":\"account1\"}]"
    val response: HttpResponse[String] = Http(URL).postData(requestBody).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.nonEmpty)
    assert(searchResponse.orgs.contains(TESTORGS(0).orgId))
    assert(searchResponse.orgs(TESTORGS(0).orgId).heartbeatIntervals === JsonMethods.parse(TESTORGS(0).heartbeatIntervals).extract[NodeHeartbeatIntervals]) //convert json string to NodeHeartbeatIntervals object
    assert(searchResponse.orgs(TESTORGS(0).orgId).description === TESTORGS(0).description)
    assert(searchResponse.orgs(TESTORGS(0).orgId).label === TESTORGS(0).label)
    assert(searchResponse.orgs(TESTORGS(0).orgId).lastUpdated === TESTORGS(0).lastUpdated)
    assert(searchResponse.orgs(TESTORGS(0).orgId).limits === JsonMethods.parse(TESTORGS(0).limits).extract[OrgLimits]) //convert json string to orglimits object
    assert(searchResponse.orgs(TESTORGS(0).orgId).orgType === TESTORGS(0).orgType)
    assert(searchResponse.orgs(TESTORGS(0).orgId).tags.get === TESTORGS(0).tags.get.extract[Map[String, String]])
  }

  test("POST /myorgs -- 3 orgs returned -- 200 success") {
    val requestBody: Seq[IamAccountInfo] = Seq(
      IamAccountInfo(
        id = "account1",
        name = "Account 1",
        description = "The first account",
        createdOn = ApiTime.nowUTC
      ),
      IamAccountInfo(
        id = "account2",
        name = "Account 2",
        description = "The second account",
        createdOn = ApiTime.nowUTC
      )
    )
    val response: HttpResponse[String] = Http(URL).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.size >= 3)
    assert(searchResponse.orgs.contains(TESTORGS(0).orgId))
    assert(searchResponse.orgs(TESTORGS(0).orgId).heartbeatIntervals === JsonMethods.parse(TESTORGS(0).heartbeatIntervals).extract[NodeHeartbeatIntervals]) //convert json string to NodeHeartbeatIntervals object
    assert(searchResponse.orgs(TESTORGS(0).orgId).description === TESTORGS(0).description)
    assert(searchResponse.orgs(TESTORGS(0).orgId).label === TESTORGS(0).label)
    assert(searchResponse.orgs(TESTORGS(0).orgId).lastUpdated === TESTORGS(0).lastUpdated)
    assert(searchResponse.orgs(TESTORGS(0).orgId).limits === JsonMethods.parse(TESTORGS(0).limits).extract[OrgLimits]) //convert json string to orglimits object
    assert(searchResponse.orgs(TESTORGS(0).orgId).orgType === TESTORGS(0).orgType)
    assert(searchResponse.orgs(TESTORGS(0).orgId).tags.get === TESTORGS(0).tags.get.extract[Map[String, String]])

    assert(searchResponse.orgs.contains(TESTORGS(1).orgId))
    assert(searchResponse.orgs(TESTORGS(1).orgId).heartbeatIntervals === JsonMethods.parse(TESTORGS(1).heartbeatIntervals).extract[NodeHeartbeatIntervals]) //convert json string to NodeHeartbeatIntervals object
    assert(searchResponse.orgs(TESTORGS(1).orgId).description === TESTORGS(1).description)
    assert(searchResponse.orgs(TESTORGS(1).orgId).label === TESTORGS(1).label)
    assert(searchResponse.orgs(TESTORGS(1).orgId).lastUpdated === TESTORGS(1).lastUpdated)
    assert(searchResponse.orgs(TESTORGS(1).orgId).limits === JsonMethods.parse(TESTORGS(1).limits).extract[OrgLimits]) //convert json string to orglimits object
    assert(searchResponse.orgs(TESTORGS(1).orgId).orgType === TESTORGS(1).orgType)
    assert(searchResponse.orgs(TESTORGS(1).orgId).tags.get === TESTORGS(1).tags.get.extract[Map[String, String]])

    assert(searchResponse.orgs.contains(TESTORGS(2).orgId))
    assert(searchResponse.orgs(TESTORGS(2).orgId).heartbeatIntervals === JsonMethods.parse(TESTORGS(2).heartbeatIntervals).extract[NodeHeartbeatIntervals]) //convert json string to NodeHeartbeatIntervals object
    assert(searchResponse.orgs(TESTORGS(2).orgId).description === TESTORGS(2).description)
    assert(searchResponse.orgs(TESTORGS(2).orgId).label === TESTORGS(2).label)
    assert(searchResponse.orgs(TESTORGS(2).orgId).lastUpdated === TESTORGS(2).lastUpdated)
    assert(searchResponse.orgs(TESTORGS(2).orgId).limits === JsonMethods.parse(TESTORGS(2).limits).extract[OrgLimits]) //convert json string to orglimits object
    assert(searchResponse.orgs(TESTORGS(2).orgId).orgType === TESTORGS(2).orgType)
    assert(searchResponse.orgs(TESTORGS(2).orgId).tags.get === TESTORGS(2).tags.get.extract[Map[String, String]])
  }

  test("POST /myorgs -- as hub admin -- 200 success") {
    val requestBody: Seq[IamAccountInfo] = Seq(IamAccountInfo(
      id = "account1",
      name = "Account 1",
      description = "The first account",
      createdOn = ApiTime.nowUTC
    ))
    val response: HttpResponse[String] = Http(URL).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.nonEmpty)
    assert(searchResponse.orgs.contains(TESTORGS(0).orgId))
    assert(searchResponse.orgs(TESTORGS(0).orgId).heartbeatIntervals === JsonMethods.parse(TESTORGS(0).heartbeatIntervals).extract[NodeHeartbeatIntervals]) //convert json string to NodeHeartbeatIntervals object
    assert(searchResponse.orgs(TESTORGS(0).orgId).description === TESTORGS(0).description)
    assert(searchResponse.orgs(TESTORGS(0).orgId).label === TESTORGS(0).label)
    assert(searchResponse.orgs(TESTORGS(0).orgId).lastUpdated === TESTORGS(0).lastUpdated)
    assert(searchResponse.orgs(TESTORGS(0).orgId).limits === JsonMethods.parse(TESTORGS(0).limits).extract[OrgLimits]) //convert json string to orglimits object
    assert(searchResponse.orgs(TESTORGS(0).orgId).orgType === TESTORGS(0).orgType)
    assert(searchResponse.orgs(TESTORGS(0).orgId).tags.get === TESTORGS(0).tags.get.extract[Map[String, String]])
  }

  test("POST /myorgs -- as admin -- 200 success") {
    val requestBody: Seq[IamAccountInfo] = Seq(IamAccountInfo(
      id = "account1",
      name = "Account 1",
      description = "The first account",
      createdOn = ApiTime.nowUTC
    ))
    val response: HttpResponse[String] = Http(URL).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.nonEmpty)
    assert(searchResponse.orgs.contains(TESTORGS(0).orgId))
    assert(searchResponse.orgs(TESTORGS(0).orgId).heartbeatIntervals === JsonMethods.parse(TESTORGS(0).heartbeatIntervals).extract[NodeHeartbeatIntervals]) //convert json string to NodeHeartbeatIntervals object
    assert(searchResponse.orgs(TESTORGS(0).orgId).description === TESTORGS(0).description)
    assert(searchResponse.orgs(TESTORGS(0).orgId).label === TESTORGS(0).label)
    assert(searchResponse.orgs(TESTORGS(0).orgId).lastUpdated === TESTORGS(0).lastUpdated)
    assert(searchResponse.orgs(TESTORGS(0).orgId).limits === JsonMethods.parse(TESTORGS(0).limits).extract[OrgLimits]) //convert json string to orglimits object
    assert(searchResponse.orgs(TESTORGS(0).orgId).orgType === TESTORGS(0).orgType)
    assert(searchResponse.orgs(TESTORGS(0).orgId).tags.get === TESTORGS(0).tags.get.extract[Map[String, String]])
  }

  test("POST /myorgs -- as user -- 200 success") {
    val requestBody: Seq[IamAccountInfo] = Seq(IamAccountInfo(
      id = "account1",
      name = "Account 1",
      description = "The first account",
      createdOn = ApiTime.nowUTC
    ))
    val response: HttpResponse[String] = Http(URL).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.nonEmpty)
    assert(searchResponse.orgs.contains(TESTORGS(0).orgId))
    assert(searchResponse.orgs(TESTORGS(0).orgId).heartbeatIntervals === JsonMethods.parse(TESTORGS(0).heartbeatIntervals).extract[NodeHeartbeatIntervals]) //convert json string to NodeHeartbeatIntervals object
    assert(searchResponse.orgs(TESTORGS(0).orgId).description === TESTORGS(0).description)
    assert(searchResponse.orgs(TESTORGS(0).orgId).label === TESTORGS(0).label)
    assert(searchResponse.orgs(TESTORGS(0).orgId).lastUpdated === TESTORGS(0).lastUpdated)
    assert(searchResponse.orgs(TESTORGS(0).orgId).limits === JsonMethods.parse(TESTORGS(0).limits).extract[OrgLimits]) //convert json string to orglimits object
    assert(searchResponse.orgs(TESTORGS(0).orgId).orgType === TESTORGS(0).orgType)
    assert(searchResponse.orgs(TESTORGS(0).orgId).tags.get === TESTORGS(0).tags.get.extract[Map[String, String]])
  }

  test("POST /myorgs -- as user getting orgs they aren't in -- 200 success") {
    val requestBody: Seq[IamAccountInfo] = Seq(IamAccountInfo(
      id = "account2",
      name = "Account 2",
      description = "The second account",
      createdOn = ApiTime.nowUTC
    ))
    val response: HttpResponse[String] = Http(URL).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.size >= 2)
    assert(searchResponse.orgs.contains(TESTORGS(1).orgId))
    assert(searchResponse.orgs(TESTORGS(1).orgId).heartbeatIntervals === JsonMethods.parse(TESTORGS(1).heartbeatIntervals).extract[NodeHeartbeatIntervals]) //convert json string to NodeHeartbeatIntervals object
    assert(searchResponse.orgs(TESTORGS(1).orgId).description === TESTORGS(1).description)
    assert(searchResponse.orgs(TESTORGS(1).orgId).label === TESTORGS(1).label)
    assert(searchResponse.orgs(TESTORGS(1).orgId).lastUpdated === TESTORGS(1).lastUpdated)
    assert(searchResponse.orgs(TESTORGS(1).orgId).limits === JsonMethods.parse(TESTORGS(1).limits).extract[OrgLimits]) //convert json string to orglimits object
    assert(searchResponse.orgs(TESTORGS(1).orgId).orgType === TESTORGS(1).orgType)
    assert(searchResponse.orgs(TESTORGS(1).orgId).tags.get === TESTORGS(1).tags.get.extract[Map[String, String]])

    assert(searchResponse.orgs.contains(TESTORGS(2).orgId))
    assert(searchResponse.orgs(TESTORGS(2).orgId).heartbeatIntervals === JsonMethods.parse(TESTORGS(2).heartbeatIntervals).extract[NodeHeartbeatIntervals]) //convert json string to NodeHeartbeatIntervals object
    assert(searchResponse.orgs(TESTORGS(2).orgId).description === TESTORGS(2).description)
    assert(searchResponse.orgs(TESTORGS(2).orgId).label === TESTORGS(2).label)
    assert(searchResponse.orgs(TESTORGS(2).orgId).lastUpdated === TESTORGS(2).lastUpdated)
    assert(searchResponse.orgs(TESTORGS(2).orgId).limits === JsonMethods.parse(TESTORGS(2).limits).extract[OrgLimits]) //convert json string to orglimits object
    assert(searchResponse.orgs(TESTORGS(2).orgId).orgType === TESTORGS(2).orgType)
    assert(searchResponse.orgs(TESTORGS(2).orgId).tags.get === TESTORGS(2).tags.get.extract[Map[String, String]])
  }

}
