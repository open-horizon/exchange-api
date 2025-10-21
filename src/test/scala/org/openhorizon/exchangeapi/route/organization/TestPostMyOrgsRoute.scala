package org.openhorizon.exchangeapi.route.organization

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.openhorizon.exchangeapi.auth.cloud.IamAccountInfo
import org.openhorizon.exchangeapi.table.node.NodeHeartbeatIntervals
import org.openhorizon.exchangeapi.table.organization.{Org, OrgLimits, OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostMyOrgsRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/myorgs"

  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp

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
          "{\"ibmcloud_id\":\"account1\"}"
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
          "{\"ibmcloud_id\":\"account2\"}"
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
          "{\"ibmcloud_id\":\"account2\"}"
        )),
        limits             = "{\"maxNodes\":15}",
        heartbeatIntervals = "{" +
          "\"minInterval\":10," +
          "\"maxInterval\":11," +
          "\"intervalAdjustment\":12" +
          "}"
      )
    )

  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "TestPostMyOrgsRouteHubAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(USERPASSWORD)),
                username     = "TestPostMyOrgsRouteUser"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(ADMINPASSWORD)),
                username     = "TestPostMyOrgsRouteAdmin"))
  }
  
  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).organization + "/" + TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + USERPASSWORD))
  private val ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).organization + "/" + TESTUSERS(2).username + ":" + ADMINPASSWORD))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      (OrgsTQ ++= TESTORGS) andThen
      (UsersTQ ++= TESTUSERS)
    ), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPostMyOrgsRouteOrg").delete andThen
      OrgsTQ.filter(_.orgid startsWith "TestPostMyOrgsRouteOrg").delete andThen
      UsersTQ.filter(_.organization === "root")
             .filter(_.username startsWith "TestPostMyOrgsRouteHubAdmin").delete), AWAITDURATION)
  }

  def assertOrgsEqual(org1: Org, org2: OrgRow): Unit = {
    assert(org1.heartbeatIntervals === JsonMethods.parse(org2.heartbeatIntervals).extract[NodeHeartbeatIntervals]) //convert json string to NodeHeartbeatIntervals object
    assert(org1.description === org2.description)
    assert(org1.label === org2.label)
    assert(org1.lastUpdated === org2.lastUpdated)
    assert(org1.limits === JsonMethods.parse(org2.limits).extract[OrgLimits]) //convert json string to orglimits object
    assert(org1.orgType === org2.orgType)
    assert(org1.tags.get === org2.tags.get.extract[Map[String, String]])
  }

  test("POST /myorgs -- empty body -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL).postData("{}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.BadRequest.intValue)
  }

  test("POST /myorgs -- invalid body -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL).postData("{\"invalidKey\":\"invalidValue\"}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.BadRequest.intValue)
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
    assert(response.code === StatusCodes.NotFound.intValue)
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
    assert(response.code === StatusCodes.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.nonEmpty)
    assert(searchResponse.orgs.contains(TESTORGS(0).orgId))
    assertOrgsEqual(searchResponse.orgs(TESTORGS(0).orgId), TESTORGS(0))
  }

  test("POST /myorgs -- only id provided -- 200 success") {
    val requestBody = "[{\"id\":\"account1\"}]"
    val response: HttpResponse[String] = Http(URL).postData(requestBody).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.nonEmpty)
    assert(searchResponse.orgs.contains(TESTORGS(0).orgId))
    assertOrgsEqual(searchResponse.orgs(TESTORGS(0).orgId), TESTORGS(0))
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
    assert(response.code === StatusCodes.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.size >= 3)
    assert(searchResponse.orgs.contains(TESTORGS(0).orgId))
    assertOrgsEqual(searchResponse.orgs(TESTORGS(0).orgId), TESTORGS(0))

    assert(searchResponse.orgs.contains(TESTORGS(1).orgId))
    assertOrgsEqual(searchResponse.orgs(TESTORGS(1).orgId), TESTORGS(1))

    assert(searchResponse.orgs.contains(TESTORGS(2).orgId))
    assertOrgsEqual(searchResponse.orgs(TESTORGS(2).orgId), TESTORGS(2))
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
    assert(response.code === StatusCodes.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.nonEmpty)
    assert(searchResponse.orgs.contains(TESTORGS(0).orgId))
    assertOrgsEqual(searchResponse.orgs(TESTORGS(0).orgId), TESTORGS(0))
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
    assert(response.code === StatusCodes.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.nonEmpty)
    assert(searchResponse.orgs.contains(TESTORGS(0).orgId))
    assertOrgsEqual(searchResponse.orgs(TESTORGS(0).orgId), TESTORGS(0))
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
    assert(response.code === StatusCodes.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.nonEmpty)
    assert(searchResponse.orgs.contains(TESTORGS(0).orgId))
    assertOrgsEqual(searchResponse.orgs(TESTORGS(0).orgId), TESTORGS(0))
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
    assert(response.code === StatusCodes.OK.intValue)
    val searchResponse: GetOrgsResponse = JsonMethods.parse(response.body).extract[GetOrgsResponse]
    assert(searchResponse.orgs.size >= 2)
    assert(searchResponse.orgs.contains(TESTORGS(1).orgId))
    assertOrgsEqual(searchResponse.orgs(TESTORGS(1).orgId), TESTORGS(1))

    assert(searchResponse.orgs.contains(TESTORGS(2).orgId))
    assertOrgsEqual(searchResponse.orgs(TESTORGS(2).orgId), TESTORGS(2))
  }

}
