package exchangeapi

import org.json4s.DefaultFormats
//import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jvalue2extractable
import org.json4s.native.Serialization.write
import org.json4s.string2JsonInput
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import com.horizon.exchangeapi.AdminHashpwRequest
import com.horizon.exchangeapi.AdminHashpwResponse
import com.horizon.exchangeapi.ApiRespType
import com.horizon.exchangeapi.ApiResponse
import com.horizon.exchangeapi.ApiUtils
import com.horizon.exchangeapi.GetAdminStatusResponse
import com.horizon.exchangeapi.HttpCode
import com.horizon.exchangeapi.Password
import com.horizon.exchangeapi.PostPutOrgRequest
import com.horizon.exchangeapi.PostPutPatternRequest
import com.horizon.exchangeapi.PostPutServiceRequest
import com.horizon.exchangeapi.PostPutUsersRequest
import com.horizon.exchangeapi.PutAgbotsRequest
import com.horizon.exchangeapi.PutNodesRequest
import com.horizon.exchangeapi.Role
import com.horizon.exchangeapi.tables.PServiceVersions
import com.horizon.exchangeapi.tables.PServices

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
class AdminSuite extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT              = ("Accept", "application/json")
  private val ACCEPTTEXT          = ("Accept", "text/plain")
  private val AGBOT: String       = "agbot"
  private val CONTENT             = ("Content-Type", "application/json")
  private val NODE: String        = "node"
  private val PATTERN: String     = "pattern"
  private val ROOTAUTH            = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "") /* need to put this root pw in config.json */))
  private val SERVICE: String     = "service"
  private val URL                 = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1"
  private val USERS: List[String] = List("admin", "user")
  private val ORGS: List[String]  = List("adminsuite", "root")

  implicit val FORMATS            = DefaultFormats // Brings in default date formats etc.

  override def beforeAll() {
    Http(URL + "/orgs/" + ORGS(0)).postData(write(PostPutOrgRequest(None, (ORGS(0)), "AdminSuite Test Organization", None, None))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString

    for (org <- ORGS) {
      for (user <- USERS) {
        Http(URL + "/orgs/" + org + "/users/" + user).postData(write(PostPutUsersRequest("password", user.endsWith("admin"), user + "@host.domain"))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      }
      
      Http(URL + "/orgs/" + org + "/agbots/" + AGBOT).postData(write(PutAgbotsRequest("password", AGBOT, None, "password"))).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      Http(URL + "/orgs/" + org + "/services").postData(write(PostPutServiceRequest(SERVICE, None, true, None, URL + "/orgs/" + org + "/services/" + SERVICE, "0.0.1", "test-arch", "multiple", None, None, None, None, None, None, None, None))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      Http(URL + "/orgs/" + org + "/patterns/" + PATTERN).postData(write(PostPutPatternRequest(PATTERN, Some("AdminSuite Test Pattern"), None, List(PServices(URL + "/orgs/" + org + "/services/" + SERVICE, org, "test-arch", None, List(PServiceVersions("0.0.1", None, None, None, None)), None, None)), None, None))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      Http(URL + "/orgs/" + org + "/nodes/" + NODE).postData(write(PutNodesRequest("password", NODE, None, org + "/" + PATTERN, None, None, None, None, "password", None, None))).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    }
  }

  override def afterAll() {
    for (org <- ORGS) {
      for (user <- USERS) {
        Http(URL + "/orgs/" + org + "/users/" + user).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      }
      
      Http(URL + "/orgs/" + org + "/agbots/" + AGBOT).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      Http(URL + "/orgs/" + org + "/nodes/" + NODE).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      Http(URL + "/orgs/" + org + "/patterns/" + PATTERN).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      Http(URL + "/orgs/" + org + "/services/" + "localhost:8080-v1-orgs-" + org + "-services-" + SERVICE + "_0.0.1_test-arch").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    }
    
    Http(URL + "/orgs/" + ORGS(0)).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
  }

  // =============== Hash a Password ===============
  for (org <- ORGS) {
    test("POST /admin/hashpw - " + org + "/" + AGBOT) {
      val input = AdminHashpwRequest("foobar")
      val response = Http(URL + "/admin/hashpw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + AGBOT + ":" + "password"))).asString
      info("http status code: " + response.code)
      info("body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    }
  }

  for (org <- ORGS) {
    test("POST /admin/hashpw - " + org + "/" + NODE) {
      val input = AdminHashpwRequest("foobar")
      val response = Http(URL + "/admin/hashpw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + NODE + ":" + "password"))).asString
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
      val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + AGBOT + ":" + "password"))).asString
      info("http status code: " + response.code)
      info("body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      val postResp = parse(response.body).extract[ApiResponse]
      assert(ApiRespType.ACCESS_DENIED === postResp.code)
    }
  }

  for (org <- ORGS) {
    test("POST /admin/reload - " + org + "/" + NODE) {
      val response = Http(URL + "/admin/reload").method("post").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + NODE + ":" + "password"))).asString
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
      val response = Http(URL + "/admin/status").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + AGBOT + ":" + "password"))).asString
      info("http status code: " + response.code)
      info("body: " + response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    }
  }

  for (org <- ORGS) {
    test("GET /admin/status - " + org + "/" + NODE) {
      val response = Http(URL + "/admin/status").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(org + "/" + NODE + ":" + "password"))).asString
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
        val getResp = parse(response.body).extract[GetAdminStatusResponse]
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
    val getResp = parse(response.body).extract[GetAdminStatusResponse]
    assert(getResp.msg.contains("operating normally"))
  }

  test("GET /admin/status - root/root - Invaild Credentials") {
    val response = Http(URL + "/admin/status").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + "invaildcredentials"))).asString
    info("http status code: " + response.code)
    // info("headers: " + response.headers)
    info("body: " + response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
  }

  // =============== Get Exchange API Version ===============
  /** API is open to any user/call. */
  test("GET /admin/version") {
    val response = Http(URL + "/admin/version").headers(ACCEPTTEXT).asString
    info("http status code: " + response.code)
    info("version: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
  }
}
