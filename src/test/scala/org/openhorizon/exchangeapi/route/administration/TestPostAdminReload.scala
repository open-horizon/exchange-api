package org.openhorizon.exchangeapi.route.administration

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiUtils, HttpCode}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.Http
import org.openhorizon.exchangeapi.table.agreementbot.message.{AgbotMsgRow, AgbotMsgsTQ}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.agreement.{NodeAgreementRow, NodeAgreementsTQ}
import org.openhorizon.exchangeapi.table.node.message.{NodeMsgRow, NodeMsgsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.schema.SchemaTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import scala.concurrent.Await
import slick.jdbc
import slick.jdbc.PostgresProfile.api._
import org.openhorizon.exchangeapi.utility.ApiTime.fixFormatting
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, ExchMsg, HttpCode}
import scala.concurrent.duration.{Duration, DurationInt}
import org.scalatest.BeforeAndAfterAll
import org.openhorizon.exchangeapi.route.administration.{AdminHashpwRequest, AdminHashpwResponse}
class TestPostAdminReload extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val PASSWORD = "password"
  implicit val formats: DefaultFormats.type = DefaultFormats

  private val TEST_USERS: Seq[UserRow] = Seq(
    UserRow(
      orgid = "root",
      username = "root/testUser",
      admin = true,
      hubAdmin = false,
      hashedPw = Password.hash("password"),
      email = "",
      lastUpdated = ApiTime.nowUTC,
      updatedBy = ""
    ),
    UserRow(
      orgid = "someOrg",
      username = "someOrg/user",
      admin = false,
      hubAdmin = false,
      hashedPw = Password.hash("password"),
      email = "",
      lastUpdated = ApiTime.nowUTC,
      updatedBy = ""
    )
  )

  private val TEST_NODES: Seq[NodeRow] = Seq(
    NodeRow(
      id = "someOrg/node",
      orgid = "someOrg",
      owner = "someOrg/user",
      token = Password.hash("password"),
      arch = "amd64",
      pattern = "",
      regServices = "",
      softwareVersions = "",
      publicKey = "",
      lastUpdated = ApiTime.nowUTC,
      lastHeartbeat = None,
      msgEndPoint = "",
      heartbeatIntervals = "",
      name = "",
      nodeType = "",
      userInput = ""
    )
  )

  private val TEST_AGBOTS: Seq[AgbotRow] = Seq(
    AgbotRow(
      id = "someOrg/agbot",
      orgid = "someOrg",
      owner = "someOrg/user",
      token = Password.hash("password"),
      lastHeartbeat = ApiTime.nowUTC,
      msgEndPoint = "",
      name = "Test Agbot",
      publicKey = ""
    )
  )

override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      (OrgsTQ += OrgRow(
        orgId = "someOrg",
        label = "someOrg",
        description = "someOrg Test Organization",
        lastUpdated = ApiTime.nowUTC,
        heartbeatIntervals = "",
        limits = "",
        orgType = "",
        tags = None
      )) andThen
      (UsersTQ ++= TEST_USERS) andThen
      (NodesTQ ++= TEST_NODES) andThen
      (AgbotsTQ ++= TEST_AGBOTS)
    ), AWAITDURATION)
}


override def afterAll(): Unit = {
  Await.ready(DBCONNECTION.run(
    UsersTQ.filter(_.username inSet TEST_USERS.map(_.username)).delete andThen
    NodesTQ.filter(_.id inSet TEST_NODES.map(_.id)).delete andThen
    AgbotsTQ.filter(_.id inSet TEST_AGBOTS.map(_.id)).delete andThen
    OrgsTQ.filter(_.orgid === "someOrg").delete 
  ), AWAITDURATION)
}

    private val API_URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/admin/reload"
    private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
    private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TEST_USERS(1).username + ":" + PASSWORD))
    private val AGBOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(TEST_AGBOTS.head.id + ":" + PASSWORD))
    private val NODEAUTH = ("Authorization", "Basic " + ApiUtils.encode(TEST_NODES.head.id + ":" + PASSWORD))
    
    test("POST /admin/reload - Access Denied for AGBOT") {
    val response = Http(API_URL).method("post").headers(ACCEPT)
      .headers(AGBOTAUTH).asString

    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(ApiRespType.ACCESS_DENIED === postResp.code)
  }

    test("POST /admin/reload - Access Denied for NODE") {
    val response = Http(API_URL).method("post").headers(ACCEPT)
      .headers(NODEAUTH).asString

    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(ApiRespType.ACCESS_DENIED === postResp.code)
  }

  test("POST /admin/reload - Access Denied for Normal User") {
  val response = Http(API_URL).method("post").headers(ACCEPT)
    .headers(USERAUTH).asString

  info("http status code: " + response.code)
  info("body: " + response.body)
  assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  val postResp = parse(response.body).extract[ApiResponse]
  assert(ApiRespType.ACCESS_DENIED === postResp.code)
  }


  test("POST /admin/reload - Success for root") {
    val response = Http(API_URL).method("post").headers(ACCEPT).headers(ROOTAUTH).asString

    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(ApiRespType.OK === postResp.code)
  }

  test("POST /admin/reload - Invalid Credentials") {
    val response = Http(API_URL).method("post").headers(ACCEPT)
      .headers(("Authorization", "Basic " + ApiUtils.encode("root:invalidpassword"))).asString

    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
  }
}
