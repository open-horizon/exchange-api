package org.openhorizon.exchangeapi.route.user

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
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

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPatchUserRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

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
  
  val rootUser: UUID = Await.result(DBCONNECTION.run(Compiled(UsersTQ.filter(users => users.organization === "root" && users.username === "root").map(_.user).take(1)).result.head), AWAITDURATION)

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

  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "TestPatchUserRouteHubAdmin"),
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
      (ResourceChangesTQ.filter(_.orgId startsWith "testPatchUserRoute").delete andThen
       OrgsTQ.filter(_.orgid startsWith "testPatchUserRoute").delete andThen
       UsersTQ.filter(_.organization === "root")
              .filter(_.username startsWith "TestPatchUserRouteHubAdmin").delete).transactionally
    ), AWAITDURATION)
  }

  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.run(
      (UsersTQ.filter(_.user === TESTUSERS(0).user).update(TESTUSERS(0)) andThen
       UsersTQ.filter(_.user === TESTUSERS(1).user).update(TESTUSERS(1)) andThen
       UsersTQ.filter(_.user === TESTUSERS(2).user).update(TESTUSERS(2)) andThen
       UsersTQ.filter(_.user === TESTUSERS(4).user).update(TESTUSERS(4))).transactionally
    ), AWAITDURATION)
    
    val response: HttpResponse[String] = Http(BASEURL + "/admin/clearauthcaches").method("POST").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
  }

  def assertNoChanges(user: UserRow): Unit = {
    val dbUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === user.user).result), AWAITDURATION).head
    assert(dbUser.username === user.username)
    assert(dbUser.organization === user.organization)
    assert(dbUser.password === user.password)
    assert(dbUser.isOrgAdmin === user.isOrgAdmin)
    assert(dbUser.isHubAdmin === user.isHubAdmin)
    assert(dbUser.email === user.email)
    assert(dbUser.modifiedAt.toString.nonEmpty)
    assert(dbUser.modified_by === user.modified_by)
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
  
  @JsonInclude(Include.NON_ABSENT) // Hides key/value pairs that are None.
  private val normalRequestBody: PatchUsersRequest = PatchUsersRequest(
    password = Some("newPassword"),
    admin = None,
    hubAdmin = None,
    email = None
  )

  private val normalUsernameToUpdate = TESTUSERS(2).username
 
  test("PATCH /orgs/doesNotExist" + ROUTE + normalUsernameToUpdate + " -- 404 not found") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE + normalUsernameToUpdate).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.NOT_FOUND.intValue)
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + "doesNotExist -- 404 not found") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "doesNotExist").postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.NOT_FOUND.intValue)
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- empty body -- 400 bad input") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData("{}").method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      assertNoChanges(TESTUSERS(2))
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- invalid body -- 400 bad input") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData("{\"invalidKey\":\"invalidValue\"}").method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      assertNoChanges(TESTUSERS(2))
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- blank password -- as org admin -- 201 OK") {
    withOauthDisabled {
      val requestBody: PatchUsersRequest = PatchUsersRequest(
        password = Some(""),
        admin = None,
        hubAdmin = None,
        email = None
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
      val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(2).user).result), AWAITDURATION).head
      assert(newUser.username === TESTUSERS(2).username)
      assert(newUser.organization === TESTORGS(0).orgId)
      assert(newUser.modified_by === Option(TESTUSERS(1).user)) //updated by root
      assert(newUser.password.isEmpty)
      assert(newUser.isOrgAdmin === TESTUSERS(2).isOrgAdmin)
      assert(newUser.isHubAdmin === TESTUSERS(2).isHubAdmin)
      assert(newUser.email === TESTUSERS(2).email)
      assert(newUser.modifiedAt.after(TESTUSERS(2).modifiedAt))
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- blank password -- as root -- 201 OK") {
    withOauthDisabled {
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
      val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(2).user).result), AWAITDURATION).head
      assert(newUser.username === TESTUSERS(2).username)
      assert(newUser.organization === TESTORGS(0).orgId)
      assert(newUser.modified_by === Option(rootUser)) //updated by root
      assert(newUser.password.isEmpty)
      assert(newUser.isOrgAdmin === TESTUSERS(2).isOrgAdmin)
      assert(newUser.isHubAdmin === TESTUSERS(2).isHubAdmin)
      assert(newUser.email === TESTUSERS(2).email)
      assert(newUser.modifiedAt.after(TESTUSERS(2).modifiedAt))
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- user tries to make self admin -- 400 bad input") {
    withOauthDisabled {
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
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- org admin tries to update user to hub admin -- 400 bad input") {
    withOauthDisabled {
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
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- try to update user to hub admin -- 400 bad input") {
    withOauthDisabled {
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
  }

  test("PATCH /orgs/root" + ROUTE + "TestPatchUserRouteHubAdmin -- root turns hub admin into a normal user -- 201 OK") {
    withOauthDisabled {
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
      val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(0).user).result), AWAITDURATION).head
      assert(newUser.username === TESTUSERS(0).username)
      assert(newUser.organization === TESTUSERS(0).organization)
      assert(newUser.modified_by.getOrElse("") === rootUser) //updated by root
      assert(newUser.password === TESTUSERS(0).password)
      assert(newUser.isOrgAdmin === TESTUSERS(0).isOrgAdmin)
      assert(newUser.isHubAdmin === requestBody.hubAdmin.get)
      assert(newUser.email === TESTUSERS(0).email)
      assert(newUser.modifiedAt.after(TESTUSERS(0).modifiedAt))
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + "orgAdmin -- hub admin tries to make org admin a regular user -- 201 OK") {
    withOauthDisabled {
      val requestBody: PatchUsersRequest = PatchUsersRequest(
        password = None,
        admin = Some(false),
        hubAdmin = None,
        email = None
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "orgAdmin").postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
      //insure new user is in DB correctly
      val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(1).user).result), AWAITDURATION).head
      assert(newUser.username === TESTUSERS(1).username)
      assert(newUser.organization === TESTUSERS(1).organization)
      assert(newUser.modified_by === Option(TESTUSERS(0).user))
      assert(newUser.password === TESTUSERS(1).password)
      assert(newUser.isOrgAdmin === requestBody.admin.get)
      assert(newUser.isHubAdmin === TESTUSERS(1).isHubAdmin)
      assert(newUser.email === TESTUSERS(1).email)
      assert(newUser.modifiedAt.after(TESTUSERS(1).modifiedAt))
    }
  }

  test("PATCH /orgs/root" + ROUTE + "TestPatchUserRouteHubAdmin -- try to give admin privileges to hub admin -- 404 not found") {
    withOauthDisabled {
      val requestBody: PatchUsersRequest = PatchUsersRequest(
        password = None,
        admin = Some(true),
        hubAdmin = None,
        email = None
      )
      val response: HttpResponse[String] = Http(URL + "root" + ROUTE + "TestPatchUserRouteHubAdmin").postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.NOT_FOUND.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assertNoChanges(TESTUSERS(0))
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + "orgUser2 -- as org admin -- 201 OK") {
    withOauthDisabled {
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
      val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(4).user).result), AWAITDURATION).head
      assert(newUser.username === TESTUSERS(4).username)
      assert(newUser.organization === TESTUSERS(4).organization)
      assert(newUser.modified_by === Option(TESTUSERS(1).user)) //updated by org admin
      assert(newUser.password === TESTUSERS(4).password)
      assert(newUser.isOrgAdmin === requestBody.admin.get)
      assert(newUser.isHubAdmin === TESTUSERS(4).isHubAdmin)
      assert(newUser.email === TESTUSERS(4).email)
      assert(newUser.modifiedAt.after(TESTUSERS(4).modifiedAt))
    }
  }

  test("PATCH /orgs/" + TESTORGS(1).orgId + ROUTE + normalUsernameToUpdate + " -- org admin tries to update user in other org -- 403 access denied") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      assertNoChanges(TESTUSERS(3))
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- regular user updates self -- 201 OK") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
      //insure new user is in DB correctly
      val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(2).user).result), AWAITDURATION).head
      assert(newUser.username === TESTUSERS(2).username)
      assert(newUser.organization === TESTUSERS(2).organization)
      assert(newUser.modified_by === Option(TESTUSERS(2).user)) //updated by self
      //assert(Password.check(normalRequestBody.password.get, newUser.password.getOrElse("")))
      assert(newUser.isOrgAdmin === TESTUSERS(2).isOrgAdmin)
      assert(newUser.isHubAdmin === TESTUSERS(2).isHubAdmin)
      assert(newUser.email === TESTUSERS(2).email)
      assert(newUser.modifiedAt.after(TESTUSERS(2).modifiedAt))
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + "orgUser2 -- regular user tries to update other user -- 403 access denied") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "orgUser2").postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1USERAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      assertNoChanges(TESTUSERS(4))
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- as agbot -- 403 access denied") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(AGBOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      assertNoChanges(TESTUSERS(2))
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- as node -- 403 access denied") {
    withOauthDisabled {
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(normalRequestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(NODEAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      assertNoChanges(TESTUSERS(2))
    }
  }

  //when multiple attributes are provided, it is undefined which gets precedence, but functionally the precedence is: password, admin, hubAdmin, email
  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- multiple attributes provided -- 201 OK") {
    withOauthDisabled {
      val requestBody: PatchUsersRequest = PatchUsersRequest(
        password = Some("newPassword"),
        admin = Some(false),
        hubAdmin = Some(false),
        email = Some("newEmail@ibm.com")
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- update email -- 201 OK") {
    withOauthDisabled {
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
      val newUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(2).user).result), AWAITDURATION).head
      assert(newUser.username === TESTUSERS(2).username)
      assert(newUser.organization === TESTUSERS(2).organization)
      assert(newUser.modified_by.getOrElse("") === rootUser) //updated by root
      assert(newUser.password === TESTUSERS(2).password)
      assert(newUser.isOrgAdmin === TESTUSERS(2).isOrgAdmin)
      assert(newUser.isHubAdmin === TESTUSERS(2).isHubAdmin)
      assert(newUser.email === requestBody.email)
      assert(newUser.modifiedAt.after(TESTUSERS(2).modifiedAt))
    }
  }
  // OAuth mode tests
  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- OAuth mode blocks password modification -- 400 bad input") {
    withOauthEnabled {
      val requestBody: PatchUsersRequest = PatchUsersRequest(
        password = Some("newPassword"),
        admin = None,
        hubAdmin = None,
        email = None
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("user.attr.not.allowed.oauth", "password"))
      assertNoChanges(TESTUSERS(2))
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- OAuth mode blocks email modification -- 400 bad input") {
    withOauthEnabled {
      val requestBody: PatchUsersRequest = PatchUsersRequest(
        password = None,
        admin = None,
        hubAdmin = None,
        email = Some("newEmail@example.com")
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.BAD_INPUT.intValue)
      val responseBody: ApiResponse = JsonMethods.parse(response.body).extract[ApiResponse]
      assert(responseBody.msg === ExchMsg.translate("user.attr.not.allowed.oauth", "email"))
      assertNoChanges(TESTUSERS(2))
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- OAuth mode allows admin modification -- 201 OK") {
    withOauthEnabled {
      val requestBody: PatchUsersRequest = PatchUsersRequest(
        password = None,
        admin = Some(true),
        hubAdmin = None,
        email = None
      )
      val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate).postData(Serialization.write(requestBody)).method("PATCH").headers(ACCEPT).headers(CONTENT).headers(ORG1ADMINAUTH).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
      
      val updatedUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(2).user).result), AWAITDURATION).head
      assert(updatedUser.isOrgAdmin === true)
      assert(updatedUser.email === TESTUSERS(2).email) // Should remain unchanged
      assert(updatedUser.password === TESTUSERS(2).password) // Should remain unchanged
    }
  }

  test("PATCH /orgs/root" + ROUTE + "TestPatchUserRouteHubAdmin -- OAuth mode allows hubAdmin modification -- 201 OK") {
    withOauthEnabled {
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
      
      val updatedUser: UserRow = Await.result(DBCONNECTION.run(UsersTQ.filter(_.user === TESTUSERS(0).user).result), AWAITDURATION).head
      assert(updatedUser.isHubAdmin === false)
      assert(updatedUser.email === TESTUSERS(0).email) // Should remain unchanged
      assert(updatedUser.password === TESTUSERS(0).password) // Should remain unchanged
    }
  }

  test("PATCH /orgs/" + TESTORGS(0).orgId + ROUTE + normalUsernameToUpdate + " -- OAuth mode still validates admin permissions -- 400 bad input") {
    withOauthEnabled {
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
  }

}
