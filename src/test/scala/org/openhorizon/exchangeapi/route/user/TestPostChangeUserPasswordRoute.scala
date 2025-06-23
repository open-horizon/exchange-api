package org.openhorizon.exchangeapi.route.user

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.json4s.native.Serialization
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceIdentity
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
import scalacache.modes.scalaFuture._
import scalacache.modes.sync.mode
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostChangeUserPasswordRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  
  private val localUrlRoot = "http://localhost:8080"
  private val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  private val runningLocally = (urlRoot == localUrlRoot)
  
  private val BASEURL = urlRoot + "/v1"
  private val URL = BASEURL + "/orgs/"
  private val ROUTE1 = "/users/"
  private val ROUTE2 = "/changepw"

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

  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "TestPostChangeUserPasswordRouteHubAdmin"),
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
                username     = "orgUser2"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(0).orgId,
                password     = None,
                identityProvider ="External OAuth",
                username     = "externalUser"))
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
      (ResourceChangesTQ.filter(_.orgId startsWith "testPostChangeUserPasswordRoute").delete andThen
       OrgsTQ.filter(_.orgid startsWith "testPostChangeUserPasswordRoute").delete andThen
       UsersTQ.filter(_.organization === "root")
              .filter(_.username startsWith "TestPostChangeUserPasswordRouteHubAdmin").delete).transactionally
    ), AWAITDURATION)
  }
  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.run(
      (UsersTQ.filter(_.user === TESTUSERS(2).user).update(TESTUSERS(2)) andThen
       UsersTQ.filter(_.user === TESTUSERS(1).user).update(TESTUSERS(1))).transactionally
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

  private val normalRequestBody: ChangePwRequest = ChangePwRequest(newPassword = "newPassword")

  private val normalUsernameToUpdate = TESTUSERS(2).username

  test("POST /orgs/doesNotExist" + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- 404 not found") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.NOT_FOUND.intValue)
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + "doesNotExist" + ROUTE2 + " -- 404 not found") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + "doesNotExist" + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.NOT_FOUND.intValue)
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- empty body -- 400 bad input") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData("{}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val dbPass = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user ===TESTUSERS(2).user).result), AWAITDURATION).head.password
      assert(dbPass === TESTUSERS(2).password) //assert password hasn't changed
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- blank password -- 400 bad input") {
    withOauthDisabled {
      val requestBody: ChangePwRequest = ChangePwRequest(newPassword = "")
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val dbPass = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user ===TESTUSERS(2).user).result), AWAITDURATION).head.password
      assert(dbPass === TESTUSERS(2).password) //assert password hasn't changed
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- as root -- 201 OK") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
      val dbPass = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user ===TESTUSERS(2).user).result), AWAITDURATION).head.password
      //assert(BCrypt.checkpw(normalRequestBody.newPassword, dbPass.getOrElse(""))) //assert password was updated
    }
  }

  //currently a hub admin is able to change the password of any user (other than root). However, a hub admin is only supposed to be able to change the password of admins
  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- as hub admin -- 404 NOT FOUND") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.NOT_FOUND.intValue)
      val dbPass = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user ===TESTUSERS(2).user).result), AWAITDURATION).head.password
      assert(dbPass === TESTUSERS(2).password) //assert password hasn't changed
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + "orgAdmin" + ROUTE2 + " -- as hub admin -- 201 OK") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + "orgAdmin" + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
      val dbPass = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user ===TESTUSERS(1).user).result), AWAITDURATION).head.password
      //assert(BCrypt.checkpw(normalRequestBody.newPassword, dbPass.getOrElse(""))) //assert password was updated
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- as org admin -- 201 OK") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
      val dbPass = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user ===TESTUSERS(2).user).result), AWAITDURATION).head.password
      //assert(BCrypt.checkpw(normalRequestBody.newPassword, dbPass.getOrElse(""))) //assert password was updated
    }
  }

  test("POST /orgs/" + TESTORGS(1).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- as org admin in other org -- 403 ACCESS DENIED") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      val dbPass = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user ===TESTUSERS(3).user).result), AWAITDURATION).head.password
      assert(dbPass === TESTUSERS(3).password) //assert password hasn't changed
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- user updates self -- 201 OK") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
      val dbPass = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user ===TESTUSERS(2).user).result), AWAITDURATION).head.password
      //assert(BCrypt.checkpw(normalRequestBody.newPassword, dbPass.getOrElse(""))) //assert password was updated
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + "orgUser2" + ROUTE2 + " -- user tries to update other user -- 403 ACCESS DENIED") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + "orgUser2" + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      val dbPass = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user ===TESTUSERS(4).user).result), AWAITDURATION).head.password
      assert(dbPass === TESTUSERS(4).password) //assert password hasn't changed
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- as node -- 403 ACCESS DENIED") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(NODEAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      val dbPass = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user ===TESTUSERS(2).user).result), AWAITDURATION).head.password
      assert(dbPass === TESTUSERS(2).password) //assert password hasn't changed
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- as agbot -- 403 ACCESS DENIED") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      val dbPass = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user ===TESTUSERS(2).user).result), AWAITDURATION).head.password
      assert(dbPass === TESTUSERS(2).password) //assert password hasn't changed
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + "externalUser" + ROUTE2 + " -- external user password change disabled in OAuth mode -- 405 METHOD NOT ALLOWED") {
    withOauthEnabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + "externalUser" + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.NOT_ALLOWED.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("password.disabled.oauth.user"))
      val dbPass = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user ===TESTUSERS(5).user).result), AWAITDURATION).head.password
      assert(dbPass === TESTUSERS(5).password) //assert password hasn't changed
    }
  }

  test("POST /orgs/" + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2 + " -- internal user password change allowed in OAuth mode -- 201 OK") {
    withOauthEnabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE1 + normalUsernameToUpdate + ROUTE2).postData(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
      val dbPass = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user ===TESTUSERS(2).user).result), AWAITDURATION).head.password
      assert(Password.check(normalRequestBody.newPassword, dbPass.getOrElse("")))
    }
  }

}