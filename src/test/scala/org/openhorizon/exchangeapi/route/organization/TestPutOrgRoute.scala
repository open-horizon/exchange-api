package org.openhorizon.exchangeapi.route.organization

import org.openhorizon.exchangeapi.TestDBConnection
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.node.NodeHeartbeatIntervals
import org.openhorizon.exchangeapi.table.organization.{OrgLimits, OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, ExchConfig, HttpCode}
import org.openhorizon.exchangeapi.TestDBConnection
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc.PostgresProfile.api._

class TestPutOrgRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"

  private implicit val formats = DefaultFormats

  private val HUBADMINPASSWORD = "hubadminpassword"
  private val ORGADMIN1PASSWORD = "orgadmin1password"
  private val ORGADMIN2PASSWORD = "orgadmin2password"
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
        description        = "TestPutOrgRoute1",
        label              = "TestPutOrgRoute1",
        lastUpdated        = ApiTime.nowUTC,
        limits             =
          """
            |{
            | "maxNodes": 100
            |}
            |""".stripMargin,
        orgId              = "testPutOrgRoute1",
        orgType            = "TestPutOrgRoute1",
        tags               = Some(JsonMethods.parse(
          """
            |{
            | "tagName": "tagValue"
            |}
            |""".stripMargin
        ))),
      OrgRow( //this org is just to run a test as an admin of another org
        heartbeatIntervals = "",
        description        = "TestPutOrgRoute2",
        label              = "TestPutOrgRoute2",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPutOrgRoute2",
        orgType            = "",
        tags               = None
      ))

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestPutOrgRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestPutOrgRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/TestPutOrgRouteOrgAdmin1",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORGADMIN1PASSWORD),
        admin       = true,
        hubAdmin    = false,
        email       = "TestPutOrgRouteOrgAdmin1@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(1).orgId + "/TestPutOrgRouteOrgAdmin2",
        orgid       = TESTORGS(1).orgId,
        hashedPw    = Password.hash(ORGADMIN2PASSWORD),
        admin       = true,
        hubAdmin    = false,
        email       = "TestPutOrgRouteOrgAdmin2@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/TestPutOrgRouteUser1",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(USER1PASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "TestPutOrgRouteUser1@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(1).orgId + "/TestPutOrgRouteUser2",
        orgid       = TESTORGS(1).orgId,
        hashedPw    = Password.hash(USER2PASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "TestPutOrgRouteUser2@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      )
    )

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORGADMIN1AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + ORGADMIN1PASSWORD))
  private val ORGADMIN2AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + ORGADMIN2PASSWORD))
  private val USER1AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(3).username + ":" + USER1PASSWORD))
  private val USER2AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(4).username + ":" + USER2PASSWORD))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      (OrgsTQ ++= TESTORGS) andThen
      (UsersTQ ++= TESTUSERS)), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      ResourceChangesTQ.filter(_.orgId startsWith "testPutOrgRoute").delete andThen
        OrgsTQ.filter(_.orgid startsWith "testPutOrgRoute").delete andThen
        UsersTQ.filter(_.username startsWith "root/TestPutOrgRouteHubAdmin").delete //this guy doesn't get deleted on cascade
    ), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      TESTORGS(0).update andThen //reset testPutOrgRoute1 each time
      ResourceChangesTQ.filter(_.orgId startsWith "testPutOrgRoute").delete
    ), AWAITDURATION)
  }

  def assertNoChanges(org: OrgRow): Unit = {
    val dbOrg: OrgRow = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === org.orgId).result), AWAITDURATION).head
    assert(dbOrg.orgType === org.orgType)
    assert(dbOrg.tags.get === org.tags.get)
    assert(dbOrg.orgId === org.orgId)
    assert(dbOrg.limits === org.limits)
    assert(dbOrg.label === org.label)
    assert(dbOrg.description === org.description)
    assert(dbOrg.heartbeatIntervals === org.heartbeatIntervals)
    assert(dbOrg.lastUpdated === org.lastUpdated)
  }

  def assertOrg1Updated(request: PostPutOrgRequest): Unit = {
    val dbOrg: OrgRow = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === TESTORGS(0).orgId).result), AWAITDURATION).head
    assert(dbOrg.orgType === request.orgType.getOrElse(""))
    assert(dbOrg.tags.get.extract[Map[String, String]] === request.tags.get)
    assert(dbOrg.orgId === TESTORGS(0).orgId)
    assert(JsonMethods.parse(dbOrg.limits).extract[OrgLimits] === request.limits.get)
    assert(dbOrg.label === request.label)
    assert(dbOrg.description === request.description)
    assert(JsonMethods.parse(dbOrg.heartbeatIntervals).extract[NodeHeartbeatIntervals] === request.heartbeatIntervals.get)
    assert(dbOrg.lastUpdated !== TESTORGS(0).lastUpdated) //make sure lastUpdated has changed
  }

  def assertCreatedModifiedEntryExists(orgId: String): Unit = {
    assert(
      Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ
        .filter(_.orgId === orgId)
        .filter(_.id === orgId)
        .filter(_.category === ResChangeCategory.ORG.toString)
        .filter(_.resource === ResChangeResource.ORG.toString)
        .filter(_.operation === ResChangeOperation.CREATEDMODIFIED.toString)
        .result), AWAITDURATION)
        .nonEmpty
    )
  }

  val normalRequestBody: PostPutOrgRequest = PostPutOrgRequest(
    orgType = None,
    label = "newLabel",
    description = "newDescription",
    tags = Some(Map("newKey" -> "newValue")),
    limits = Some(OrgLimits(101)),
    heartbeatIntervals = Some(NodeHeartbeatIntervals(
      minInterval = 4,
      maxInterval = 5,
      intervalAdjustment = 6
    ))
  )

  test("PUT /orgs/doesNotExist -- 404 not found") {
    val request: HttpResponse[String] = Http(URL + "doesNotExist").put(Serialization.write(normalRequestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.NOT_FOUND.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === "doesNotExist").result), AWAITDURATION).length
    assert(numOrgs === 0) //insure org is not added
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === "doesNotExist").result), AWAITDURATION).isEmpty)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + " -- invalid body -- 400 bad input") {
    val requestBody: Map[String, String] = Map("invalidKey" -> "invalidValue")
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).put(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.BAD_INPUT.intValue)
    assertNoChanges(TESTORGS(0))
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + " -- null label -- 400 bad input") {
    val requestBody: Map[String, String] = Map( //can't use PostPutOrgRequest here because it throws an exception if it's improperly created
      "orgType" -> null,
      "label" -> null,
      "description" -> "description",
      "tags" -> null,
      "limits" -> null,
      "heartbeatIntervals" -> null
    )
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).put(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.BAD_INPUT.intValue)
    assertNoChanges(TESTORGS(0))
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + " -- null description -- 400 bad input") {
    val requestBody: Map[String, String] = Map( //can't use PostPutOrgRequest here because it throws an exception if it's improperly created
      "orgType" -> null,
      "label" -> "label",
      "description" -> null,
      "tags" -> null,
      "limits" -> null,
      "heartbeatIntervals" -> null
    )
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).put(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.BAD_INPUT.intValue)
    assertNoChanges(TESTORGS(0))
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + " -- max nodes too large -- 400 bad input") {
    val exchangeMaxNodes: Int = ExchConfig.getInt("api.limits.maxNodes")
    val requestBody: PostPutOrgRequest = PostPutOrgRequest(
      orgType = None,
      label = "label",
      description = "description",
      tags = None,
      limits = Some(OrgLimits(exchangeMaxNodes + 1)),
      heartbeatIntervals = None
    )
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).put(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.BAD_INPUT.intValue)
    assertNoChanges(TESTORGS(0))
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + " as root -- normal success") {
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).put(Serialization.write(normalRequestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.PUT_OK.intValue)
    assertOrg1Updated(normalRequestBody)
    assertCreatedModifiedEntryExists(TESTORGS(0).orgId)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + " -- just label and description -- success, all else set to null or empty string") {
    val requestBody: Map[String, String] = Map(
      "label" -> "newLabel",
      "description" -> "newDescription"
    )
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).put(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.PUT_OK.intValue)
    val dbOrg: OrgRow = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === TESTORGS(0).orgId).result), AWAITDURATION).head
    assert(dbOrg.orgType === "")
    assert(dbOrg.tags.isEmpty)
    assert(dbOrg.orgId === TESTORGS(0).orgId)
    assert(dbOrg.limits === "")
    assert(dbOrg.label === requestBody("label"))
    assert(dbOrg.description === requestBody("description"))
    assert(dbOrg.heartbeatIntervals === "")
    assert(dbOrg.lastUpdated !== TESTORGS(0).lastUpdated) //make sure lastUpdated has changed
    assertCreatedModifiedEntryExists(TESTORGS(0).orgId)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + " as hub admin -- normal success") {
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).put(Serialization.write(normalRequestBody)).headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.PUT_OK.intValue)
    assertOrg1Updated(normalRequestBody)
    assertCreatedModifiedEntryExists(TESTORGS(0).orgId)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + " as admin in org -- normal success") {
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).put(Serialization.write(normalRequestBody)).headers(CONTENT).headers(ACCEPT).headers(ORGADMIN1AUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.PUT_OK.intValue)
    assertOrg1Updated(normalRequestBody)
    assertCreatedModifiedEntryExists(TESTORGS(0).orgId)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + " as regular user in org -- 403 access denied") {
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).put(Serialization.write(normalRequestBody)).headers(CONTENT).headers(ACCEPT).headers(USER1AUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTORGS(0))
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + " as org admin in other org -- 403 access denied") {
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).put(Serialization.write(normalRequestBody)).headers(CONTENT).headers(ACCEPT).headers(ORGADMIN2AUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTORGS(0))
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + " as regular user in other org -- 403 access denied") {
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).put(Serialization.write(normalRequestBody)).headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTORGS(0))
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

}
