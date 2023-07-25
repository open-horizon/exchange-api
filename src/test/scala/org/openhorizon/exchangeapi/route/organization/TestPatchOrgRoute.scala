package org.openhorizon.exchangeapi.route.organization

import org.openhorizon.exchangeapi.{ApiTime, ApiUtils, ExchConfig, HttpCode, Password, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.openhorizon.exchangeapi.table.node.NodeHeartbeatIntervals
import org.openhorizon.exchangeapi.table.organization.{OrgLimits, OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.{Password, Role, TestDBConnection}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc.PostgresProfile.api._

class TestPatchOrgRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

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
        description        = "TestPatchOrgRoute1",
        label              = "TestPatchOrgRoute1",
        lastUpdated        = ApiTime.nowUTC,
        limits             =
          """
            |{
            | "maxNodes": 100
            |}
            |""".stripMargin,
        orgId              = "testPatchOrgRoute1",
        orgType            = "TestPatchOrgRoute1",
        tags               = Some(JsonMethods.parse(
          """
            |{
            | "tagName": "tagValue"
            |}
            |""".stripMargin
        ))),
      OrgRow( //this org is just to run a test as an admin of another org
        heartbeatIntervals = "",
        description        = "TestPatchOrgRoute2",
        label              = "TestPatchOrgRoute2",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPatchOrgRoute2",
        orgType            = "",
        tags               = None
      ))

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestPatchOrgRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestPatchOrgRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/TestPatchOrgRouteOrgAdmin1",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORGADMIN1PASSWORD),
        admin       = true,
        hubAdmin    = false,
        email       = "TestPatchOrgRouteOrgAdmin1@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = TESTORGS(1).orgId + "/TestPatchOrgRouteOrgAdmin2",
        orgid       = TESTORGS(1).orgId,
        hashedPw    = Password.hash(ORGADMIN2PASSWORD),
        admin       = true,
        hubAdmin    = false,
        email       = "TestPatchOrgRouteOrgAdmin2@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/TestPatchOrgRouteUser1",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(USER1PASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "TestPatchOrgRouteUser1@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = TESTORGS(1).orgId + "/TestPatchOrgRouteUser2",
        orgid       = TESTORGS(1).orgId,
        hashedPw    = Password.hash(USER2PASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "TestPatchOrgRouteUser2@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
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
      ResourceChangesTQ.filter(_.orgId startsWith "testPatchOrgRoute").delete andThen
        OrgsTQ.filter(_.orgid startsWith "testPatchOrgRoute").delete andThen
        UsersTQ.filter(_.username startsWith "root/TestPatchOrgRouteHubAdmin").delete //this guy doesn't get deleted on cascade
    ), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      TESTORGS(0).update andThen
        ResourceChangesTQ.filter(_.orgId startsWith "testPatchOrgRoute").delete
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

  def assertPatchedEntryCreated(orgId: String): Unit = {
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ
      .filter(_.orgId === orgId)
      .filter(_.id === orgId)
      .filter(_.category === ResChangeCategory.ORG.toString)
      .filter(_.resource === ResChangeResource.ORG.toString)
      .filter(_.operation === ResChangeOperation.MODIFIED.toString)
      .result), AWAITDURATION)
    .nonEmpty)
  }

  private val normalRequestBody: PatchOrgRequest = PatchOrgRequest(
    orgType = Some("newType"),
    label = None,
    description = None,
    tags = None,
    limits = None,
    heartbeatIntervals = None
  )

  test("PATCH /orgs/doesNotExist -- 404 not found") {
    val request: HttpResponse[String] = Http(URL + "doesNotExist").postData(Serialization.write(normalRequestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.NOT_FOUND.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === "doesNotExist").result), AWAITDURATION).length
    assert(numOrgs === 0) //insure org is not added
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === "doesNotExist").result), AWAITDURATION).isEmpty)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- invalid body -- 400 bad input") {
    val requestBody: Map[String, String] = Map(
      "invalidKey" -> "invalidValue"
    )
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(requestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.BAD_INPUT.intValue)
    assertNoChanges(TESTORGS(0))
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- all fields empty -- 400 bad input") {
    val requestBody: PatchOrgRequest = PatchOrgRequest(
      orgType = None,
      label = None,
      description = None,
      tags = None,
      limits = None,
      heartbeatIntervals = None
    )
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(requestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.BAD_INPUT.intValue)
    assertNoChanges(TESTORGS(0))
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- max nodes too large -- 400 bad input") {
    val exchangeMaxNodes: Int = ExchConfig.getInt("api.limits.maxNodes")
    val requestBody: PatchOrgRequest = PatchOrgRequest(
      orgType = None,
      label = None,
      description = None,
      tags = None,
      limits = Some(OrgLimits(exchangeMaxNodes + 1)),
      heartbeatIntervals = None
    )
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(requestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.BAD_INPUT.intValue)
    assertNoChanges(TESTORGS(0))
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

  // it is undefined what happens when >1 attributes are included in the body, but based on the code it follows this order of preference:
  // orgType, label, description, heartbeatIntervals, tags, limits
  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- 2 attributes included -- success, only one attribute updated") {
    val requestBody = "{\"label\":\"newLabel\",\"orgType\":\"newType\"}" //easier to just type the JSON as a string to maintain the order of attributes
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(requestBody).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.POST_OK.intValue)
    val dbOrg: OrgRow = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === TESTORGS(0).orgId).result), AWAITDURATION).head
    assert(dbOrg.orgType === "newType")
    assert(dbOrg.tags.get === TESTORGS(0).tags.get)
    assert(dbOrg.orgId === TESTORGS(0).orgId)
    assert(dbOrg.limits === TESTORGS(0).limits)
    assert(dbOrg.label === TESTORGS(0).label)
    assert(dbOrg.description === TESTORGS(0).description)
    assert(dbOrg.heartbeatIntervals === TESTORGS(0).heartbeatIntervals)
    assert(dbOrg.lastUpdated !== TESTORGS(0).lastUpdated)
    assertPatchedEntryCreated(TESTORGS(0).orgId)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- update orgType -- normal success") {
    val requestBody: PatchOrgRequest = PatchOrgRequest(
      orgType = Some("newType"),
      label = None,
      description = None,
      tags = None,
      limits = None,
      heartbeatIntervals = None
    )
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(requestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.POST_OK.intValue)
    val dbOrg: OrgRow = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === TESTORGS(0).orgId).result), AWAITDURATION).head
    assert(dbOrg.orgType === requestBody.orgType.get)
    assert(dbOrg.tags.get === TESTORGS(0).tags.get)
    assert(dbOrg.orgId === TESTORGS(0).orgId)
    assert(dbOrg.limits === TESTORGS(0).limits)
    assert(dbOrg.label === TESTORGS(0).label)
    assert(dbOrg.description === TESTORGS(0).description)
    assert(dbOrg.heartbeatIntervals === TESTORGS(0).heartbeatIntervals)
    assert(dbOrg.lastUpdated !== TESTORGS(0).lastUpdated)
    assertPatchedEntryCreated(TESTORGS(0).orgId)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- update label -- normal success") {
    val requestBody: PatchOrgRequest = PatchOrgRequest(
      orgType = None,
      label = Some("newLabel"),
      description = None,
      tags = None,
      limits = None,
      heartbeatIntervals = None
    )
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(requestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.POST_OK.intValue)
    val dbOrg: OrgRow = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === TESTORGS(0).orgId).result), AWAITDURATION).head
    assert(dbOrg.orgType === TESTORGS(0).orgType)
    assert(dbOrg.tags.get === TESTORGS(0).tags.get)
    assert(dbOrg.orgId === TESTORGS(0).orgId)
    assert(dbOrg.limits === TESTORGS(0).limits)
    assert(dbOrg.label === requestBody.label.get)
    assert(dbOrg.description === TESTORGS(0).description)
    assert(dbOrg.heartbeatIntervals === TESTORGS(0).heartbeatIntervals)
    assert(dbOrg.lastUpdated !== TESTORGS(0).lastUpdated)
    assertPatchedEntryCreated(TESTORGS(0).orgId)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- update description -- normal success") {
    val requestBody: PatchOrgRequest = PatchOrgRequest(
      orgType = None,
      label = None,
      description = Some("new description"),
      tags = None,
      limits = None,
      heartbeatIntervals = None
    )
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(requestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.POST_OK.intValue)
    val dbOrg: OrgRow = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === TESTORGS(0).orgId).result), AWAITDURATION).head
    assert(dbOrg.orgType === TESTORGS(0).orgType)
    assert(dbOrg.tags.get === TESTORGS(0).tags.get)
    assert(dbOrg.orgId === TESTORGS(0).orgId)
    assert(dbOrg.limits === TESTORGS(0).limits)
    assert(dbOrg.label === TESTORGS(0).label)
    assert(dbOrg.description === requestBody.description.get)
    assert(dbOrg.heartbeatIntervals === TESTORGS(0).heartbeatIntervals)
    assert(dbOrg.lastUpdated !== TESTORGS(0).lastUpdated)
    assertPatchedEntryCreated(TESTORGS(0).orgId)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- update tags -- normal success") {
    val requestBody: PatchOrgRequest = PatchOrgRequest(
      orgType = None,
      label = None,
      description = None,
      tags = Some(Map("newKey" -> Some("newValue"))),
      limits = None,
      heartbeatIntervals = None
    )
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(requestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.POST_OK.intValue)
    val dbOrg: OrgRow = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === TESTORGS(0).orgId).result), AWAITDURATION).head
    assert(dbOrg.orgType === TESTORGS(0).orgType)
    assert(dbOrg.tags.get.extract[Map[String, Option[String]]] === requestBody.tags.get)
    assert(dbOrg.orgId === TESTORGS(0).orgId)
    assert(dbOrg.limits === TESTORGS(0).limits)
    assert(dbOrg.label === TESTORGS(0).label)
    assert(dbOrg.description === TESTORGS(0).description)
    assert(dbOrg.heartbeatIntervals === TESTORGS(0).heartbeatIntervals)
    assert(dbOrg.lastUpdated !== TESTORGS(0).lastUpdated)
    assertPatchedEntryCreated(TESTORGS(0).orgId)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- update limits -- normal success") {
    val requestBody: PatchOrgRequest = PatchOrgRequest(
      orgType = None,
      label = None,
      description = None,
      tags = None,
      limits = Some(OrgLimits(101)),
      heartbeatIntervals = None
    )
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(requestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.POST_OK.intValue)
    val dbOrg: OrgRow = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === TESTORGS(0).orgId).result), AWAITDURATION).head
    assert(dbOrg.orgType === TESTORGS(0).orgType)
    assert(dbOrg.tags.get === TESTORGS(0).tags.get)
    assert(dbOrg.orgId === TESTORGS(0).orgId)
    assert(JsonMethods.parse(dbOrg.limits).extract[OrgLimits] === requestBody.limits.get)
    assert(dbOrg.label === TESTORGS(0).label)
    assert(dbOrg.description === TESTORGS(0).description)
    assert(dbOrg.heartbeatIntervals === TESTORGS(0).heartbeatIntervals)
    assert(dbOrg.lastUpdated !== TESTORGS(0).lastUpdated)
    assertPatchedEntryCreated(TESTORGS(0).orgId)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- update heartbeatIntervals -- normal success") {
    val requestBody: PatchOrgRequest = PatchOrgRequest(
      orgType = None,
      label = None,
      description = None,
      tags = None,
      limits = None,
      heartbeatIntervals = Some(NodeHeartbeatIntervals(
        minInterval = 4,
        maxInterval = 5,
        intervalAdjustment = 6
      ))
    )
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(requestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.POST_OK.intValue)
    val dbOrg: OrgRow = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === TESTORGS(0).orgId).result), AWAITDURATION).head
    assert(dbOrg.orgType === TESTORGS(0).orgType)
    assert(dbOrg.tags.get === TESTORGS(0).tags.get)
    assert(dbOrg.orgId === TESTORGS(0).orgId)
    assert(dbOrg.limits === TESTORGS(0).limits)
    assert(dbOrg.label === TESTORGS(0).label)
    assert(dbOrg.description === TESTORGS(0).description)
    assert(JsonMethods.parse(dbOrg.heartbeatIntervals).extract[NodeHeartbeatIntervals] === requestBody.heartbeatIntervals.get)
    assert(dbOrg.lastUpdated !== TESTORGS(0).lastUpdated)
    assertPatchedEntryCreated(TESTORGS(0).orgId)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- as hub admin -- normal success") {
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.POST_OK.intValue)
    val dbOrg: OrgRow = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === TESTORGS(0).orgId).result), AWAITDURATION).head
    assert(dbOrg.orgType === normalRequestBody.orgType.get)
    assert(dbOrg.tags.get === TESTORGS(0).tags.get)
    assert(dbOrg.orgId === TESTORGS(0).orgId)
    assert(dbOrg.limits === TESTORGS(0).limits)
    assert(dbOrg.label === TESTORGS(0).label)
    assert(dbOrg.description === TESTORGS(0).description)
    assert(dbOrg.heartbeatIntervals === TESTORGS(0).heartbeatIntervals)
    assert(dbOrg.lastUpdated !== TESTORGS(0).lastUpdated)
    assertPatchedEntryCreated(TESTORGS(0).orgId)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- as admin in org -- normal success") {
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(ORGADMIN1AUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.POST_OK.intValue)
    val dbOrg: OrgRow = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === TESTORGS(0).orgId).result), AWAITDURATION).head
    assert(dbOrg.orgType === normalRequestBody.orgType.get)
    assert(dbOrg.tags.get === TESTORGS(0).tags.get)
    assert(dbOrg.orgId === TESTORGS(0).orgId)
    assert(dbOrg.limits === TESTORGS(0).limits)
    assert(dbOrg.label === TESTORGS(0).label)
    assert(dbOrg.description === TESTORGS(0).description)
    assert(dbOrg.heartbeatIntervals === TESTORGS(0).heartbeatIntervals)
    assert(dbOrg.lastUpdated !== TESTORGS(0).lastUpdated)
    assertPatchedEntryCreated(TESTORGS(0).orgId)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- as regular user in org -- 403 access denied") {
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(USER1AUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTORGS(0))
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- as org admin in other org -- 403 access denied") {
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(ORGADMIN2AUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTORGS(0))
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + " -- as regular user in other org -- 403 access denied") {
    val request: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    assert(request.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTORGS(0))
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).result), AWAITDURATION).isEmpty)
  }

}
