package org.openhorizon.exchangeapi.route.administration

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.agreementbot.agreement.{AgbotAgreementRow, AgbotAgreementsTQ}
import org.openhorizon.exchangeapi.table.agreementbot.message.{AgbotMsgRow, AgbotMsgsTQ}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.agreement.{NodeAgreementRow, NodeAgreementsTQ}
import org.openhorizon.exchangeapi.table.node.message.{NodeMsgRow, NodeMsgsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.schema.SchemaTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.tag.AdminStatusTest
import org.openhorizon.exchangeapi.utility.ApiTime.fixFormatting
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, ExchMsg, HttpCode}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.sql.Timestamp
import java.time.ZoneId
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}



class TestGetAdminStatus extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/status"
  private val SCHEMAVERSION: Int = Await.result(DBCONNECTION.run(SchemaTQ.getSchemaVersion.result), AWAITDURATION).head

  private implicit val formats: DefaultFormats.type = DefaultFormats

  private val PASSWORD = "password"
  
  private val TIMESTAMP: Timestamp = ApiTime.nowUTCTimestamp
  private val TIMESTAMPSTRING: String = fixFormatting(TIMESTAMP.toInstant.atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC")).toString)

  private val TESTORGS: Seq[OrgRow] =
    Seq(OrgRow(description = "",
               heartbeatIntervals = "",
               label = "",
               lastUpdated = TIMESTAMPSTRING,
               limits = "",
               orgId = "testGetOrgStatusRoute0",
               orgType = "",
               tags = None),
        OrgRow(description = "",
               heartbeatIntervals = "",
               label = "",
               lastUpdated = TIMESTAMPSTRING,
               limits = "",
               orgId = "testGetOrgStatusRoute1",
               orgType = "",
               tags = None))

  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(createdAt        = TIMESTAMP,
                isHubAdmin       = true,
                isOrgAdmin       = false,
                modifiedAt       = TIMESTAMP,
                organization     = "root",
                password         = Option(Password.hash(PASSWORD)),
                username         = "TestGetOrgStatusRouteHubAdmin0"),
        UserRow(createdAt        = TIMESTAMP,
                isHubAdmin       = false,
                isOrgAdmin       = true,
                modifiedAt       = TIMESTAMP,
                organization     = "testGetOrgStatusRoute0",
                password         = Option(Password.hash(PASSWORD)),
                username         = "admin0"),
        UserRow(createdAt        = TIMESTAMP,
                isHubAdmin       = false,
                isOrgAdmin       = false,
                modifiedAt       = TIMESTAMP,
                organization     = "testGetOrgStatusRoute0",
                password         = Option(Password.hash(PASSWORD)),
                username         = "user0"),
        UserRow(createdAt        = TIMESTAMP,
                isHubAdmin       = false,
                isOrgAdmin       = false,
                modifiedAt       = TIMESTAMP,
                organization     = "testGetOrgStatusRoute1",
                password         = Option(Password.hash(PASSWORD)),
                username         = "user0"))

  private val TESTNODES: Seq[NodeRow] =
    Seq(NodeRow(arch               = "",
                id                 = TESTORGS(0).orgId + "/node0",
                heartbeatIntervals = "",
                lastHeartbeat      = None,
                lastUpdated        = TIMESTAMPSTRING,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS(0).orgId,
                owner              = TESTUSERS(1).user,
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = Password.hash(PASSWORD),
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS(0).orgId + "/node1",
                heartbeatIntervals = "",
                lastHeartbeat      = None,
                lastUpdated        = TIMESTAMPSTRING,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS(0).orgId,
                owner              = TESTUSERS(2).user,
                pattern            = "",
                publicKey          = "registered",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS(1).orgId + "/node0",
                heartbeatIntervals = "",
                lastHeartbeat      = None,
                lastUpdated        = TIMESTAMPSTRING,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS(1).orgId,
                owner              = TESTUSERS(3).user,
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""))

  private val TESTAGBOTS: Seq[AgbotRow] = //must have an agbot in order to create a node message
    Seq(AgbotRow(id            = "IBM/testGetOrgStatusRoute0",
                 lastHeartbeat = TIMESTAMPSTRING,
                 msgEndPoint   = "",
                 name          = "testAgbot",
                 orgid         = "IBM",
                 owner         = TESTUSERS(0).user,
                 publicKey     = "",
                 token         = Password.hash(PASSWORD)),
        AgbotRow(id            = TESTORGS(0).orgId + "/agbot0",
                 lastHeartbeat = TIMESTAMPSTRING,
                 msgEndPoint   = "",
                 name          = "testAgbot",
                 orgid         = TESTORGS(0).orgId,
                 owner         = TESTUSERS(1).user,
                 publicKey     = "",
                 token         = Password.hash(PASSWORD)),
        AgbotRow(id            = TESTORGS(0).orgId + "/agbot1",
                 lastHeartbeat = TIMESTAMPSTRING,
                 msgEndPoint   = "",
                 name          = "testAgbot",
                 orgid         = TESTORGS(0).orgId,
                 owner         = TESTUSERS(2).user,
                 publicKey     = "",
                 token         = ""),
        AgbotRow(id            = TESTORGS(1).orgId + "/agbot0",
                 lastHeartbeat = TIMESTAMPSTRING,
                 msgEndPoint   = "",
                 name          = "testAgbot",
                 orgid         = TESTORGS(1).orgId,
                 owner         = TESTUSERS(3).user,
                 publicKey     = "",
                 token         = ""),
    )

  private val TESTAGBOTAGREEMENTS: Seq[AgbotAgreementRow] =
    Seq(AgbotAgreementRow(agrId = "TestGetOrgStatusRoute0",
                          agbotId = TESTAGBOTS(0).id,
                          dataLastReceived = "",
                          lastUpdated = TIMESTAMPSTRING,
                          serviceOrgid = "IBM",
                          servicePattern = "",
                          serviceUrl = "",
                          state = "active"),
        AgbotAgreementRow(agrId = "TestGetOrgStatusRoute1",
                          agbotId = TESTAGBOTS(1).id,
                          dataLastReceived = "",
                          lastUpdated = TIMESTAMPSTRING,
                          serviceOrgid = TESTORGS(0).orgId,
                          servicePattern = "",
                          serviceUrl = "",
                          state = "active"),
        AgbotAgreementRow(agrId = "TestGetOrgStatusRoute2",
                          agbotId = TESTAGBOTS(2).id,
                          dataLastReceived = "",
                          lastUpdated = TIMESTAMPSTRING,
                          serviceOrgid = TESTORGS(0).orgId,
                          servicePattern = "",
                          serviceUrl = "",
                          state = "active"),
        AgbotAgreementRow(agrId = "TestGetOrgStatusRoute3",
                          agbotId = TESTAGBOTS(3).id,
                          dataLastReceived = "",
                          lastUpdated = TIMESTAMPSTRING,
                          serviceOrgid = TESTORGS(1).orgId,
                          servicePattern = "",
                          serviceUrl = "",
                          state = "active"))
  
  private val TESTNODEAGREEMENTS: Seq[NodeAgreementRow] =
    Seq(NodeAgreementRow(agId          = "TestGetOrgStatusRoute0",
                         agrSvcOrgid   = TESTORGS(0).orgId,
                         agrSvcPattern = "",
                         agrSvcUrl     = "",
                         lastUpdated   = TIMESTAMPSTRING,
                         nodeId        = TESTNODES(0).id,
                         services      = "",
                         state         = "active"),
        NodeAgreementRow(agId          = "TestGetOrgStatusRoute1",
                         agrSvcOrgid   = TESTORGS(0).orgId,
                         agrSvcPattern = "",
                         agrSvcUrl     = "",
                         lastUpdated   = TIMESTAMPSTRING,
                         nodeId        = TESTNODES(1).id,
                         services      = "",
                         state         = "active"),
        NodeAgreementRow(agId          = "TestGetOrgStatusRoute2",
                         agrSvcOrgid   = TESTORGS(1).orgId,
                         agrSvcPattern = "",
                         agrSvcUrl     = "",
                         lastUpdated   = TIMESTAMPSTRING,
                         nodeId        = TESTNODES(2).id,
                         services      = "",
                         state         = "active"))
  
  private val TESTAGBOTMESSAGES: Seq[AgbotMsgRow] =
    Seq(AgbotMsgRow(agbotId = TESTAGBOTS(0).id,
                    message = "",
                    msgId = 0,
                    nodeId = TESTNODES(0).id,
                    nodePubKey = "",
                    timeExpires = ApiTime.futureUTC(120),
                    timeSent = TIMESTAMPSTRING),
        AgbotMsgRow(agbotId = TESTAGBOTS(1).id,
                    message = "",
                    msgId = 0,
                    nodeId = TESTNODES(0).id,
                    nodePubKey = "",
                    timeExpires = ApiTime.futureUTC(120),
                    timeSent = TIMESTAMPSTRING),
        AgbotMsgRow(agbotId = TESTAGBOTS(2).id,
                    message = "",
                    msgId = 0,
                    nodeId = TESTNODES(1).id,
                    nodePubKey = "",
                    timeExpires = ApiTime.futureUTC(120),
                    timeSent = TIMESTAMPSTRING),
        AgbotMsgRow(agbotId = TESTAGBOTS(3).id,
                    message = "",
                    msgId = 0,
                    nodeId = TESTNODES(2).id,
                    nodePubKey = "",
                    timeExpires = ApiTime.futureUTC(120),
                    timeSent = TIMESTAMPSTRING))
  
  private val TESTNODEMESSAGES: Seq[NodeMsgRow] =
    Seq(NodeMsgRow(agbotId = TESTAGBOTS(1).id,
                   agbotPubKey = "",
                   message = "",
                   msgId = 0, // this will be automatically set to a unique ID by the DB
                   nodeId = TESTNODES(0).id,
                   timeExpires = ApiTime.futureUTC(120),
                   timeSent = TIMESTAMPSTRING),
        NodeMsgRow(agbotId = TESTAGBOTS(2).id,
                   agbotPubKey = "",
                   message = "",
                   msgId = 0, // this will be automatically set to a unique ID by the DB
                   nodeId = TESTNODES(1).id,
                   timeExpires = ApiTime.futureUTC(120),
                   timeSent = TIMESTAMPSTRING),
        NodeMsgRow(agbotId = TESTAGBOTS(3).id,
                   agbotPubKey = "",
                   message = "",
                   msgId = 0, // this will be automatically set to a unique ID by the DB
                   nodeId = TESTNODES(2).id,
                   timeExpires = ApiTime.futureUTC(120),
                   timeSent = TIMESTAMPSTRING))

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).organization + "/" + TESTUSERS(0).username + ":" + PASSWORD))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).organization + "/" + TESTUSERS(2).username + ":" + PASSWORD))
  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + PASSWORD))
  private val NODEAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + PASSWORD))
  private val MULTITENANTAGBOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + PASSWORD))
  private val AGBOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(1).id + ":" + PASSWORD))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGS) andThen
                                 (UsersTQ ++= TESTUSERS) andThen
                                 (AgbotsTQ ++= TESTAGBOTS) andThen
                                 (NodesTQ ++= TESTNODES) andThen
                                 (NodeMsgsTQ ++= TESTNODEMESSAGES) andThen
                                 (NodeAgreementsTQ ++= TESTNODEAGREEMENTS) andThen
                                 (AgbotMsgsTQ ++= TESTAGBOTMESSAGES) andThen
                                 (AgbotAgreementsTQ ++= TESTAGBOTAGREEMENTS)), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "testGetOrgStatusRoute").delete andThen
                                 OrgsTQ.filter(_.orgid startsWith "testGetOrgStatusRoute").delete andThen
                                 UsersTQ.filter(users => users.organization === "root" && users.user === TESTUSERS.head.user).delete andThen
                                 AgbotsTQ.filter(agreement_bots => agreement_bots.id === TESTAGBOTS.head.id &&
                                                                   agreement_bots.orgid === TESTAGBOTS.head.orgid).delete), AWAITDURATION)
    
    val response: HttpResponse[String] = Http(sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/admin/clearauthcaches").method("POST").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
  }
  

  //is this intended? I would think this should fail with 404 not found
  test("GET /orgs/doesNotExist" + ROUTE + " -- root - 404 not found - Organization does not exist") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("GET /orgs/" + TESTORGS(1).orgId + ROUTE + " -- user - 403 access denied - as user in other org") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE).headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- root - 200 ok - normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminStatus = JsonMethods.parse(response.body).extract[AdminStatus]
    assert(status.dbSchemaVersion.isEmpty)
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfAgbotAgreements === 2)
    assert(status.numberOfAgbotMsgs === 2)
    assert(status.numberOfAgbots === 2)
    assert(status.numberOfNodeAgreements.get === 2)
    assert(status.numberOfNodeMsgs.get === 2)
    assert(status.numberOfNodes.get === 2)
    assert(status.numberOfOrganizations === 1)
    assert(status.numberOfRegisteredNodes.get === 1)
    assert(status.numberOfUnregisteredNodes.get === 1)
    assert(status.numberOfUsers.get === 2)
    assert(status.SchemaVersion.get === SCHEMAVERSION)
  }
  
  test("GET /orgs/" + TESTORGS(1).orgId + ROUTE + " -- root - 200 ok - normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminStatus = JsonMethods.parse(response.body).extract[AdminStatus]
    assert(status.dbSchemaVersion.isEmpty)
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfAgbotAgreements === 1)
    assert(status.numberOfAgbotMsgs === 1)
    assert(status.numberOfAgbots === 1)
    assert(status.numberOfNodeAgreements.get === 1)
    assert(status.numberOfNodeMsgs.get === 1)
    assert(status.numberOfNodes.get === 1)
    assert(status.numberOfOrganizations === 1)
    assert(status.numberOfRegisteredNodes.get === 0)
    assert(status.numberOfUnregisteredNodes.get === 1)
    assert(status.numberOfUsers.get === 1)
    assert(status.SchemaVersion.get === SCHEMAVERSION)
  }
  
  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- hub admin - 200 ok - normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminStatus = JsonMethods.parse(response.body).extract[AdminStatus]
    assert(status.dbSchemaVersion.isEmpty)
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfAgbotAgreements === 2)
    assert(status.numberOfAgbotMsgs === 2)
    assert(status.numberOfAgbots === 2)
    assert(status.numberOfNodeAgreements.get === -1)
    assert(status.numberOfNodeMsgs.get === -1)
    assert(status.numberOfNodes.get === -1)
    assert(status.numberOfOrganizations === 1)
    assert(status.numberOfRegisteredNodes.isEmpty)
    assert(status.numberOfUnregisteredNodes.isEmpty)
    assert(status.numberOfUsers.get === 1)
    assert(status.SchemaVersion.get === SCHEMAVERSION)
  }
  
  test("GET /orgs/" + TESTORGS(1).orgId + ROUTE + " -- hub admin - 200 ok - normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminStatus = JsonMethods.parse(response.body).extract[AdminStatus]
    assert(status.dbSchemaVersion.isEmpty)
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfAgbotAgreements === 1)
    assert(status.numberOfAgbotMsgs === 1)
    assert(status.numberOfAgbots === 1)
    assert(status.numberOfNodes.get === -1)
    assert(status.numberOfNodeMsgs.get === -1)
    assert(status.numberOfNodeAgreements.get === -1)
    assert(status.numberOfOrganizations === 1)
    assert(status.numberOfRegisteredNodes.isEmpty)
    assert(status.numberOfUnregisteredNodes.isEmpty)
    assert(status.numberOfUsers.get === 0)
    assert(status.SchemaVersion.get === SCHEMAVERSION)
  }
  
  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- organization admin - 200 ok - normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(ORGADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminStatus = JsonMethods.parse(response.body).extract[AdminStatus]
    assert(status.dbSchemaVersion.isEmpty)
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfAgbotAgreements === 2)
    assert(status.numberOfAgbotMsgs === 2)
    assert(status.numberOfAgbots === 2)
    assert(status.numberOfNodes.get === 2)
    assert(status.numberOfNodeMsgs.get === 2)
    assert(status.numberOfNodeAgreements.get === 2)
    assert(status.numberOfOrganizations === 1)
    assert(status.numberOfRegisteredNodes.get === 1)
    assert(status.numberOfUnregisteredNodes.get === 1)
    assert(status.numberOfUsers.get === 2)
    assert(status.SchemaVersion.get === -1)
  }
  
  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- user - 200 ok - normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminStatus = JsonMethods.parse(response.body).extract[AdminStatus]
    assert(status.dbSchemaVersion.isEmpty)
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfAgbotAgreements === 2)
    assert(status.numberOfAgbotMsgs === 2)
    assert(status.numberOfAgbots === 2)
    assert(status.numberOfNodeAgreements.get === 1)
    assert(status.numberOfNodeMsgs.get === 1)
    assert(status.numberOfNodes.get === 1)
    assert(status.numberOfOrganizations === 1)
    assert(status.numberOfRegisteredNodes.get === 1)
    assert(status.numberOfUnregisteredNodes.get === 0)
    assert(status.numberOfUsers.get === 1)
    assert(status.SchemaVersion.get === -1)
  }
  
