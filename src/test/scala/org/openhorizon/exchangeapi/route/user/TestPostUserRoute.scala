package org.openhorizon.exchangeapi.route.user

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.route.administration.AdminConfigRequest
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

import java.time.Instant
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostUserRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  private val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase

  private val localUrlRoot = "http://localhost:8080"
  private val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  private val runningLocally = (urlRoot == localUrlRoot)

  private val BASEURL = urlRoot + "/v1"
  private val URL = BASEURL + "/orgs/"
  private val ROUTE = "/users/"
  private val ACCEPT: (String, String) = ("Accept", "application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")

  private val HUBADMINPASSWORD = "hubadminpassword"
  private val ORGADMINPASSWORD = "orgadminpassword"
  private val ORGUSERPASSWORD = "orguserpassword"
  private val NODETOKEN = "nodetoken"
  private val AGBOTTOKEN = "agbottoken"
  
  val rootUser: UUID = Await.result(DBCONNECTION.run(Compiled(UsersTQ.filter(users => users.organization === "root" && users.username === "root").map(_.user)).result.head.transactionally), AWAITDURATION)

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

  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "TestPostUserRouteHubAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(ORGADMINPASSWORD)),
                username     = "orgAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(ORGUSERPASSWORD)),
                username     = "orgUser"))
  }
  
  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(
      AgbotRow(
        id = TESTORGS(0).orgId + "/agbot",
        orgid = TESTORGS(0).orgId,
        token = Password.hash(AGBOTTOKEN),
        name = "",
        owner = TESTUSERS(2).user, //org 1 user
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
        owner              = TESTUSERS(2).user, //org 1 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = Password.hash(NODETOKEN),
        userInput          = ""
      )
    )

  private val ROOTAUTH      = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val HUBADMINAUTH  = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).organization + "/" + TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORG1ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + ORGADMINPASSWORD))
  private val ORG1USERAUTH  = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).organization + "/" + TESTUSERS(2).username + ":" + ORGUSERPASSWORD))
  private val AGBOTAUTH     = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + AGBOTTOKEN))
  private val NODEAUTH      = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + NODETOKEN))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      ((OrgsTQ ++= TESTORGS) andThen
       (UsersTQ ++= TESTUSERS) andThen
       (AgbotsTQ ++= TESTAGBOTS) andThen
       (NodesTQ ++= TESTNODES)).transactionally
    ), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      (ResourceChangesTQ.filter(_.orgId startsWith "testPostUserRoute").delete andThen
       OrgsTQ.filter(_.orgid startsWith "testPostUserRoute").delete andThen
       UsersTQ.filter(users => users.organization === "root" && users.username === "TestPostUserRouteHubAdmin").delete).transactionally
    ), AWAITDURATION)
  }

  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.run(
      (UsersTQ.filter(users => users.organization === TESTORGS(0).orgId && users.username === "newUser")).delete andThen
      (UsersTQ.filter(users => users.organization === "root" && users.username === "TestPostUserRouteNewUser").delete).transactionally
    ), AWAITDURATION)

    val response: HttpResponse[String] = Http(BASEURL + "/admin/clearauthcaches").method("POST").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
  }
  // Note:
  // If the environment variable EXCHANGE_OAUTH_USER_INFO_URL is set (via `export`),
  // the updateConfig(...) call cannot override its value.
  // This might cause tests that rely on non-OAuth mode to fail.
  // To ensure consistent behavior, unset the variable before running tests with:
  // unset EXCHANGE_OAUTH_USER_INFO_URL
  def updateConfig(key: String, value: String): Unit = {
      val configInput = AdminConfigRequest(key, value)
      val response = Http(BASEURL+"/admin/config").postData(Serialization.write(configInput)).method("PUT").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      assert(response.code === HttpCode.PUT_OK.intValue)
  }

  def withOauthDisabled(testCode: => Unit): Unit = {
    val oauthEnabled = Configuration.getConfig.hasPath("api.authentication.oauth.provider.user_info.url")
    assume(!oauthEnabled || runningLocally, "Skipping: OAuth mode enabled and not running locally")

    if (oauthEnabled && runningLocally) {
      updateConfig("api.authentication.oauth.provider.user_info.url", "")
    } 
    try {
      testCode
    } finally {
      if (oauthEnabled && runningLocally) {
        updateConfig("api.authentication.oauth.provider.user_info.url", "http://localhost:8080/mock-oauth")
      }
    }
  }

  def withOauthEnabled(testCode: => Unit): Unit = {
    val oauthEnabled = Configuration.getConfig.hasPath("api.authentication.oauth.provider.user_info.url")
    assume(oauthEnabled || runningLocally, "Skipping: OAuth mode disabled and not running locally")

    if (!oauthEnabled && runningLocally) {
      updateConfig("api.authentication.oauth.provider.user_info.url", "http://localhost:8080/mock-oauth")
    }
    try {
      testCode
    } finally {
      if (!oauthEnabled && runningLocally) {
        updateConfig("api.authentication.oauth.provider.user_info.url", "")
      }
    }
  }

  def assertUsersEqual(user1: PostPutUsersRequest, user2: UserRow): Unit = {
    //assert(BCrypt.checkpw(user1.password, user2.password.getOrElse("")))
    assert(user1.email === user2.email.getOrElse(""))
    assert(user1.admin === user2.isOrgAdmin)
    assert(user1.hubAdmin.getOrElse(false) === user2.isHubAdmin)
  }

  private val normalRequestBody: PostPutUsersRequest = PostPutUsersRequest(
    password = "newPassword",
    admin = false,
    hubAdmin = None,
    email = "newUser@ibm.com"
  )

  //should this give 404 not found instead?
  test("POST /orgs/doesNotExist" + ROUTE + "newUser -- 500 SQL error") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE + "newUser").method("POST").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.INTERNAL_ERROR.intValue)
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === "doesNotExist/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- empty body -- 400 bad input") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").method("POST").postData("{}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- null password -- 400 bad input") {
    withOauthDisabled {
      val requestBody: Map[String, String] = Map( //can't use PostPutUsersRequest here because it would throw error for null password
        "password" -> null,
        "admin" -> null,
        "hubAdmin" -> null,
        "email" -> "newUser@ibm.com"
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").method("POST").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- null email -- 400 bad input") {
    withOauthDisabled {
      val requestBody: Map[String, String] = Map( //can't use PostPutUsersRequest here because it would throw error for null email
        "password" -> "newPassword",
        "admin" -> null,
        "hubAdmin" -> null,
        "email" -> null
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").method("POST").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- blank password -- as org admin -- 400 bad input") {
    withOauthDisabled {
      /*val requestBody: PostPutUsersRequest = PostPutUsersRequest(
        password = "",
        admin = false,
        hubAdmin = None,
        email = "newUser@ibm.com"
      )*/
      
      val requestBody: Map[String, String] = Map( //can't use PostPutUsersRequest here because it would throw error for null email
        "password" -> "",
        "admin" -> "false",
        "hubAdmin" -> "false",
        "email" -> "newUser@ibm.com"
      )
      
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").method("POST").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("password.must.be.non.blank.when.creating.user"))
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- blank password -- as root -- 400 bad input") {
    withOauthDisabled {
      val requestBody: PostPutUsersRequest = PostPutUsersRequest(
        password = "",
        admin = false,
        hubAdmin = None,
        email = "newUser@ibm.com"
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").method("POST").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("password.must.be.non.blank.when.creating.user"))
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- org admin tries to create hub admin -- 400 bad input") {
    withOauthDisabled {
      val requestBody: PostPutUsersRequest = PostPutUsersRequest(
        password = "newPassword",
        admin = false,
        hubAdmin = Some(true),
        email = "newUser@ibm.com"
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").method("POST").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("only.super.users.make.hub.admins"))
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(_.username === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }
  
  test("POST /orgs/root" + ROUTE + "TestPostUserRouteNewUser -- hub admin creates new hub admin -- 201 OK") {
    withOauthDisabled {
      val requestBody: PostPutUsersRequest = PostPutUsersRequest(
        password = "newPassword",
        admin = false,
        hubAdmin = Some(true),
        email = "TestPostUserRouteNewUser@ibm.com"
      )
      val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "TestPostUserRouteNewUser").method("POST").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
      //insure new user is in DB correctly
      val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) ===  "root/TestPostUserRouteNewUser").result), AWAITDURATION).head
      assert(newUser.organization === "root")
      assert(newUser.username === "TestPostUserRouteNewUser")
      assert(newUser.modified_by === Option(TESTUSERS(0).user)) //updated by hub admin
      assertUsersEqual(requestBody, newUser)
    }
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- try to create hubAdmin in non-root org -- 400 bad input") {
    withOauthDisabled {
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
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }
  
  test("POST /orgs/root" + ROUTE + "TestPostUserRouteNewUser -- try to create regular user in root org -- 400 bad input") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "TestPostUserRouteNewUser2").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("user.cannot.be.in.root.org"))
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === "root/TestPostUserRouteNewUser2").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- hub admin tries to create regular user -- 400 bad input") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("hub.admins.only.write.admins"))
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }
  
  test("POST /orgs/root" + ROUTE + "TestPostUserRouteNewUser -- try to make a user who is both admin and hub admin -- 400 bad input") {
    withOauthDisabled {
      val requestBody: PostPutUsersRequest = PostPutUsersRequest(
        password = "newPassword",
        admin = true,
        hubAdmin = Some(true),
        email = "newUser@ibm.com"
      )
      val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "TestPostUserRouteNewUser").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === "root/TestPostUserRouteNewUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "orgUser -- try to create user with existing username -- 400 bad input") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "orgUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(0).orgId + "/orgUser").result), AWAITDURATION).length === 1) //insure only one exists
    }
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- as org admin -- 201 OK") {
    withOauthDisabled {
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
      val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).head
      assert(newUser.username === "newUser")
      assert(newUser.organization === TESTORGS(0).orgId)
      assert(newUser.modified_by === Option(TESTUSERS(1).user)) //updated by org admin
      assertUsersEqual(requestBody, newUser)
    }
  }
  
  test("POST /orgs/" + TESTORGS(1).orgId + ROUTE + "newUser -- org admin tries to create user in other org -- 403 access denied") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + "newUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(1).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- as regular user -- 403 access denied") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- as agbot -- 403 access denied") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser -- as node -- 403 access denied") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser").postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(NODEAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(0).orgId + "/newUser").result), AWAITDURATION).isEmpty) //insure new user wasn't added
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "apikey -- reserved username -- 400 bad input") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "apikey")
        .postData(Serialization.write(normalRequestBody))
        .headers(ACCEPT)
        .headers(CONTENT)
        .headers(ROOTAUTH)
        .asString

      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("user.reserved.name"))
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => users.organization === TESTORGS(0).orgId && users.username === "apikey").result), AWAITDURATION).isEmpty)
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "iamapikey -- reserved username -- 400 bad input") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "iamapikey")
        .postData(Serialization.write(normalRequestBody))
        .headers(ACCEPT)
        .headers(CONTENT)
        .headers(ROOTAUTH)
        .asString

      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("user.reserved.name"))
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => users.organization === TESTORGS(0).orgId && users.username === "apikey").result), AWAITDURATION).isEmpty)
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "aPIkey -- reserved username -- 400 bad input") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "aPIkey")
        .postData(Serialization.write(normalRequestBody))
        .headers(ACCEPT)
        .headers(CONTENT)
        .headers(ROOTAUTH)
        .asString

      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("user.reserved.name"))
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => users.organization === TESTORGS(0).orgId && users.username === "apikey").result), AWAITDURATION).isEmpty)
    }
  }
  
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE + "newUser2 -- operation disabled in OAuth mode") {
    withOauthEnabled {
      val requestBody: PostPutUsersRequest = PostPutUsersRequest(
        password = "newPassword",
        admin = false,
        hubAdmin = None,
        email = "newUser2@ibm.com"
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUser2").postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.NOT_ALLOWED.intValue)
      assert(Await.result(DBCONNECTION.run(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === TESTORGS(0).orgId + "/newUser2").result), AWAITDURATION).isEmpty)
    }
  }
}
