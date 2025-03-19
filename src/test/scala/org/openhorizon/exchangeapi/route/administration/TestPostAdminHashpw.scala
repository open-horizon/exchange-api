package org.openhorizon.exchangeapi.route.administration
import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer
import org.openhorizon.exchangeapi.table.organization.OrgRow
import org.openhorizon.exchangeapi.utility.{ApiTime, Configuration}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.utility.{ApiUtils, HttpCode}
import org.openhorizon.exchangeapi.route.administration.{AdminHashpwRequest, AdminHashpwResponse}
import org.json4s.jackson.Serialization.write
import scalaj.http.Http
import org.openhorizon.exchangeapi.route.organization.PostPutOrgRequest
import org.openhorizon.exchangeapi.route.service.PostPutServiceRequest
import org.openhorizon.exchangeapi.route.node.PutNodesRequest
import org.openhorizon.exchangeapi.route.agreementbot.PutAgbotsRequest
import org.openhorizon.exchangeapi.route.deploymentpattern.PostPutPatternRequest
import org.openhorizon.exchangeapi.route.user.PostPutUsersRequest
import org.openhorizon.exchangeapi.table.deploymentpattern.{PServiceVersions, PServices}
@RunWith(classOf[JUnitRunner])
class TestPostAdminHashpw extends AnyFunSuite with BeforeAndAfterAll {

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

}
