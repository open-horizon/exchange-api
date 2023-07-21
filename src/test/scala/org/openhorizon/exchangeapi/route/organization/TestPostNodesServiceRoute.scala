package org.openhorizon.exchangeapi.route.organization

import org.openhorizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Password, Role, TestDBConnection}
import org.openhorizon.exchangeapi.table.{AgbotRow, AgbotsTQ, OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.openhorizon.exchangeapi.route.node.{PostServiceSearchRequest, PostServiceSearchResponse}
import org.openhorizon.exchangeapi.table.node.status.{NodeStatusRow, NodeStatusTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.{Password, Role, TestDBConnection}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc.PostgresProfile.api._

class TestPostNodesServiceRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/search/nodes/service"

  private implicit val formats = DefaultFormats

  private val HUBADMINPASSWORD = "adminpassword"
  private val ORG1USERPASSWORD = "org1userpassword"
  private val ORG1ADMINPASSWORD = "org1adminpassword"
  private val ORG2USERPASSWORD = "org2userpassword"
  private val ORG2ADMINPASSWORD = "org2adminpassword"
  private val NODE1TOKEN = "node1token"
  private val NODE2TOKEN = "node2token"
  private val AGBOT1TOKEN = "agbot1token"
  private val AGBOT2TOKEN = "agbot2token"

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow(
        heartbeatIntervals = "",
        description        = "Test Organization 1",
        label              = "testPostNodesService",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPostNodesServiceRoute1",
        orgType            = "",
        tags               = None),
      OrgRow(
        heartbeatIntervals = "",
        description        = "Test Organization 2",
        label              = "testPostNodesService",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPostNodesServiceRoute2",
        orgType            = "",
        tags               = None
      ))

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestPostNodesServiceRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestPostNodesServiceRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/org1user",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORG1USERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "org1user@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/org1admin",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORG1ADMINPASSWORD),
        admin       = true,
        hubAdmin    = false,
        email       = "org1admin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = TESTORGS(1).orgId + "/org2user",
        orgid       = TESTORGS(1).orgId,
        hashedPw    = Password.hash(ORG2USERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "org2user@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = TESTORGS(1).orgId + "/org2admin",
        orgid       = TESTORGS(1).orgId,
        hashedPw    = Password.hash(ORG2ADMINPASSWORD),
        admin       = true,
        hubAdmin    = false,
        email       = "org2uadmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ))

  private val TESTNODES: Seq[NodeRow] =
    Seq(
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/node1",
        heartbeatIntervals = "",
        lastHeartbeat      = None,
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).username, //org 1 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = Password.hash(NODE1TOKEN),
        userInput          = ""),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(1).orgId + "/node2",
        heartbeatIntervals = "",
        lastHeartbeat      = None,
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(1).orgId,
        owner              = TESTUSERS(3).username, //org 2 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = Password.hash(NODE2TOKEN),
        userInput          = ""),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/liveusernode",
        heartbeatIntervals = "",
        lastHeartbeat      = None,
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).username, //org 1 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/liveadminnode",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(2).username, //org 1 admin
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/deadadminnode",
        heartbeatIntervals = "",
        lastHeartbeat      = None,
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(2).username, //org 1 admin
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""))

  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(AgbotRow(
      id            = TESTORGS(0).orgId + "/agbot1",
      orgid         = TESTORGS(0).orgId,
      token         = Password.hash(AGBOT1TOKEN),
      name          = "",
      owner         = TESTUSERS(1).username, //org 1 user
      msgEndPoint   = "",
      lastHeartbeat = ApiTime.nowUTC,
      publicKey     = ""
    ),
      AgbotRow(
        id            = TESTORGS(1).orgId + "/agbot2",
        orgid         = TESTORGS(1).orgId,
        token         = Password.hash(AGBOT2TOKEN),
        name          = "",
        owner         = TESTUSERS(3).username, //org 2 user
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      ))

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORG1USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + ORG1USERPASSWORD))
  private val ORG1ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + ORG1ADMINPASSWORD))
  private val ORG2USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(3).username + ":" + ORG2USERPASSWORD))
  private val ORG2ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(4).username + ":" + ORG2ADMINPASSWORD))
  private val NODE1AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + NODE1TOKEN))
  private val NODE2AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(1).id + ":" + NODE2TOKEN))
  private val AGBOT1AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + AGBOT1TOKEN))
  private val AGBOT2AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(1).id + ":" + AGBOT2TOKEN))

  private val SERVICEURL = "TestPostNodesServiceRouteService"
  private val SERVICEVERSION = "1.0.0"
  private val SERVICEARCH = "arm64"

  private val TESTNODESTATUSES: Seq[NodeStatusRow] =
    Seq(
      NodeStatusRow(
        nodeId = TESTNODES(0).id,
        connectivity = "",
        services = "",
        runningServices = "",
        lastUpdated = ApiTime.nowUTC
      ),
      NodeStatusRow(
        nodeId = TESTNODES(1).id,
        connectivity = "",
        services = "",
        runningServices = "|" + TESTORGS(0).orgId + "/" + SERVICEURL + "_" + SERVICEVERSION + "_" + SERVICEARCH + "|",
        lastUpdated = ApiTime.nowUTC
      ),
      NodeStatusRow(
        nodeId = TESTNODES(2).id,
        connectivity = "",
        services = "",
        runningServices = "|" + TESTORGS(0).orgId + "/" + SERVICEURL + "_" + SERVICEVERSION + "_" + SERVICEARCH + "|",
        lastUpdated = ApiTime.nowUTC
      ),
      NodeStatusRow(
        nodeId = TESTNODES(3).id,
        connectivity = "",
        services = "",
        runningServices = "|" + TESTORGS(0).orgId + "/" + SERVICEURL + "_" + SERVICEVERSION + "_" + SERVICEARCH + "|",
        lastUpdated = ApiTime.nowUTC
      ),
      NodeStatusRow(
        nodeId = TESTNODES(4).id,
        connectivity = "",
        services = "",
        runningServices = "|" + TESTORGS(0).orgId + "/fakeService_" + SERVICEVERSION + "_" + SERVICEARCH + "|",
        lastUpdated = ApiTime.nowUTC
      )
    )

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      (OrgsTQ ++= TESTORGS) andThen
        (UsersTQ ++= TESTUSERS) andThen
        (AgbotsTQ ++= TESTAGBOTS) andThen
        (NodesTQ ++= TESTNODES) andThen
        (NodeStatusTQ ++= TESTNODESTATUSES)), AWAITDURATION
    )
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "testPostNodesServiceRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testPostNodesServiceRoute").delete andThen
      UsersTQ.filter(_.username startsWith "root/TestPostNodesServiceRouteHubAdmin").delete), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  val normalRequestBody: PostServiceSearchRequest = PostServiceSearchRequest(
    orgid = TESTORGS(0).orgId,
    serviceURL = SERVICEURL,
    serviceVersion = SERVICEVERSION,
    serviceArch = SERVICEARCH
  )

  test("POST /orgs/doesNotExist/search/nodes/service -- 404 not found -- empty return") {
    val requestBody: PostServiceSearchRequest = PostServiceSearchRequest(
      orgid = "doesNotExist",
      serviceURL = SERVICEURL,
      serviceVersion = SERVICEVERSION,
      serviceArch = SERVICEARCH)
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- empty body -- 400 bad input -- empty return") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData("{}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- invalid body -- 400 bad input -- empty return") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData("{\"invalidKey\":\"invalidValue\"}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- null orgid in body -- 400 bad input -- empty return") {
    val requestBody: Map[String, String] = Map(
      "orgid" -> null,
      "serviceURL" -> SERVICEURL,
      "serviceVersion" -> SERVICEVERSION,
      "serviceArch" -> SERVICEARCH)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- null serviceURL in body -- 400 bad input -- empty return") {
    val requestBody: Map[String, String] = Map(
      "orgid" -> TESTORGS(0).orgId,
      "serviceURL" -> null,
      "serviceVersion" -> SERVICEVERSION,
      "serviceArch" -> SERVICEARCH)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- null serviceVersion in body -- 400 bad input -- empty return") {
    val requestBody: Map[String, String] = Map(
      "orgid" -> TESTORGS(0).orgId,
      "serviceURL" -> SERVICEURL,
      "serviceVersion" -> null,
      "serviceArch" -> SERVICEARCH)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- null serviceArch in body -- 400 bad input -- empty return") {
    val requestBody: Map[String, String] = Map(
      "orgid" -> TESTORGS(0).orgId,
      "serviceURL" -> SERVICEURL,
      "serviceVersion" -> SERVICEVERSION,
      "serviceArch" -> null)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- nonexistent service -- 404 not found -- empty return") {
    val requestBody: PostServiceSearchRequest = PostServiceSearchRequest(
      orgid = TESTORGS(0).orgId,
      serviceURL = "doesNotExist",
      serviceVersion = SERVICEVERSION,
      serviceArch = SERVICEARCH)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- as root -- success, all nodes returned") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //the json4s extract method doesn't recognize a 2-element array as a tuple, so The PostServiceSearchResponse class in NodesRoutes won't work
    //Instead, treat the (id, lastHeartbeat) tuple as a sequence of optional strings so that json4s can extract it
    val searchResponse: Seq[Seq[Option[String]]] = (JsonMethods.parse(response.body) \ "nodes").extract[Seq[Seq[Option[String]]]]
    assert(searchResponse.length === 2)
    assert(searchResponse.contains(Seq(Some(TESTNODES(2).id), TESTNODES(2).lastHeartbeat)))
    assert(searchResponse.contains(Seq(Some(TESTNODES(3).id), TESTNODES(3).lastHeartbeat)))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- as hub admin -- 403 access denied -- empty return") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- as user in org -- success, only nodes owned by user returned") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //the json4s extract method doesn't recognize a 2-element array as a tuple, so The PostServiceSearchResponse class in NodesRoutes won't work
    //Instead, treat the (id, lastHeartbeat) tuple as a sequence of optional strings so that json4s can extract it
    val searchResponse: Seq[Seq[Option[String]]] = (JsonMethods.parse(response.body) \ "nodes").extract[Seq[Seq[Option[String]]]]
    assert(searchResponse.length === 1)
    assert(searchResponse.contains(Seq(Some(TESTNODES(2).id), TESTNODES(2).lastHeartbeat)))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- as user in other org -- 403 access denied -- empty return") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG2USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- as admin in org -- success, all nodes returned") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //the json4s extract method doesn't recognize a 2-element array as a tuple, so The PostServiceSearchResponse class in NodesRoutes won't work
    //Instead, treat the (id, lastHeartbeat) tuple as a sequence of optional strings so that json4s can extract it
    val searchResponse: Seq[Seq[Option[String]]] = (JsonMethods.parse(response.body) \ "nodes").extract[Seq[Seq[Option[String]]]]
    assert(searchResponse.length === 2)
    assert(searchResponse.contains(Seq(Some(TESTNODES(2).id), TESTNODES(2).lastHeartbeat)))
    assert(searchResponse.contains(Seq(Some(TESTNODES(3).id), TESTNODES(3).lastHeartbeat)))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- as admin in other org -- 403 access denied -- empty return") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG2ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- as agbot in org -- success, all nodes returned") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //the json4s extract method doesn't recognize a 2-element array as a tuple, so The PostServiceSearchResponse class in NodesRoutes won't work
    //Instead, treat the (id, lastHeartbeat) tuple as a sequence of optional strings so that json4s can extract it
    val searchResponse: Seq[Seq[Option[String]]] = (JsonMethods.parse(response.body) \ "nodes").extract[Seq[Seq[Option[String]]]]
    assert(searchResponse.length === 2)
    assert(searchResponse.contains(Seq(Some(TESTNODES(2).id), TESTNODES(2).lastHeartbeat)))
    assert(searchResponse.contains(Seq(Some(TESTNODES(3).id), TESTNODES(3).lastHeartbeat)))
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- as agbot in other org -- 403 access denied -- empty return") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOT2AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- as node in org -- 403 access denied -- empty return") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(NODE1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + "/search/nodes/service -- as node in other org -- 403 access denied -- empty return") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(NODE2AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(1).orgId + "/search/nodes/service -- search for service that isn't running in this org -- 404 not found -- empty return") {
    val requestBody: PostServiceSearchRequest = PostServiceSearchRequest(
      orgid = TESTORGS(0).orgId,
      serviceURL = "fakeService",
      serviceVersion = SERVICEVERSION,
      serviceArch = SERVICEARCH)
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val searchResponse: PostServiceSearchResponse = JsonMethods.parse(response.body).extract[PostServiceSearchResponse]
    assert(searchResponse.nodes.isEmpty)
  }

}
