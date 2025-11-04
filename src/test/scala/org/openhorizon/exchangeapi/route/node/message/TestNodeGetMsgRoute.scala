package org.openhorizon.exchangeapi.route.node.message

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.openhorizon.exchangeapi.auth.Role
import org.openhorizon.exchangeapi.route.node.GetNodeMsgsResponse
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.message.{NodeMsgRow, NodeMsgsTQ}
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

class TestNodeGetMsgRoute extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  // private val ORGID = "TestNodeGetMsgRoute"
  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/TestNodeGetMsgRoute/nodes/n1/msgs/"
  
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp
  
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "TestNodeGetMsgRoute",
                password     = None,
                username     = "u1"))
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id            = "TestNodeGetMsgRoute/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint   = "",
             name          = "",
             orgid         = "TestNodeGetMsgRoute",
             owner         = TESTUSERS(0).user,
             publicKey     = "",
             token         = "")
  private val TESTNODEMESSAGES: Seq[NodeMsgRow] =
    Seq(NodeMsgRow(agbotId     = "TestNodeGetMsgRoute/a1",
                          agbotPubKey = "",
                          message     = """{msg1 from node1 to agbot1}""",
                          msgId       = -1,
                          nodeId      = "TestNodeGetMsgRoute/n1",
                          timeExpires = Instant.now().plusSeconds(1080.toLong).toString,
                          timeSent    = ApiTime.nowUTC))
  private val TESTNODES: Seq[NodeRow] =
    Seq(NodeRow(arch               = "",
                id                 = "TestNodeGetMsgRoute/n1",
                heartbeatIntervals = "",
                lastHeartbeat      = None,
                lastUpdated        = ApiTime.nowUTC,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = "TestNodeGetMsgRoute",
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
               orgId              = "TestNodeGetMsgRoute",
               orgType            = "",
               tags               = None))
  
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGANIZATIONS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (AgbotsTQ += TESTAGBOT) andThen
                                       (NodesTQ ++= TESTNODES) andThen
                                       (NodeMsgsTQ ++= TESTNODEMESSAGES)), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestNodeGetMsgRoute").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestNodeGetMsgRoute").delete), AWAITDURATION)
  }
  
  
  test("GET /orgs/" + "TestNodeGetMsgRoute" + "/nodes/" + "n1" + "/msgs/" + "-1" + " -- Message Not Found - Http Code 404") {
    val response: HttpResponse[String] = Http(URL + "-1").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NotFound.intValue)
  }
  
  test("GET /orgs/" + "TestNodeGetMsgRoute" + "/nodes/" + "n1" + "/msgs/{msgid} -- Message") {
    val TESTNODEMESSAGEID: Int =
      Await.result(DBCONNECTION.run(NodeMsgsTQ
                                                    .filter(_.agbotId === TESTNODEMESSAGES(0).agbotId)
                                                    .filter(_.agbotPubKey === TESTNODEMESSAGES(0).agbotPubKey)
                                                    .filter(_.nodeId === TESTNODEMESSAGES(0).nodeId)
                                                    .filter(_.message === TESTNODEMESSAGES(0).message)
                                                    .filter(_.timeExpires === TESTNODEMESSAGES(0).timeExpires)
                                                    .filter(_.timeSent === TESTNODEMESSAGES(0).timeSent)
                                                    .sortBy(_.msgId.asc)
                                                    .map(_.msgId)
                                                    .take(1)
                                                    .result), AWAITDURATION).head
    
    val response: HttpResponse[String] = Http(URL + TESTNODEMESSAGEID).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
    
    val message: GetNodeMsgsResponse = parse(response.body).extract[GetNodeMsgsResponse]
    assert(message.lastIndex === 0)
    assert(message.messages.size === 1)
    
    assert(message.messages.head.agbotId === TESTNODEMESSAGES(0).agbotId)
    assert(message.messages.head.agbotPubKey === TESTNODEMESSAGES(0).agbotPubKey)
    assert(message.messages.head.msgId === TESTNODEMESSAGEID)
    assert(message.messages.head.message === TESTNODEMESSAGES(0).message)
    assert(message.messages.head.timeExpires === TESTNODEMESSAGES(0).timeExpires)
    assert(message.messages.head.timeSent === TESTNODEMESSAGES(0).timeSent)
  }
}
