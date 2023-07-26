package org.openhorizon.exchangeapi.route.user

import org.openhorizon.exchangeapi.TestDBConnection
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.mindrot.jbcrypt.BCrypt
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiResponse, ApiTime, ApiUtils, ExchMsg, HttpCode}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPatchUserRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
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
        label              = "testPatchUser",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPatchUserRouteOrg1",
        orgType            = "",
        tags               = None
      ),
      OrgRow( //to try to update user in other org
        heartbeatIntervals = "",
        description        = "Test Organization 2",
        label              = "testPatchUser",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPatchUserRouteOrg2",
        orgType            = "",
        tags               = None
      )
    )

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestPatchUserRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestPatchUserRouteHubAdmin@ibm.com",
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
      ResourceChangesTQ.filter(_.orgId startsWith "testPatchUserRoute").delete andThen
        OrgsTQ.filter(_.orgid startsWith "testPatchUserRoute").delete andThen
        UsersTQ.filter(_.username startsWith "root/TestPatchUserRouteHubAdmin").delete
    ), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      TESTUSERS(0).updateUser() andThen
        TESTUSERS(2).updateUser() andThen
        TESTUSERS(4).updateUser
    ), AWAITDURATION)
  }

  def assertNoChanges(user: UserRow): Unit = {
    val dbUser: UserRow = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === user.username).result), AWAITDURATION).head
    assert(dbUser.username === user.username)
    assert(dbUser.orgid === user.orgid)
    assert(dbUser.hashedPw === user.hashedPw)
    assert(dbUser.admin === user.admin)
    assert(dbUser.hubAdmin === user.hubAdmin)
    assert(dbUser.email === user.email)
    assert(dbUser.lastUpdated === user.lastUpdated)
    assert(dbUser.updatedBy === user.updatedBy)
  }

  private val normalRequestBody: PatchUsersRequest = PatchUsersRequest(
    password = Some("newPassword"),
    admin = None,
    hubAdmin = None,
    email = None
  )

  private val normalUsernameToUpdate = TESTUSERS(2).username.split("/")(1)

  test("PATCH /orgs/doesNotExist" + ROUTE + normalUsernameToUpdate + " -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE + normalUsernameToUpdate).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + "doesNotExist -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "doesNotExist").postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- empty body -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData("{}").method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assertNoChanges(TESTUSERS(2))
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- invalid body -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData("{\"invalidKey\":\"invalidValue\"}").method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assertNoChanges(TESTUSERS(2))
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- blank password -- as org admin -- 400 bad input") {
    val requestBody: PatchUsersRequest = PatchUsersRequest(
      password = Some(""),
      admin = None,
      hubAdmin = None,
      email = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("password.cannot.be.set.to.empty.string"))
    assertNoChanges(TESTUSERS(2))
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- blank password -- as root -- 201 OK") {
    val requestBody: PatchUsersRequest = PatchUsersRequest(
      password = Some(""),
      admin = None,
      hubAdmin = None,
      email = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //insure new user is in DB correctly
    val newUser: UserRow = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head
    assert(newUser.username === TESTUSERS(2).username)
    assert(newUser.orgid === TESTORGS(0).orgId)
    assert(newUser.updatedBy === "root/root") //updated by root
    assert(BCrypt.checkpw(requestBody.password.get, newUser.hashedPw))
    assert(newUser.admin === TESTUSERS(2).admin)
    assert(newUser.hubAdmin === TESTUSERS(2).hubAdmin)
    assert(newUser.email === TESTUSERS(2).email)
    assert(newUser.lastUpdated > TESTUSERS(2).lastUpdated)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- user tries to make self admin -- 400 bad input") {
    val requestBody: PatchUsersRequest = PatchUsersRequest(
      password = None,
      admin = Some(true),
      hubAdmin = None,
      email = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
    assertNoChanges(TESTUSERS(2))
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- org admin tries to update user to hub admin -- 400 bad input") {
    val requestBody: PatchUsersRequest = PatchUsersRequest(
      password = None,
      admin = None,
      hubAdmin = Some(true),
      email = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("only.super.users.make.hub.admins"))
    assertNoChanges(TESTUSERS(2))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- try to update user to hub admin -- 400 bad input") {
    val requestBody: PatchUsersRequest = PatchUsersRequest(
      password = None,
      admin = None,
      hubAdmin = Some(true),
      email = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("hub.admins.in.root.org"))
    assertNoChanges(TESTUSERS(2))
  }

  test("PATCH /orgs/root" + ROUTE + "TestPatchUserRouteHubAdmin -- root turns hub admin into a normal user -- 201 OK") {
    val requestBody: PatchUsersRequest = PatchUsersRequest(
      password = None,
      admin = None,
      hubAdmin = Some(false),
      email = None
    )
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "TestPatchUserRouteHubAdmin").postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //insure new user is in DB correctly
    val newUser: UserRow = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(0).username).result), AWAITDURATION).head
    assert(newUser.username === TESTUSERS(0).username)
    assert(newUser.orgid === TESTUSERS(0).orgid)
    assert(newUser.updatedBy === "root/root") //updated by root
    assert(newUser.hashedPw === TESTUSERS(0).hashedPw)
    assert(newUser.admin === TESTUSERS(0).admin)
    assert(newUser.hubAdmin === requestBody.hubAdmin.get)
    assert(newUser.email === TESTUSERS(0).email)
    assert(newUser.lastUpdated > TESTUSERS(0).lastUpdated)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + "orgAdmin -- hub admin tries to make org admin a regular user -- 400 bad input") {
    val requestBody: PatchUsersRequest = PatchUsersRequest(
      password = None,
      admin = Some(false),
      hubAdmin = None,
      email = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "orgAdmin").postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("hub.admins.only.write.admins"))
    assertNoChanges(TESTUSERS(1))
  }

  test("PATCH /orgs/root" + ROUTE + "TestPatchUserRouteHubAdmin -- try to give admin privileges to hub admin -- 400 bad input") {
    val requestBody: PatchUsersRequest = PatchUsersRequest(
      password = None,
      admin = Some(true),
      hubAdmin = None,
      email = None
    )
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "TestPatchUserRouteHubAdmin").postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("user.cannot.be.in.root.org"))
    assertNoChanges(TESTUSERS(0))
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + "orgUser2 -- as org admin -- 201 OK") {
    val requestBody: PatchUsersRequest = PatchUsersRequest(
      password = None,
      admin = Some(true),
      hubAdmin = None,
      email = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "orgUser2").postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //insure new user is in DB correctly
    val newUser: UserRow = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(4).username).result), AWAITDURATION).head
    assert(newUser.username === TESTUSERS(4).username)
    assert(newUser.orgid === TESTUSERS(4).orgid)
    assert(newUser.updatedBy === TESTUSERS(1).username) //updated by org admin
    assert(newUser.hashedPw === TESTUSERS(4).hashedPw)
    assert(newUser.admin === requestBody.admin.get)
    assert(newUser.hubAdmin === TESTUSERS(4).hubAdmin)
    assert(newUser.email === TESTUSERS(4).email)
    assert(newUser.lastUpdated > TESTUSERS(4).lastUpdated)
  }

  test("PATCH /orgs/" + TESTORGS(1).orgId + ROUTE + normalUsernameToUpdate + " -- org admin tries to update user in other org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTUSERS(3))
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- regular user updates self -- 201 OK") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //insure new user is in DB correctly
    val newUser: UserRow = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head
    assert(newUser.username === TESTUSERS(2).username)
    assert(newUser.orgid === TESTUSERS(2).orgid)
    assert(newUser.updatedBy === TESTUSERS(2).username) //updated by self
    assert(BCrypt.checkpw(normalRequestBody.password.get, newUser.hashedPw))
    assert(newUser.admin === TESTUSERS(2).admin)
    assert(newUser.hubAdmin === TESTUSERS(2).hubAdmin)
    assert(newUser.email === TESTUSERS(2).email)
    assert(newUser.lastUpdated > TESTUSERS(2).lastUpdated)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + "orgUser2 -- regular user tries to update other user -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "orgUser2").postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTUSERS(4))
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- as agbot -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTUSERS(2))
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- as node -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTUSERS(2))
  }

  //when multiple attributes are provided, it is undefined which gets precedence, but functionally the precedence is: password, admin, hubAdmin, email
  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- multiple attributes provided -- 201 OK") {
    val requestBody: PatchUsersRequest = PatchUsersRequest(
      password = Some("newPassword"),
      admin = Some(false),
      hubAdmin = Some(false),
      email = Some("newEmail@ibm.com")
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //insure new user is in DB correctly
    val newUser: UserRow = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head
    assert(newUser.username === TESTUSERS(2).username)
    assert(newUser.orgid === TESTUSERS(2).orgid)
    assert(newUser.updatedBy === "root/root") //updated by root
    assert(BCrypt.checkpw(requestBody.password.get, newUser.hashedPw))
    assert(newUser.admin === TESTUSERS(2).admin)
    assert(newUser.hubAdmin === TESTUSERS(2).hubAdmin)
    assert(newUser.email === TESTUSERS(2).email)
    assert(newUser.lastUpdated > TESTUSERS(2).lastUpdated)
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- update email -- 201 OK") {
    val requestBody: PatchUsersRequest = PatchUsersRequest(
      password = None,
      admin = None,
      hubAdmin = None,
      email = Some("newEmail@ibm.com")
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //insure new user is in DB correctly
    val newUser: UserRow = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head
    assert(newUser.username === TESTUSERS(2).username)
    assert(newUser.orgid === TESTUSERS(2).orgid)
    assert(newUser.updatedBy === "root/root") //updated by root
    assert(newUser.hashedPw === TESTUSERS(2).hashedPw)
    assert(newUser.admin === TESTUSERS(2).admin)
    assert(newUser.hubAdmin === TESTUSERS(2).hubAdmin)
    assert(newUser.email === requestBody.email.get)
    assert(newUser.lastUpdated > TESTUSERS(2).lastUpdated)
  }

}
