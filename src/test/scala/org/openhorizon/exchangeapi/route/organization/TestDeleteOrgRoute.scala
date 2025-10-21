package org.openhorizon.exchangeapi.route.organization

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc.PostgresProfile.api._

import java.time.Instant

class TestDeleteOrgRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"

  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp

  private val HUBADMINPASSWORD = "hubadminpassword"
  private val USERPASSWORD = "userpassword"
  private val ORGADMINPASSWORD = "orgadminpassword"

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow(
        heartbeatIntervals =
          """
            |{
            | "minInterval": 1,
            | "maxInterval": 2,
            | "intervalAdjustment": 3
            |}
            |""".stripMargin,
        description        = "TestDeleteOrgRoute1",
        label              = "TestDeleteOrgRoute1",
        lastUpdated        = ApiTime.nowUTC,
        limits             =
          """
            |{
            | "maxNodes": 100
            |}
            |""".stripMargin,
        orgId              = "testDeleteOrgRoute1",
        orgType            = "TestDeleteOrgRoute1",
        tags               = Some(JsonMethods.parse(
          """
            |{
            | "tagName": "tagValue"
            |}
            |""".stripMargin
        ))))

  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "TestDeleteOrgRouteHubAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(USERPASSWORD)),
                username     = "TestDeleteOrgRouteUser"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(ORGADMINPASSWORD)),
                username     = "TestDeleteOrgRouteOrgAdmin"))
  }
  
  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(AgbotRow(
      id            = TESTORGS(0).orgId + "/a1",
      orgid         = TESTORGS(0).orgId,
      token         = "",
      name          = "testAgbot",
      owner         = TESTUSERS(1).user,
      msgEndPoint   = "",
      lastHeartbeat = ApiTime.nowUTC,
      publicKey     = ""
    ))

  private val TESTNODES: Seq[NodeRow] =
    Seq(
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/n1",
        heartbeatIntervals = "",
        lastHeartbeat      = None,
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).user,
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""))

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode((TESTUSERS(0).organization + "/" + TESTUSERS(0).username + ":" + HUBADMINPASSWORD)))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode((TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + USERPASSWORD)))
  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode((TESTUSERS(2).organization + "/" + TESTUSERS(2).username + ":" + ORGADMINPASSWORD)))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run(UsersTQ += TESTUSERS(0)), AWAITDURATION) //add hub admin
  }

  override def beforeEach(): Unit = {
    info("beforeEach running")
    Await.ready(DBCONNECTION.run(
      ResourceChangesTQ.filter(_.orgId startsWith "testDeleteOrgRoute").delete andThen
      OrgsTQ.insertOrUpdate(TESTORGS(0)) andThen //can't do "insertOrUpdateAll", so do them individually
      UsersTQ.insertOrUpdate(TESTUSERS(1)) andThen
      UsersTQ.filter(_.user === TESTUSERS(2).user).update(TESTUSERS(2)) andThen
      (UsersTQ += TESTUSERS(2)) andFinally
      AgbotsTQ.insertOrUpdate(TESTAGBOTS(0)) andThen
      NodesTQ.insertOrUpdate(TESTNODES(0))
      ), AWAITDURATION
    )
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "testDeleteOrgRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testDeleteOrgRoute").delete andThen
      UsersTQ.filter(_.organization === "root")
             .filter(_.username startsWith "TestDeleteOrgRouteHubAdmin").delete), AWAITDURATION)
  }

  def assertDbClear(orgId: String): Unit = {
    assert(Await.result(DBCONNECTION.run(OrgsTQ.filter(_.orgid === orgId).result), AWAITDURATION).isEmpty) //insure org is gone
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.organization === orgId).result), AWAITDURATION).isEmpty) //insure users are gone
    assert(Await.result(DBCONNECTION.run(NodesTQ.filter(_.orgid === orgId).result), AWAITDURATION).isEmpty) //insure nodes are gone
    assert(Await.result(DBCONNECTION.run(AgbotsTQ.filter(_.orgid === orgId).result), AWAITDURATION).isEmpty) //insure agbots are gone
  }

  def assertDeletedEntryCreated(orgId: String): Unit = {
    assert(
      Await.result(DBCONNECTION.run(ResourceChangesTQ
        .filter(_.orgId === orgId)
        .filter(_.id === orgId)
        .filter(_.category === ResChangeCategory.ORG.toString)
        .filter(_.resource === ResChangeResource.ORG.toString)
        .filter(_.operation === ResChangeOperation.DELETED.toString)
        .result), AWAITDURATION)
      .nonEmpty
    )
  }

  def assertDeletedEntryNotCreated(orgId: String): Unit = {
    assert(
      Await.result(DBCONNECTION.run(ResourceChangesTQ
        .filter(_.orgId === orgId)
        .filter(_.id === orgId)
        .filter(_.category === ResChangeCategory.ORG.toString)
        .filter(_.resource === ResChangeResource.ORG.toString)
        .filter(_.operation === ResChangeOperation.DELETED.toString)
        .result), AWAITDURATION)
      .isEmpty
    )
  }

  test("DELETE /orgs/doesNotExist -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist").method("DELETE").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NotFound.intValue)
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === "doesNotExist").result), AWAITDURATION).isEmpty)
  }

  test("DELETE /orgs/root -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + "root").method("DELETE").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.run(OrgsTQ.filter(_.orgid === "root").result), AWAITDURATION).length
    assert(numOrgs === 1) //insure root org is still there
    assertDeletedEntryNotCreated("root")
  }

  //it would be a pain to restore all resources belonging to the IBM org, so we're not gonna run this test for now
  /*test("DELETE /orgs/IBM -- success") {
    val response: HttpResponse[String] = Http(URL + "IBM").method("DELETE").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NoContent.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.run(OrgsTQ.filter(_.orgid === "IBM").result), AWAITDURATION).length
    val numUsers: Int = Await.result(DBCONNECTION.run(UsersTQ.filter(_.orgid === "IBM").result), AWAITDURATION).length
    val numNodes: Int = Await.result(DBCONNECTION.run(NodesTQ.filter(_.orgid === "IBM").result), AWAITDURATION).length
    val numAgbots: Int = Await.result(DBCONNECTION.run(AgbotsTQ.filter(_.orgid === "IBM").result), AWAITDURATION).length
    assert(numOrgs === 0) //insure ibm org is gone
    assert(numUsers === 0) //insure users are gone
    assert(numNodes === 0) //insure nodes are gone
    assert(numAgbots === 0) //insure agbots are gone
    //insure entry was created in Resource Changes table
    val rcEntryExists: Boolean = Await.result(DBCONNECTION.run(ResourceChangesTQ
      .filter(_.orgId === "IBM")
      .filter(_.id === "IBM")
      .filter(_.category === ResChangeCategory.ORG.toString)
      .filter(_.resource === ResChangeResource.ORG.toString)
      .filter(_.operation === ResChangeOperation.DELETED.toString)
      .result), AWAITDURATION).nonEmpty
    assert(rcEntryExists)
  }*/

  test("DELETE /orgs/" + TESTORGS(0).orgId + " as root -- normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).method("DELETE").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NoContent.intValue)
    assertDbClear(TESTORGS(0).orgId)
    assertDeletedEntryCreated(TESTORGS(0).orgId)
  }

  test("DELETE /orgs/" + TESTORGS(0).orgId + " as hub admin -- normal success") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).method("DELETE").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NoContent.intValue)
    assertDbClear(TESTORGS(0).orgId)
    assertDeletedEntryCreated(TESTORGS(0).orgId)
  }

  test("DELETE /orgs/" + TESTORGS(0).orgId + " as org admin -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).method("DELETE").headers(ACCEPT).headers(ORGADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.run(OrgsTQ.filter(_.orgid === TESTORGS(0).orgId).result), AWAITDURATION).length
    assert(numOrgs === 1) //insure org is still there
    assertDeletedEntryNotCreated(TESTORGS(0).orgId)
  }

  test("DELETE /orgs/" + TESTORGS(0).orgId + " as regular user in org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId).method("DELETE").headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.run(OrgsTQ.filter(_.orgid === TESTORGS(0).orgId).result), AWAITDURATION).length
    assert(numOrgs === 1) //insure org is still there
    assertDeletedEntryNotCreated(TESTORGS(0).orgId)
  }

}
