package org.openhorizon.exchangeapi.route.user

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
import org.openhorizon.exchangeapi.utility.{ApiResponse, ApiTime, ApiUtils, Configuration, DatabaseConnection, ExchMsg, HttpCode}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostUserRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/users/"

  private implicit val formats = DefaultFormats

  private val HUBADMINPASSWORD = "hubadminpassword"
  private val ORGADMINPASSWORD = "orgadminpassword"
  private val ORGUSERPASSWORD = "orguserpassword"
  private val NODETOKEN = "nodetoken"
  private val AGBOTTOKEN = "agbottoken"

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow( //main test org
        heartbeatIntervals = "",
        description        = "Test Organization 1",
        label              = "testPostUser",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPostUserRouteOrg1",
        orgType            = "",
        tags               = None
      ),
      OrgRow( //to try to create user in other org
        heartbeatIntervals = "",
        description        = "Test Organization 2",
        label              = "testPostUser",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPostUserRouteOrg2",
        orgType            = "",
        tags               = None
      )
    )

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestPostUserRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestPostUserRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/orgAdmin",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORGADMINPASSWORD),
        admin       = true,
        hubAdmin    = false,
        email       = "orgAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/orgUser",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORGUSERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "orgUser@ibm.com",
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

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORG1ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + ORGADMINPASSWORD))
  private val ORG1USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + ORGUSERPASSWORD))
  private val AGBOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + AGBOTTOKEN))
  private val NODEAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + NODETOKEN))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      (OrgsTQ ++= TESTORGS) andThen
      (UsersTQ ++= TESTUSERS) andThen
      (AgbotsTQ ++= TESTAGBOTS) andThen
      (NodesTQ ++= TESTNODES)
    ), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      ResourceChangesTQ.filter(_.orgId startsWith "testPostUserRoute").delete andThen
        OrgsTQ.filter(_.orgid startsWith "testPostUserRoute").delete andThen
        UsersTQ.filter(_.username startsWith "root/TestPostUserRouteHubAdmin").delete
    ), AWAITDURATION)
  }

  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.run(
      UsersTQ.filter(_.username startsWith (TESTORGS(0).orgId + "/newUser")).delete andThen
      UsersTQ.filter(_.username startsWith "root/TestPostUserRouteNewUser").delete
    ), AWAITDURATION)
  }

  def assertUsersEqual(user1: PostPutUsersRequest, user2: UserRow): Unit = {
    assert(BCrypt.checkpw(user1.password, user2.hashedPw))
    assert(user1.email === user2.email)
    assert(user1.admin === user2.admin)
    assert(user1.hubAdmin.getOrElse(false) === user2.hubAdmin)
  }

  private val normalRequestBody: PostPutUsersRequest = PostPutUsersRequest(
    password = "newPassword",
    admin = false,
    hubAdmin = None,
    email = "newUser@ibm.com"
  )

  //should this give 404 not found instead?
  test("POST /orgs/doesNotExist" + ROUTE + "newUser -- 500 SQL error") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE + "newUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.INTERNAL_ERROR.intValue)
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === "doesNotExist/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- empty body -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData("{}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- null password -- 400 bad input") {
    val requestBody: Map[String, String] = Map( //can't use PostPutUsersRequest here because it would throw error for null password
      "password" -> null,
      "admin" -> null,
      "hubAdmin" -> null,
      "email" -> "newUser@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- null email -- 400 bad input") {
    val requestBody: Map[String, String] = Map( //can't use PostPutUsersRequest here because it would throw error for null email
      "password" -> "newPassword",
      "admin" -> null,
      "hubAdmin" -> null,
      "email" -> null
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- blank password -- as org admin -- 400 bad input") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "",
      admin = false,
      hubAdmin = None,
      email = "newUser@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("password.must.be.non.blank.when.creating.user"))
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- blank password -- as root -- 201 OK") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "",
      admin = false,
      hubAdmin = None,
      email = "newUser@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //insure new user is in DB correctly
    val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).head
    assert(newUser.username === TESTORGS(0).orgId + "/newUser")
    assert(newUser.orgid === TESTORGS(0).orgId)
    assert(newUser.updatedBy === "root/root") //updated by root
    assertUsersEqual(requestBody, newUser)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- org admin tries to create hub admin -- 400 bad input") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "newPassword",
      admin = false,
      hubAdmin = Some(true),
      email = "newUser@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("only.super.users.make.hub.admins"))
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

  test("POST /orgs/root" + ROUTE + "TestPostUserRouteNewUser -- hub admin creates new hub admin -- 201 OK") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "newPassword",
      admin = false,
      hubAdmin = Some(true),
      email = "TestPostUserRouteNewUser@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "TestPostUserRouteNewUser").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //insure new user is in DB correctly
    val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.username ===  "root/TestPostUserRouteNewUser").result), AWAITDURATION).head
    assert(newUser.username === "root/TestPostUserRouteNewUser")
    assert(newUser.orgid === "root")
    assert(newUser.updatedBy === TESTUSERS(0).username) //updated by hub admin
    assertUsersEqual(requestBody, newUser)
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- try to create hubAdmin in non-root org -- 400 bad input") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "newPassword",
      admin = false,
      hubAdmin = Some(true),
      email = "newUser@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("hub.admins.in.root.org"))
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

  test("POST /orgs/root" + ROUTE + "newUser -- try to create regular user in root org -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "newUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("user.cannot.be.in.root.org"))
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === "root/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- hub admin tries to create regular user -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === ExchMsg.translate("hub.admins.only.write.admins"))
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

  test("POST /orgs/root" + ROUTE + "newUser -- try to make a user who is both admin and hub admin -- 400 bad input") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "newPassword",
      admin = true,
      hubAdmin = Some(true),
      email = "newUser@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "newUser").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
    assert(responseBody.msg === "User cannot be admin and hubAdmin at the same time")
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === "root/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "orgUser -- try to create user with existing username -- 400 bad input") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "orgUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/orgUser").result), AWAITDURATION).length === 1) //insure only one exists
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- as org admin -- 201 OK") {
    val requestBody: PostPutUsersRequest = PostPutUsersRequest(
      password = "newPassword",
      admin = true,
      hubAdmin = None,
      email = "newUser@ibm.com"
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    //insure new user is in DB correctly
    val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).head
    assert(newUser.username === TESTORGS(0).orgId + "/newUser")
    assert(newUser.orgid === TESTORGS(0).orgId)
    assert(newUser.updatedBy === TESTUSERS(1).username) //updated by org admin
    assertUsersEqual(requestBody, newUser)
  }

  test("POST /orgs/" + TESTORGS(1).orgId + ROUTE + "newUser -- org admin tries to create user in other org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + "newUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(1).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- as regular user -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- as agbot -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- as node -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
  }

}
