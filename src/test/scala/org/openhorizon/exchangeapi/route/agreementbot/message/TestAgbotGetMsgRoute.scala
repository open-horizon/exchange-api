package org.openhorizon.exchangeapi.route.agreementbot.message

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.openhorizon.exchangeapi.auth.Role
import org.openhorizon.exchangeapi.route.agreementbot.GetAgbotMsgsResponse
import org.openhorizon.exchangeapi.table.agreementbot.message.{AgbotMsgRow, AgbotMsgsTQ}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, HttpCode}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestAgbotGetMsgRoute extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  // private val ORGID = "TestAgbotGetMsgRoute"
  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/TestAgbotGetMsgRoute/agbots/a1/msgs/"
  
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp
  
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "TestAgbotGetMsgRoute",
                password     = None,
                username     = "u1"))
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id            = "TestAgbotGetMsgRoute/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint   = "",
             name          = "",
             orgid         = "TestAgbotGetMsgRoute",
             owner         = TESTUSERS(0).user,
             publicKey     = "",
             token         = "")
  private val TESTAGBOTMESSAGES: Seq[AgbotMsgRow] =
    Seq(AgbotMsgRow(agbotId     = "TestAgbotGetMsgRoute/a1",
                    message     = """{msg1 from node1 to agbot1}""",
                    msgId       = -1,
                    nodeId      = "TestAgbotGetMsgRoute/n1",
                    nodePubKey  = "",
                    timeExpires = Instant.now().plusSeconds(1080).toString,
                    timeSent    = ApiTime.nowUTC))
  private val TESTNODES: Seq[NodeRow] =
    Seq(NodeRow(arch               = "",
                id                 = "TestAgbotGetMsgRoute/n1",
                heartbeatIntervals = "",
                lastHeartbeat      = None,
                lastUpdated        = ApiTime.nowUTC,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = "TestAgbotGetMsgRoute",
                owner              = TESTUSERS(0).user,
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""))
  private val TESTORGANIZATIONS: Seq[OrgRow] =
    Seq(OrgRow(heartbeatIntervals = "",
               description        = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               limits             = "",
               orgId              = "TestAgbotGetMsgRoute",
               orgType            = "",
               tags               = None))
  
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGANIZATIONS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (AgbotsTQ += TESTAGBOT) andThen
                                       (AgbotMsgsTQ ++= TESTAGBOTMESSAGES) andThen
                                       (NodesTQ ++= TESTNODES)), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestAgbotGetMsgRoute").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestAgbotGetMsgRoute").delete), AWAITDURATION)
  }
  
  
  test("GET /orgs/" + "TestAgbotGetMsgRoute" + "/agbots/" + "a1" + "/msgs/" + "-1" + " -- Message Not Found - Http Code 404") {
    val response: HttpResponse[String] = Http(URL + "-1").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("GET /orgs/" + "TestAgbotGetMsgRoute" + "/agbots/" + "a1" + "/msgs/{msgid} -- Message") {
    val TESTAGBOTMESSAGEID: Int =
      Await.result(DBCONNECTION.run(AgbotMsgsTQ
                                                     .filter(_.agbotId === TESTAGBOTMESSAGES(0).agbotId)
                                                     .filter(_.nodeId === TESTAGBOTMESSAGES(0).nodeId)
                                                     .filter(_.message === TESTAGBOTMESSAGES(0).message)
                                                     .filter(_.nodePubKey === TESTAGBOTMESSAGES(0).nodePubKey)
                                                     .filter(_.timeExpires === TESTAGBOTMESSAGES(0).timeExpires)
                                                     .filter(_.timeSent === TESTAGBOTMESSAGES(0).timeSent)
                                                     .sortBy(_.msgId.asc)
                                                     .map(_.msgId)
                                                     .take(1)
                                                     .result), AWAITDURATION).head
    
    val response: HttpResponse[String] = Http(URL + TESTAGBOTMESSAGEID).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val message: GetAgbotMsgsResponse = parse(response.body).extract[GetAgbotMsgsResponse]
    assert(message.lastIndex === 0)
    assert(message.messages.size === 1)
    
    assert(message.messages.head.msgId === TESTAGBOTMESSAGEID)
    assert(message.messages.head.message === TESTAGBOTMESSAGES(0).message)
    assert(message.messages.head.nodeId === TESTAGBOTMESSAGES(0).nodeId)
    assert(message.messages.head.nodePubKey === TESTAGBOTMESSAGES(0).nodePubKey)
    assert(message.messages.head.timeExpires === TESTAGBOTMESSAGES(0).timeExpires)
    assert(message.messages.head.timeSent === TESTAGBOTMESSAGES(0).timeSent)
  }
}
