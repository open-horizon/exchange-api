package org.openhorizon.exchangeapi.route.organization

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc.PostgresProfile.api._

import java.time.Instant

class TestGetMaxChangeIDRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/changes/maxchangeid"

  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp

  private val NODETOKEN = "nodetoken"
  private val AGBOTTOKEN = "agbottoken"
  private val USERPASSWORD = "userpassword"
  private val ADMINPASSWORD = "adminpassword"
  private val HUBADMINPASSWORD = "hubadminpassword"

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow(
        orgId              = "testGetMaxChangeIDRouteOrg",
        orgType            = "",
        label              = "testGetMaxChangeIDRouteOrg",
        description        = "Test Organization",
        lastUpdated        = ApiTime.nowUTC,
        tags               = None,
        limits             = "",
        heartbeatIntervals = ""
      )
    )

  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "testGetMaxChangeIDRouteHubAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS.head.orgId,
                password     = Option(Password.hash(USERPASSWORD)),
                username     = "user1"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS.head.orgId,
                password     = Option(Password.hash(ADMINPASSWORD)),
                username     = "orgadminadmin1"))
  }
  
  private val TESTNODES: Seq[NodeRow] =
    Seq(
      NodeRow(
        arch               = "",
        id                 = TESTORGS.head.orgId + "/node1",
        heartbeatIntervals = "",
        lastHeartbeat      = None,
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS.head.orgId,
        owner              = TESTUSERS(1).user,
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = Password.hash(NODETOKEN),
        userInput          = ""
      )
    )

  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(
      AgbotRow(
        id            = TESTORGS.head.orgId + "/agbot1",
        orgid         = TESTORGS.head.orgId,
        token         = Password.hash(AGBOTTOKEN),
        name          = "",
        owner         = TESTUSERS(1).user,
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      )
    )

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).organization + "/" + TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + USERPASSWORD))
  private val ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).organization + "/" + TESTUSERS(2).username + ":" + ADMINPASSWORD))
  private val NODEAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES.head.id + ":" + NODETOKEN))
  private val AGBOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS.head.id + ":" + AGBOTTOKEN))

  private val TESTRESOURCECHANGES: Seq[ResourceChangeRow] = Seq(
    ResourceChangeRow(
      changeId = 0L,
      orgId = TESTORGS.head.orgId,
      id = "",
      category = "",
      public = "",
      resource = "",
      operation = "",
      lastUpdated = ApiTime.nowUTCTimestamp
    )
  )

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      (OrgsTQ ++= TESTORGS) andThen
      (UsersTQ ++= TESTUSERS) andThen
      (AgbotsTQ ++= TESTAGBOTS) andThen
      (NodesTQ ++= TESTNODES) andThen
      (ResourceChangesTQ ++= TESTRESOURCECHANGES)), AWAITDURATION
    )
  }

  override def afterAll(): Unit = {
    Await.ready(
      DBCONNECTION.run(
        ResourceChangesTQ.filter(_.orgId startsWith "testGetMaxChangeIDRoute").delete andThen
        OrgsTQ.filter(_.orgid startsWith "testGetMaxChangeIDRoute").delete andThen
        UsersTQ.filter(_.organization === "root")
               .filter(_.username startsWith "testGetMaxChangeIDRoute").delete
      ), AWAITDURATION)
  }

  // Resource Changes that are dynamically needed, specific to the test case.
  def fixtureResourceChanges(testCode: Seq[ResourceChangeRow] => Any, testData: Seq[ResourceChangeRow]): Any = {
    try{
      Await.result(DBCONNECTION.run(ResourceChangesTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.lastUpdated inSet testData.map(_.lastUpdated)).delete), AWAITDURATION) //hopefully lastUpdated is unique? because don't know change id
  }

  test("GET /changes/maxchangeid -- as root -- success") {
    val maxChangeIdBeforeRequest: Long = Await.result(DBCONNECTION.run(ResourceChangesTQ.sortBy(_.changeId.desc).take(1).result), AWAITDURATION).head.changeId
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
    val responseObj: MaxChangeIdResponse = JsonMethods.parse(response.body).extract[MaxChangeIdResponse]
    assert(responseObj.maxChangeId >= maxChangeIdBeforeRequest) //may have changed because tests run concurrently
  }

  test("GET /changes/maxchangeid -- insure maxChangeId increases when new RC is added") {
    val newRCs: Seq[ResourceChangeRow] = Seq(
      ResourceChangeRow(
        changeId = 0L,
        orgId = TESTORGS.head.orgId,
        id = "",
        category = "",
        public = "",
        resource = "",
        operation = "",
        lastUpdated = ApiTime.nowUTCTimestamp
      )
    )
    val response1: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code 1: " + response1.code)
    info("Body 1: " + response1.body)
    assert(response1.code === StatusCodes.OK.intValue)
    val responseObj1: MaxChangeIdResponse = JsonMethods.parse(response1.body).extract[MaxChangeIdResponse]
    fixtureResourceChanges(
      _ =>{
        val response2: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code 2: " + response2.code)
        info("Body 2: " + response2.body)
        assert(response2.code === StatusCodes.OK.intValue)
        val responseObj2: MaxChangeIdResponse = JsonMethods.parse(response2.body).extract[MaxChangeIdResponse]
        assert(responseObj2.maxChangeId > responseObj1.maxChangeId) //must increase since new RC was added
      }, newRCs)
  }

  test("GET /changes/maxchangeid -- as hub admin -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
  }

  test("GET /changes/maxchangeid -- as org admin -- success") {
    val maxChangeIdBeforeRequest: Long = Await.result(DBCONNECTION.run(ResourceChangesTQ.sortBy(_.changeId.desc).take(1).result), AWAITDURATION).head.changeId
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
    val responseObj: MaxChangeIdResponse = JsonMethods.parse(response.body).extract[MaxChangeIdResponse]
    assert(responseObj.maxChangeId >= maxChangeIdBeforeRequest) //may have changed because tests run concurrently
  }

  test("GET /changes/maxchangeid -- as user -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
  }

  test("GET /changes/maxchangeid -- as node -- success") {
    val maxChangeIdBeforeRequest: Long = Await.result(DBCONNECTION.run(ResourceChangesTQ.sortBy(_.changeId.desc).take(1).result), AWAITDURATION).head.changeId
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
    val responseObj: MaxChangeIdResponse = JsonMethods.parse(response.body).extract[MaxChangeIdResponse]
    assert(responseObj.maxChangeId >= maxChangeIdBeforeRequest) //may have changed because tests run concurrently
  }

  test("GET /changes/maxchangeid -- as agbot -- success") {
    val maxChangeIdBeforeRequest: Long = Await.result(DBCONNECTION.run(ResourceChangesTQ.sortBy(_.changeId.desc).take(1).result), AWAITDURATION).head.changeId
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
    val responseObj: MaxChangeIdResponse = JsonMethods.parse(response.body).extract[MaxChangeIdResponse]
    assert(responseObj.maxChangeId >= maxChangeIdBeforeRequest) //may have changed because tests run concurrently
  }

}
