package org.openhorizon.exchangeapi.route.node

import org.json4s.DefaultFormats
import org.json4s.native.Serialization.write
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, HttpCode}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestGetNode extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Accept","application/json")
  private val ADMINAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestGetNode" + "/" + "u2" + ":" + "u2pw"))
  private val AWAITDURATION: Duration = 15.seconds
  private val CONTENT: (String, String) = ("Content-Type","application/json")
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val NODEAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestGetNode" + "/" + "n2" + ":" + "n2tok"))
  // private val ORGID = "TestGetNode"
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestGetNode" + "/" + "u1" + ":" + "u1pw"))
  import slick.jdbc.PostgresProfile.api._
  
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  private val TESTNODES: Seq[NodeRow] =
    Seq(NodeRow(arch               = "",
                id                 = "TestGetNode/n1",
                heartbeatIntervals = "",
                lastHeartbeat      = None,
                lastUpdated        = ApiTime.nowUTC,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = "TestGetNode",
                owner              = "TestGetNode/u1",
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""))
  private val TESTORGANIZATIONS: Seq[OrgRow] =
    Seq(OrgRow(description        = "",
               heartbeatIntervals = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               limits             = "",
               orgId              = "TestGetNode",
               orgType            = "",
               tags               = None),
        OrgRow(description        = "",
               heartbeatIntervals = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               limits             = "",
               orgId              = "TestGetNode2",
               orgType            = "",
               tags               = None),
        OrgRow(description        = "",
               heartbeatIntervals = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               limits             = "",
               orgId              = "TestGetNode3@somecomp.com",
               orgType            = "",
               tags               = None))
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(admin       = false,
                email       = "",
                hashedPw    = Password.hash("u1pw"),
                hubAdmin    = false,
                lastUpdated = ApiTime.nowUTC,
                orgid       = "TestGetNode",
                updatedBy   = "",
                username    = "TestGetNode/u1"))
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGANIZATIONS) andThen
                                       (UsersTQ ++= TESTUSERS)), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestGetNode").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestGetNode").delete), AWAITDURATION)
  }
  
  
  def fixtureNodes(testCode: Seq[NodeRow] => Any, testData: Seq[NodeRow]): Any = {
    try {
      Await.result(DBCONNECTION.run(NodesTQ ++= testData), AWAITDURATION)
      testCode(testData)
    } finally Await.result(DBCONNECTION.run(NodesTQ.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }
  
  
  test("GET /v1/orgs/" + "TestGetNode" + "/nodes/" + "n2" + " -- something") {
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  clusterNamespace   = None,
                  id                 = "TestGetNode/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestGetNode",
                  owner              = "TestGetNode/u1",
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
  
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + TESTORGANIZATIONS(0).orgId + "/nodes/" + testnodes(0).id.split("/")(1)).param("attribute", "owner").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === HttpCode.OK.intValue)
      },
      testnodes)
  }
}
