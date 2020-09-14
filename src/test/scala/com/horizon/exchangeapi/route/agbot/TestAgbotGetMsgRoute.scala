package com.horizon.exchangeapi.route.agbot

import com.horizon.exchangeapi.{ApiTime, ApiUtils, GetAgbotMsgsResponse, HttpCode, Role, TestDBConnection}
import com.horizon.exchangeapi.tables.{AgbotMsgRow, AgbotMsgsTQ, AgbotRow, AgbotsTQ, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestAgbotGetMsgRoute extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  // private val ORGID = "TestAgbotGetMsgRoute"
  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/TestAgbotGetMsgRoute/agbots/a1/msgs/"
  
  private implicit val formats = DefaultFormats
  
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id            = "TestAgbotGetMsgRoute/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint   = "",
             name          = "",
             orgid         = "TestAgbotGetMsgRoute",
             owner         = "TestAgbotGetMsgRoute/u1",
             publicKey     = "",
             token         = "")
  private val TESTAGBOTMESSAGES: Seq[AgbotMsgRow] =
    Seq(AgbotMsgRow(agbotId     = "TestAgbotGetMsgRoute/a1",
                    message     = """{msg1 from node1 to agbot1}""",
                    msgId       = -1,
                    nodeId      = "TestAgbotGetMsgRoute/n1",
                    nodePubKey  = "",
                    timeExpires = ApiTime.futureUTC(1080),
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
                owner              = "TestAgbotGetMsgRoute/u1",
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
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(admin       = false,
                email       = "",
                hashedPw    = "",
                hubAdmin    = false,
                lastUpdated = ApiTime.nowUTC,
                orgid       = "TestAgbotGetMsgRoute",
                updatedBy   = "",
                username    = "TestAgbotGetMsgRoute/u1"))
  
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ.rows ++= TESTORGANIZATIONS) andThen
                                       (UsersTQ.rows ++= TESTUSERS) andThen
                                       (AgbotsTQ.rows += TESTAGBOT) andThen
                                       (AgbotMsgsTQ.rows ++= TESTAGBOTMESSAGES) andThen
                                       (NodesTQ.rows ++= TESTNODES)), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.rows.filter(_.orgId startsWith "TestAgbotGetMsgRoute").delete andThen
                                       OrgsTQ.rows.filter(_.orgid startsWith "TestAgbotGetMsgRoute").delete), AWAITDURATION)
  
    DBCONNECTION.getDb.close()
  }
  
  
  test("GET /orgs/" + "TestAgbotGetMsgRoute" + "/agbots/" + "a1" + "/msgs/" + "-1" + " -- Message Not Found - Http Code 404") {
    val response: HttpResponse[String] = Http(URL + "-1").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("GET /orgs/" + "TestAgbotGetMsgRoute" + "/agbots/" + "a1" + "/msgs/{msgid} -- Message") {
    val TESTAGBOTMESSAGEID: Int =
      Await.result(DBCONNECTION.getDb.run(AgbotMsgsTQ.rows
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
