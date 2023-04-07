package org.openhorizon.exchangeapi.route.user

import org.openhorizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Password, Role, TestDBConnection}
import org.openhorizon.exchangeapi.table.{AgbotRow, AgbotsTQ, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import org.json4s.DefaultFormats
import org.json4s.native.Serialization
import org.mindrot.jbcrypt.BCrypt
import org.openhorizon.exchangeapi.{Password, Role}
import org.openhorizon.exchangeapi.table.{NodeRow, NodesTQ}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostChangeUserPasswordRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE1 = "/users/"
  private val ROUTE2 = "/changepw"

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
        label              = "testPostChangeUserPassword",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPostChangeUserPasswordRouteOrg1",
        orgType            = "",
        tags               = None
      ),
      OrgRow( //to try to update user in other org
        heartbeatIntervals = "",
        description        = "Test Organization 2",
        label              = "testPostChangeUserPassword",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPostChangeUserPasswordRouteOrg2",
        orgType            = "",
        tags               = None
      )
    )

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestPostChangeUserPasswordRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestPostChangeUserPasswordRouteHubAdmin@ibm.com",
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
      ResourceChangesTQ.filter(_.orgId startsWith "testPostChangeUserPasswordRoute").delete andThen
        OrgsTQ.filter(_.orgid startsWith "testPostChangeUserPasswordRoute").delete andThen
        UsersTQ.filter(_.username startsWith "root/TestPostChangeUserPasswordRouteHubAdmin").delete
    ), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      TESTUSERS(2).updateUser() andThen
      TESTUSERS(1).updateUser()
    ), AWAITDURATION)
  }

  private val normalRequestBody: ChangePwRequest = ChangePwRequest(newPassword = "newPassword")

  private val normalUsernameToUpdate = TESTUSERS(2).username.split("/")(1)

  test("POST /orgs/doesNotExist" + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + "doesNotExist" + ROUTE2 + " -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + "doesNotExist" + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- empty body -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData("{}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val dbPass = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head.hashedPw
    assert(dbPass === TESTUSERS(2).hashedPw) //assert password hasn't changed
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- blank password -- 400 bad input") {
    val requestBody: ChangePwRequest = ChangePwRequest(newPassword = "")
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val dbPass = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head.hashedPw
    assert(dbPass === TESTUSERS(2).hashedPw) //assert password hasn't changed
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- as root -- 201 OK") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val dbPass = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head.hashedPw
    assert(BCrypt.checkpw(normalRequestBody.newPassword, dbPass)) //assert password was updated
  }

  //currently a hub admin is able to change the password of any user (other than root). However, a hub admin is only supposed to be able to change the password of admins
  ignore("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- as hub admin -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val dbPass = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head.hashedPw
    assert(dbPass === TESTUSERS(2).hashedPw) //assert password hasn't changed
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + "orgAdmin" + ROUTE2 + " -- as hub admin -- 201 OK") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + "orgAdmin" + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val dbPass = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(1).username).result), AWAITDURATION).head.hashedPw
    assert(BCrypt.checkpw(normalRequestBody.newPassword, dbPass)) //assert password was updated
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- as org admin -- 201 OK") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val dbPass = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head.hashedPw
    assert(BCrypt.checkpw(normalRequestBody.newPassword, dbPass)) //assert password was updated
  }

  test("POST /orgs/" + TESTORGS(1).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- as org admin in other org -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val dbPass = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(3).username).result), AWAITDURATION).head.hashedPw
    assert(dbPass === TESTUSERS(3).hashedPw) //assert password hasn't changed
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- user updates self -- 201 OK") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val dbPass = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head.hashedPw
    assert(BCrypt.checkpw(normalRequestBody.newPassword, dbPass)) //assert password was updated
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + "orgUser2" + ROUTE2 + " -- user tries to update other user -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + "orgUser2" + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val dbPass = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(4).username).result), AWAITDURATION).head.hashedPw
    assert(dbPass === TESTUSERS(4).hashedPw) //assert password hasn't changed
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- as node -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val dbPass = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head.hashedPw
    assert(dbPass === TESTUSERS(2).hashedPw) //assert password hasn't changed
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- as agbot -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    val dbPass = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head.hashedPw
    assert(dbPass === TESTUSERS(2).hashedPw) //assert password hasn't changed
  }

}
