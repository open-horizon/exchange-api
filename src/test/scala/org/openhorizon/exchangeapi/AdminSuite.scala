package org.openhorizon.exchangeapi

import org.openhorizon.exchangeapi.route.administration.{AdminHashpwResponse, AdminStatus, DeleteOrgChangesRequest, GetAdminOrgStatusResponse}

import scala.util.matching.Regex
import org.json4s.DefaultFormats
import org.openhorizon.exchangeapi.table.organization.OrgRow
import org.openhorizon.exchangeapi.utility.{ApiTime, Configuration}

import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer
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
import org.openhorizon.exchangeapi.route.administration.{AdminHashpwRequest, AdminHashpwResponse}
import org.openhorizon.exchangeapi.route.agreementbot.PutAgbotsRequest
import org.openhorizon.exchangeapi.route.deploymentpattern.PostPutPatternRequest
import org.openhorizon.exchangeapi.route.node.PutNodesRequest
import org.openhorizon.exchangeapi.route.organization.PostPutOrgRequest
import org.openhorizon.exchangeapi.route.service.PostPutServiceRequest
import org.openhorizon.exchangeapi.route.user.PostPutUsersRequest
import org.openhorizon.exchangeapi.table.deploymentpattern.{PServiceVersions, PServices}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiUtils, HttpCode}

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
  private val HUBADMINAUTH        = ("Authorization", "Basic " + ApiUtils.encode("root/"+hubadmin+":"+pw))
  private val orgsList            = List(ORGS.head)
  private var resources           = new ListBuffer[String]()

  implicit val FORMATS: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.

  val TESTORGS: Seq[OrgRow] =
    Seq(OrgRow(description = "AdminSuite Test Organization",
               heartbeatIntervals = "",
               label = "",
               lastUpdated = ApiTime.nowUTC,
               limits = "",
               orgId = "admin",
               orgType = "",
               tags = None))
  
  override def beforeAll(): Unit = {
    Http(URL + "/orgs/" + ORGS(0)).postData(write(PostPutOrgRequest(None, (ORGS(0)), "AdminSuite Test Organization", None, None, None))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString

    for (org <- ORGS) {
      for (user <- USERS) {
        val response = Http(URL + "/orgs/" + org + "/users/" + user).postData(write(PostPutUsersRequest(password="password", admin=user.endsWith("admin") && org!="root", hubAdmin=Some(org=="root"), email=user + "@host.domain"))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        assert(response.code == HttpCode.POST_OK.intValue)
      }
      var response = Http(URL + "/orgs/" + org + "/agbots/" + AGBOT).postData(write(PutAgbotsRequest("TokAbcDefGh1234", AGBOT, None, "password"))).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      assert(response.code == HttpCode.PUT_OK.intValue)
      response = Http(URL + "/orgs/" + org + "/services").postData(write(PostPutServiceRequest(SERVICE, None, public = true, None, URL + "/orgs/" + org + "/services/" + SERVICE, "0.0.1", "test-arch", "multiple", None, None, None, None, None, None, None, None ))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      assert(response.code == HttpCode.POST_OK.intValue)
      response = Http(URL + "/orgs/" + org + "/patterns/" + PATTERN).postData(write(PostPutPatternRequest(PATTERN, Some("AdminSuite Test Pattern"), None, List(PServices(URL + "/orgs/" + org + "/services/" + SERVICE, org, "test-arch", None, List(PServiceVersions("0.0.1", None, None, None, None)), None, None)), None, None, None))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      assert(response.code == HttpCode.POST_OK.intValue)
      response = Http(URL + "/orgs/" + org + "/nodes/" + NODE).postData(write(PutNodesRequest("TokAbcDefGh1234", NODE, None, org + "/" + PATTERN, None, None, None, None, "password", None, None))).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      assert(response.code == HttpCode.PUT_OK.intValue)
    }
  }

  override def afterAll(): Unit = {
    for (org <- ORGS) {
      for (user <- USERS) {
        val response = Http(URL + "/orgs/" + org + "/users/" + user).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
        assert(response.code == HttpCode.DELETED.intValue)
      }
      
      var response = Http(URL + "/orgs/" + org + "/agbots/" + AGBOT).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      assert(response.code == HttpCode.DELETED.intValue)
      response = Http(URL + "/orgs/" + org + "/nodes/" + NODE).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      assert(response.code == HttpCode.DELETED.intValue)
      response = Http(URL + "/orgs/" + org + "/patterns/" + PATTERN).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      assert(response.code == HttpCode.DELETED.intValue)
      response = Http(URL + "/orgs/" + org + "/services/" + ("/".r.replaceAllIn(("""(http[sS]{0,1}://(www.){0,1}){0,1}""".r.replaceFirstIn(URL, "")), "-")) + "-orgs-" + org + "-services-" + SERVICE + "_0.0.1_test-arch").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      assert(response.code == HttpCode.DELETED.intValue)
    }
    
    val response = Http(URL + "/orgs/" + ORGS(0)).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    assert(response.code == HttpCode.DELETED.intValue)
  }

  // =============== Hash a Password ===============
  for (org <- ORGS) {
    test("POST /admin/hashpw - " + org + "/" + AGBOT) {
      val input = AdminHashpwRequest("foobar")
      val response = Http(URL + "/admin/hashpw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + AGBOT + ":" + "TokAbcDefGh1234"))).asString
      info("http status code: " + response.code)
      info("body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    }
  }

  for (org <- ORGS) {
    test("POST /admin/hashpw - " + org + "/" + NODE) {
      val input = AdminHashpwRequest("foobar")
      val response = Http(URL + "/admin/hashpw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + NODE + ":" + "TokAbcDefGh1234"))).asString
      info("http status code: " + response.code)
      info("body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    }
  }

  for (org <- ORGS) {
    for (user <- USERS) {
      test("POST /admin/hashpw - " + org + "/" + user) {
        val input = AdminHashpwRequest("foobar")
        val response = Http(URL + "/admin/hashpw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + user + ":" + "password"))).asString
        info("http status code: " + response.code)
        info("body: " + response.body)
        assert(response.code === HttpCode.POST_OK.intValue)
        val postResp = parse(response.body).extract[AdminHashpwResponse]
        assert(Password.check(input.password, postResp.hashedPassword))
      }
    }
  }

  test("POST /admin/hashpw - root/root") {
    val input = AdminHashpwRequest("foobar")
    val response = Http(URL + "/admin/hashpw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val postResp = parse(response.body).extract[AdminHashpwResponse]
    assert(Password.check(input.password, postResp.hashedPassword))
  }

  test("POST /admin/hashpw - root/root - Invaild Credentials") {
    val input = AdminHashpwRequest("foobar")
    val response = Http(URL + "/admin/hashpw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + "invaildcredentials"))).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
  }

  // =============== Log ===============
  /** Set log level 
  ignore("POST /admin/loglevel") {
    val input = AdminLogLevelRequest("info")
    val response = Http(URL+"/admin/loglevel").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(postResp.code === ApiRespType.OK)
  } */

  /** Set invalid log level 
  ignore("POST /admin/loglevel - bad") {
    val input = AdminLogLevelRequest("foobar")
    val response = Http(URL+"/admin/loglevel").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(postResp.code === ApiRespType.BAD_INPUT)
  } */

  // =============== Reload Configuration File ===============
  for (org <- ORGS) {
    test("POST /admin/reload - " + org + "/" + AGBOT) {
      val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + AGBOT + ":" + "TokAbcDefGh1234"))).asString
      info("http status code: " + response.code)
      info("body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      val postResp = parse(response.body).extract[ApiResponse]
      assert(ApiRespType.ACCESS_DENIED === postResp.code)
    }
  }

  for (org <- ORGS) {
    test("POST /admin/reload - " + org + "/" + NODE) {
      val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + NODE + ":" + "TokAbcDefGh1234"))).asString
      info("http status code: " + response.code)
      info("body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      val postResp = parse(response.body).extract[ApiResponse]
      assert(ApiRespType.ACCESS_DENIED === postResp.code)
    }
  }

  for (org <- ORGS) {
    for (user <- USERS) {
      test("POST /admin/reload - " + org + "/" + user) {
        val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + user + ":" + "password"))).asString
        info("http status code: " + response.code)
        info("body: " + response.body)
        assert(response.code === HttpCode.ACCESS_DENIED.intValue)
        val postResp = parse(response.body).extract[ApiResponse]
        assert(ApiRespType.ACCESS_DENIED === postResp.code)
      }
    }
  }

  test("POST /admin/reload - root/root") {
    val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(ROOTAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(ApiRespType.OK === postResp.code)
  }

  test("POST /admin/reload - root/root - Invaild Credentials") {
    val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + "invaildcredentials"))).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
  }

  // =============== Get Exchange Status ===============
  for (org <- ORGS) {
    test("GET /admin/status - " + org + "/" + AGBOT) {
      val response = Http(URL + "/admin/status").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + AGBOT + ":" + "TokAbcDefGh1234"))).asString
      info("http status code: " + response.code)
      info("body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    }
  }

  for (org <- ORGS) {
    test("GET /admin/status - " + org + "/" + NODE) {
      val response = Http(URL + "/admin/status").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + NODE + ":" + "TokAbcDefGh1234"))).asString
      info("http status code: " + response.code)
      info("body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    }
  }

  for (org <- ORGS) {
    for (user <- USERS) {
      test("GET /admin/status - " + org + "/" + user) {
        val response = Http(URL + "/admin/status").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + user + ":" + "password"))).asString
        info("http status code: " + response.code)
        info("body: " + response.body)
        assert(response.code === HttpCode.OK.intValue)
        val getResp = parse(response.body).extract[AdminStatus]
        assert(getResp.msg.contains("operating normally"))
      }
    }
  }

  test("GET /admin/status - root/root") {
    val response = Http(URL + "/admin/status").headers(ACCEPT).headers(ROOTAUTH).asString
    info("http status code: " + response.code)
    // info("headers: " + response.headers)
    info("body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getResp = parse(response.body).extract[AdminStatus]
    assert(getResp.msg.contains("operating normally"))
  }

  test("GET /admin/status - root/root - Invaild Credentials") {
    val response = Http(URL + "/admin/status").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + "invaildcredentials"))).asString
    info("http status code: " + response.code)
    // info("headers: " + response.headers)
    info("body: " + response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
  }

  // =============== Get Exchange Org-Specific Status ===============
  test("GET /admin/orgstatus - root/root") {
    val response = Http(URL + "/admin/orgstatus").headers(ACCEPT).headers(ROOTAUTH).asString
    info("http status code: " + response.code)
    // info("headers: " + response.headers)
    info("body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getResp = parse(response.body).extract[GetAdminOrgStatusResponse]
    assert(getResp.msg.contains("operating normally"))
    assert(!getResp.nodes.isEmpty) // nodes should be visible
    assert(getResp.nodes.size >= ORGS.size) // account for orgs that might already exist in db, but there should be at least 2
    assert(getResp.nodes.contains(ORGS.head))
    assert(getResp.nodes.contains(ORGS(1)))
  }

  test("GET /admin/orgstatus - normal user without access Access Denied") {
    val response = Http(URL + "/admin/orgstatus").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(ORGS.head + "/" + USERS(1) + ":" + "password"))).asString
    info("http status code: " + response.code)
    // info("headers: " + response.headers)
    info("body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    assert(response.body.contains("Access denied"))
  }

  test("GET /admin/orgstatus - org admin") {
    val response = Http(URL + "/admin/orgstatus").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(ORGS.head + "/" + USERS.head + ":" + "password"))).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getResp = parse(response.body).extract[GetAdminOrgStatusResponse]
    assert(getResp.msg.contains("operating normally"))
    assert(!getResp.nodes.isEmpty) // nodes should be visible
    assert(getResp.nodes.size >= ORGS.size) // account for orgs that might already exist in db, but there should be at least 2
    assert(getResp.nodes.contains(ORGS.head))
    assert(getResp.nodes.contains(ORGS(1)))
  }

  // make a hubadmin
  test("POST /orgs/root/users/" + hubadmin ) {
    val input = PostPutUsersRequest(pw, admin = false, Some(true), hubadmin + "@hotmail.com")
    val response = Http(urlRootOrg + "/users/" + hubadmin).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("GET /admin/orgstatus - hub admin") {
    val response = Http(URL + "/admin/orgstatus").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("http status code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getResp = parse(response.body).extract[GetAdminOrgStatusResponse]
    assert(getResp.msg.contains("operating normally"))
    assert(!getResp.nodes.isEmpty) // nodes should be visible
    assert(getResp.nodes.size >= ORGS.size) // account for orgs that might already exist in db, but there should be at least 2
    assert(getResp.nodes.contains(ORGS.head))
    assert(getResp.nodes.contains(ORGS(1)))
  }

  // delete the hubadmin
  test("DELETE /orgs/root/users/" + hubadmin ) {
    val response = Http(urlRootOrg + "/users/" + hubadmin).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  // =============== Get Exchange API Version ===============
  /** API is open to any user/call. */
  test("GET /admin/version") {
    val response = Http(URL + "/admin/version").headers(ACCEPTTEXT).asString
    info("http status code: " + response.code)
    info("version: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
  }

  test("DELETE root org changes") {
    val res = List(USERS.head, USERS(1), AGBOT, PATTERN, NODE, ("/".r.replaceAllIn(("""(http[sS]{0,1}://(www.){0,1}){0,1}""".r.replaceFirstIn(URL, "")), "-")) + "-orgs-" + ORGS(1) + "-services-" + SERVICE + "_0.0.1_test-arch", hubadmin)
    val input = DeleteOrgChangesRequest(res)
    val response = Http(URL+"/orgs/root/changes/cleanup").postData(write(input)).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("DELETE org changes") {
    val input = DeleteOrgChangesRequest(List())
    val response = Http(URL+"/orgs/"+ORGS.head+"/changes/cleanup").postData(write(input)).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }
}
