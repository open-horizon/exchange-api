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
  val orgid2 = "ServicesSuiteTests-SecondOrg"
  val authpref=orgid+"/"
  val authpref2=orgid2+"/"
  val URL = urlRoot+"/v1/orgs/"+orgid
  val URL2 = urlRoot+"/v1/orgs/"+orgid2
  val user = "9999"
  val orguser = authpref+user
  val pw = user+"pw"
  val USERAUTH = ("Authorization","Basic "+ApiUtils.encode(orguser+":"+pw))
  val user2 = "10000"
  val orguser2 = authpref+user2
  val pw2 = user2+"pw"
  val USER2AUTH = ("Authorization","Basic "+ApiUtils.encode(orguser2+":"+pw2))
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+ApiUtils.encode(rootuser+":"+rootpw))
  val nodeId = "9912"     // the 1st node created, that i will use to run some rest methods
  val nodeToken = nodeId+"tok"
  val NODEAUTH = ("Authorization","Basic "+ApiUtils.encode(authpref+nodeId+":"+nodeToken))
  val nodeId2 = "9913"     // the 1st node created, that i will use to run some rest methods
  val nodeToken2 = nodeId2+"tok"
  val NODEAUTH2 = ("Authorization","Basic "+ApiUtils.encode(authpref+nodeId2+":"+nodeToken2))
  val agbotId = "9947"
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+ApiUtils.encode(authpref+agbotId+":"+agbotToken))
  val svcBase = "svc9920"
  val svcDoc = "http://" + svcBase
  val svcUrl = "" + svcBase
  val svcVersion = "1.0.0"
  val svcArch = "arm"
  val service = svcBase + "_" + svcVersion + "_" + svcArch
  val orgservice = authpref+service
  val org2service = authpref2+service
  val svcBase2 = "svc9921"
  val svcUrl2 = "http://" + svcBase2
  val svcVersion2 = "1.0.0"
  val svcArch2 = "arm"
  val service2 = svcBase2 + "_" + svcVersion2 + "_" + svcArch2
  val orgservice2 = authpref+service2
  val svcBase3 = "svc9922"
  val svcUrl3 = "http://" + svcBase3
  val svcVersion3 = "1.0.0"
  val svcArch3 = "arm"
  val service3 = svcBase3 + "_" + svcVersion3 + "_" + svcArch3
  val orgservice3 = authpref+service3
  val svcBase4 = "svc9923"
  val svcUrl4 = "http://" + svcBase4
  val svcVersion4 = "1.0.0"
  val svcArch4 = "arm"
  val service4 = svcBase4 + "_" + svcVersion4 + "_" + svcArch4
  val orgservice4 = authpref+service4
  val reqsvcurl = "https://bluehorizon.network/services/network"
  val reqsvcarch = "arm"
  val reqsvcversion = "1.0.0"
  val reqsvcurl2 = "https://bluehorizon.network/services/rtlsdr"
  val reqsvcarch2 = "arm"
  val reqsvcversion2 = "2.0.0"
  val reqsvcurl3 = "https://bluehorizon.network/services/rtlsdr"
  val reqsvcarch3 = "amd64"   // intentionally different arch
  val reqsvcversion3 = "1.0.0"
  val IBMURL = urlRoot+"/v1/orgs/IBM"
  val ibmSvcBase = "service-only-for-automated-tests"
  val ibmSvcUrl = "http://" + ibmSvcBase
  val ibmSvcVersion = "9.7.5"
  val ibmSvcArch = "arm"
  val ibmService = ibmSvcBase + "_" + ibmSvcVersion + "_" + ibmSvcArch
  val ibmOrgService = "IBM/"+ibmService
  val patid = "p1"
  val compositePatid = orgid+"/"+patid
  val agProto = "ExchangeAutomatedTest"    // using this to avoid db entries from real users and predefined ones
  val ALL_VERSIONS = "[0.0.0,INFINITY)"
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
  val maxRecords = 10000
  val secondsAgo = 120

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers() = {
    for (i <- List(user,user2)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    }
  }

  /** Create an org to use for this test */
  test("POST /orgs/"+orgid+" - create org") {
    // Try deleting it 1st, in case it is left over from previous test
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)

    val input = PostPutOrgRequest(None, "My Org", "desc", None, None)
    response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  /** Create a second org to use for this test */
  test("POST /orgs/"+orgid2+" - create org") {
    // Try deleting it 1st, in case it is left over from previous test
    var response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)

    val input = PostPutOrgRequest(None, "My Second Org", "desc", None, None)
    response = Http(URL2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the services, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllUsers()
  }

  /** Add users, node, agbot, for future tests */
  test("Add users, node, agbot for future tests") {
    var userInput = PostPutUsersRequest(pw, admin = false, user+"@hotmail.com")
    var userResponse = Http(URL+"/users/"+user).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+userResponse.code+", userResponse.body: "+userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK.intValue)

    userInput = PostPutUsersRequest(pw2, admin = false, user2+"@hotmail.com")
    userResponse = Http(URL+"/users/"+user2).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+userResponse.code+", userResponse.body: "+userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK.intValue)

    val devInput = PutNodesRequest(nodeToken, "bc dev test", "", None, None, None, None, "", None, None)
    val devResponse = Http(URL+"/nodes/"+nodeId).postData(write(devInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+devResponse.code)
    assert(devResponse.code === HttpCode.PUT_OK.intValue)

    val devInput2 = PutNodesRequest(nodeToken2, "bc dev test", "", None, None, None, None, "", None, None)
    val devResponse2 = Http(URL+"/nodes/"+nodeId2).postData(write(devInput2)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+devResponse2.code)
    assert(devResponse2.code === HttpCode.PUT_OK.intValue)

    val agbotInput = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-norm", None, "ABC")
    val agbotResponse = Http(URL+"/agbots/"+agbotId).postData(write(agbotInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+agbotResponse.code+", agbotResponse.body: "+agbotResponse.body)
    assert(agbotResponse.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" before the referenced service exists - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), Some(""), reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("POST /orgs/"+orgid+"/services - add service so other services can reference it") {
    val input = PostPutServiceRequest("testSvc", Some("desc"), public = false, None, reqsvcurl, reqsvcversion, reqsvcarch, "single", None, None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }


  test("PUT /orgs/"+orgid+"/services/"+service+" - update service that is not there yet - should fail") {
    // PostPutServiceRequest(label: String, description: String, serviceUrl: String, version: String, arch: String, downloadUrl: String, apiSpec: List[Map[String,String]], userInput: List[Map[String,String]], services: List[Map[String,String]]) {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" that is not signed - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" that needs 2 MSes - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch),ServiceRef(reqsvcurl2,orgid,Some(reqsvcversion2), None, reqsvcarch2))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" that requires service of different arch - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl3,orgid,Some(reqsvcversion3), None, reqsvcarch3))), None, "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" as user that requires a service") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, Some(svcDoc), svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("service '"+orgservice+"' created"))
  }

  test("POST /orgs/"+orgid2+"/services - add public "+service+" as root in second org to check that its in response") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = true, Some(svcDoc), svcUrl, svcVersion, svcArch, "multiple", None, None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL2+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("service '"+org2service+"' created"))
  }

  test("POST /orgs/"+orgid+"/changes - verify " + service + " was created and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.orgId == orgid) && (y.id == service) && (y.operation == ResourceChangeConfig.CREATED) && (y.resource == "service")}))
    assert(parsedBody.changes.exists(y => {(y.orgId == orgid2) && (y.id == service) && (y.operation == ResourceChangeConfig.CREATED) && (y.resource == "service")}))
  }

  test("POST /orgs/"+orgid+"/services - add "+service3+" as user that requires a service with reqService.versionRange and version") {
    val input = PostPutServiceRequest(svcBase3+" arm", None, public = false, None, svcUrl3, svcVersion3, svcArch3, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), Some(reqsvcversion), reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("service '"+orgservice3+"' created"))
  }

  test("POST /orgs/"+orgid+"/services - add "+service4+" as user with no reqSer.version or versionRange -- should fail") {
    val input = PostPutServiceRequest(svcBase4+" arm", None, public = false, None, svcUrl4, svcVersion4, svcArch4, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,None, None, reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("POST /orgs/"+orgid+"/services - add "+service4+" as user that requires a service with just reqService.versionRange") {
    val input = PostPutServiceRequest(svcBase4+" arm", None, public = false, None, svcUrl4, svcVersion4, svcArch4, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,None, Some(reqsvcversion), reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("service '"+orgservice4+"' created"))
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" again - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ALREADY_EXISTS.intValue)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update to need 2 MSes - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch),ServiceRef(reqsvcurl2,orgid,Some(reqsvcversion2), None, reqsvcarch2))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update changing arch - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, "amd64", "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid, Some(reqsvcversion), None, reqsvcarch))), None, "", "", None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update with invalid sharable value - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcUrl, svcVersion, svcArch, "foobar", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch))), None, "", "", None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("POST /orgs/"+orgid+"/services - add 2nd service so services can reference both") {
    val input = PostPutServiceRequest("testSvc", None, public = false, None, reqsvcurl2, reqsvcversion2, reqsvcarch2, "single", None, None, None, "", "", None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update to need 2 MSes - this time should succeed") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, Some(svcDoc), svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch),ServiceRef(reqsvcurl2,orgid,Some(reqsvcversion2), None, reqsvcarch2))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + service + " was updated and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == service) && (y.operation == ResourceChangeConfig.CREATEDMODIFIED) && (y.resource == "service")}))
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update to need 2 MSes, but 1 version is not in range - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, Some(svcDoc), svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some("2.0.0"), None, reqsvcarch),ServiceRef(reqsvcurl2,orgid,Some(reqsvcversion2), None, reqsvcarch2))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update as 2nd user - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, Some(svcDoc), svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+" - update as agbot - should fail") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, Some(svcDoc), svcUrl, svcVersion, svcArch, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PUT /orgs/"+orgid+"/services/"+service2+" - invalid service body") {
    val badJsonInput = """{
      "labelxx": "GPS x86_64"
    }"""
    val response = Http(URL+"/services/"+service2).postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("POST /orgs/"+orgid+"/services - add "+service2+" as node - should fail") {
    val input = PostPutServiceRequest(svcBase2+" arm", None, public = false, None, svcUrl2, svcVersion2, svcArch2, "multiple", None, None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("POST /orgs/"+orgid+"/services - add "+service2+" as 2nd user, with no referenced MSes") {
    val input = PostPutServiceRequest(svcBase2+" arm", None, public = true, None, svcUrl2, svcVersion2, svcArch2, "multiple", None, None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("PUT /orgs/"+orgid+"/services/"+service2+" - add "+service2+" as 2nd user, with a referenced MS so future GETs work") {
    val input = PostPutServiceRequest(svcBase2+" arm", None, public = true, None, svcUrl2, svcVersion2, svcArch2, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PUT /orgs/"+orgid+"/services/"+service2+" - add "+service2+" as 2nd user, with added reqServices.versionRange") {
    val input = PostPutServiceRequest(svcBase2+" arm", None, public = true, None, svcUrl2, svcVersion2, svcArch2, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), Some(reqsvcversion), reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PUT /orgs/"+orgid+"/services/"+service2+" - add "+service2+" as 2nd user, with changing version or reqSer to None") {
    val input = PostPutServiceRequest(svcBase2+" arm", None, public = true, None, svcUrl2, svcVersion2, svcArch2, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,None, Some(reqsvcversion), reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PUT /orgs/"+orgid+"/services/"+service2+" - add "+service2+" as 2nd user, with no version or versionRange -- should fail") {
    val input = PostPutServiceRequest(svcBase2+" arm", None, public = true, None, svcUrl2, svcVersion2, svcArch2, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,None, None, reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PUT /orgs/"+orgid+"/services/"+service2+" - add "+service2+" as 2nd user, add back reqSer.versionRange") {
    val input = PostPutServiceRequest(svcBase2+" arm", None, public = true, None, svcUrl2, svcVersion2, svcArch2, "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), Some(reqsvcversion), reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val response = Http(URL+"/services/"+service2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  /*someday: when all test suites are run at the same time, there are sometimes timing problems them all setting config values...
  test("POST /orgs/"+orgid+"/services - with low maxServices - should fail") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      ExchConfig.load()
      val origMaxServices = ExchConfig.getInt("api.limits.maxServices")
      info(origMaxServices.toString)
      val NOORGURL = urlRoot+"/v1"

      // Change the maxServices config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxServices", "1")    // user only owns 1 currently
      var response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)

      // Now try adding another 2 services - expect it to be rejected
      var input = PostPutServiceRequest(svcBase4+" arm", None, public = true, None, svcUrl4, "0.0.1", "arm", "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), Some(reqsvcversion), reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
      response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.POST_OK.intValue)

      // Now try adding another service - expect it to be rejected
      input = PostPutServiceRequest(svcBase3+" arm", None, public = true, None, svcUrl3, "0.0.1", "arm", "multiple", None, Some(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), Some(reqsvcversion), reqsvcarch))), Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
      response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      val respObj = parse(response.body).extract[ApiResponse]
      assert(respObj.msg.contains("Access Denied: you are over the limit of 1 services"))

      // Restore the maxServices config value in the svr
      configInput = AdminConfigRequest("api.limits.maxServices", origMaxServices.toString)
      response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)

      //ServicesSuiteTests/svc9923_0.0.1_arm
      response = Http(URL+"/services/svc9923_0.0.1_arm").method("delete").headers(ACCEPT).headers(USER2AUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue)
    }
  }
  */

  test("GET /orgs/"+orgid+"/services") {
    val response: HttpResponse[String] = Http(URL+"/services").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    //info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 6)

    assert(respObj.services.contains(orgservice))
    var wk = respObj.services(orgservice)     // the 2nd get turns the Some(val) into val
    assert(wk.label === svcBase+" arm")
    assert(wk.owner === orguser)

    assert(respObj.services.contains(orgservice2))
    wk = respObj.services(orgservice2)     // the 2nd get turns the Some(val) into val
    assert(wk.label === svcBase2+" arm")
    assert(wk.owner === orguser2)
  }

  test("GET /orgs/"+orgid+"/services - filter owner and requiredurl") {
    val response: HttpResponse[String] = Http(URL+"/services").headers(ACCEPT).headers(USERAUTH).param("owner",orguser2).param("requiredurl",reqsvcurl).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 1)
    assert(respObj.services.contains(orgservice2))
  }

  test("GET /orgs/"+orgid+"/services - filter url and arch") {
    val response: HttpResponse[String] = Http(URL+"/services").headers(ACCEPT).headers(USERAUTH).param("url",svcUrl2).param("arch",svcArch2).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 1)
    assert(respObj.services.contains(orgservice2))
  }

  test("GET /orgs/"+orgid+"/services - filter by public setting") {
    // Find the public==true services
    var response: HttpResponse[String] = Http(URL+"/services").headers(ACCEPT).headers(USERAUTH).param("public","true").asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    var respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 1)
    assert(respObj.services.contains(orgservice2))

    // Find the public==false services
    response = Http(URL+"/services").headers(ACCEPT).headers(USERAUTH).param("public","false").asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 5)
    assert(respObj.services.contains(orgservice))
  }

  test("GET /orgs/"+orgid+"/services - as node") {
    val response: HttpResponse[String] = Http(URL+"/services").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 6)
  }

  test("GET /orgs/"+orgid+"/services - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/services").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 6)
  }

  test("GET /orgs/"+orgid+"/services/"+service+" - as user") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 1)

    assert(respObj.services.contains(orgservice))
    val wk = respObj.services(orgservice)     // the 2nd get turns the Some(val) into val
    assert(wk.label === svcBase+" arm")

    // Verify the lastUpdated from the PUT above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastUp = ZonedDateTime.parse(wk.lastUpdated).toEpochSecond
    assert(now - lastUp <= 9)    // should not be more than 3 seconds from the time the put was done above
    val rsMap = respObj.services(orgservice).requiredServices
    assert(rsMap.head.versionRange.isDefined)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - change arch - should fail") {
    val jsonInput = """{ "arch": "amd64" }"""
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - invalid sharable value - should fail") {
    val jsonInput = """{ "sharable": "foobar" }"""
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - as user") {
    val jsonInput = """{ "sharable": "exclusive" }"""
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + service + " was updated via PATCH and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    Thread.sleep(1000)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == service) && (y.operation == ResourceChangeConfig.MODIFIED) && (y.resource == "service")}))
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - as user with whitespace") {
    val jsonInput = """    { "sharable": "exclusive" }      """
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - as user with newlines") {
    val jsonInput =
      """
        { "sharable": "exclusive" }
        """
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - patch required service -- bad input") {
    val jsonInput = write(List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch)))
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    //assert(response.body.contains("invalid input"))
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - patch required service") {
    val jsonInput = List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch))
    info(write(jsonInput))
    val input = PatchServiceRequest(None, None, None, None, None, None, None, None, None, Some(jsonInput), None, None, None, None)
    info(write(input))
    val response = Http(URL+"/services/"+service).postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - patch versionRange of required service") {
    val jsonInput = List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), Some(reqsvcversion), reqsvcarch))
    info(write(jsonInput))
    val input = PatchServiceRequest(None, None, None, None, None, None, None, None, None, Some(jsonInput), None, None, None, None)
    info(write(input))
    val response = Http(URL+"/services/"+service).postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - patch version of required service") {
    val jsonInput = List(ServiceRef(reqsvcurl,orgid,None, Some(reqsvcversion), reqsvcarch))
    info(write(jsonInput))
    val input = PatchServiceRequest(None, None, None, None, None, None, None, None, None, Some(jsonInput), None, None, None, None)
    info(write(input))
    val response = Http(URL+"/services/"+service).postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - patch versionRange of required service to None") {
    val jsonInput = List(ServiceRef(reqsvcurl,orgid,Some(reqsvcversion), None, reqsvcarch))
    info(write(jsonInput))
    val input = PatchServiceRequest(None, None, None, None, None, None, None, None, None, Some(jsonInput), None, None, None, None)
    info(write(input))
    val response = Http(URL+"/services/"+service).postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - as user2 - should fail") {
    val jsonInput = """{ "label": "this is now patched" }"""
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/"+orgid+"/services/"+service+" - as agbot, check patch by getting that 1 attr") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service).headers(ACCEPT).headers(AGBOTAUTH).param("attribute","sharable").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetServiceAttributeResponse]
    assert(respObj.attribute === "sharable")
    assert(respObj.value === "exclusive")
  }

  test("GET /orgs/"+orgid+"/services/"+service+"notthere - as user - should fail") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"notthere").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    //val getServiceResp = parse(response.body).extract[GetServicesResponse]
    //assert(getServiceResp.services.size === 0)
  }


  // IBM service tests ==============================================

  test("POST /orgs/IBM/services - add IBM service so other services can reference it") {
    val input = PostPutServiceRequest("IBMTestSvc", Some("desc"), public = true, None, ibmSvcUrl, ibmSvcVersion, ibmSvcArch, "single", None, None, None, "{\"services\":{}}", "a", None)
    val response = Http(IBMURL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("GET /orgs/IBM/services") {
    val response: HttpResponse[String] = Http(IBMURL+"/services").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    //info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetServicesResponse]
    //assert(respObj.services.size === 1)  // cant check this because there could be other services defined in the IBM org

    assert(respObj.services.contains(ibmOrgService))
    val wk = respObj.services(ibmOrgService)
    assert(wk.label === "IBMTestSvc")
  }

  test("GET /orgs/IBM/services/"+ibmService) {
    val response: HttpResponse[String] = Http(IBMURL+"/services/"+ibmService).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    //info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 1)  // cant check this because there could be other services defined in the IBM org

    assert(respObj.services.contains(ibmOrgService))
    val wk = respObj.services(ibmOrgService)
    assert(wk.label === "IBMTestSvc")
  }

  // the test to try to get an IBM service that doesnt exist is at the end when we are cleaning up

  //~~~~~ Service policy ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("PUT /orgs/"+orgid+"/services/"+service+"/policy - as user") {
    val input = PutServicePolicyRequest(Some(List(OneProperty("purpose",None,"location"))), Some(List("a == b")))
    val response = Http(URL+"/services/"+service+"/policy").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + service + " policy was created and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == service) && (y.operation == ResourceChangeConfig.CREATEDMODIFIED) && (y.resource == "servicepolicies")}))
  }

  test("GET /orgs/"+orgid+"/services/"+service+"/policy - as node") {
    val response = Http(URL+"/services/"+service+"/policy").method("get").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getResp = parse(response.body).extract[ServicePolicy]
    assert(getResp.properties.size === 1)
    assert(getResp.properties.head.name === "purpose")
  }

  test("DELETE /orgs/"+orgid+"/services/"+service+"/policy - as user") {
    val response = Http(URL+"/services/"+service+"/policy").method("delete").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + service + " policy was deleted and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == service) && (y.operation == ResourceChangeConfig.DELETED) && (y.resource == "servicepolicies")}))
  }

  test("GET /orgs/"+orgid+"/services/"+service+"/policy - as node - should not be there") {
    val response = Http(URL+"/services/"+service+"/policy").method("get").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  // Key tests ==============================================
  test("GET /orgs/"+orgid+"/services/"+service+"/keys - no keys have been created yet - should fail") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val resp = parse(response.body).extract[List[String]]
    assert(resp.size === 0)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+"/keys/"+keyId+" - add "+keyId+" as user") {
    //val input = PutServiceKeyRequest(key)
    val response = Http(URL+"/services/"+service+"/keys/"+keyId).postData(key).method("put").headers(CONTENTTEXT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + service + " key was created and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == service) && (y.operation == ResourceChangeConfig.CREATEDMODIFIED) && (y.resource == "servicekeys")}))
  }

  test("PUT /orgs/"+orgid+"/services/"+service+"/keys/"+keyId2+" - add "+keyId2+" as user") {
    //val input = PutServiceKeyRequest(key2)
    val response = Http(URL+"/services/"+service+"/keys/"+keyId2).postData(key2).method("put").headers(CONTENTTEXT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("GET /orgs/"+orgid+"/services/"+service+"/keys - should be 2 now") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
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
    assert(response.code === HttpCode.OK.intValue)
    assert(response.body === key)
  }

  test("DELETE /orgs/"+orgid+"/services/"+service+"/keys/"+keyId) {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys/"+keyId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + service + " key was deleted and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == service) && (y.operation == ResourceChangeConfig.DELETED) && (y.resource == "servicekeys")}))
  }

  test("DELETE /orgs/"+orgid+"/services/"+service+"/keys/"+keyId+" try deleting it again - should fail") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys/"+keyId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("GET /orgs/"+orgid+"/services/"+service+"/keys/"+keyId+" - verify it is gone") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys/"+keyId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("DELETE /orgs/"+orgid+"/services/"+service+"/keys - delete all keys") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys").method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + service + " all keys were deleted and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == service) && (y.operation == ResourceChangeConfig.DELETED) && (y.resource == "servicekeys")}))
  }

  test("GET /orgs/"+orgid+"/services/"+service+"/keys - all keys should be gone now") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/keys").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val resp = parse(response.body).extract[List[String]]
    assert(resp.size === 0)
  }

  // DockAuth tests ==============================================
  test("GET /orgs/"+orgid+"/services/"+service+"/dockauths - no dockauths have been created yet - should fail") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/dockauths").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val resp = parse(response.body).extract[List[ServiceDockAuth]]
    assert(resp.size === 0)
  }

  test("PUT /orgs/"+orgid+"/services/"+service+"/dockauths/1 - try to update before any exist - should fail") {
    val input = PostPutServiceDockAuthRequest(dockAuthRegistry+"-updated", None, dockAuthToken+"-updated")
    val response = Http(URL+"/services/"+service+"/dockauths/1").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("POST /orgs/"+orgid+"/services/"+service+"/dockauths - add a dockauth as user") {
    val input = PostPutServiceDockAuthRequest(dockAuthRegistry, Some(dockAuthUsername), dockAuthToken)
    val response = Http(URL+"/services/"+service+"/dockauths").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + service + "dockauth was created and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == service) && (y.operation == ResourceChangeConfig.CREATED) && (y.resource == "servicedockauths")}))
  }

  test("POST /orgs/"+orgid+"/services/"+service+"/dockauths - add another dockauth as user") {
    val input = PostPutServiceDockAuthRequest(dockAuthRegistry2, None, dockAuthToken2)
    val response = Http(URL+"/services/"+service+"/dockauths").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/services/"+service+"/dockauths - add a duplicate, it should just update the existing") {
    val input = PostPutServiceDockAuthRequest(dockAuthRegistry2, None, dockAuthToken2)
    val response = Http(URL+"/services/"+service+"/dockauths").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("GET all the dockauths, PUT one, GET one, DELETE one, and verify") {
    // We have to do this all together and get the id's from the GETs, because they are auto-generated
    info("GET /orgs/"+orgid+"/services/"+service+"/dockauths - as node, should be 2 now")
    var response: HttpResponse[String] = Http(URL+"/services/"+service+"/dockauths").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
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
    assert(response.code === HttpCode.PUT_OK.intValue)

    info("POST /orgs/"+orgid+"/changes - verify " + service + "dockauth was created and stored")
    val time = ApiTime.pastUTC(secondsAgo)
    val anotherInput = ResourceChangesRequest(0, Some(time), maxRecords, None)
    response = Http(URL+"/changes").postData(write(anotherInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == service) && (y.operation == ResourceChangeConfig.CREATEDMODIFIED) && (y.resource == "servicedockauths")}))

    info("GET /orgs/"+orgid+"/services/"+service+"/dockauths/"+dockAuthId+" - and check content")
    response = Http(URL+"/services/"+service+"/dockauths/"+dockAuthId).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    dockAuth = parse(response.body).extract[ServiceDockAuth]
    assert(dockAuth.dockAuthId === dockAuthId)
    assert(dockAuth.registry === dockAuthRegistry+"-updated")
    assert(dockAuth.username === dockAuthUsername+"-updated")
    assert(dockAuth.token === dockAuthToken+"-updated")

    info("DELETE /orgs/"+orgid+"/services/"+service+"/dockauths/"+dockAuthId)
    response = Http(URL+"/services/"+service+"/dockauths/"+dockAuthId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED.intValue)

    info("DELETE /orgs/"+orgid+"/services/"+service+"/dockauths/"+dockAuthId+" try deleting it again - should fail")
    response = Http(URL+"/services/"+service+"/dockauths/"+dockAuthId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND.intValue)

    info("GET /orgs/"+orgid+"/services/"+service+"/dockauths/"+dockAuthId+" - verify it is gone")
    response = Http(URL+"/services/"+service+"/dockauths/"+dockAuthId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + service + "dockauth was deleted and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == service) && (y.operation == ResourceChangeConfig.DELETED) && (y.resource == "servicedockauths")}))
  }

  test("DELETE /orgs/"+orgid+"/services/"+service+"/dockauths - delete all keys") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/dockauths").method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + service + " all dockauths were deleted and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == service) && (y.operation == ResourceChangeConfig.DELETED) && (y.resource == "servicedockauths")}))
  }

  test("GET /orgs/"+orgid+"/services/"+service+"/dockauths - all dockauths should be gone now") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service+"/dockauths").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val resp = parse(response.body).extract[List[ServiceDockAuth]]
    assert(resp.size === 0)
  }


  // Start shutting down ==============================================
  test("DELETE /orgs/"+orgid+"/services/"+service) {
    val response = Http(URL+"/services/"+service).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + service + " was deleted and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == service) && (y.operation == ResourceChangeConfig.DELETED) && (y.resource == "service")}))
  }

  test("GET /orgs/"+orgid+"/services/"+service+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    //val getServiceResp = parse(response.body).extract[GetServicesResponse]
    //assert(getServiceResp.services.size === 0)
  }

  test("DELETE /orgs/"+orgid+"/services/"+service2+" - so its owner cache entry is also deleted") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service2).method("delete").headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("DELETE /orgs/"+orgid+"/users/"+user2) {
    val response = Http(URL+"/users/"+user2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("GET /orgs/"+orgid+"/services/"+service2+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/services/"+service2).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    //val getServiceResp = parse(response.body).extract[GetServicesResponse]
    //assert(getServiceResp.services.size === 0)
  }

  test("DELETE /orgs/IBM/services/"+ibmService) {
    val response = Http(IBMURL+"/services/"+ibmService).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("GET /orgs/IBM/services"+ibmService+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(IBMURL+"/services/"+ibmService).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    //info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    //assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("DELETE IBM changes") {
    val res = List(ibmService)
    val input = DeleteIBMChangesRequest(res)
    val response = Http(urlRoot+"/v1/orgs/IBM/changes/cleanup").postData(write(input)).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  /** Clean up, delete all the test users */
  test("Cleanup - DELETE all test users") {
    deleteAllUsers()
  }

  /** Delete the org we used for this test */
  test("POST /orgs/"+orgid+" - delete org") {
    // Try deleting it 1st, in case it is left over from previous test
    val response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

}