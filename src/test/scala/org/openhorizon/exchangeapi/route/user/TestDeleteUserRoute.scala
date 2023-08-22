package org.openhorizon.exchangeapi.route.user

import org.openhorizon.exchangeapi.TestDBConnection
import org.json4s.DefaultFormats
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, HttpCode}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestDeleteUserRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/users/"

  private implicit val formats = DefaultFormats

  private val HUBADMINPASSWORD = "hubadminpassword"
  private val ORG1ADMINPASSWORD = "org1adminpassword"
  private val ORG1USERPASSWORD = "org1userpassword"
  private val NODETOKEN = "nodetoken"
  private val AGBOTTOKEN = "agbottoken"

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow( //main test org
        heartbeatIntervals = "",
        description        = "Test Organization 1",
        label              = "testDeleteUser",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testDeleteUserRouteOrg1",
        orgType            = "",
        tags               = None
      ),
      OrgRow( //to try to update user in other org
        heartbeatIntervals = "",
        description        = "Test Organization 2",
        label              = "testDeleteUser",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testDeleteUserRouteOrg2",
        orgType            = "",
        tags               = None
      )
    )

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestDeleteUserRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestDeleteUserRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/orgAdmin",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORG1ADMINPASSWORD),
        admin       = true,
        hubAdmin    = false,
        email       = "orgAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow( //main user to be updated
        username    = TESTORGS(0).orgId + "/orgUser",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORG1USERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "org1User@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(1).orgId + "/orgUser",
        orgid       = TESTORGS(1).orgId,
        hashedPw    = "",
        admin       = false,
        hubAdmin    = false,
        email       = "org2User@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/orgUser2",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORG1USERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "org1User2@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = "root/TestDeleteUserRouteHubAdmin2",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestDeleteUserRouteHubAdmin2@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      )
    )

  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(
      AgbotRow(
        id = TESTORGS(0).orgId + "/agbot",
        orgid = TESTORGS(0).orgId,
        token = Password.hash(AGBOTTOKEN),
        name = "",
        owner = TESTUSERS(2).username, //org 1 user
        msgEndPoint = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey = ""
      )
    )

  private val TESTNODES: Seq[NodeRow] =
    Seq(
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/node",
        heartbeatIntervals = "",
        lastHeartbeat      = None,
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(2).username, //org 1 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = Password.hash(NODETOKEN),
        userInput          = ""
      )
    )

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORG1ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + ORG1ADMINPASSWORD))
  private val ORG1USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + ORG1USERPASSWORD))
  private val AGBOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + AGBOTTOKEN))
  private val NODEAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + NODETOKEN))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      (OrgsTQ ++= TESTORGS) andThen
        (UsersTQ ++= TESTUSERS) andThen
        (AgbotsTQ ++= TESTAGBOTS) andThen
        (NodesTQ ++= TESTNODES)
    ), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      ResourceChangesTQ.filter(_.orgId startsWith "testDeleteUserRoute").delete andThen
        OrgsTQ.filter(_.orgid startsWith "testDeleteUserRoute").delete andThen
        UsersTQ.filter(_.username startsWith "root/TestDeleteUserRouteHubAdmin").delete
    ), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      TESTUSERS(2).upsertUser andThen
      TESTUSERS(0).upsertUser andThen
      TESTUSERS(1).upsertUser andThen
      TESTUSERS(5).upsertUser andThen
      TESTNODES(0).upsert andThen
      TESTAGBOTS(0).upsert
    ), AWAITDURATION)
  }

  def assertDbClear(username: String): Unit = {
    assert(Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === username).result), AWAITDURATION).isEmpty) //insure users are gone
    assert(Await.result(DBCONNECTION.getDb.run(NodesTQ.filter(_.owner === username).result), AWAITDURATION).isEmpty) //insure nodes are gone
    assert(Await.result(DBCONNECTION.getDb.run(AgbotsTQ.filter(_.owner === username).result), AWAITDURATION).isEmpty) //insure agbots are gone
  }

  private val normalUsernameToDelete = TESTUSERS(2).username.split("/")(1)

  test("DELETE /orgs/doesNotExist" + ROUTE + normalUsernameToDelete + " -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE + normalUsernameToDelete).method("DELETE").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("DELETE /orgs/" + TESTORGS(0).orgId + ROUTE + "doesNotExist -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "doesNotExist").method("DELETE").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("DELETE /orgs/root" + ROUTE + "root -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "root").method("DELETE").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assert(Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === "root/root").result), AWAITDURATION).nonEmpty) //insure user still exists
  }

  test("DELETE /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToDelete + " -- user deletes self -- 204 DELETED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToDelete).method("DELETE").headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
    assertDbClear(TESTUSERS(2).username)
  }

  test("DELETE /orgs/" + TESTORGS(0).orgId + ROUTE + "orgUser2 -- user tries to delete other user -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "orgUser2").method("DELETE").headers(ACCEPT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assert(Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(4).username).result), AWAITDURATION).nonEmpty) //insure user still exists
  }

  test("DELETE /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToDelete + " -- org admin deletes user -- 204 DELETED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToDelete).method("DELETE").headers(ACCEPT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
    assertDbClear(TESTUSERS(2).username)
  }

  test("DELETE /orgs/" + TESTORGS(1).orgId + ROUTE + normalUsernameToDelete + " -- org admin tries to delete user in other org -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + normalUsernameToDelete).method("DELETE").headers(ACCEPT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assert(Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(3).username).result), AWAITDURATION).nonEmpty) //insure user still exists
  }

  test("DELETE /orgs/root" + ROUTE + "TestDeleteUserRouteHubAdmin -- org admin tries to delete hub admin -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "TestDeleteUserRouteHubAdmin").method("DELETE").headers(ACCEPT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assert(Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(0).username).result), AWAITDURATION).nonEmpty) //insure user still exists
  }

  test("DELETE /orgs/root" + ROUTE + "TestDeleteUserRouteHubAdmin -- hub admin deletes self -- 204 DELETED") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "TestDeleteUserRouteHubAdmin").method("DELETE").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
    assert(Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(0).username).result), AWAITDURATION).isEmpty) //insure user is deleted
  }

  test("DELETE /orgs/root" + ROUTE + "TestDeleteUserRouteHubAdmin2 -- hub admin deletes other hub admin -- 204 DELETED") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "TestDeleteUserRouteHubAdmin2").method("DELETE").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
    assert(Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(5).username).result), AWAITDURATION).isEmpty) //insure user is deleted
  }

  //currently a hub admin is able to delete any user (other than root). However, a hub admin is only supposed to be able to delete admins
  ignore("DELETE /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToDelete + " -- hub admin tries to delete regular user -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToDelete).method("DELETE").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assert(Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).nonEmpty) //insure user still exists
  }

  test("DELETE /orgs/" + TESTORGS(0).orgId + ROUTE + "orgAdmin -- hub admin deletes org admin -- 204 DELETED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "orgAdmin").method("DELETE").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
    assert(Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(1).username).result), AWAITDURATION).isEmpty) //insure user is deleted
  }

  test("DELETE /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToDelete + " -- node tries to delete user -- 204 DELETED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToDelete).method("DELETE").headers(ACCEPT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assert(Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).nonEmpty) //insure user still exists
  }

  test("DELETE /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToDelete + " -- agbot tries to delete user -- 204 DELETED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToDelete).method("DELETE").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assert(Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).nonEmpty) //insure user still exists
  }

}
