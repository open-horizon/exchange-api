package org.openhorizon.exchangeapi.route.organization

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.CacheDirectives.public
import org.openhorizon.exchangeapi.ExchangeApi
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.sql.Timestamp
import java.time.{Instant, ZoneId, ZonedDateTime}
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostOrgChangesRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT: (String, String) = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/changes"

  private val EXCHANGEVERSION: String = ExchangeApi.adminVersion()

  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val INSTANT = Instant.now()
  val TIMESTAMPSTR: String = INSTANT.toString
  val TIMESTAMPPAST60: String = INSTANT.minusSeconds(60).toString
  val TIMESTAMPPAST600: String = INSTANT.minusSeconds(600).toString
  val TIMESTAMPFUTURE600: String = INSTANT.plusSeconds(600).toString
  private val HUBADMINPASSWORD = "hubadminpassword"
  private val ORG1USERPASSWORD = "org1userpassword"
  private val ORG2USERPASSWORD = "org2userpassword"
  private val ORG1NODETOKEN = "org1nodetoken"
  private val ORG2NODETOKEN = "org2nodetoken"
  private val ORG1AGBOTTOKEN = "org1agbottoken"
  private val ORG2AGBOTTOKEN = "org2agbottoken"
  private val IBMAGBOTTOKEN = "ibmagbottoken"
  
  val rootUser: UUID = Await.result(DBCONNECTION.run(Compiled(UsersTQ.filter(users => users.organization === "root" && users.username === "root").map(_.user)).result.head), AWAITDURATION)
  
  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow(
        orgId              = "testPostOrgChangesRouteOrg1",
        orgType            = "",
        label              = "testPostOrgChangesRouteOrg",
        description        = "Test Organization 1",
        lastUpdated        = TIMESTAMPSTR,
        tags               = None,
        limits             = "",
        heartbeatIntervals = ""),
      OrgRow(
        orgId              = "testPostOrgChangesRouteOrg2",
        orgType            = "",
        label              = "testPostOrgChangesRouteOrg",
        description        = "Test Organization 2",
        lastUpdated        = TIMESTAMPSTR,
        tags               = None,
        limits             = "",
        heartbeatIntervals = ""
      )
    )

  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = INSTANT,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = INSTANT,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "testPostOrgChangesRouteHubAdmin"),
        UserRow(createdAt    = INSTANT,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = INSTANT,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(ORG1USERPASSWORD)),
                username     = "org1user"),
        UserRow(createdAt    = INSTANT,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = INSTANT,
                organization = TESTORGS(1).orgId,
                password     = Option(Password.hash(ORG2USERPASSWORD)),
                username     = "org2user"))
  }
  
  private val TESTNODES: Seq[NodeRow] =
    Seq(
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/org1node",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(TIMESTAMPSTR),
        lastUpdated        = TIMESTAMPSTR,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).user, //org 1 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = Password.hash(ORG1NODETOKEN),
        userInput          = ""
      ),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(1).orgId + "/org2node",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(TIMESTAMPSTR),
        lastUpdated        = TIMESTAMPSTR,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(1).orgId,
        owner              = TESTUSERS(2).user, //org 2 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = Password.hash(ORG2NODETOKEN),
        userInput          = ""
      )
    )

  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(AgbotRow(
      id            = TESTORGS(0).orgId + "/org1agbot",
      orgid         = TESTORGS(0).orgId,
      token         = Password.hash(ORG1AGBOTTOKEN),
      name          = "",
      owner         = TESTUSERS(1).user, //org 1 user
      msgEndPoint   = "",
      lastHeartbeat = TIMESTAMPSTR,
      publicKey     = ""
    ),
      AgbotRow(
        id            = TESTORGS(1).orgId + "/org2agbot",
        orgid         = TESTORGS(1).orgId,
        token         = Password.hash(ORG2AGBOTTOKEN),
        name          = "",
        owner         = TESTUSERS(2).user, //org 2 user
        msgEndPoint   = "",
        lastHeartbeat = TIMESTAMPSTR,
        publicKey     = ""
      ),
      AgbotRow(
        id            = "IBM/testPostOrgChangesRouteIBMAgbot",
        orgid         = "IBM",
        token         = Password.hash(IBMAGBOTTOKEN),
        name          = "",
        owner         = rootUser,
        msgEndPoint   = "",
        lastHeartbeat = TIMESTAMPSTR,
        publicKey     = ""
      )
    )

  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val HUBADMINAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).organization + "/" + TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORG1USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + ORG1USERPASSWORD))
  private val ORG2USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).organization + "/" + TESTUSERS(2).username + ":" + ORG2USERPASSWORD))
  private val ORG1NODEAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + ORG1NODETOKEN))
  private val ORG2NODEAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(1).id + ":" + ORG2NODETOKEN))
  private val ORG1AGBOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + ORG1AGBOTTOKEN))
  private val ORG2AGBOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(1).id + ":" + ORG2AGBOTTOKEN))
  private val IBMAGBOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(2).id + ":" + IBMAGBOTTOKEN))

  private val TESTRESOURCECHANGES: Seq[ResourceChangeRow] = Seq(
    // old -- 0
    ResourceChangeRow(changeId = 0L,
                      orgId = TESTORGS(0).orgId,
                      id = TESTNODES(0).id,
                      category = "node",
                      public = "true",
                      resource = "org",
                      operation = "created",
                      lastUpdated = INSTANT.minusSeconds(3600)), //1 hour ago
    // other org, not public -- 1
    ResourceChangeRow(changeId = 0L,
                      orgId = TESTORGS(1).orgId,
                      id = TESTNODES(0).id,
                      category = "service",
                      public = "false",
                      resource = "agbot",
                      operation = "created",
                      lastUpdated = INSTANT),
    // node category, id of other node -- 2
    ResourceChangeRow(changeId = 0L,
                      orgId = TESTORGS(0).orgId,
                      id = TESTNODES(1).id,
                      category = "node",
                      public = "false",
                      resource = "agbotagreements",
                      operation = "deleted",
                      lastUpdated = INSTANT),
    // mgmtpolicy -- 3
    ResourceChangeRow(changeId = 0L,
                      orgId = TESTORGS(0).orgId,
                      id = TESTNODES(1).id,
                      category = "mgmtpolicy",
                      public = "false",
                      resource = "nodeagreements",
                      operation = "deleted",
                      lastUpdated = INSTANT),
    // other org, public -- 4
    ResourceChangeRow(changeId = 0L,
                      orgId = TESTORGS(1).orgId,
                      id = TESTNODES(1).id,
                      category = "mgmtpolicy",
                      public = "true",
                      resource = "node",
                      operation = "deleted",
                      lastUpdated = INSTANT),
    // resource nodemsgs -- 5
    ResourceChangeRow(changeId = 0L,
                      orgId = TESTORGS(1).orgId,
                      id = TESTNODES(0).id,
                      category = "service",
                      public = "true",
                      resource = "nodemsgs",
                      operation = "deleted",
                      lastUpdated = INSTANT),
    // resource nodestatus ... 6
    ResourceChangeRow(changeId = 0L,
                      orgId = TESTORGS(1).orgId,
                      id = TESTNODES(0).id,
                      category = "org",
                      public = "false",
                      resource = "nodestatus",
                      operation = "deleted",
                      lastUpdated = INSTANT),
    // resource nodeagreements + op createdmodified -- 7
    ResourceChangeRow(changeId = 0L,
                      orgId = TESTORGS(0).orgId,
                      id = TESTNODES(0).id.split("/")(1), //want the id without the org part
                      category = "node",
                      public = "true",
                      resource = "nodeagreements",
                      operation = "created/modified",
                      lastUpdated = INSTANT),
    // resource agbotagreements + op createdmodified -- 8
    ResourceChangeRow(changeId = 0L,
                      orgId = TESTORGS(0).orgId,
                      id = TESTNODES(0).id,
                      category = "mgmtpolicy",
                      public = "true",
                      resource = "agbotagreements",
                      operation = "created/modified",
                      lastUpdated = INSTANT),
    // agbot success -- 9
    ResourceChangeRow(changeId = 0L,
                      orgId = TESTORGS(0).orgId,
                      id = TESTNODES(0).id,
                      category = "mgmtpolicy",
                      public = "false",
                      resource = "org",
                      operation = "created",
                      lastUpdated = INSTANT),
    // 10
    ResourceChangeRow(changeId = 0L,
                      orgId = TESTORGS(0).orgId,
                      id = "nodegroup0",
                      category = "ha_group",
                      public = "false",
                      resource = "ha_group",
                      operation = "modified",
                      lastUpdated = INSTANT)
  )

  var lastChangeId: Long = 0L //will be set in beforeAll()

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGS) andThen
                                 (UsersTQ ++= TESTUSERS) andThen
                                 (ResourceChangesTQ ++= TESTRESOURCECHANGES) andThen
                                 (AgbotsTQ ++= TESTAGBOTS) andThen
                                 (NodesTQ ++= TESTNODES)), AWAITDURATION
    )
    lastChangeId =
      Await.result(DBCONNECTION.run(ResourceChangesTQ //get changeId of last RC added to DB
                                      .filter(_.orgId startsWith "testPostOrgChangesRoute")
                                      .map(_.changeId)
                                      .sortBy(_.desc)
                                      .take(1)
                                      .result), AWAITDURATION).head
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "testPostOrgChangesRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testPostOrgChangesRoute").delete andThen
      UsersTQ.filter(_.organization === "root")
             .filter(_.username startsWith "testPostOrgChangesRouteHubAdmin").delete andThen
      AgbotsTQ.filter(_.id startsWith "IBM/testPostOrgChangesRouteIBMAgbot").delete), AWAITDURATION)
  }

  override def afterEach(): Unit = {
    //need to reset heartbeat each time to ensure it actually gets changed in each test
    Await.ready(DBCONNECTION.run(
      NodesTQ.update(TESTNODES(0)) andThen //can't do "updateAll", so do them individually
      NodesTQ.update(TESTNODES(1)) andThen
      AgbotsTQ.update(TESTAGBOTS(0)) andThen
      AgbotsTQ.update(TESTAGBOTS(1)) andThen
      AgbotsTQ.update(TESTAGBOTS(2))
    ), AWAITDURATION)
  }

  // Resource Change that is dynamically needed, specific to the test case.
  //only does one at a time for ease of deleting when finished
  def fixtureResourceChange(testCode: ResourceChangeRow => Any, testData: ResourceChangeRow): Any = {
    try{
      Await.result(DBCONNECTION.run(ResourceChangesTQ += testData), AWAITDURATION)
      testCode(testData)
    }
    finally Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(x => x.orgId === testData.orgId && x.resource === testData.resource && x.id === testData.id).delete), AWAITDURATION)
  }

  def assertResourceChangeExists(rc: ResourceChangeRow, body: ResourceChangesRespObject): Unit = {
    assert(body.changes.exists(x => x.orgId === rc.orgId && x.resource === rc.resource && x.id === rc.id && x.operation === rc.operation)) //should be enough to uniquely identify an RC
  }

  private val defaultIdRequest: ResourceChangesRequest =
    ResourceChangesRequest(changeId = lastChangeId, //will ensure that at least the final RC added will be returned
                           lastUpdated = None,
                           maxRecords = 300,
                           orgList = None)

  private val defaultTimeRequest: ResourceChangesRequest = ResourceChangesRequest(
    changeId = 0L, // <- 0 means don't use
    lastUpdated = Some(TIMESTAMPPAST600), //10 min ago, should get everything added with ApiTime.nowUTC
    maxRecords = 100,
    orgList = None
  )

  //I assume this returns empty list because maxRecords = 0?
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- empty body -- success, empty return") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData("{}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
    assert(responseObj.changes.isEmpty)
    assert(responseObj.exchangeVersion === EXCHANGEVERSION)
    assert(!responseObj.hitMaxRecords)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- invalid body -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData("{\"invalidKey\":\"invalidValue\"}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.BadRequest.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- improperly formatted time -- 400 bad input") {
    val request: ResourceChangesRequest = ResourceChangesRequest(
      changeId = 0, //shouldn't actually be used so value doesn't matter
      lastUpdated = Some("asdf"), //invalid
      maxRecords = 100,
      orgList = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(request)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.BadRequest.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- specify changeId -- success, returns last TESTRC") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(defaultIdRequest)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
    assert(responseObj.changes.nonEmpty)
    assert(responseObj.changes.exists(_.resourceChanges.exists(_.changeId === lastChangeId))) //check if RC with lastChangeId is in response
    assert(responseObj.exchangeVersion === EXCHANGEVERSION)
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- specify changeId for record that no longer exists -- success, returns last TESTRC") {
    
    val request =
      ResourceChangesRequest(changeId = defaultIdRequest.changeId - 1000,
                             lastUpdated = None,
                             maxRecords = 300,
                             orgList = None)
    
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(request)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
    assert(responseObj.changes.nonEmpty)
    assert(responseObj.changes.exists(_.resourceChanges.exists(_.changeId === lastChangeId))) //check if RC with lastChangeId is in response
    assert(responseObj.exchangeVersion === EXCHANGEVERSION)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- both changeId and lastUpdated provided -- success, uses lastUpdated") {
    val request: ResourceChangesRequest =
      ResourceChangesRequest(changeId = lastChangeId, //will ensure that at least the final RC added will be returned
                             lastUpdated = Option(TIMESTAMPFUTURE600), //if this were to be used, none of the TESTRCs would be included
                             maxRecords = 100,
                             orgList = None)
    info("request.lastUpdated:  " + request.lastUpdated)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(request)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
    assert(responseObj.changes.nonEmpty)
    assert(responseObj.changes.exists(_.resourceChanges.exists(_.changeId.equals(lastChangeId)))) //check if RC with lastChangeId is in response
    assert(responseObj.exchangeVersion === EXCHANGEVERSION)
  }
  
  test("POST /orgs/doesNotExist" + ROUTE + " -- 403 access denied - other organization - user") {
    val request: ResourceChangesRequest =
      ResourceChangesRequest(changeId = lastChangeId - 5, //hopefully some of the TESTRCs will be returned. if not, no big deal, we should still get some public RCs
                             lastUpdated = None,
                             maxRecords = 5, //only take 5 for speed's sake
                             orgList = None)
    
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE).postData(Serialization.write(request)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
  }

  ignore("POST /orgs/" + TESTORGS(1).orgId + ROUTE + " -- " + TESTORGS(1).orgId + " not in orgList, should be automatically added -- success") {
    val request: ResourceChangesRequest = ResourceChangesRequest(
      changeId = 0,
      lastUpdated = Some(TIMESTAMPPAST600), //should get most TESTRCs
      maxRecords = 100,
      orgList = Some(List.empty)
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE).postData(Serialization.write(request)).headers(ACCEPT).headers(CONTENT).headers(IBMAGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
    assert(responseObj.changes.size >= 3)
    assertResourceChangeExists(TESTRESOURCECHANGES(1), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(4), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(9), responseObj)
    assert(responseObj.exchangeVersion === EXCHANGEVERSION)
    //check that heartbeat of caller was updated
    assert(Await.result(DBCONNECTION.run(AgbotsTQ.filter(_.id === TESTAGBOTS(2).id).result), AWAITDURATION).head.lastHeartbeat > TESTAGBOTS(2).lastHeartbeat)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- " + TESTORGS(0).orgId + " wildcard '*' -- success") {
    val request: ResourceChangesRequest = ResourceChangesRequest(
      changeId = 0,
      lastUpdated = Some(TIMESTAMPPAST60), //should get most TESTRCs
      maxRecords = 100,
      orgList = Some(List("*"))
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(request)).headers(ACCEPT).headers(CONTENT).headers(IBMAGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
    assert(responseObj.changes.size >= 5)
    assertResourceChangeExists(TESTRESOURCECHANGES(1), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(2), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(3), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(4), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(9), responseObj)
    assert(responseObj.exchangeVersion === EXCHANGEVERSION)
    //check that heartbeat of caller was updated
    assert(Await.result(DBCONNECTION.run(AgbotsTQ.filter(_.id === TESTAGBOTS(2).id).result), AWAITDURATION).head.lastHeartbeat > TESTAGBOTS(2).lastHeartbeat)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- " + TESTORGS(0).orgId + " wildcard '' (empty string) -- success") {
    val request: ResourceChangesRequest =
      ResourceChangesRequest(changeId = 0,
                             lastUpdated = Some(TIMESTAMPPAST60), //should get most TESTRCs
                             maxRecords = 100,
                             orgList = Some(List("*")))
    
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(request)).headers(ACCEPT).headers(CONTENT).headers(IBMAGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
    assert(responseObj.changes.size >= 5)
    assertResourceChangeExists(TESTRESOURCECHANGES(1), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(2), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(3), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(4), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(9), responseObj)
    assert(responseObj.exchangeVersion === EXCHANGEVERSION)
    //check that heartbeat of caller was updated
    assert(Await.result(DBCONNECTION.run(AgbotsTQ.filter(_.id === TESTAGBOTS(2).id).result), AWAITDURATION).head.lastHeartbeat > TESTAGBOTS(2).lastHeartbeat)
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as agbot in org -- success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(defaultTimeRequest)).headers(ACCEPT).headers(CONTENT).headers(ORG1AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
    assert(responseObj.changes.size >= 4) //can't check for exact num because it may pick up some public RCs from other tests
    assertResourceChangeExists(TESTRESOURCECHANGES(2), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(3), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(4), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(9), responseObj)
    assert(responseObj.exchangeVersion === EXCHANGEVERSION)
    //check that heartbeat of caller was updated
    assert(Await.result(DBCONNECTION.run(AgbotsTQ.filter(_.id === TESTAGBOTS(0).id).result), AWAITDURATION).head.lastHeartbeat > TESTAGBOTS(0).lastHeartbeat)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as agbot in other org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(defaultTimeRequest)).headers(ACCEPT).headers(CONTENT).headers(ORG2AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    //insure heartbeat hasn't changed
    assert(Await.result(DBCONNECTION.run(AgbotsTQ.filter(_.id === TESTAGBOTS(1).id).result), AWAITDURATION).head.lastHeartbeat === TESTAGBOTS(1).lastHeartbeat)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as node in org -- success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(defaultTimeRequest)).headers(ACCEPT).headers(CONTENT).headers(ORG1NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
    assert(responseObj.changes.size >= 6) //can't check for exact num because it may pick up some public RCs from other tests
    assertResourceChangeExists(TESTRESOURCECHANGES(3), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(4), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(5), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(7), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(8), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(9), responseObj)
    assert(responseObj.exchangeVersion === EXCHANGEVERSION)
    //check that heartbeat of caller was updated
    assert(Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === TESTNODES(0).id).result), AWAITDURATION).head.lastHeartbeat.get > TESTNODES(0).lastHeartbeat.get)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as node in other org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(defaultTimeRequest)).headers(ACCEPT).headers(CONTENT).headers(ORG2NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    //insure heartbeat hasn't changed
    assert(Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === TESTNODES(1).id).result), AWAITDURATION).head.lastHeartbeat === TESTNODES(1).lastHeartbeat)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as root -- success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(defaultTimeRequest)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
    assert(responseObj.changes.size >= 7) //can't check for exact num because it may pick up some public RCs from other tests
    assertResourceChangeExists(TESTRESOURCECHANGES(2), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(3), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(4), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(5), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(7), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(8), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(9), responseObj)
    assert(responseObj.exchangeVersion === EXCHANGEVERSION)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as hub admin -- success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(defaultTimeRequest)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
    assert(responseObj.changes.size >= 7) //can't check for exact num because it may pick up some public RCs from other tests
    assertResourceChangeExists(TESTRESOURCECHANGES(2), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(3), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(4), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(5), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(7), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(8), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(9), responseObj)
    assert(responseObj.exchangeVersion === EXCHANGEVERSION)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as user in org -- success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(defaultTimeRequest)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
    assert(responseObj.changes.size >= 7) //can't check for exact num because it may pick up some public RCs from other tests
    assertResourceChangeExists(TESTRESOURCECHANGES(2), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(3), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(4), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(5), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(7), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(8), responseObj)
    assertResourceChangeExists(TESTRESOURCECHANGES(9), responseObj)
    assert(responseObj.exchangeVersion === EXCHANGEVERSION)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as user in other org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(defaultTimeRequest)).headers(ACCEPT).headers(CONTENT).headers(ORG2USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- insure changing maxRecords cuts down return size -- success") {
    val request: ResourceChangesRequest = ResourceChangesRequest(
      changeId = 0L, // <- 0 means don't use
      lastUpdated = Some(TIMESTAMPPAST600), //should get everything added with ApiTime.nowUTC
      maxRecords = 2,
      orgList = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(request)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
    if (responseObj.changes.size === 1) assert(responseObj.changes(0).resourceChanges.size === 2) else assert(responseObj.changes.size === 2)
    assert(responseObj.exchangeVersion === EXCHANGEVERSION)
  }

  //possibly: add test where maxRecords is > 10,000 and ensure that there are only 10,000 returns... would involve adding > 10,000 RCs to the DB... not sure if that's worth testing

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- insure 2 RCs are added under same change -- success") {
    val newRC: ResourceChangeRow = ResourceChangeRow( //very similar to 9 -- 10
      changeId = 0L,
      orgId = TESTORGS(0).orgId,
      id = TESTNODES(0).id,
      category = "agbot",
      public = "true",
      resource = "org",
      operation = "deleted",
      lastUpdated = INSTANT
    )
    fixtureResourceChange(
      _ =>{
        val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(defaultTimeRequest)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        assert(response.code === StatusCodes.Created.intValue)
        val responseObj: ResourceChangesRespObject = JsonMethods.parse(response.body).extract[ResourceChangesRespObject]
        assertResourceChangeExists(newRC, responseObj)
        val change: ChangeEntry = responseObj.changes.filter(_.orgId === TESTRESOURCECHANGES(9).orgId).filter(_.resource === TESTRESOURCECHANGES(9).resource).filter(_.id === TESTRESOURCECHANGES(9).id).head
        assert(change.operation === newRC.operation)
        assert(change.resourceChanges.length === 2)
        assert(responseObj.exchangeVersion === EXCHANGEVERSION)
      }, newRC)
  }
}
