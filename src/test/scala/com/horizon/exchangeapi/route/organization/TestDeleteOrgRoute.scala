package com.horizon.exchangeapi.route.organization

import com.horizon.exchangeapi.tables.{AgbotRow, AgbotsTQ, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangesTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Password, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc.PostgresProfile.api._

class TestDeleteOrgRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINPASSWORD = "hubadminpassword"
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("root/TestDeleteOrgRouteHubAdmin:" + HUBADMINPASSWORD))
  private val USERPASSWORD = "userpassword"
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode("testDeleteOrgRoute1/TestDeleteOrgRouteUser:" + USERPASSWORD))
  private val ORGADMINPASSWORD = "orgadminpassword"
  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("testDeleteOrgRoute1/TestDeleteOrgRouteOrgAdmin:" + ORGADMINPASSWORD))

  private implicit val formats = DefaultFormats

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

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestDeleteOrgRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestDeleteOrgRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = "testDeleteOrgRoute1/TestDeleteOrgRouteUser",
        orgid       = "testDeleteOrgRoute1",
        hashedPw    = Password.hash(USERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "TestDeleteOrgRouteUser@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ),
      UserRow(
        username    = "testDeleteOrgRoute1/TestDeleteOrgRouteOrgAdmin",
        orgid       = "testDeleteOrgRoute1",
        hashedPw    = Password.hash(ORGADMINPASSWORD),
        admin       = true,
        hubAdmin    = false,
        email       = "TestDeleteOrgRouteOrgAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root"
      ))

  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(AgbotRow(
      id            = "testDeleteOrgRoute1/a1",
      orgid         = "testDeleteOrgRoute1",
      token         = "",
      name          = "testAgbot",
      owner         = "testDeleteOrgRoute1/TestDeleteOrgRouteUser1",
      msgEndPoint   = "",
      lastHeartbeat = ApiTime.nowUTC,
      publicKey     = ""
    ))

  private val TESTNODES: Seq[NodeRow] =
    Seq(
      NodeRow(
        arch               = "",
        id                 = "testDeleteOrgRoute1/n1",
        heartbeatIntervals = "",
        lastHeartbeat      = None,
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = "testDeleteOrgRoute1",
        owner              = "testDeleteOrgRoute1/TestDeleteOrgRouteUser1",
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(UsersTQ += TESTUSERS(0)), AWAITDURATION) //add hub admin
  }

  override def beforeEach(): Unit = {
    info("beforeEach running")
    Await.ready(DBCONNECTION.getDb.run(
      ResourceChangesTQ.filter(_.orgId startsWith "testDeleteOrgRoute").delete andThen
      OrgsTQ.insertOrUpdate(TESTORGS(0)) andThen //can't do "insertOrUpdateAll", so do them individually
      UsersTQ.insertOrUpdate(TESTUSERS(1)) andThen
      UsersTQ.insertOrUpdate(TESTUSERS(2)) andThen
      AgbotsTQ.insertOrUpdate(TESTAGBOTS(0)) andThen
      NodesTQ.insertOrUpdate(TESTNODES(0))
      ), AWAITDURATION
    )
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "testDeleteOrgRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testDeleteOrgRoute").delete andThen
      UsersTQ.filter(_.username startsWith "root/TestDeleteOrgRouteHubAdmin").delete), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  test("DELETE /orgs/doesNotExist -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist").method("DELETE").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === "doesNotExist").result), AWAITDURATION).isEmpty)
  }

  test("DELETE /orgs/root -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + "root").method("DELETE").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === "root").result), AWAITDURATION).length
    assert(numOrgs === 1) //insure root org is still there
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ
      .filter(_.orgId === "root")
      .filter(_.id === "root")
      .filter(_.category === ResChangeCategory.ORG.toString)
      .filter(_.resource === ResChangeResource.ORG.toString)
      .filter(_.operation === ResChangeOperation.DELETED.toString)
      .result), AWAITDURATION)
      .isEmpty)
  }

  //it would be a nightmare to restore all resources belonging to the IBM org, so we're not gonna run this test for now
  /*test("DELETE /orgs/IBM -- success") {
    val response: HttpResponse[String] = Http(URL + "IBM").method("DELETE").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === "IBM").result), AWAITDURATION).length
    val numUsers: Int = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.orgid === "IBM").result), AWAITDURATION).length
    val numNodes: Int = Await.result(DBCONNECTION.getDb.run(NodesTQ.filter(_.orgid === "IBM").result), AWAITDURATION).length
    val numAgbots: Int = Await.result(DBCONNECTION.getDb.run(AgbotsTQ.filter(_.orgid === "IBM").result), AWAITDURATION).length
    assert(numOrgs === 0) //insure ibm org is gone
    assert(numUsers === 0) //insure users are gone
    assert(numNodes === 0) //insure nodes are gone
    assert(numAgbots === 0) //insure agbots are gone
    //insure entry was created in Resource Changes table
    val rcEntryExists: Boolean = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ
      .filter(_.orgId === "IBM")
      .filter(_.id === "IBM")
      .filter(_.category === ResChangeCategory.ORG.toString)
      .filter(_.resource === ResChangeResource.ORG.toString)
      .filter(_.operation === ResChangeOperation.DELETED.toString)
      .result), AWAITDURATION).nonEmpty
    assert(rcEntryExists)
  }*/

  test("DELETE /orgs/testDeleteOrgRoute1 as root -- normal success") {
    val response: HttpResponse[String] = Http(URL + "testDeleteOrgRoute1").method("DELETE").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === "testDeleteOrgRoute1").result), AWAITDURATION).length
    val numUsers: Int = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.orgid === "testDeleteOrgRoute1").result), AWAITDURATION).length
    val numNodes: Int = Await.result(DBCONNECTION.getDb.run(NodesTQ.filter(_.orgid === "testDeleteOrgRoute1").result), AWAITDURATION).length
    val numAgbots: Int = Await.result(DBCONNECTION.getDb.run(AgbotsTQ.filter(_.orgid === "testDeleteOrgRoute1").result), AWAITDURATION).length
    assert(numOrgs === 0) //insure org is gone
    assert(numUsers === 0) //insure users are gone
    assert(numNodes === 0) //insure nodes are gone
    assert(numAgbots === 0) //insure agbots are gone
    //insure entry was created in Resource Changes table
    val rcEntryExists: Boolean = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ
      .filter(_.orgId === "testDeleteOrgRoute1")
      .filter(_.id === "testDeleteOrgRoute1")
      .filter(_.category === ResChangeCategory.ORG.toString)
      .filter(_.resource === ResChangeResource.ORG.toString)
      .filter(_.operation === ResChangeOperation.DELETED.toString)
      .result), AWAITDURATION).nonEmpty
    assert(rcEntryExists)
  }

  test("DELETE /orgs/testDeleteOrgRoute1 as hub admin -- normal success") {
    val response: HttpResponse[String] = Http(URL + "testDeleteOrgRoute1").method("DELETE").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === "testDeleteOrgRoute1").result), AWAITDURATION).length
    val numUsers: Int = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.orgid === "testDeleteOrgRoute1").result), AWAITDURATION).length
    val numNodes: Int = Await.result(DBCONNECTION.getDb.run(NodesTQ.filter(_.orgid === "testDeleteOrgRoute1").result), AWAITDURATION).length
    val numAgbots: Int = Await.result(DBCONNECTION.getDb.run(AgbotsTQ.filter(_.orgid === "testDeleteOrgRoute1").result), AWAITDURATION).length
    assert(numOrgs === 0) //insure org is gone
    assert(numUsers === 0) //insure users are gone
    assert(numNodes === 0) //insure nodes are gone
    assert(numAgbots === 0) //insure agbots are gone
    //insure entry was created in Resource Changes table
    val rcEntryExists: Boolean = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ
      .filter(_.orgId === "testDeleteOrgRoute1")
      .filter(_.id === "testDeleteOrgRoute1")
      .filter(_.category === ResChangeCategory.ORG.toString)
      .filter(_.resource === ResChangeResource.ORG.toString)
      .filter(_.operation === ResChangeOperation.DELETED.toString)
      .result), AWAITDURATION).nonEmpty
    assert(rcEntryExists)
  }

  test("DELETE /orgs/testDeleteOrgRoute1 as org admin -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + "testDeleteOrgRoute1").method("DELETE").headers(ACCEPT).headers(ORGADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === "testDeleteOrgRoute1").result), AWAITDURATION).length
    assert(numOrgs === 1) //insure org is still there
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === "testDeleteOrgRoute1").result), AWAITDURATION).isEmpty)
  }

  test("DELETE /orgs/testDeleteOrgRoute1 as regular user in org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + "testDeleteOrgRoute1").method("DELETE").headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val numOrgs: Int = Await.result(DBCONNECTION.getDb.run(OrgsTQ.filter(_.orgid === "testDeleteOrgRoute1").result), AWAITDURATION).length
    assert(numOrgs === 1) //insure org is still there
    //insure nothing was added to resource changes table
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === "testDeleteOrgRoute1").result), AWAITDURATION).isEmpty)
  }

}
