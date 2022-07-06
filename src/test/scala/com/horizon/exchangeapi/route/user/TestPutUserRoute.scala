package com.horizon.exchangeapi.route.user

import com.horizon.exchangeapi.{ApiResponse, ApiTime, ApiUtils, ExchMsg, HttpCode, Password, PostPutUsersRequest, Role, TestDBConnection}
import com.horizon.exchangeapi.tables.{AgbotRow, AgbotsTQ, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.mindrot.jbcrypt.BCrypt
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPutUserRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

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
        label              = "testPutUser",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPutUserRouteOrg1",
        orgType            = "",
        tags               = None
      ),
      OrgRow( //to try to update user in other org
        heartbeatIntervals = "",
        description        = "Test Organization 2",
        label              = "testPutUser",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPutUserRouteOrg2",
        orgType            = "",
        tags               = None
      )
    )

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestPutUserRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestPutUserRouteHubAdmin@ibm.com",
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
      ResourceChangesTQ.filter(_.orgId startsWith "testPutUserRoute").delete andThen
        OrgsTQ.filter(_.orgid startsWith "testPutUserRoute").delete andThen
        UsersTQ.filter(_.username startsWith "root/TestPutUserRouteHubAdmin").delete
    ), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      TESTUSERS(2).updateUser()
    ), AWAITDURATION)
  }

  def assertUsersEqual(user1: PostPutUsersRequest, user2: UserRow): Unit = {
    assert(BCrypt.checkpw(user1.password, user2.hashedPw))
    assert(user1.email === user2.email)
    assert(user1.admin === user2.admin)
    assert(user1.hubAdmin.getOrElse(false) === user2.hubAdmin)
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

  private val normalRequestBody: PostPutUsersRequest = PostPutUsersRequest(
    password = "newPassword",
    admin = false,
    hubAdmin = None,
    email = "newEmail@ibm.com"
  )

  private val normalUsernameToUpdate = TESTUSERS(2).username.split("/")(1)

  test("PUT /orgs/doesNotExist" + ROUTE + normalUsernameToUpdate + " -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE + normalUsernameToUpdate).put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + "doesNotExist -- 404 not found") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "doesNotExist").put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- empty body -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put("{}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assertNoChanges(TESTUSERS(2))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- null password -- 400 bad input") {
    val requestBody: Map[String, String] = Map( //can't use PostPutUsersRequest here because it would throw error for null password
      "password" -> null,
      "admin" -> null,
      "hubAdmin" -> null,
      "email" -> "newEmail@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assertNoChanges(TESTUSERS(2))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- null email -- 400 bad input") {
    val requestBody: Map[String, String] = Map( //can't use PostPutUsersRequest here because it would throw error for null email
      "password" -> "newPassword",
      "admin" -> null,
      "hubAdmin" -> null,
      "email" -> null
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assertNoChanges(TESTUSERS(2))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- blank password -- as org admin -- 400 bad input") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "",
      admin = false,
      hubAdmin = None,
      email = "newEmail@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("password.must.be.non.blank.when.creating.user"))
    assertNoChanges(TESTUSERS(2))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- blank password -- as root -- 201 OK") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "",
      admin = false,
      hubAdmin = None,
      email = "newEmail@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //insure new user is in DB correctly
    val newUser: UserRow = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head
    assert(newUser.username === TESTUSERS(2).username)
    assert(newUser.orgid === TESTORGS(0).orgId)
    assert(newUser.updatedBy === "root/root") //updated by root
    assert(newUser.lastUpdated > TESTUSERS(2).lastUpdated)
    assertUsersEqual(requestBody, newUser)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- user tries to make self admin -- 400 bad input") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "newPassword",
      admin = true,
      hubAdmin = None,
      email = "newEmail@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
    assertNoChanges(TESTUSERS(2))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- org admin tries to update user to hub admin -- 400 bad input") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "newPassword",
      admin = false,
      hubAdmin = Some(true),
      email = "newEmail@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("only.super.users.make.hub.admins"))
    assertNoChanges(TESTUSERS(2))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- try to update user to hub admin -- 400 bad input") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "newPassword",
      admin = false,
      hubAdmin = Some(true),
      email = "newEmail@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("hub.admins.in.root.org"))
    assertNoChanges(TESTUSERS(2))
  }

  test("PUT /orgs/root" + ROUTE + "TestPutUserRouteHubAdmin -- try to remove hub admin privileges -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "TestPutUserRouteHubAdmin").put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("user.cannot.be.in.root.org"))
    assertNoChanges(TESTUSERS(0))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + "orgAdmin -- hub admin tries to make org admin a regular user -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "orgAdmin").put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("hub.admins.only.write.admins"))
    assertNoChanges(TESTUSERS(1))
  }

  test("PUT /orgs/root" + ROUTE + "TestPutUserRouteHubAdmin -- try to give admin privileges to hub admin -- 400 bad input") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "newPassword",
      admin = true,
      hubAdmin = Some(true),
      email = "newEmail@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "TestPutUserRouteHubAdmin").put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === "User cannot be admin and hubAdmin at the same time")
    assertNoChanges(TESTUSERS(0))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- as org admin -- 201 OK") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "newPassword",
      admin = true,
      hubAdmin = None,
      email = "newUser@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //insure new user is in DB correctly
    val newUser: UserRow = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head
    assert(newUser.username === TESTUSERS(2).username)
    assert(newUser.orgid === TESTORGS(0).orgId)
    assert(newUser.updatedBy === TESTUSERS(1).username) //updated by org admin
    assert(newUser.lastUpdated > TESTUSERS(2).lastUpdated)
    assertUsersEqual(requestBody, newUser)
  }

  test("PUT /orgs/" + TESTORGS(1).orgId + ROUTE + normalUsernameToUpdate + " -- org admin tries to update user in other org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTUSERS(3))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- regular user updates self -- 201 OK") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //insure new user is in DB correctly
    val newUser: UserRow = Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username === TESTUSERS(2).username).result), AWAITDURATION).head
    assert(newUser.username === TESTUSERS(2).username)
    assert(newUser.orgid === TESTORGS(0).orgId)
    assert(newUser.updatedBy === TESTUSERS(2).username) //updated by self
    assert(newUser.lastUpdated > TESTUSERS(2).lastUpdated)
    assertUsersEqual(normalRequestBody, newUser)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + "orgUser2 -- regular user tries to update other user -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "orgUser2").put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTUSERS(4))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- as agbot -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTUSERS(2))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- as node -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assertNoChanges(TESTUSERS(2))
  }

}
