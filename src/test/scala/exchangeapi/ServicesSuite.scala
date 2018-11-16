package exchangeapi

import java.time._

import com.horizon.exchangeapi._
import com.horizon.exchangeapi.tables._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.collection.immutable._
import scalaj.http._

/**
 * Tests for the /services routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class ServicesSuite extends FunSuite {

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val ACCEPTTEXT = ("Accept","text/plain")
  val CONTENT = ("Content-Type","application/json")
  val CONTENTTEXT = ("Content-Type","text/plain")
  val orgid = "ServicesSuiteTests"
  val authpref=orgid+"/"
  val URL = urlRoot+"/v1/orgs/"+orgid
  val user = "9999"
  val orguser = authpref+user
  val pw = user+"pw"
  val USERAUTH = ("Authorization","Basic "+orguser+":"+pw)
  val user2 = "10000"
  val orguser2 = authpref+user2
  val pw2 = user2+"pw"
  val USER2AUTH = ("Authorization","Basic "+orguser2+":"+pw2)
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+rootuser+":"+rootpw)
  val nodeId = "9912"     // the 1st node created, that i will use to run some rest methods
  val nodeToken = nodeId+"tok"
  val NODEAUTH = ("Authorization","Basic "+authpref+nodeId+":"+nodeToken)
  val agbotId = "9947"
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+authpref+agbotId+":"+agbotToken)
  val svcBase = "svc9920"
  val svcDoc = "http://" + svcBase
  val svcUrl = "" + svcBase
  val svcVersion = "1.0.0"
  val svcArch = "arm"
  val service = svcBase + "_" + svcVersion + "_" + svcArch
  val orgservice = authpref+service
  val svcBase2 = "svc9921"
  val svcUrl2 = "http://" + svcBase2
  val svcVersion2 = "1.0.0"
  val svcArch2 = "arm"
  val service2 = svcBase2 + "_" + svcVersion2 + "_" + svcArch2
  val orgservice2 = authpref+service2
  val reqsvcurl = "https://bluehorizon.network/services/network"
  val reqsvcarch = "arm"
  val reqsvcversion = "1.0.0"
  val reqsvcurl2 = "https://bluehorizon.network/services/rtlsdr"
  val reqsvcarch2 = "arm"
  val reqsvcversion2 = "2.0.0"
  val reqsvcurl3 = "https://bluehorizon.network/services/rtlsdr"
  val reqsvcarch3 = "amd64"   // intentionally different arch
  val reqsvcversion3 = "1.0.0"
  val resName = "res9920"
  val resDoc = "http://doc-" + resName
  val resUrl = "http://" + resName
  val resVersion = "1.0.0"
  val resArch = "arm"
  val resource = resName + "_" + resVersion
  val orgresource = authpref+resource
  val keyId = "mykey.pem"
  val key = "abcdefghijk"
  val keyId2 = "mykey2.pem"
  val key2 = "lnmopqrstuvwxyz"
  val dockAuthRegistry = "registry.ng.bluemix.net"
  val dockAuthUsername = "iamapikey"
  val dockAuthToken = "tok1"
  val dockAuthRegistry2 = "registry.eu-de.bluemix.net"
  // we don't need dockAuthUsername2 because we will let it default to 'token'
  val dockAuthToken2 = "tok2"

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers() = {
    for (i <- List(user,user2)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Create an org to use for this test */
  test("POST /orgs/"+orgid+" - create org") {
    // Try deleting it 1st, in case it is left over from previous test
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)

    val input = PostPutOrgRequest("My Org", "desc", None)
    response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the services, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllUsers()
  }

  /** Add users, node, agbot, services, resource for future tests */
  test("Add users, node, service for future tests") {
    var userInput = PostPutUsersRequest(pw, admin = false, user+"@hotmail.com")
    var userResponse = Http(URL+"/users/"+user).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+userResponse.code+", userResponse.body: "+userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)

    userInput = PostPutUsersRequest(pw2, admin = false, user2+"@hotmail.com")
    userResponse = Http(URL+"/users/"+user2).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+userResponse.code+", userResponse.body: "+userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)

    val devInput = PutNodesRequest(nodeToken, "bc dev test", "", None, "", Map(), "")
    val devResponse = Http(URL+"/nodes/"+nodeId).postData(write(devInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+devResponse.code)
    assert(devResponse.code === HttpCode.PUT_OK)

    val agbotInput = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-norm", /*List[APattern](),*/ "whisper-id", "ABC")
    val agbotResponse = Http(URL+"/agbots/"+agbotId).postData(write(agbotInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+agbotResponse.code+", agbotResponse.body: "+agbotResponse.body)
    assert(agbotResponse.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" before the referenced service exists - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch))), None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" before the referenced resource exists - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, None, Some(List(ResourceRef(orgid,resName,resVersion))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/services - add service so services can reference it") {
    val input = PostPutServiceRequest("testSvc", Some("desc"), public = false, None, reqsvcurl, reqsvcversion, reqsvcarch, "single", None, None, None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/resources - add "+resource+" so services can reference it") {
    val input = PostPutResourceRequest(resName, None, public = false, None, resVersion, Some(resArch), Map("url" -> resUrl))
    val response = Http(URL+"/resources").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update WK that is not there yet - should fail") {
    // PostPutServiceRequest(label: String, description: String, serviceUrl: String, version: String, arch: String, downloadUrl: String, apiSpec: List[Map[String,String]], userInput: List[Map[String,String]], services: List[Map[String,String]]) {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch))), None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" that is not signed - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch))), None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" that needs 2 MSes - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch),ServiceRef(reqsvcurl2,orgid,reqsvcversion2,reqsvcarch2))), None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" that requires service of different arch - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl3,orgid,reqsvcversion3,reqsvcarch3))), None, None, "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" as user that requires a service and resource") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, Some(svcDoc), svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch))), Some(List(ResourceRef(orgid,resName,resVersion))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("service '"+orgservice+"' created"))
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" again - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch))), None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ALREADY_EXISTS)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update to need 2 MSes - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch),ServiceRef(reqsvcurl2,orgid,reqsvcversion2,reqsvcarch2))), None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update changing arch - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, "amd64", "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch))), None, None, "", "", None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update with invalid sharable value - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "foobar", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch))), None, None, "", "", None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update needing 2 resources, one of with does not exist - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, Some(svcDoc), svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch))), Some(List(ResourceRef(orgid,resName,resVersion),ResourceRef(orgid,"notthere",resVersion))), None, "", "", None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update needing only the existing service and resource") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, Some(svcDoc), svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch))), Some(List(ResourceRef(orgid,resName,resVersion))), None, "", "", None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/services - add 2nd service so services can reference both") {
    val input = PostPutServiceRequest("testSvc", None, public = false, None, reqsvcurl2, reqsvcversion2, reqsvcarch2, "single", None, None, None, None, "", "", None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update to need 2 MSes - this time should succeed") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, Some(svcDoc), svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch),ServiceRef(reqsvcurl2,orgid,reqsvcversion2,reqsvcarch2))), Some(List(ResourceRef(orgid,resName,resVersion))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update to need 2 MSes, but 1 version is not in range - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, Some(svcDoc), svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,"2.0.0",reqsvcarch),ServiceRef(reqsvcurl2,orgid,reqsvcversion2,reqsvcarch2))), None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update as 2nd user - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, Some(svcDoc), svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch))), None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update as agbot - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, Some(svcDoc), svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch))), None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/services/"+service2+" - invalid service body") {
    val badJsonInput = """{
      "labelxx": "GPS x86_64"
    }"""
    val response = Http(URL+"/services/"+service2).postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/services - add "+service2+" as node - should fail") {
    val input = PostPutServiceRequest(svcBase2+" arm", None, public = false, None, svcUrl2, svcVersion2, svcArch2, "multiple", None, None, None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("POST /orgs/"+orgid+"/services - add "+service2+" as 2nd user, with no referenced MSes") {
    val input = PostPutServiceRequest(svcBase2+" arm", None, public = true, None, svcUrl2, svcVersion2, svcArch2, "multiple", None, None, None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/services/"+service2+" - add "+service2+" as 2nd user, with a referenced MS so future GETs work") {
    val input = PostPutServiceRequest(svcBase2+" arm", None, public = true, None, svcUrl2, svcVersion2, svcArch2, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch))), None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /*todo: when all test suites are run at the same time, there are sometimes timing problems them all setting config values...
  test("POST /orgs/"+orgid+"/services - with low maxServices - should fail") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      ExchConfig.load()
      val origMaxServices = ExchConfig.getInt("api.limits.maxServices")

      // Change the maxServices config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxServices", "0")    // user only owns 1 currently
      var response = Http(URL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // Now try adding another service - expect it to be rejected
      val input = PostPutServiceRequest(wkBase3+" arm", "desc", wkUrl3, "1.0.0", "arm", "", List(WServices(svcurl,orgid,svcversion,svcarch)), Some(List(Map("name" -> "foo"))), List(MDockerImages("{\"services\":{}}","a","a")))
      response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED)
      val respObj = parse(response.body).extract[ApiResponse]
      assert(respObj.msg.contains("Access Denied"))

      // Restore the maxServices config value in the svr
      configInput = AdminConfigRequest("api.limits.maxServices", origMaxServices.toString)
      response = Http(URL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)
    }
  }
  */

  test("GET /orgs/"+orgid+"/services") {
    val response: HttpResponse[String] = Http(URL+"/services").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    //info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 4)

    assert(respObj.services.contains(orgservice))
    var wk = respObj.services(orgservice)     // the 2nd get turns the Some(val) into val
    assert(wk.label === svcBase+" arm")
    assert(wk.owner === orguser)

    assert(respObj.services.contains(orgservice2))
    wk = respObj.services(orgservice2)     // the 2nd get turns the Some(val) into val
    assert(wk.label === svcBase2+" arm")
    assert(wk.owner === orguser2)
  }

  test("GET /orgs/"+orgid+"/services - filter owner and serviceUrl") {
    val response: HttpResponse[String] = Http(URL+"/services").headers(ACCEPT).headers(USERAUTH).param("owner",orguser2).param("specRef",reqsvcurl).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 1)
    assert(respObj.services.contains(orgservice2))
  }

  test("GET /orgs/"+orgid+"/services - filter by public setting") {
    // Find the public==true services
    var response: HttpResponse[String] = Http(URL+"/services").headers(ACCEPT).headers(USERAUTH).param("public","true").asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    var respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 1)
    assert(respObj.services.contains(orgservice2))

    // Find the public==false services
    response = Http(URL+"/services").headers(ACCEPT).headers(USERAUTH).param("public","false").asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 3)
    assert(respObj.services.contains(orgservice))
  }

  test("GET /orgs/"+orgid+"/services - as node") {
    val response: HttpResponse[String] = Http(URL+"/services").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 4)
  }

  test("GET /orgs/"+orgid+"/services - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/services").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 4)
  }

  test("GET /orgs/"+orgid+"/services/"+service+" - as user") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 1)

    assert(respObj.services.contains(orgservice))
    val wk = respObj.services(orgservice)     // the 2nd get turns the Some(val) into val
    assert(wk.label === svcBase+" arm")

    // Verify the lastUpdated from the PUT above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastUp = ZonedDateTime.parse(wk.lastUpdated).toEpochSecond
    assert(now - lastUp <= 5)    // should not be more than 3 seconds from the time the put was done above
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - change arch - should fail") {
    val jsonInput = """{ "arch": "amd64" }"""
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - invalid sharable value - should fail") {
    val jsonInput = """{ "sharable": "foobar" }"""
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - as user") {
    val jsonInput = """{ "sharable": "exclusive" }"""
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - patch required service") {
    val jsonInput = write(List(ServiceRef(reqsvcurl,orgid,reqsvcversion,reqsvcarch)))
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - patch required resource") {
    val jsonInput = write(List(ResourceRef(orgid,resName,resVersion)))
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - as user2 - should fail") {
    val jsonInput = """{ "downloadUrl": "this is now patched" }"""
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("GET /orgs/"+orgid+"/services/"+service+" - as agbot, check patch by getting that 1 attr") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service).headers(ACCEPT).headers(AGBOTAUTH).param("attribute","sharable").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetServiceAttributeResponse]
    assert(respObj.attribute === "sharable")
    assert(respObj.value === "exclusive")
  }

  test("GET /orgs/"+orgid+"/services/"+service+"notthere - as user - should fail") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"notthere").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getServiceResp = parse(response.body).extract[GetServicesResponse]
    assert(getServiceResp.services.size === 0)
  }


  // Key tests ==============================================
  test("GET /orgs/"+orgid+"/services/"+service+"/keys - no keys have been created yet - should fail") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val resp = parse(response.body).extract[List[String]]
    assert(resp.size === 0)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+"/keys/"+keyId+" - add "+keyId+" as user") {
    //val input = PutServiceKeyRequest(key)
    val response = Http(URL+"/services/"+service+"/keys/"+keyId).postData(key).method("put").headers(CONTENTTEXT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+"/keys/"+keyId2+" - add "+keyId2+" as user") {
    //val input = PutServiceKeyRequest(key2)
    val response = Http(URL+"/services/"+service+"/keys/"+keyId2).postData(key2).method("put").headers(CONTENTTEXT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("GET /orgs/"+orgid+"/services/"+service+"/keys - should be 2 now") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val resp = parse(response.body).extract[List[String]]
    assert(resp.size === 2)
    assert(resp.contains(keyId) && resp.contains(keyId2))
  }

  test("GET /orgs/"+orgid+"/services/"+service+"/keys/"+keyId+" - get 1 of the keys and check content") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys/"+keyId).headers(ACCEPTTEXT).headers(NODEAUTH).asString
    //val response: HttpResponse[Array[Byte]] = Http(URL+"/services/"+service+"/keys/"+keyId).headers(ACCEPTTEXT).headers(USERAUTH).asBytes
    //val bodyStr = (response.body.map(_.toChar)).mkString
    //info("code: "+response.code+", response.body: "+bodyStr)
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    assert(response.body === key)
  }

  test("DELETE /orgs/"+orgid+"/services/"+service+"/keys/"+keyId) {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys/"+keyId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED)
  }

  test("DELETE /orgs/"+orgid+"/services/"+service+"/keys/"+keyId+" try deleting it again - should fail") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys/"+keyId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("GET /orgs/"+orgid+"/services/"+service+"/keys/"+keyId+" - verify it is gone") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys/"+keyId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("DELETE /orgs/"+orgid+"/services/"+service+"/keys - delete all keys") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys").method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/services/"+service+"/keys - all keys should be gone now") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val resp = parse(response.body).extract[List[String]]
    assert(resp.size === 0)
  }


  // DockAuth tests ==============================================
  test("GET /orgs/"+orgid+"/services/"+service+"/dockauths - no dockauths have been created yet - should fail") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/dockauths").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val resp = parse(response.body).extract[List[ServiceDockAuth]]
    assert(resp.size === 0)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+"/dockauths/1 - try to update before any exist - should fail") {
    val input = PostPutServiceDockAuthRequest(dockAuthRegistry+"-updated", None, dockAuthToken+"-updated")
    val response = Http(URL+"/services/"+service+"/dockauths/1").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("POST /orgs/"+orgid+"/services/"+service+"/dockauths - add a dockauth as user") {
    val input = PostPutServiceDockAuthRequest(dockAuthRegistry, Some(dockAuthUsername), dockAuthToken)
    val response = Http(URL+"/services/"+service+"/dockauths").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/services/"+service+"/dockauths - add another dockauth as user") {
    val input = PostPutServiceDockAuthRequest(dockAuthRegistry2, None, dockAuthToken2)
    val response = Http(URL+"/services/"+service+"/dockauths").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/services/"+service+"/dockauths - add a duplicate, it should just update the existing") {
    val input = PostPutServiceDockAuthRequest(dockAuthRegistry2, None, dockAuthToken2)
    val response = Http(URL+"/services/"+service+"/dockauths").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("GET all the dockauths, PUT one, GET one, DELETE one, and verify") {
    // We have to do this all together and get the id's from the GETs, because they are auto-generated
    info("GET /orgs/"+orgid+"/services/"+service+"/dockauths - as node, should be 2 now")
    var response: HttpResponse[String] = Http(URL+"/services/"+service+"/dockauths").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val resp = parse(response.body).extract[List[ServiceDockAuth]]
    assert(resp.size === 2)
    var dockAuth = resp.find(d => d.registry == dockAuthRegistry).orNull
    assert(dockAuth != null)
    val dockAuthId = dockAuth.dockAuthId
    assert(dockAuth.username === dockAuthUsername)
    assert(dockAuth.token === dockAuthToken)
    dockAuth = resp.find(d => d.registry == dockAuthRegistry2).orNull
    assert(dockAuth != null)
    //val dockAuthId2 = dockAuth.dockAuthId  // do not need this
    assert(dockAuth.username === "token")   // we didn't specify it, so this is the default value
    assert(dockAuth.token === dockAuthToken2)

    info("PUT /orgs/"+orgid+"/services/"+service+"/dockauths/"+dockAuthId+" - update "+dockAuthId+" as user")
    val input = PostPutServiceDockAuthRequest(dockAuthRegistry+"-updated", Some(dockAuthUsername+"-updated"), dockAuthToken+"-updated")
    response = Http(URL+"/services/"+service+"/dockauths/"+dockAuthId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)

    info("GET /orgs/"+orgid+"/services/"+service+"/dockauths/"+dockAuthId+" - and check content")
    response = Http(URL+"/services/"+service+"/dockauths/"+dockAuthId).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    dockAuth = parse(response.body).extract[ServiceDockAuth]
    assert(dockAuth.dockAuthId === dockAuthId)
    assert(dockAuth.registry === dockAuthRegistry+"-updated")
    assert(dockAuth.username === dockAuthUsername+"-updated")
    assert(dockAuth.token === dockAuthToken+"-updated")

    info("DELETE /orgs/"+orgid+"/services/"+service+"/dockauths/"+dockAuthId)
    response = Http(URL+"/services/"+service+"/dockauths/"+dockAuthId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED)

    info("DELETE /orgs/"+orgid+"/services/"+service+"/dockauths/"+dockAuthId+" try deleting it again - should fail")
    response = Http(URL+"/services/"+service+"/dockauths/"+dockAuthId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)

    info("GET /orgs/"+orgid+"/services/"+service+"/dockauths/"+dockAuthId+" - verify it is gone")
    response = Http(URL+"/services/"+service+"/dockauths/"+dockAuthId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("DELETE /orgs/"+orgid+"/services/"+service+"/dockauths - delete all keys") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/dockauths").method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/services/"+service+"/dockauths - all dockauths should be gone now") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/dockauths").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val resp = parse(response.body).extract[List[ServiceDockAuth]]
    assert(resp.size === 0)
  }


  // Start shutting down ==============================================
  test("DELETE /orgs/"+orgid+"/services/"+service) {
    val response = Http(URL+"/services/"+service).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/services/"+service+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getServiceResp = parse(response.body).extract[GetServicesResponse]
    assert(getServiceResp.services.size === 0)
  }

  test("DELETE /orgs/"+orgid+"/users/"+user2+" - which should also delete service2") {
    val response = Http(URL+"/users/"+user2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/services/"+service2+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service2).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getServiceResp = parse(response.body).extract[GetServicesResponse]
    assert(getServiceResp.services.size === 0)
  }

  /** Clean up, delete all the test services */
  test("Cleanup - DELETE all test services") {
    deleteAllUsers()
  }

  /** Delete the org we used for this test */
  test("POST /orgs/"+orgid+" - delete org") {
    // Try deleting it 1st, in case it is left over from previous test
    val response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

}