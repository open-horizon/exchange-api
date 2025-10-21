package org.openhorizon.exchangeapi.route.organization

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.node.NodeHeartbeatIntervals
import org.openhorizon.exchangeapi.table.organization.{Org, OrgLimits, OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostOrgRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"

  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp

  private val HUBADMINPASSWORD = "adminpassword"
  private val USER1PASSWORD = "user1password"

  private val TESTORGS: Seq[OrgRow] = //this org is created so that we can create a user that is a part of it and so we can attempt to add another org with the same name
    Seq(
      OrgRow(
        heartbeatIntervals = "",
        description        = "TestPostOrgRoute",
        label              = "TestPostOrgRoute",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "TEMPtestPostOrgRoute",
        orgType            = "",
        tags               = None))

  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "TestPostOrgRouteHubAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(USER1PASSWORD)),
                username     = "TestPostOrgRouteUser1"))
  }
  
  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).organization + "/" + TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val USER1AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + USER1PASSWORD))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      (OrgsTQ ++= TESTORGS) andThen
      (UsersTQ ++= TESTUSERS)), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      ResourceChangesTQ.filter(_.orgId startsWith "TEMPtestPostOrgRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "TEMPtestPostOrgRoute").delete andThen
      UsersTQ.filter(_.organization === "root")
             .filter(_.username startsWith "TestPostOrgRouteHubAdmin").delete //this guy doesn't get deleted on cascade
    ), AWAITDURATION)
  }

  private val orgId: String = "testPostOrgRoute1"
  private val normalRequestBody: PostPutOrgRequest = PostPutOrgRequest(
    heartbeatIntervals = Some(NodeHeartbeatIntervals(
      minInterval = 1,
      maxInterval = 2,
      intervalAdjustment = 3
    )),
    description        = "Test Organization 1",
    label              = "testPostOrgRoute",
    limits             = Some(OrgLimits(
      maxNodes = 100
    )),
    orgType            = Some("testPostOrgRoute"),
    tags               = Some(Map("tagName" -> "tagValue")
    ))

  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.run(
      ResourceChangesTQ.filter(_.orgId startsWith "testPostOrgRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testPostOrgRoute").delete), AWAITDURATION)
  }

  def assertOrgsEqual(org1: PostPutOrgRequest, org2: OrgRow): Unit = {
    assert(JsonMethods.parse(org2.heartbeatIntervals).extract[NodeHeartbeatIntervals] === org1.heartbeatIntervals.get)
    assert(org2.orgType === org1.orgType.get)
    assert(org2.description === org1.description)
    assert(org2.label === org1.label)
    assert(JsonMethods.parse(org2.limits).extract[OrgLimits] === org1.limits.get)
    assert(org2.tags.get.extract[Map[String, String]] === org1.tags.get)
  }

  def assertCreatedEntryExists(orgId: String): Unit = {
    assert(
      Await.result(DBCONNECTION.run(ResourceChangesTQ
        .filter(_.orgId === orgId)
        .filter(_.id === orgId)
        .filter(_.category === ResChangeCategory.ORG.toString)
        .filter(_.resource === ResChangeResource.ORG.toString)
        .filter(_.operation === ResChangeOperation.CREATED.toString)
        .result), AWAITDURATION)
        .nonEmpty
    )
  }

  test("POST /orgs/" + orgId + " -- invalid body -- 400 bad input") {
    val requestBody: Map[String, String] = Map("invalidKey" -> "invalidValue")
    val request: HttpResponse[String] = Http(URL + orgId).postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === StatusCodes.BadRequest.intValue)
    assert(Await.result(DBCONNECTION.run(OrgsTQ.getOrgid(orgId).result), AWAITDURATION).isEmpty) //make sure org didn't actually get added to DB
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === orgId).result), AWAITDURATION).isEmpty)
  }

  test("POST /orgs/" + orgId + " -- null label -- 400 bad input") {
    val requestBody: Map[String, String] = Map( //can't use PostPutOrgRequest here because it throws an exception if it's improperly created
      "orgType" -> null,
      "label" -> null,
      "description" -> "description",
      "tags" -> null,
      "limits" -> null,
      "heartbeatIntervals" -> null
    )
    val request: HttpResponse[String] = Http(URL + orgId).postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === StatusCodes.BadRequest.intValue)
    assert(Await.result(DBCONNECTION.run(OrgsTQ.getOrgid(orgId).result), AWAITDURATION).isEmpty) //make sure org didn't actually get added to DB
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === orgId).result), AWAITDURATION).isEmpty)
  }

  //error message "requirement failed" isn't very descriptive here
  test("POST /orgs/" + orgId + " -- null description -- 400 bad input") {
    val requestBody: Map[String, String] = Map( //can't use PostPutOrgRequest here because it throws an exception if it's improperly created
      "orgType" -> null,
      "label" -> "label",
      "description" -> null,
      "tags" -> null,
      "limits" -> null,
      "heartbeatIntervals" -> null
    )
    val request: HttpResponse[String] = Http(URL + orgId).postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === StatusCodes.BadRequest.intValue)
    assert(Await.result(DBCONNECTION.run(OrgsTQ.getOrgid(orgId).result), AWAITDURATION).isEmpty) //make sure org didn't actually get added to DB
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === orgId).result), AWAITDURATION).isEmpty)
  }

  test("POST /orgs/" + orgId + " -- max nodes too large -- 400 bad input") {
    val exchangeMaxNodes: Int = Configuration.getConfig.getInt("api.limits.maxNodes")
    val requestBody: PostPutOrgRequest = PostPutOrgRequest(
      orgType = None,
      label = "label",
      description = "description",
      tags = None,
      limits = Some(OrgLimits(exchangeMaxNodes + 1)),
      heartbeatIntervals = None
    )
    val request: HttpResponse[String] = Http(URL + orgId).postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === StatusCodes.BadRequest.intValue)
    assert(Await.result(DBCONNECTION.run(OrgsTQ.getOrgid(orgId).result), AWAITDURATION).isEmpty) //make sure org didn't actually get added to DB
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === orgId).result), AWAITDURATION).isEmpty)
  }

  test("POST /orgs/" + orgId + " as root -- normal success") {
    val request: HttpResponse[String] = Http(URL + orgId).postData(Serialization.write(normalRequestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === StatusCodes.Created.intValue)
    val newOrg: OrgRow = Await.result(DBCONNECTION.run(OrgsTQ.filter(_.orgid === orgId).take(1).result), AWAITDURATION).head
    assert(newOrg.orgId === orgId)
    assertOrgsEqual(normalRequestBody, newOrg)
    assertCreatedEntryExists(orgId)
  }

  test("POST /orgs/" + orgId + " as hub admin -- normal success") {
    val request: HttpResponse[String] = Http(URL + orgId).postData(Serialization.write(normalRequestBody)).headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === StatusCodes.Created.intValue)
    val newOrg: OrgRow = Await.result(DBCONNECTION.run(OrgsTQ.filter(_.orgid === orgId).take(1).result), AWAITDURATION).head
    assert(newOrg.orgId === orgId)
    assertOrgsEqual(normalRequestBody, newOrg)
    assertCreatedEntryExists(orgId)
  }

  test("POST /orgs/" + orgId + " as regular user -- 403 access denied") {
    val request: HttpResponse[String] = Http(URL + orgId).postData(Serialization.write(normalRequestBody)).headers(CONTENT).headers(ACCEPT).headers(USER1AUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === StatusCodes.Forbidden.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.run(OrgsTQ.getOrgid(orgId).result), AWAITDURATION).length
    assert(numOrgs === 0) //make sure org didn't actually get added to DB
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === orgId).result), AWAITDURATION).isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + " -- 409 conflict (already exists)") {
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(normalRequestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === StatusCodes.Conflict.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.run(OrgsTQ.getOrgid(TESTORGS(0).orgId).result), AWAITDURATION).length
    assert(numOrgs === 1)
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

}
