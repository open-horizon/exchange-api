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

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPutUserRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  
  private val localUrlRoot = "http://localhost:8080"
  private val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  private val runningLocally = (urlRoot == localUrlRoot)
  
  private val BASEURL = urlRoot + "/v1"
  private val URL = BASEURL + "/orgs/"
  private val ROUTE = "/users/"

  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: java.sql.Timestamp = ApiTime.nowUTCTimestamp

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

  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "TestPutUserRouteHubAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(ORG1ADMINPASSWORD)),
                username     = "orgAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(ORG1USERPASSWORD)),
                username     = "orgUser"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(1).orgId,
                password     = None,
                username     = "orgUser"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = Option(Password.hash(ORG1USERPASSWORD)),
                username     = "orgUser2"))
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
  private val ORG1ADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + ORG1ADMINPASSWORD))
  private val ORG1USERAUTH  = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).organization + "/" + TESTUSERS(2).username + ":" + ORG1USERPASSWORD))
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
      (ResourceChangesTQ.filter(_.orgId startsWith "testPutUserRoute").delete andThen
       OrgsTQ.filter(_.orgid startsWith "testPutUserRoute").delete andThen
       UsersTQ.filter(_.organization === "root")
              .filter(_.username startsWith "TestPutUserRouteHubAdmin").delete.transactionally)
    ), AWAITDURATION)
  }

  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.run(
      UsersTQ.filter(_.user === TESTUSERS(2).user)
             .update(TESTUSERS(2))
             .transactionally
    ), AWAITDURATION)
    
    val response: HttpResponse[String] = Http(BASEURL + "/admin/clearauthcaches").method("POST").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
  }

  def updateConfig(key: String, value: String): Unit = {
      val configInput = AdminConfigRequest(key, value)
      val response = Http(BASEURL+"/admin/config").postData(Serialization.write(configInput)).method("PUT").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      assert(response.code === HttpCode.PUT_OK.intValue)
  }

  def withOauthDisabled(testCode: => Unit): Unit = {
    val oauthEnabled = Configuration.getConfig.getBoolean("api.oauth.enabled")
    assume(!oauthEnabled || runningLocally, "Skipping: OAuth mode enabled and not running locally")

    if (oauthEnabled && runningLocally) {
      updateConfig("api.oauth.enabled", "false")
    } 
    try {
      testCode
    } finally {
      if (oauthEnabled && runningLocally) {
        updateConfig("api.oauth.enabled", "true")
      }
    }
  }

  def withOauthEnabled(testCode: => Unit): Unit = {
    val oauthEnabled = Configuration.getConfig.getBoolean("api.oauth.enabled")
    assume(oauthEnabled || runningLocally, "Skipping: OAuth mode disabled and not running locally")

    if (!oauthEnabled && runningLocally) {
      updateConfig("api.oauth.enabled", "true")
    } 
    try {
      testCode
    } finally {
      if (!oauthEnabled && runningLocally) {
        updateConfig("api.oauth.enabled", "false")
      }
    }
  }

  def assertUsersEqual(user1: PostPutUsersRequest, user2: UserRow): Unit = {
    //assert(BCrypt.checkpw(user1.password, user2.password.getOrElse("")))
    assert(Option(user1.email) === user2.email)
    assert(user1.admin === user2.isOrgAdmin)
    assert(user1.hubAdmin.getOrElse(false) === user2.isHubAdmin)
  }

  def assertNoChanges(user: UserRow): Unit = {
    val dbUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === user.user).result), AWAITDURATION).head
    assert(dbUser.username === user.username)
    assert(dbUser.organization === user.organization)
    assert(dbUser.password === user.password)
    assert(dbUser.isOrgAdmin === user.isOrgAdmin)
    assert(dbUser.isHubAdmin === user.isHubAdmin)
    assert(dbUser.email === user.email)
    assert(dbUser.modified_by === user.modified_by)
  }

  private val normalRequestBody: PostPutUsersRequest = PostPutUsersRequest(
    password = "newPassword",
    admin = false,
    hubAdmin = None,
    email = "newEmail@ibm.com"
  )

  private val normalUsernameToUpdate = TESTUSERS(2).username

  test("PUT /orgs/doesNotExist" + ROUTE + normalUsernameToUpdate + " -- 404 not found") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE + normalUsernameToUpdate).put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.NOT_FOUND.intValue)
    }
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + "doesNotExist -- 201 ok") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "doesNotExist").put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)
    }
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- empty body -- 400 bad input") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put("{}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      assertNoChanges(TESTUSERS(2))
    }
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- null password -- 400 bad input") {
    withOauthDisabled {
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
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- null email -- 400 bad input") {
    withOauthDisabled {
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
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- blank password -- as org admin -- 400 bad input") {
    withOauthDisabled {
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
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- blank password -- as root -- 201 OK") {
    withOauthDisabled {
      val requestBody: PostPutUsersRequest = PostPutUsersRequest(
        password = "",
        admin = false,
        hubAdmin = None,
        email = "newEmail@ibm.com"
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
    }
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- user tries to make self admin -- 400 bad input") {
    withOauthDisabled {
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
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- org admin tries to update user to hub admin -- 400 bad input") {
    withOauthDisabled {
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
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- try to update user to hub admin -- 400 bad input") {
    withOauthDisabled {
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
  }

  test("PUT /orgs/root" + ROUTE + "TestPutUserRouteHubAdmin -- try to remove hub admin privileges -- 400 bad input") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "TestPutUserRouteHubAdmin").put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("user.cannot.be.in.root.org"))
      assertNoChanges(TESTUSERS(0))
    }
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + "orgAdmin -- hub admin tries to make org admin a regular user -- 400 bad input") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "orgAdmin").put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("hub.admins.only.write.admins"))
      assertNoChanges(TESTUSERS(1))
    }
  }

  test("PUT /orgs/root" + ROUTE + "TestPutUserRouteHubAdmin -- try to give admin privileges to hub admin -- 400 bad input") {
    withOauthDisabled {
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
      assert(responseBody.msg === "a user without admin privilege can not give admin privilege")
      assertNoChanges(TESTUSERS(0))
    }
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- as org admin -- 201 OK") {
    withOauthDisabled {
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
      val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(2).user).result), AWAITDURATION).head
      assert(newUser.username === TESTUSERS(2).username)
      assert(newUser.organization === TESTORGS(0).orgId)
      assert(newUser.modified_by === Option(TESTUSERS(1).user)) //updated by org admin
      assert(newUser.modifiedAt.after(TESTUSERS(2).modifiedAt))
      assertUsersEqual(requestBody, newUser)
    }
  }

  test("PUT /orgs/" + TESTORGS(1).orgId + ROUTE + normalUsernameToUpdate + " -- org admin tries to update user in other org -- 403 access denied") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      assertNoChanges(TESTUSERS(3))
    }
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- regular user updates self -- 201 OK") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
      //insure new user is in DB correctly
      val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(2).user).result), AWAITDURATION).head
      assert(newUser.username === TESTUSERS(2).username)
      assert(newUser.organization === TESTORGS(0).orgId)
      assert(newUser.modified_by === Option(TESTUSERS(2).user)) //updated by self
      assert(newUser.modifiedAt.after(TESTUSERS(2).modifiedAt))
      assertUsersEqual(normalRequestBody, newUser)
    }
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + "orgUser2 -- regular user tries to update other user -- 403 access denied") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "orgUser2").put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      assertNoChanges(TESTUSERS(4))
    }
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- as agbot -- 403 access denied") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      assertNoChanges(TESTUSERS(2))
    }
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- as node -- 403 access denied") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(NODEAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      assertNoChanges(TESTUSERS(2))
    }
  }
  // OAuth mode tests
  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- OAuth mode silently ignores email/password, updates admin -- 201 OK") {
    withOauthEnabled {
      val requestBody: PostPutUsersRequest = PostPutUsersRequest(
        password = "thisWillBeIgnored",
        admin = true,
        hubAdmin = None,
        email = "thisWillBeIgnored@example.com"
      )
      val originalUser = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(2).user).result), AWAITDURATION).head
      
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
      
      val updatedUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(2).user).result), AWAITDURATION).head
      // Admin permission should be updated
      assert(updatedUser.isOrgAdmin === true)
      // Email and password should remain unchanged (silently ignored)
      assert(updatedUser.email === originalUser.email)
      assert(updatedUser.password === originalUser.password)
    }
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- OAuth mode allows empty/null email and password in request -- 201 OK") {
    withOauthEnabled {
      val requestBody: Map[String, Any] = Map(
        "password" -> "",
        "admin" -> true,
        "hubAdmin" -> null,
        "email" -> null
      )
      val originalUser = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(2).user).result), AWAITDURATION).head
      
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
      
      val updatedUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(2).user).result), AWAITDURATION).head
      // Admin permission should be updated
      assert(updatedUser.isOrgAdmin === true)
      // Email and password should remain unchanged (silently ignored)
      assert(updatedUser.email === originalUser.email)
      assert(updatedUser.password === originalUser.password)
    }
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- OAuth mode still validates admin permissions -- 400 bad input") {
    withOauthEnabled {
      val requestBody: PostPutUsersRequest = PostPutUsersRequest(
        password = "ignored",
        admin = true,
        hubAdmin = None,
        email = "ignored@example.com"
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
      assertNoChanges(TESTUSERS(2))
    }
  }
  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + "newUserInOAuth -- OAuth mode blocks user creation -- 405 METHOD NOT ALLOWED") {
    withOauthEnabled {
      val requestBody: PostPutUsersRequest = PostPutUsersRequest(
        password = "somePassword",
        admin = false,
        hubAdmin = None,
        email = "newuser@example.com"
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "newUserInOAuth").put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.NOT_ALLOWED.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("user.creation.disabled.oauth"))
    }
  }

}