/*  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- agbot - 200 ok - normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminStatus = JsonMethods.parse(response.body).extract[AdminStatus]
    assert(status.dbSchemaVersion.isEmpty)
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfAgbotAgreements === 2)
    assert(status.numberOfAgbotMsgs === 2)
    assert(status.numberOfAgbots === 2)
    assert(status.numberOfNodes.get === 2)
    assert(status.numberOfNodeMsgs.get === 2)
    assert(status.numberOfNodeAgreements.get === 2)
    assert(status.numberOfOrganizations === 1)
    assert(status.numberOfRegisteredNodes.get === 1)
    assert(status.numberOfUnregisteredNodes.get === 1)
    assert(status.numberOfUsers.get === -1)
    assert(status.SchemaVersion.get === -1)
  }
  
  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- node - 200 ok - normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminStatus = JsonMethods.parse(response.body).extract[AdminStatus]
    assert(status.dbSchemaVersion.isEmpty)
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfAgbotAgreements === 2)
    assert(status.numberOfAgbotMsgs === 2)
    assert(status.numberOfAgbots === 2)
    assert(status.numberOfNodes.get === 1)
    assert(status.numberOfNodeMsgs.get === 1)
    assert(status.numberOfNodeAgreements.get === 1)
    assert(status.numberOfOrganizations === 1)
    assert(status.numberOfRegisteredNodes.get === 0)
    assert(status.numberOfUnregisteredNodes.get === 1)
    assert(status.numberOfUsers.get === -1)
    assert(status.SchemaVersion.get === -1)
  }
  
  test("GET /orgs/" + "IBM" + ROUTE + " -- node - 200 ok - normal success", AdminStatusTest) {
    val response: HttpResponse[String] = Http(URL + "IBM" + ROUTE).headers(ACCEPT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminStatus = JsonMethods.parse(response.body).extract[AdminStatus]
    assert(status.dbSchemaVersion.isEmpty)
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfAgbotAgreements === 1)
    assert(status.numberOfAgbotMsgs === 1)
    assert(status.numberOfAgbots === 1)
    assert(status.numberOfNodes.get === 0)
    assert(status.numberOfNodeMsgs.get === 0)
    assert(status.numberOfNodeAgreements.get === 0)
    assert(status.numberOfOrganizations === 1)
    assert(status.numberOfRegisteredNodes.get === 0)
    assert(status.numberOfUnregisteredNodes.get === 0)
    assert(status.numberOfUsers.get === -1)
    assert(status.SchemaVersion.get === -1)
  }
  
  // --------------- .../v1/admin/status ---------------
  // [WARNING] No test suite isolation!
  // Run these test cases by themselves. $ sbt onlyAdminStatusTests
  
  test("GET /admin/status" + " -- agbot - 403 access denied", AdminStatusTest) {
    val response: HttpResponse[String] = Http("http://0.0.0.0:8080/v1/" + "admin/status").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("GET /admin/status" + " -- node - 403 access denied", AdminStatusTest) {
    val response: HttpResponse[String] = Http("http://0.0.0.0:8080/v1/" + "admin/status").headers(ACCEPT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("GET /admin/status" + " -- root - 200 ok - normal success", AdminStatusTest) {
    val response: HttpResponse[String] = Http("http://0.0.0.0:8080/v1/" + "admin/status").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminStatus = JsonMethods.parse(response.body).extract[AdminStatus]
    assert(status.dbSchemaVersion.get === SCHEMAVERSION)
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfAgbotAgreements === 4)
    assert(status.numberOfAgbotMsgs === 4)
    assert(status.numberOfAgbots === 4)
    assert(status.numberOfNodeAgreements.get === 3)
    assert(status.numberOfNodeMsgs.get === 3)
    assert(status.numberOfNodes.get === 3)
    assert(status.numberOfOrganizations === 4)
    assert(status.numberOfRegisteredNodes.get === 1)
    assert(status.numberOfUnregisteredNodes.get === 2)
    assert(status.numberOfUsers.get === 5)
    assert(status.SchemaVersion.isEmpty)
  }
  
  test("GET /admin/status" + " -- hub admin - 200 ok - normal success", AdminStatusTest) {
    val response: HttpResponse[String] = Http("http://0.0.0.0:8080/v1/" + "admin/status").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminStatus = JsonMethods.parse(response.body).extract[AdminStatus]
    assert(status.dbSchemaVersion.get === SCHEMAVERSION)
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfAgbotAgreements === 4)
    assert(status.numberOfAgbotMsgs === 4)
    assert(status.numberOfAgbots === 4)
    assert(status.numberOfNodeAgreements.get === -1)
    assert(status.numberOfNodeMsgs.get === -1)
    assert(status.numberOfNodes.get === -1)
    assert(status.numberOfOrganizations === 4)
    assert(status.numberOfRegisteredNodes.isEmpty)
    assert(status.numberOfUnregisteredNodes.isEmpty)
    assert(status.numberOfUsers.get === 5)
    assert(status.SchemaVersion.isEmpty)
  }
  
  test("GET /admin/status" + " -- organization admin - 200 ok - normal success", AdminStatusTest) {
    val response: HttpResponse[String] = Http("http://0.0.0.0:8080/v1/" + "admin/status").headers(ACCEPT).headers(ORGADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminStatus = JsonMethods.parse(response.body).extract[AdminStatus]
    assert(status.dbSchemaVersion.get === -1)
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfAgbotAgreements === 3)
    assert(status.numberOfAgbotMsgs === 3)
    assert(status.numberOfAgbots === 3)
    assert(status.numberOfNodeAgreements.get === 2)
    assert(status.numberOfNodeMsgs.get === 2)
    assert(status.numberOfNodes.get === 2)
    assert(status.numberOfOrganizations === 2)
    assert(status.numberOfRegisteredNodes.get === 1)
    assert(status.numberOfUnregisteredNodes.get === 1)
    assert(status.numberOfUsers.get === 2)
    assert(status.SchemaVersion.isEmpty)
  }
  
  test("GET /admin/status" + " -- user - 200 ok - normal success", AdminStatusTest) {
    val response: HttpResponse[String] = Http("http://0.0.0.0:8080/v1/" + "admin/status").headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminStatus = JsonMethods.parse(response.body).extract[AdminStatus]
    assert(status.dbSchemaVersion.get === -1)
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.numberOfAgbotAgreements === 3)
    assert(status.numberOfAgbotMsgs === 3)
    assert(status.numberOfAgbots === 3)
    assert(status.numberOfNodeAgreements.get === 1)
    assert(status.numberOfNodeMsgs.get === 1)
    assert(status.numberOfNodes.get === 1)
    assert(status.numberOfOrganizations === 2)
    assert(status.numberOfRegisteredNodes.get === 1)
    assert(status.numberOfUnregisteredNodes.get === 0)
    assert(status.numberOfUsers.get === 1)
    assert(status.SchemaVersion.isEmpty)
  }
  
  
  // --------------- .../v1/admin/orgstatus ---------------
  // [WARNING] No test suite isolation!
  // Run these test cases by themselves. $ sbt onlyAdminStatusTests
  
  test("GET /admin/orgstatus" + " -- agbot - 403 access denied", AdminStatusTest) {
    val response: HttpResponse[String] = Http("http://0.0.0.0:8080/v1/" + "admin/orgstatus").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("GET /admin/orgstatus" + " -- node - 403 access denied", AdminStatusTest) {
    val response: HttpResponse[String] = Http("http://0.0.0.0:8080/v1/" + "admin/orgstatus").headers(ACCEPT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("GET /admin/orgstatus" + " -- user - 403 access denied", AdminStatusTest) {
    val response: HttpResponse[String] = Http("http://0.0.0.0:8080/v1/" + "admin/orgstatus").headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    /*assert(response.code === HttpCode.OK.intValue)
    val status: AdminOrgStatus = JsonMethods.parse(response.body).extract[AdminOrgStatus]
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.nodes.size === 2)
    assert(status.nodes.contains("IBM"))
    assert(status.nodes("IBM") === 0)
    assert(!status.nodes.contains("root"))
    assert(status.nodes.contains(TESTORGS.head.orgId))
    assert(status.nodes(TESTORGS.head.orgId) === 1)
    assert(!status.nodes.contains(TESTORGS(1).orgId))*/
  }
  
  test("GET /admin/orgstatus" + " -- root - 200 ok - normal success", AdminStatusTest) {
    val response: HttpResponse[String] = Http("http://0.0.0.0:8080/v1/" + "admin/orgstatus").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminOrgStatus = JsonMethods.parse(response.body).extract[AdminOrgStatus]
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.nodes.size === 4)
    assert(status.nodes.contains("IBM"))
    assert(status.nodes("IBM") === 0)
    assert(status.nodes.contains("root"))
    assert(status.nodes("root") === 0)
    assert(status.nodes.contains(TESTORGS.head.orgId))
    assert(status.nodes(TESTORGS.head.orgId) === 2)
    assert(status.nodes.contains(TESTORGS(1).orgId))
    assert(status.nodes(TESTORGS(1).orgId) === 1)
  }
  
  test("GET /admin/orgstatus" + " -- hub admin - 200 ok - normal success", AdminStatusTest) {
    val response: HttpResponse[String] = Http("http://0.0.0.0:8080/v1/" + "admin/orgstatus").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminOrgStatus = JsonMethods.parse(response.body).extract[AdminOrgStatus]
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.nodes.size === 4)
    assert(status.nodes.contains("IBM"))
    assert(status.nodes("IBM") === -1)
    assert(status.nodes.contains("root"))
    assert(status.nodes("root") === -1)
    assert(status.nodes.contains(TESTORGS.head.orgId))
    assert(status.nodes(TESTORGS.head.orgId) === -1)
    assert(status.nodes.contains(TESTORGS(1).orgId))
    assert(status.nodes(TESTORGS(1).orgId) === -1)
  }
  
  test("GET /admin/orgstatus" + " -- organization admin - 200 ok - normal success", AdminStatusTest) {
    val response: HttpResponse[String] = Http("http://0.0.0.0:8080/v1/" + "admin/orgstatus").headers(ACCEPT).headers(ORGADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val status: AdminOrgStatus = JsonMethods.parse(response.body).extract[AdminOrgStatus]
    assert(status.msg === ExchMsg.translate("exchange.server.operating.normally"))
    assert(status.nodes.size === 2)
    assert(status.nodes.contains("IBM"))
    assert(status.nodes("IBM") === 0)
    assert(!status.nodes.contains("root"))
    assert(status.nodes.contains(TESTORGS.head.orgId))
    assert(status.nodes(TESTORGS.head.orgId) === 2)
    assert(!status.nodes.contains(TESTORGS(1).orgId))
  }*/
}
