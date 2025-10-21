package org.openhorizon.exchangeapi.route.search

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.route.organization.{PostNodeHealthRequest, PostNodeHealthResponse}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.agreement.{NodeAgreementRow, NodeAgreementsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
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

class TestPostNodeHealthRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/search/nodehealth"

  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp

  private val HUBADMINPASSWORD = "adminpassword"
  private val ORG1USERPASSWORD = "org1userpassword"
  private val ORG2USERPASSWORD = "org2userpassword"
  private val NODE1TOKEN = "node1token"
  private val NODE2TOKEN = "node2token"
  private val AGBOT1TOKEN = "agbot1token"
  private val AGBOT2TOKEN = "agbot2token"
  private val ORG1ADMINPASSWORD = "org1adminpassword"

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow(
        orgId              = "testPostNodeHealthRoute1",
        orgType            = "",
        label              = "testPostNodeHealth",
        description        = "Test Organization 1",
        lastUpdated        = ApiTime.nowUTC,
        tags               = None,
        limits             = "",
        heartbeatIntervals = ""),
      OrgRow(
        orgId              = "testPostNodeHealthRoute2",
        orgType            = "",
        label              = "testPostNodeHealth",
        description        = "Test Organization 2",
        lastUpdated        = ApiTime.nowUTC,
        tags               = None,
        limits             = "",
        heartbeatIntervals = ""
    ))

  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "TestPostNodeHealthRouteHubAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(ORG1USERPASSWORD)),
                username     = "org1user"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(1).orgId,
                password     = Option(Password.hash(ORG2USERPASSWORD)),
                username     = "org2user"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(ORG1ADMINPASSWORD)),
                username     = "org1admin"))
  }
  
  private val TESTNODES: Seq[NodeRow] =
    Seq(
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/node1",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).user, //org 1 user
        pattern            = "hasPattern",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = Password.hash(NODE1TOKEN),
        userInput          = ""),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(1).orgId + "/node2",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(1).orgId,
        owner              = TESTUSERS(2).user, //org 2 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = Password.hash(NODE2TOKEN),
        userInput          = ""),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/deadusernode",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(Instant.now().minusSeconds(600).toString), //long time ago
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).user, //org 1 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/liveusernode",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).user, //org 1 user
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
        owner              = TESTUSERS(3).user, //org 1 admin
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/noheartbeat",
        heartbeatIntervals = "",
        lastHeartbeat      = None,
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).user, //org 1 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = "")
    )

  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(AgbotRow(
      id            = TESTORGS(0).orgId + "/agbot1",
      orgid         = TESTORGS(0).orgId,
      token         = Password.hash(AGBOT1TOKEN),
      name          = "",
      owner         = TESTUSERS(1).user, //org 1 user
      msgEndPoint   = "",
      lastHeartbeat = ApiTime.nowUTC,
      publicKey     = ""
    ),
      AgbotRow(
        id            = TESTORGS(1).orgId + "/agbot2",
        orgid         = TESTORGS(1).orgId,
        token         = Password.hash(AGBOT2TOKEN),
        name          = "",
        owner         = TESTUSERS(2).user, //org 2 user
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      ))

  private val TESTAGREEMENTS: Seq[NodeAgreementRow] = Seq(
    NodeAgreementRow(
      agId          = TESTORGS(0).orgId + "/agreement1",
      nodeId        = TESTNODES(3).id,
      services      = "",
      agrSvcOrgid   = "",
      agrSvcPattern = "",
      agrSvcUrl     = "",
      state         = "",
      lastUpdated   = ApiTime.nowUTC),
    NodeAgreementRow(
      agId          = TESTORGS(0).orgId + "/agreement2",
      nodeId        = TESTNODES(3).id,
      services      = "",
      agrSvcOrgid   = "",
      agrSvcPattern = "",
      agrSvcUrl     = "",
      state         = "",
      lastUpdated   = ApiTime.nowUTC)
  )

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).organization + "/" + TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORG1USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + ORG1USERPASSWORD))
  private val ORG2USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).organization + "/" + TESTUSERS(2).username + ":" + ORG2USERPASSWORD))
  private val NODE1AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + NODE1TOKEN))
  private val NODE2AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(1).id + ":" + NODE2TOKEN))
  private val AGBOT1AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + AGBOT1TOKEN))
  private val AGBOT2AUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(1).id + ":" + AGBOT2TOKEN))
  private val ORG1ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(3).organization + "/" + TESTUSERS(3).username + ":" + ORG1ADMINPASSWORD))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      (OrgsTQ ++= TESTORGS) andThen
        (UsersTQ ++= TESTUSERS) andThen
        (AgbotsTQ ++= TESTAGBOTS) andThen
        (NodesTQ ++= TESTNODES) andThen
        (NodeAgreementsTQ ++= TESTAGREEMENTS)), AWAITDURATION
    )
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "testPostNodeHealthRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testPostNodeHealthRoute").delete andThen
      UsersTQ.filter(_.organization === "root")
             .filter(_.username startsWith "TestPostNodeHealthRouteHubAdmin").delete), AWAITDURATION)
  }

  private val oneMinuteAgo: PostNodeHealthRequest = PostNodeHealthRequest(Instant.now().minusSeconds(60).toString, None) //not sure what nodeOrgIds is for... it is never referenced in the Route

  test("POST /orgs/doesNotExist" + ROUTE + " -- 404 not found -- empty return") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE).postData(Serialization.write(oneMinuteAgo)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NotFound.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- empty body -- 400 bad input -- empty return") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData("{}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.BadRequest.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- invalid body -- 400 bad input -- empty return") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData("{\"invalidKey\":\"invalidValue\"}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.BadRequest.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- invalid time format -- success, return based on string comparison") {
    val requestBody: PostNodeHealthRequest = PostNodeHealthRequest("a", None)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NotFound.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- very recent time -- 404, empty return") {
    val requestBody: PostNodeHealthRequest = PostNodeHealthRequest(ApiTime.nowUTC, None)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NotFound.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- very old time -- success, all nodes with no patterns returned") {
    val requestBody: PostNodeHealthRequest = PostNodeHealthRequest(Instant.now().minusSeconds(3600).toString, None)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.size === 3)
    assert(searchResponse.nodes.contains(TESTNODES(2).id))
    assert(searchResponse.nodes(TESTNODES(2).id).lastHeartbeat === TESTNODES(2).lastHeartbeat)
    assert(searchResponse.nodes(TESTNODES(2).id).agreements.isEmpty)
    assert(searchResponse.nodes.contains(TESTNODES(3).id))
    assert(searchResponse.nodes(TESTNODES(3).id).lastHeartbeat === TESTNODES(3).lastHeartbeat)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.size === 2)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.contains(TESTAGREEMENTS(0).agId))
    assert(searchResponse.nodes(TESTNODES(3).id).agreements(TESTAGREEMENTS(0).agId).lastUpdated === TESTAGREEMENTS(0).lastUpdated)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.contains(TESTAGREEMENTS(1).agId))
    assert(searchResponse.nodes(TESTNODES(3).id).agreements(TESTAGREEMENTS(1).agId).lastUpdated === TESTAGREEMENTS(1).lastUpdated)
    assert(searchResponse.nodes.contains(TESTNODES(4).id))
    assert(searchResponse.nodes(TESTNODES(4).id).lastHeartbeat === TESTNODES(4).lastHeartbeat)
    assert(searchResponse.nodes(TESTNODES(4).id).agreements.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- time provided as empty string -- success, all nodes with no patterns returned") {
    val requestBody: PostNodeHealthRequest = PostNodeHealthRequest("", None)
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.size === 3)
    assert(searchResponse.nodes.contains(TESTNODES(2).id))
    assert(searchResponse.nodes(TESTNODES(2).id).lastHeartbeat === TESTNODES(2).lastHeartbeat)
    assert(searchResponse.nodes(TESTNODES(2).id).agreements.isEmpty)
    assert(searchResponse.nodes.contains(TESTNODES(3).id))
    assert(searchResponse.nodes(TESTNODES(3).id).lastHeartbeat === TESTNODES(3).lastHeartbeat)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.size === 2)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.contains(TESTAGREEMENTS(0).agId))
    assert(searchResponse.nodes(TESTNODES(3).id).agreements(TESTAGREEMENTS(0).agId).lastUpdated === TESTAGREEMENTS(0).lastUpdated)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.contains(TESTAGREEMENTS(1).agId))
    assert(searchResponse.nodes(TESTNODES(3).id).agreements(TESTAGREEMENTS(1).agId).lastUpdated === TESTAGREEMENTS(1).lastUpdated)
    assert(searchResponse.nodes.contains(TESTNODES(4).id))
    assert(searchResponse.nodes(TESTNODES(4).id).lastHeartbeat === TESTNODES(4).lastHeartbeat)
    assert(searchResponse.nodes(TESTNODES(4).id).agreements.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as root -- normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(oneMinuteAgo)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.size === 2)
    assert(searchResponse.nodes.contains(TESTNODES(3).id))
    assert(searchResponse.nodes(TESTNODES(3).id).lastHeartbeat === TESTNODES(3).lastHeartbeat)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.size === 2)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.contains(TESTAGREEMENTS(0).agId))
    assert(searchResponse.nodes(TESTNODES(3).id).agreements(TESTAGREEMENTS(0).agId).lastUpdated === TESTAGREEMENTS(0).lastUpdated)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.contains(TESTAGREEMENTS(1).agId))
    assert(searchResponse.nodes(TESTNODES(3).id).agreements(TESTAGREEMENTS(1).agId).lastUpdated === TESTAGREEMENTS(1).lastUpdated)
    assert(searchResponse.nodes.contains(TESTNODES(4).id))
    assert(searchResponse.nodes(TESTNODES(4).id).lastHeartbeat === TESTNODES(4).lastHeartbeat)
    assert(searchResponse.nodes(TESTNODES(4).id).agreements.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as hub admin -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(oneMinuteAgo)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as admin in org -- normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(oneMinuteAgo)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.size === 2)
    assert(searchResponse.nodes.contains(TESTNODES(3).id))
    assert(searchResponse.nodes(TESTNODES(3).id).lastHeartbeat === TESTNODES(3).lastHeartbeat)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.size === 2)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.contains(TESTAGREEMENTS(0).agId))
    assert(searchResponse.nodes(TESTNODES(3).id).agreements(TESTAGREEMENTS(0).agId).lastUpdated === TESTAGREEMENTS(0).lastUpdated)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.contains(TESTAGREEMENTS(1).agId))
    assert(searchResponse.nodes(TESTNODES(3).id).agreements(TESTAGREEMENTS(1).agId).lastUpdated === TESTAGREEMENTS(1).lastUpdated)
    assert(searchResponse.nodes.contains(TESTNODES(4).id))
    assert(searchResponse.nodes(TESTNODES(4).id).lastHeartbeat === TESTNODES(4).lastHeartbeat)
    assert(searchResponse.nodes(TESTNODES(4).id).agreements.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as user in org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(oneMinuteAgo)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as user in other org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(oneMinuteAgo)).headers(ACCEPT).headers(CONTENT).headers(ORG2USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as agbot in org -- normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(oneMinuteAgo)).headers(ACCEPT).headers(CONTENT).headers(AGBOT1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.size === 2)
    assert(searchResponse.nodes.contains(TESTNODES(3).id))
    assert(searchResponse.nodes(TESTNODES(3).id).lastHeartbeat === TESTNODES(3).lastHeartbeat)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.size === 2)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.contains(TESTAGREEMENTS(0).agId))
    assert(searchResponse.nodes(TESTNODES(3).id).agreements(TESTAGREEMENTS(0).agId).lastUpdated === TESTAGREEMENTS(0).lastUpdated)
    assert(searchResponse.nodes(TESTNODES(3).id).agreements.contains(TESTAGREEMENTS(1).agId))
    assert(searchResponse.nodes(TESTNODES(3).id).agreements(TESTAGREEMENTS(1).agId).lastUpdated === TESTAGREEMENTS(1).lastUpdated)
    assert(searchResponse.nodes.contains(TESTNODES(4).id))
    assert(searchResponse.nodes(TESTNODES(4).id).lastHeartbeat === TESTNODES(4).lastHeartbeat)
    assert(searchResponse.nodes(TESTNODES(4).id).agreements.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as agbot in other org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(oneMinuteAgo)).headers(ACCEPT).headers(CONTENT).headers(AGBOT2AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as node in org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(oneMinuteAgo)).headers(ACCEPT).headers(CONTENT).headers(NODE1AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.isEmpty)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as node in other org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).postData(Serialization.write(oneMinuteAgo)).headers(ACCEPT).headers(CONTENT).headers(NODE2AUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val searchResponse: PostNodeHealthResponse = JsonMethods.parse(response.body).extract[PostNodeHealthResponse]
    assert(searchResponse.nodes.isEmpty)
  }

}
