package org.openhorizon.exchangeapi

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.openhorizon.exchangeapi.route.administration.{AdminOrgStatus, AdminStatus, DeleteOrgChangesRequest}

import scala.util.matching.Regex
import org.json4s.DefaultFormats
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, Configuration, DatabaseConnection}
import scalaj.http.HttpResponse
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.util.UUID
import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
//import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jvalue2extractable
import org.json4s.native.Serialization.write
import org.json4s.convertToJsonInput
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.route.agreementbot.PutAgbotsRequest
import org.openhorizon.exchangeapi.route.deploymentpattern.PostPutPatternRequest
import org.openhorizon.exchangeapi.route.node.PutNodesRequest
import org.openhorizon.exchangeapi.route.organization.PostPutOrgRequest
import org.openhorizon.exchangeapi.route.service.PostPutServiceRequest
import org.openhorizon.exchangeapi.route.user.PostPutUsersRequest
import org.openhorizon.exchangeapi.table.deploymentpattern.{PServiceVersions, PServices}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiUtils}

import scalaj.http.Http

//import scala.collection.immutable._
//import java.time._

/**
 * Tests for the /admin routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class AdminSuite extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT              = ("Accept", "application/json")
  private val ACCEPTTEXT          = ("Accept", "text/plain")
  private val AGBOT: String       = "agbot"
  private val CONTENT             = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val NODE: String        = "node"
  private val PATTERN: String     = "pattern"
  private val ROOTAUTH            = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val SERVICE: String     = "service"
  private val URL                 = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1"
  private val USERS: List[String] = List("admin", "user")
  private val ORGS: List[String]  = List("adminsuite", "root")
  private val hubadmin            = "AdminSuitTestsHubAdmin"
  private val urlRootOrg          =  URL + "/orgs/root"
  private val pw                  = "password"
  //private val HUBADMINAUTH        = ("Authorization", "Basic " + ApiUtils.encode("root/"+hubadmin+":"+pw))
  private val orgsList            = List(ORGS.head)
  private var resources           = new ListBuffer[String]()

  implicit val FORMATS: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.
  val timestamp = ApiTime.nowUTCTimestamp
  
  val rootUser: UUID = Await.result(DBCONNECTION.run(Compiled(UsersTQ.filter(users => (users.organization ++ "/" ++ users.username) === "root/root").map(_.user).take(1)).result.head.transactionally), AWAITDURATION)

  val TESTORGS: Seq[OrgRow] =
    Seq(OrgRow(description = "AdminSuite Test Organization",
               heartbeatIntervals = "",
               label = "",
               lastUpdated = ApiTime.nowUTC,
               limits = "",
               orgId = "adminsuite",
               orgType = "",
               tags = None))
  val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(createdAt    = timestamp,
                email        = Option("AdminSuitTestsHubAdmin@host.domain"),
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = timestamp,
                organization = "root",
                password     = Option(Password.hash(pw)),
                username     = "AdminSuitTestsHubAdmin"),
        UserRow(createdAt    = timestamp,
                email        = Option("admin@host.domain"),
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = timestamp,
                organization = "adminsuite",
                password     = Option(Password.hash(pw)),
                username     = "admin"),
        UserRow(createdAt    = timestamp,
                email        = Option("user@host.domain"),
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = timestamp,
                organization = "adminsuite",
                password     = Option(Password.hash(pw)),
                username     = "user"))
  val TESTAGBOTS: Seq[AgbotRow] =
    Seq(/*AgbotRow(id = "root/adminsuiteagbot",
                 lastHeartbeat = ApiTime.nowUTC,
                 msgEndPoint = "",
                 name = "",
                 orgid = "root",
                 owner = TESTUSERS(2).user,
                 publicKey = "password",
                 token = Password.fastHash("TokAbcDefGh1234")),*/
        AgbotRow(id = "adminsuite/agbot",
                 lastHeartbeat = ApiTime.nowUTC,
                 msgEndPoint = "",
                 name = "",
                 orgid = "adminsuite",
                 owner = TESTUSERS(2).user,
                 publicKey = "password",
                 token = Password.hash(pw)))
  private val TESTNODES: Seq[NodeRow] =
    Seq(/*NodeRow(arch               = "",
                id                 = "root/adminsuitenode",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(ApiTime.nowUTC),
                lastUpdated        = ApiTime.nowUTC,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = "root",
                owner              = TESTUSERS(2).user,
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = Password.fastHash("TokAbcDefGh1234"),
                userInput          = ""),*/
        NodeRow(arch               = "",
                id                 = "adminsuite/node",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(ApiTime.nowUTC),
                lastUpdated        = ApiTime.nowUTC,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = "adminsuite",
                owner              = TESTUSERS(2).user,
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = Password.hash(pw),
                userInput          = ""))
  
  val HUBADMINAUTH = ("Authorization","Basic " + ApiUtils.encode(TESTUSERS(0).organization + "/" + TESTUSERS(0).username + ":" + pw))
  val ORGADMINAUTH = ("Authorization","Basic " + ApiUtils.encode(TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + pw))
  val USERAUTH     = ("Authorization","Basic " + ApiUtils.encode(TESTUSERS(2).organization + "/" + TESTUSERS(2).username + ":" + pw))
  val AGBOTAUTH    = ("Authorization","Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + pw))
  val NODEAUTH     = ("Authorization","Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + pw))
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGS) andThen
                                 (UsersTQ ++= TESTUSERS) andThen
                                 (AgbotsTQ ++= TESTAGBOTS) andThen
                                 (NodesTQ ++= TESTNODES)), AWAITDURATION)
    
    
    //for (org <- ORGS) {
      //for (user <- USERS) {
      //  val response = Http(URL + "/orgs/" + org + "/users/" + user).postData(write(PostPutUsersRequest(password="password", admin=user.endsWith("admin") && org!="root", hubAdmin=Some(org=="root"), email=user + "@host.domain"))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      //  assert(response.code == StatusCodes.Created.intValue)
      //}
      //Http(URL + "/orgs/" + org + "/agbots/" + AGBOT).postData(write(PutAgbotsRequest("", AGBOT, None, "password"))).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      //assert(response.code == StatusCodes.Created.intValue)
      //var response = Http(URL + "/orgs/" + org + "/services").postData(write(PostPutServiceRequest(SERVICE, None, public = true, None, URL + "/orgs/" + org + "/services/" + SERVICE, "0.0.1", "test-arch", "multiple", None, None, None, None, None, None, None, None ))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      //assert(response.code == StatusCodes.Created.intValue)
      //response = Http(URL + "/orgs/" + org + "/patterns/" + PATTERN).postData(write(PostPutPatternRequest(PATTERN, Some("AdminSuite Test Pattern"), None, List(PServices(URL + "/orgs/" + org + "/services/" + SERVICE, org, "test-arch", None, List(PServiceVersions("0.0.1", None, None, None, None)), None, None)), None, None, None))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      //assert(response.code == StatusCodes.Created.intValue)
      //response = Http(URL + "/orgs/" + org + "/nodes/" + NODE).postData(write(PutNodesRequest("TokAbcDefGh1234", NODE, None, org + "/" + PATTERN, None, None, None, None, Option("password"), None, None))).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      //assert(response.code == StatusCodes.Created.intValue)
    //}
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ.filter(organizations => organizations.orgid === "adminsuite").delete andThen
                                  UsersTQ.filter(users => users.user === TESTUSERS(0).user).delete andThen
                                  ResourceChangesTQ.filter(log => log.orgId === "adminsuite").delete)), AWAITDURATION)
    
    val response: HttpResponse[String] = Http(sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/admin/clearauthcaches").method("POST").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
      
     // var response = Http(URL + "/orgs/" + org + "/agbots/" + AGBOT).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
     // assert(response.code == StatusCodes.NoContent.intValue)
     // response = Http(URL + "/orgs/" + org + "/nodes/" + NODE).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
     // assert(response.code == StatusCodes.NoContent.intValue)
     // response = Http(URL + "/orgs/" + org + "/patterns/" + PATTERN).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
     // assert(response.code == StatusCodes.NoContent.intValue)
     // response = Http(URL + "/orgs/" + org + "/services/" + ("/".r.replaceAllIn(("""(http[sS]{0,1}://(www.){0,1}){0,1}""".r.replaceFirstIn(URL, "")), "-")) + "-orgs-" + org + "-services-" + SERVICE + "_0.0.1_test-arch").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
     // assert(response.code == StatusCodes.NoContent.intValue)
    
    //val response = Http(URL + "/orgs/" + ORGS(0)).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    //assert(response.code == StatusCodes.NoContent.intValue)
  }

  // =============== Log ===============
  /** Set log level 
  ignore("POST /admin/loglevel") {
    val input = AdminLogLevelRequest("info")
    val response = Http(URL+"/admin/loglevel").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(postResp.code === ApiRespType.OK)
  } */

  /** Set invalid log level 
  ignore("POST /admin/loglevel - bad") {
    val input = AdminLogLevelRequest("foobar")
    val response = Http(URL+"/admin/loglevel").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === StatusCodes.BadRequest.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(postResp.code === ApiRespType.BAD_INPUT)
  } */

  // =============== Reload Configuration File ===============
  /*test("POST /admin/reload - " + "root" + "/adminsuite" + AGBOT) {
    val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("root/adminsuite" + AGBOT + ":" + "TokAbcDefGh1234"))).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(ApiRespType.ACCESS_DENIED === postResp.code)
  }*/
  
  test(s"POST /admin/reload - ${TESTAGBOTS(0).id}") {
    val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(ApiRespType.ACCESS_DENIED === postResp.code)
  }
  
  test(s"POST /admin/reload - ${TESTNODES(0).id}") {
    val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(NODEAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(ApiRespType.ACCESS_DENIED === postResp.code)
  }
  
  test(s"POST /admin/reload - ${TESTUSERS(0).organization}/${TESTUSERS(0).username}") {
    val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(ApiRespType.ACCESS_DENIED === postResp.code)
  }
  
  test(s"POST /admin/reload - ${TESTUSERS(1).organization}/${TESTUSERS(1).username}") {
    val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(ORGADMINAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(ApiRespType.ACCESS_DENIED === postResp.code)
  }
  
  test(s"POST /admin/reload - ${TESTUSERS(2).organization}/${TESTUSERS(2).username}") {
    val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(USERAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(ApiRespType.ACCESS_DENIED === postResp.code)
  }
  
  test("POST /admin/reload - root/root - Invaild Credentials") {
    val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + "invaildcredentials"))).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.Unauthorized.intValue)
  }

  test("POST /admin/reload - root/root") {
    val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(ROOTAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.Created.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(ApiRespType.OK === postResp.code)
  }

  // =============== Get Exchange Status ===============
  test(s"GET /admin/status - ${TESTAGBOTS(0).id}") {
    val response = Http(URL + "/admin/status").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
  }
  
  test(s"GET /admin/status - ${TESTNODES(0).id}") {
    val response = Http(URL + "/admin/status").headers(ACCEPT).headers(NODEAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
  }
  
  test(s"GET /admin/status - ${TESTUSERS(0).organization}/${TESTUSERS(0).username}") {
    val response = Http(URL + "/admin/status").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
    val getResp = parse(response.body).extract[AdminStatus]
    assert(getResp.msg.contains("operating normally"))
  }
  
  test(s"GET /admin/status - ${TESTUSERS(1).organization}/${TESTUSERS(1).username}") {
    val response = Http(URL + "/admin/status").headers(ACCEPT).headers(ORGADMINAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
    val getResp = parse(response.body).extract[AdminStatus]
    assert(getResp.msg.contains("operating normally"))
  }
  
  test(s"GET /admin/status - ${TESTUSERS(2).organization}/${TESTUSERS(2).username}") {
    val response = Http(URL + "/admin/status").headers(ACCEPT).headers(USERAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
    val getResp = parse(response.body).extract[AdminStatus]
    assert(getResp.msg.contains("operating normally"))
  }
  
  test("GET /admin/status - root/root - Invaild Credentials") {
    val response = Http(URL + "/admin/status").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + "invaildcredentials"))).asString
    info("http status code: " + response.code)
    // info("headers: " + response.headers)
    info("body: " + response.body)
    assert(response.code === StatusCodes.Unauthorized.intValue)
  }

  test("GET /admin/status - root/root") {
    val response = Http(URL + "/admin/status").headers(ACCEPT).headers(ROOTAUTH).asString
    info("http status code: " + response.code)
    // info("headers: " + response.headers)
    info("body: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
    val getResp = parse(response.body).extract[AdminStatus]
    assert(getResp.msg.contains("operating normally"))
  }
  
  // =============== Get Exchange Org-Specific Status ===============
  test("GET /admin/orgstatus - root/root") {
    val response = Http(URL + "/admin/orgstatus").headers(ACCEPT).headers(ROOTAUTH).asString
    info("http status code: " + response.code)
    // info("headers: " + response.headers)
    info("body: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
    val getResp = parse(response.body).extract[AdminOrgStatus]
    assert(getResp.msg.contains("operating normally"))
    assert(getResp.nodes.nonEmpty) // nodes should be visible
    assert(getResp.nodes.size >= ORGS.size) // account for orgs that might already exist in db, but there should be at least 2
    assert(getResp.nodes.contains(ORGS.head))
    assert(getResp.nodes.contains(ORGS(1)))
  }

  test("GET /admin/orgstatus - normal user without access Access Denied") {
    val response = Http(URL + "/admin/orgstatus").headers(ACCEPT).headers(USERAUTH).asString
    info("http status code: " + response.code)
    // info("headers: " + response.headers)
    info("body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
    assert(response.body.contains("Access denied"))
  }

  test("GET /admin/orgstatus - org admin") {
    val response = Http(URL + "/admin/orgstatus").headers(ACCEPT).headers(ORGADMINAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
    val getResp = parse(response.body).extract[AdminOrgStatus]
    assert(getResp.msg.contains("operating normally"))
    assert(getResp.nodes.nonEmpty) // nodes should be visible
    assert(getResp.nodes.size >= ORGS.size) // account for orgs that might already exist in db, but there should be at least 2
    assert(getResp.nodes.contains(ORGS.head))
    assert(!getResp.nodes.contains(ORGS(1)))
  }

  test("GET /admin/orgstatus - hub admin") {
    val response = Http(URL + "/admin/orgstatus").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
    val getResp = parse(response.body).extract[AdminOrgStatus]
    assert(getResp.msg.contains("operating normally"))
    assert(getResp.nodes.nonEmpty) // nodes should be visible
    assert(getResp.nodes.size >= ORGS.size) // account for orgs that might already exist in db, but there should be at least 2
    assert(getResp.nodes.contains(ORGS.head))
    assert(getResp.nodes.contains(ORGS(1)))
  }

  // =============== Get Exchange API Version ===============
  /** API is open to any user/call. */
  test("GET /admin/version") {
    val response = Http(URL + "/admin/version").headers(ACCEPTTEXT).asString
    info("http status code: " + response.code)
    info("version: " + response.body)
    assert(response.code === StatusCodes.OK.intValue)
  }
}
