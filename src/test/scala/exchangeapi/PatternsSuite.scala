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
 * Tests for the /patterns routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class PatternsSuite extends FunSuite {

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val ACCEPTTEXT = ("Accept","text/plain")
  val CONTENT = ("Content-Type","application/json")
  val CONTENTTEXT = ("Content-Type","text/plain")
  val orgid = "PatternsSuiteTests"
  val orgid2 = "PatternsSuiteTests-NotIBM"
  val orgid3 = "PatternsSuiteTests-IBM"
  val authpref=orgid+"/"
  val authpref2=orgid2+"/"
  val authpref3=orgid3+"/"
  val URL = urlRoot+"/v1/orgs/"+orgid
  val URL2 = urlRoot+"/v1/orgs/"+orgid2
  val URL3 = urlRoot+"/v1/orgs/"+orgid3
  val user = "9999"
  val orguser = authpref+user
  val pw = user+"pw"
  val USERAUTH = ("Authorization","Basic "+orguser+":"+pw)
  val user2 = "10000"
  val orguser2 = authpref+user2
  val pw2 = user2+"pw"
  val USER2AUTH = ("Authorization","Basic "+orguser2+":"+pw2)
  val user3 = "10001"
  val org2user3 = authpref2+user3
  val pw3 = user3+"pw"
  val USER3AUTH = ("Authorization","Basic "+org2user3+":"+pw3)
  val user4 = "10002"
  val org3user4 = authpref3+user4
  val pw4 = user4+"pw"
  val USER4AUTH = ("Authorization","Basic "+org3user4+":"+pw4)
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+rootuser+":"+rootpw)
  val nodeId = "9913"     // the 1st node created, that i will use to run some rest methods
  val nodeToken = nodeId+"tok"
  val NODEAUTH = ("Authorization","Basic "+authpref+nodeId+":"+nodeToken)
  val agbotId = "9948"
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+authpref+agbotId+":"+agbotToken)
  val svcurl = "https://bluehorizon.network/services/netspeed"
  val svcarch = "amd64"
  val svcversion = "1.0.0"
  val pattern = "pt9920"
  val orgpattern = authpref+pattern
  val pattern2 = "pt9921"
  val orgpattern2 = authpref+pattern2
  val pattern3 = "pt9922"
  val org2pattern3 = authpref2+pattern3
  val pattern4 = "pt9923"
  val org2pattern4 = authpref2+pattern4
  val pattern5 = "pt9924"
  val org3pattern5 = authpref3+pattern5
  val IBMURL = urlRoot+"/v1/orgs/IBM"
  val ibmSvcBase = "service-only-for-pattern-automated-tests"   // needs to be different from the IBM svc created in ServicesSuite
  val ibmSvcUrl = "http://" + ibmSvcBase
  val ibmSvcVersion = "9.7.5"
  val ibmSvcArch = "arm"
  val ibmService = ibmSvcBase + "_" + ibmSvcVersion + "_" + ibmSvcArch
  val ibmOrgService = "IBM/"+ibmService
  val ibmPattern = "pattern-only-for-automated-tests"
  val ibmOrgPattern = "IBM/"+ibmPattern
  val keyId = "mykey.pem"
  val key = "abcdefghijk"
  val keyId2 = "mykey2.pem"
  val key2 = "lnmopqrstuvwxyz"
  val ALL_VERSIONS = "[0.0.0,INFINITY)"
  val patid = "p-searchtest1"
  val compositePatid = orgid+"/"+patid
  val patid2 = "p-searchtest2"
  val compositePatid2 = orgid+"/"+patid2
  val patid3 = "p-searchtest3"
  val compositePatid3 = orgid+"/"+patid3
  val PWSSPEC_URL = "bluehorizon.network.pws"
  val PWSSPEC = orgid+"/"+PWSSPEC_URL
  val agProto = "ExchangeAutomatedTest"    // using this to avoid db entries from real users and predefined ones
  val NETSPEEDSPEC_URL = "bluehorizon.network.netspeed"
  val NETSPEEDSPEC = orgid+"/"+NETSPEEDSPEC_URL
  val SDRSPEC_URL = "bluehorizon.network.sdr"
  val SDRSPEC = orgid+"/"+SDRSPEC_URL
  val svcid2 = "bluehorizon.network-services-sdr_1.0.0_amd64"
  val svcarch2 = "amd64"
  val svcversion2 = "1.0.0"
  val svcid3 = "bluehorizon.network-services-netspeed_1.0.0_amd64"
  val svcarch3 = "amd64"
  val svcversion3 = "1.0.0"
  val nodeIdSearchTest1 = "n1"     // the 1st node created, that i will use to run some rest methods
  val orgnodeIdSearchTest1= authpref+nodeIdSearchTest1
  val nodeTokenSearchTest1 = "mytok"
  val nodeId2SearchTest2 = "n2"
  val orgnodeId2SearchTest2 = authpref+nodeId2SearchTest2
  val nodeToken2SearchTest2 = "my tok"   // intentionally adding a space in the token
  val nodeId3SearchTest3 = "n3"
  val orgnodeId3SearchTest3 = authpref+nodeId3SearchTest3
  val nodeToken3SearchTest3 = "mytok"
  val nodeId4SearchTest4 = "n4"
  val orgnodeId4SearchTest4 = authpref+nodeId4SearchTest4
  val nodeToken4SearchTest4 = "my tok"
  val nodeId5SearchTest5 = "n5"
  val orgnodeId5SearchTest5 = authpref+nodeId5SearchTest5
  val nodeToken5SearchTest5 = "mytok"
  val nodeId6SearchTest6 = "n6"
  val orgnodeId6SearchTest6 = authpref+nodeId6SearchTest6
  val nodeToken6SearchTest6 = "my tok"
  val svcid4 = "bluehorizon.network-services-pws_1.0.0"
  val svcarch4 = "*"
  val svcversion4 = "1.0.0"
  val svcid5 = "bluehorizon.network-services-sdr_1.0.0_arm32"
  val svcarch5 = "arm32"
  val svcversion5 = "1.0.0"

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers() = {
    for (i <- List(user,user2)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
    val respOrg2 = Http(URL2+"/users/"+user3).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("DELETE "+user3+", code: "+respOrg2.code+", response.body: "+respOrg2.body)
    assert(respOrg2.code === HttpCode.DELETED || respOrg2.code === HttpCode.NOT_FOUND)
    val respOrg3 = Http(URL3+"/users/"+user4).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("DELETE "+user4+", code: "+respOrg3.code+", response.body: "+respOrg3.body)
    assert(respOrg3.code === HttpCode.DELETED || respOrg3.code === HttpCode.NOT_FOUND)
  }

  /** Create an org to use for this test */
  test("POST /orgs/"+orgid+" - create org") {
    // Try deleting it 1st, in case it is left over from previous test
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)

    val input = PostPutOrgRequest(Some("IBM"), "My Org", "desc", None)
    response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /** Create an non IBM org to use for this test */
  test("POST /orgs/"+orgid2+" - create org") {
    // Try deleting it 1st, in case it is left over from previous test
    var response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)

    val input = PostPutOrgRequest(None, "My Second Org", "Org of orgType not IBM", None)
    response = Http(URL2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /** Create a second IBM org to use for this test */
  test("POST /orgs/"+orgid3+" - create org") {
    // Try deleting it 1st, in case it is left over from previous test
    var response = Http(URL3).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)

    val input = PostPutOrgRequest(Some("IBM"), "My Second Org", "Org of orgType not IBM", None)
    response = Http(URL3).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the patterns, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllUsers()
  }

  /** Add users, node, agbot for future tests */
  test("Add users, node, agbot for future tests") {
    var userInput = PostPutUsersRequest(pw, admin = false, user + "@hotmail.com")
    var userResponse = Http(URL + "/users/" + user).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + userResponse.code + ", userResponse.body: " + userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)

    userInput = PostPutUsersRequest(pw2, admin = false, user2 + "@hotmail.com")
    userResponse = Http(URL + "/users/" + user2).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + userResponse.code + ", userResponse.body: " + userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)

    val devInput = PutNodesRequest(nodeToken, "bc dev test", "", Some(List(RegService("foo", 1, None, "{}", List(
      Prop("arch", "arm", "string", "in"),
      Prop("version", "2.0.0", "version", "in"),
      Prop("blockchainProtocols", "agProto", "list", "in"))))), None, None, None, "NODEABC", None)
    val devResponse = Http(URL + "/nodes/" + nodeId).postData(write(devInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + devResponse.code)
    assert(devResponse.code === HttpCode.PUT_OK)

    val agbotInput = PutAgbotsRequest(agbotToken, "agbot" + agbotId + "-norm", None, "ABC")
    val agbotResponse = Http(URL + "/agbots/" + agbotId).postData(write(agbotInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + agbotResponse.code + ", agbotResponse.body: " + agbotResponse.body)
    assert(agbotResponse.code === HttpCode.PUT_OK)
  }

  test("Add users, node, agbot for future tests in non-IBM org") {
    val userInput = PostPutUsersRequest(pw3, admin = false, user3 + "@hotmail.com")
    val userResponse = Http(URL2 + "/users/" + user3).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + userResponse.code + ", userResponse.body: " + userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)
  }

  test("Add users, node, agbot for future tests in second IBM org") {
    val userInput = PostPutUsersRequest(pw4, admin = false, user4 + "@hotmail.com")
    val userResponse = Http(URL3 + "/users/" + user4).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + userResponse.code + ", userResponse.body: " + userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern+" - add "+pattern+" before service exists - should fail") {
    val input = PostPutPatternRequest(pattern, None, None,
      List( PServices(svcurl, orgid, svcarch, None, List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      None, None
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern+" - add "+pattern+" without services field -- see if this works") {
    val input = PostPutPatternRequest(pattern, None, None,
      List( PServices(svcurl, orgid, svcarch, None, List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      None, None
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("Add service for future tests") {
    val svcInput = PostPutServiceRequest("test-service", None, public = false, None, svcurl, svcversion, svcarch, "multiple", None, None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val svcResponse = Http(URL+"/services").postData(write(svcInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+svcResponse.code+", response.body: "+svcResponse.body)
    assert(svcResponse.code === HttpCode.POST_OK)
  }

  test("Add service for future tests -- non IBM org") {
    val svcInput = PostPutServiceRequest("test-service", None, public = false, None, svcurl, svcversion, svcarch, "multiple", None, None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val svcResponse = Http(URL2+"/services").postData(write(svcInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER3AUTH).asString
    info("code: "+svcResponse.code+", response.body: "+svcResponse.body)
    assert(svcResponse.code === HttpCode.POST_OK)
  }

  test("Add service for future tests -- second IBM org") {
    val svcInput = PostPutServiceRequest("test-service", None, public = false, None, svcurl, svcversion, svcarch, "multiple", None, None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val svcResponse = Http(URL3+"/services").postData(write(svcInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER4AUTH).asString
    info("code: "+svcResponse.code+", response.body: "+svcResponse.body)
    assert(svcResponse.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/patterns/"+pattern+" - update pattern that is not there yet - should fail") {
    val input = PostPutPatternRequest("Bad Pattern", None, None,
      List( PServices(svcurl, orgid, svcarch, None, List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      None, None
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    assert(response.body.contains("pattern 'PatternsSuiteTests/pt9920' not found"))
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern+" - add "+pattern+" that is not signed - should fail") {
    val input = PostPutPatternRequest(pattern, None, None,
      List( PServices(svcurl, orgid, svcarch, None, List(PServiceVersions(svcversion, Some("{\"services\":{}}"), None, None, None)), None, None )),
      None, None
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/patterns/PatternNoService - add PatternNoService with no service - should fail") {
    val input = """{
                      "label":"PatternNoService",
                      "description":"Test pattern with no service section to see if this is possible",
                      "public":false,
                      "userInput":[{"serviceOrgid":"PatternsSuiteTests","serviceUrl":"https://bluehorizon.network/services/netspeed","inputs":[{"name":"UI_STRING","value":"mystr"},{"name":"UI_INT","value":5},{"name":"UI_BOOLEAN","value":true}]}],
                      "agreementProtocols":[{"name":"Basic"}]
                  }""".stripMargin
    val response = Http(URL+"/patterns/PatternNoService").postData(input).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/patterns/PatternNoService2 - add PatternNoService with empty service - should fail") {
    val input = """{
                      "label":"PatternNoService2",
                      "description":"Test pattern with empty service section to see if this is possible",
                      "public":false,
                      "service": [],
                      "userInput":[{"serviceOrgid":"PatternsSuiteTests","serviceUrl":"https://bluehorizon.network/services/netspeed","inputs":[{"name":"UI_STRING","value":"mystr"},{"name":"UI_INT","value":5},{"name":"UI_BOOLEAN","value":true}]}],
                      "agreementProtocols":[{"name":"Basic"}]
                  }""".stripMargin
    val response = Http(URL+"/patterns/PatternNoService2").postData(input).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern+" - add "+pattern+" with invalid svc ref in userInput") {
    val input = PostPutPatternRequest(pattern, Some("desc"), Some(true),
      List( PServices(svcurl, orgid, svcarch, Some(true), List(PServiceVersions(svcversion, Some("{\"services\":{}}"), Some("a"), None, None)), None, None )),
      Some(List( OneUserInputService(orgid, svcurl, None, Some("[9.9.9,9.9.9]"), List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      None
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern+" - add "+pattern+" as user") {
    val input = PostPutPatternRequest(pattern, Some("desc"), Some(true),
      List( PServices(svcurl, orgid, svcarch, Some(true), List(PServiceVersions(svcversion, Some("{\"services\":{}}"), Some("a"), Some(Map("priority_value" -> 50)), Some(Map("lifecycle" -> "immediate")))), Some(Map("enabled"->false, "URL"->"", "user"->"", "password"->"", "interval"->0, "check_rate"->0, "metering"->Map[String,Any]())), Some(Map("check_agreement_status" -> 120)) )),
      Some(List( OneUserInputService(orgid, svcurl, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      Some(List(Map("name" -> "Basic")))
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("pattern '"+orgpattern+"' created"))
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern+" - try to add "+pattern+" again with whitespace") {
    val input = PostPutPatternRequest(pattern+" ", Some("desc"), Some(true),
      List( PServices(svcurl, orgid, svcarch, Some(true), List(PServiceVersions(svcversion, Some("{\"services\":{}}"), Some("a"), Some(Map("priority_value" -> 50)), Some(Map("lifecycle" -> "immediate")))), Some(Map("enabled"->false, "URL"->"", "user"->"", "password"->"", "interval"->0, "check_rate"->0, "metering"->Map[String,Any]())), Some(Map("check_agreement_status" -> 120)) )),
      Some(List( OneUserInputService(orgid, svcurl, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      Some(List(Map("name" -> "Basic")))
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ALREADY_EXISTS)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("already exist"))
    assert(respObj.msg.contains("duplicate key value violates unique constraint"))
  }

  /** This pattern sets up pattern5 used for testing functionality of public field in PUT and PATCH routes **/
  test("POST /orgs/"+orgid3+"/patterns/"+pattern5+" - add "+pattern5+" as user") {
    val input = PostPutPatternRequest(pattern5, Some("desc"), Some(true),
      List( PServices(svcurl, orgid3, svcarch, Some(true), List(PServiceVersions(svcversion, Some("{\"services\":{}}"), Some("a"), Some(Map("priority_value" -> 50)), Some(Map("lifecycle" -> "immediate")))), Some(Map("enabled"->false, "URL"->"", "user"->"", "password"->"", "interval"->0, "check_rate"->0, "metering"->Map[String,Any]())), Some(Map("check_agreement_status" -> 120)) )),
      Some(List( OneUserInputService(orgid3, svcurl, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      Some(List(Map("name" -> "Basic")))
    )
    val response = Http(URL3+"/patterns/"+pattern5).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER4AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("pattern '"+org3pattern5+"' created"))
  }

  test("POST /orgs/"+orgid2+"/patterns/"+pattern3+" - add "+pattern3+" public=false, non IBM org") {
    val input = PostPutPatternRequest(pattern3, Some("desc"), Some(false),
      List( PServices(svcurl, orgid2, svcarch, Some(true), List(PServiceVersions(svcversion, Some("{\"services\":{}}"), Some("a"), Some(Map("priority_value" -> 50)), Some(Map("lifecycle" -> "immediate")))), Some(Map("enabled"->false, "URL"->"", "user"->"", "password"->"", "interval"->0, "check_rate"->0, "metering"->Map[String,Any]())), Some(Map("check_agreement_status" -> 120)) )),
      Some(List( OneUserInputService(orgid2, svcurl, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      Some(List(Map("name" -> "Basic")))
    )
    val response = Http(URL2+"/patterns/"+pattern3).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER3AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("pattern '"+org2pattern3+"' created"))
  }

  test("POST /orgs/"+orgid2+"/patterns/"+pattern4+" - add "+pattern4+" public=None, non IBM org") {
    val input = PostPutPatternRequest(pattern4, Some("desc"), None,
      List( PServices(svcurl, orgid2, svcarch, Some(true), List(PServiceVersions(svcversion, Some("{\"services\":{}}"), Some("a"), Some(Map("priority_value" -> 50)), Some(Map("lifecycle" -> "immediate")))), Some(Map("enabled"->false, "URL"->"", "user"->"", "password"->"", "interval"->0, "check_rate"->0, "metering"->Map[String,Any]())), Some(Map("check_agreement_status" -> 120)) )),
      Some(List( OneUserInputService(orgid2, svcurl, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      Some(List(Map("name" -> "Basic")))
    )
    val response = Http(URL2+"/patterns/"+pattern4).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER3AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("pattern '"+org2pattern4+"' created"))
  }

  test("POST /orgs/"+orgid2+"/patterns/"+pattern3+" - add "+pattern3+" public=true, non IBM org - should fail") {
    val input = PostPutPatternRequest("Public Pattern Non IBM", None, Some(true),
      List( PServices(svcurl, orgid2, svcarch, None, List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      None, None
    )
    val response = Http(URL2+"/patterns/"+pattern3).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER3AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    assert(response.body.contains("pattern 'PatternsSuiteTests-NotIBM/pt9922' not created: only IBM patterns can be made public"))
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern+" - add "+pattern+" again - should fail") {
    val input = PostPutPatternRequest("Bad Pattern", None, None,
      List( PServices(svcurl, orgid, svcarch, None, List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      None, None
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ALREADY_EXISTS)
  }

  test("PUT /orgs/"+orgid+"/patterns/"+pattern+" - update as same user, w/o dataVerification or nodeHealth fields") {
    val input = PostPutPatternRequest(pattern+" amd64", None, None,
      List( PServices(svcurl, orgid, svcarch, Some(true), List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      Some(List( OneUserInputService(orgid, svcurl, Some(svcarch), Some(ALL_VERSIONS), List( OneUserInputValue("UI_STRING","mystr - updated"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      None
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/patterns/"+pattern+" - update as 2nd user - should fail") {
    val input = PostPutPatternRequest("Bad Pattern", Some("desc"), None,
      List( PServices(svcurl, orgid, svcarch, None, List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      None, None
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/patterns/"+pattern+" - update as agbot - should fail") {
    val input = PostPutPatternRequest("Bad Pattern", None, None,
      List( PServices(svcurl, orgid, svcarch, None, List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      None, None
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/patterns/"+pattern2+" - invalid pattern body") {
    val badJsonInput = """{
      "labelxx": "GPS x86_64"
    }"""
    val response = Http(URL+"/patterns/"+pattern2).postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern2+" - add "+pattern2+" as node - should fail") {
    val input = PostPutPatternRequest("Bad Pattern2", None, None,
      List( PServices(svcurl, orgid, svcarch, Some(true), List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      None, None
    )
    val response = Http(URL+"/patterns/"+pattern2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern2+" - add "+pattern2+" as 2nd user") {
    val input = PostPutPatternRequest(pattern2+" amd64", None, Some(true),
      List( PServices(svcurl, orgid, svcarch, Some(true), List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      None, None
    )
    val response = Http(URL+"/patterns/"+pattern2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid3+"/patterns/"+pattern5+" - update to not public") {
    val input = PostPutPatternRequest(pattern5, None, Some(false),
      List( PServices(svcurl, orgid3, svcarch, Some(true), List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      Some(List( OneUserInputService(orgid3, svcurl, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      None
    )
    val response = Http(URL3+"/patterns/"+pattern5).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER4AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid3+"/patterns/"+pattern5+" - update to public") {
    val input = PostPutPatternRequest(pattern5, None, Some(true),
      List( PServices(svcurl, orgid3, svcarch, Some(true), List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      Some(List( OneUserInputService(orgid3, svcurl, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      None
    )
    val response = Http(URL3+"/patterns/"+pattern5).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER4AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid3+"/patterns/"+pattern5+" - orgtype changed so pattern can't be updated to public - should fail") {
    val orgInput = """{ "orgType": "test" }"""
    val orgResp = Http(URL3).postData(orgInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    assert(orgResp.code === HttpCode.PUT_OK)
    //val jsonInput = """{ "public": true }"""
    val input = PostPutPatternRequest(pattern5, None, Some(true),
      List( PServices(svcurl, orgid3, svcarch, Some(true), List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      Some(List( OneUserInputService(orgid3, svcurl, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      None
    )
    val response = Http(URL3+"/patterns/"+pattern5).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER4AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val orgInput2 = """{ "orgType": "IBM" }"""
    val orgResp2 = Http(URL3).postData(orgInput2).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    assert(orgResp2.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid2+"/patterns/"+pattern3+" - update to public, not IBM should fail") {
    val input = PostPutPatternRequest(pattern3, None, Some(true),
      List( PServices(svcurl, orgid2, svcarch, Some(true), List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      Some(List( OneUserInputService(orgid2, svcurl, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      None
    )
    val response = Http(URL2+"/patterns/"+pattern3).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER3AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PUT /orgs/"+orgid2+"/patterns/"+pattern4+" - update to not public") {
    val input = PostPutPatternRequest(pattern4, None, Some(false),
      List( PServices(svcurl, orgid2, svcarch, Some(true), List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
      Some(List( OneUserInputService(orgid2, svcurl, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      None
    )
    val response = Http(URL2+"/patterns/"+pattern4).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER3AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /*todo: when all test suites are run at the same time, there are sometimes timing problems them all setting config values...
  test("POST /orgs/"+orgid+"/patterns - with low maxPatterns - should fail") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      ExchConfig.load()
      val NOORGURL = urlRoot+"/v1"
      val origMaxPatterns = ExchConfig.getInt("api.limits.maxPatterns")

      // Change the maxPatterns config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxPatterns", "1")    // user only owns 1 currently
      var response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // Now try adding another 2 patterns - expect the second to be rejected
      var input = PostPutPatternRequest(pattern5+" amd64", None, Some(true),
        List( PServices(svcurl, orgid, svcarch, Some(true), List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
        None, None
      )
      response = Http(URL+"/patterns/"+pattern5).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.POST_OK)

      input = PostPutPatternRequest(pattern2+" amd64", None, Some(true),
        List( PServices(svcurl, orgid, svcarch, Some(true), List(PServiceVersions(svcversion, None, None, None, None)), None, None )),
        None, None
      )
      response = Http(URL+"/patterns/"+pattern2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED)
      val respObj = parse(response.body).extract[ApiResponse]
      assert(respObj.msg.contains("Access Denied: you are over the limit of 1 patterns"))

      // Restore the maxPatterns config value in the svr
      configInput = AdminConfigRequest("api.limits.maxPatterns", origMaxPatterns.toString)
      response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      response = Http(URL+"/patterns/"+pattern5).method("delete").headers(ACCEPT).headers(USER2AUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED)
    }
  }
//  */

  test("GET /orgs/"+orgid+"/patterns") {
    val response: HttpResponse[String] = Http(URL+"/patterns").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 2)

    assert(respObj.patterns.contains(orgpattern))
    var pt = respObj.patterns(orgpattern)
    assert(pt.label === pattern+" amd64")
    assert(pt.owner === orguser)
    assert(pt.services.head.agreementLess.get === true)

    assert(respObj.patterns.contains(orgpattern2))
    pt = respObj.patterns(orgpattern2)
    assert(pt.label === pattern2+" amd64")
    assert(pt.owner === orguser2)
  }

  test("GET /orgs/"+orgid+"/patterns - filter owner and patternUrl") {
    val response: HttpResponse[String] = Http(URL+"/patterns").headers(ACCEPT).headers(USERAUTH).param("owner",authpref+"%").param("label",pattern+"%").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 1)
    assert(respObj.patterns.contains(orgpattern))
  }

  test("GET /orgs/"+orgid+"/patterns - filter by public setting") {
    // Find the public==true patterns
    var response: HttpResponse[String] = Http(URL+"/patterns").headers(ACCEPT).headers(USERAUTH).param("public","true").asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    var respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 1)
    assert(respObj.patterns.contains(orgpattern2))

    // Find the public==false patterns
    response = Http(URL+"/patterns").headers(ACCEPT).headers(USERAUTH).param("public","false").asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 1)
    assert(respObj.patterns.contains(orgpattern))
  }

  test("GET /orgs/"+orgid+"/patterns - as node") {
    val response: HttpResponse[String] = Http(URL+"/patterns").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 2)
  }

  test("GET /orgs/"+orgid+"/patterns - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/patterns").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 2)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+" - as user") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 1)

    assert(respObj.patterns.contains(orgpattern))
    val pt = respObj.patterns(orgpattern)     // the 2nd get turns the Some(val) into val
    assert(pt.label === pattern+" amd64")
    val uis = pt.userInput
    val uisElem = uis.head
    assert(uisElem.serviceUrl === svcurl)
    assert(uisElem.serviceArch.getOrElse("") === svcarch)
    assert(uisElem.serviceVersionRange.getOrElse("") === ALL_VERSIONS)
    val inp = uisElem.inputs
    var inpElem = inp.find(u => u.name=="UI_STRING").orNull
    assert((inpElem !== null) && (inpElem.value === "mystr - updated"))
    inpElem = inp.find(u => u.name=="UI_INT").orNull
    assert((inpElem !== null) && (inpElem.value === 5))
    inpElem = inp.find(u => u.name=="UI_BOOLEAN").orNull
    assert((inpElem !== null) && (inpElem.value === true))

    // Verify the lastUpdated from the PUT above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastUp = ZonedDateTime.parse(pt.lastUpdated).toEpochSecond
    assert(now - lastUp <= 9)    // should not be more than 3 seconds from the time the put was done above
  }

  //~~~~~ Patch and get (verify) patterns ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("PATCH /orgs/"+orgid+"/patterns/"+pattern+" - no service versions") {
    val jsonInput = """{ "services": [{ "serviceUrl": """"+svcurl+"""", "serviceOrgid": """"+orgid+"""", "serviceArch": """"+svcarch+"""", "serviceVersions": [] }] }"""
    val response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PATCH /orgs/"+orgid+"/patterns/"+pattern+" - userInput with an invalid svc ref") {
    val jsonInput = """{ "userInput": [{ "serviceOrgid": """"+orgid+"""", "serviceUrl": """"+svcurl+"""", "serviceArch": "fooarch", "serviceVersionRange": """"+ALL_VERSIONS+"""", "inputs": [{"name":"UI_STRING","value":"mystr - updated"}, {"name":"UI_INT","value": 7}, {"name":"UI_BOOLEAN","value": true}] }] }"""
    val response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PATCH /orgs/"+orgid+"/patterns/"+pattern+" - userInput without header so invalid input") {
    val jsonInput = """[{ "serviceOrgid": """"+orgid+"""", "serviceUrl": """"+svcurl+"""", "serviceArch": "fooarch", "serviceVersionRange": """"+ALL_VERSIONS+"""", "inputs": [{"name":"UI_STRING","value":"mystr - updated"}, {"name":"UI_INT","value": 7}, {"name":"UI_BOOLEAN","value": true}] }]"""
    val response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    assert(response.body.contains("invalid input"))
  }

  test("PATCH /orgs/"+orgid+"/patterns/"+pattern+" - description and userInput as user") {
    var jsonInput = """{ "description": "this is now patched" }"""
    var response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)

    jsonInput = """{ "userInput": [{ "serviceOrgid": """"+orgid+"""", "serviceUrl": """"+svcurl+"""", "serviceArch": """"+svcarch+"""", "serviceVersionRange": """"+ALL_VERSIONS+"""", "inputs": [{"name":"UI_STRING","value":"mystr - updated"}, {"name":"UI_INT","value": 7}, {"name":"UI_BOOLEAN","value": true}] }] }"""
    response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid+"/patterns/"+pattern+" - description and userInput as user with whitespace") {
    var jsonInput = """   { "description": "this is now patched" }    """
    var response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)

    jsonInput =
      """
        { "userInput": [{ "serviceOrgid": """"+orgid+"""", "serviceUrl": """"+svcurl+"""", "serviceArch": """"+svcarch+"""", "serviceVersionRange": """"+ALL_VERSIONS+
        """", "inputs": [{"name":"UI_STRING","value":"mystr - updated"}, {"name":"UI_INT","value": 7}, {"name":"UI_BOOLEAN","value": true}] }] }

          """
    response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid+"/patterns/"+pattern+" - as user2 - should fail") {
    val jsonInput = """{
      "description": "bad patch"
    }"""
    val response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PATCH /orgs/"+orgid+"/patterns/doesnotexist - pattern not found") {
    val jsonInput = """{
      "description": "bad patch"
    }"""
    val response = Http(URL+"/patterns/doesnotexist").postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+" - as agbot, check patch by getting 1 attr at a time") {
    var response: HttpResponse[String] = Http(URL+"/patterns/"+pattern).headers(ACCEPT).headers(AGBOTAUTH).param("attribute","description").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    var respObj = parse(response.body).extract[GetPatternAttributeResponse]
    assert(respObj.attribute === "description")
    assert(respObj.value === "this is now patched")

    response = Http(URL+"/patterns/"+pattern).headers(ACCEPT).headers(AGBOTAUTH).param("attribute","userInput").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    respObj = parse(response.body).extract[GetPatternAttributeResponse]
    assert(respObj.attribute === "userInput")
    val uis = parse(respObj.value).extract[List[OneUserInputService]]
    val uisElem = uis.head
    assert(uisElem.serviceUrl === svcurl)
    assert(uisElem.serviceArch.getOrElse("") === svcarch)
    assert(uisElem.serviceVersionRange.getOrElse("") === ALL_VERSIONS)
    val inp = uisElem.inputs
    var inpElem = inp.find(u => u.name=="UI_STRING").orNull
    assert((inpElem !== null) && (inpElem.value === "mystr - updated"))
    inpElem = inp.find(u => u.name=="UI_INT").orNull
    assert((inpElem !== null) && (inpElem.value === 7))
    inpElem = inp.find(u => u.name=="UI_BOOLEAN").orNull
    assert((inpElem !== null) && (inpElem.value === true))
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+"notthere - as user - should fail") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern+"notthere").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    //val getPatternResp = parse(response.body).extract[GetPatternsResponse]
    //assert(getPatternResp.patterns.size === 0)
  }

  test("PATCH /orgs/"+orgid+"/patterns/"+pattern+" - patch the service") {
    val input = List( PServices(svcurl, orgid, svcarch, None, List(PServiceVersions(svcversion, None, None, None, None)), Some(Map()), Some(Map()) ))
    val jsonInput = """{ "services": """ + write(input) + " }"
    //info("jsonInput: "+jsonInput)
    val response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid+"/patterns/"+pattern+" - patch with a nonexistent service - should fail") {
    val input = List( PServices("foo", orgid, svcarch, None, List(PServiceVersions(svcversion, None, None, None, None)), Some(Map()), Some(Map()) ))
    val jsonInput = """{ "services": """ + write(input) + " }"
    val response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PATCH /orgs/"+orgid3+"/patterns/"+pattern5+" - patch the public attribute to false from true") {
    val jsonInput = """{ "public": false }"""
    //info("jsonInput: "+jsonInput)
    val response = Http(URL3+"/patterns/"+pattern5).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER4AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid3+"/patterns/"+pattern5+" - patch the public attribute to false again") {
    val jsonInput = """{ "public": false }"""
    //info("jsonInput: "+jsonInput)
    val response = Http(URL3+"/patterns/"+pattern5).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER4AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid3+"/patterns/"+pattern5+" - patch the public attribute to true from false") {
    val jsonInput = """{ "public": true }"""
    //info("jsonInput: "+jsonInput)
    val response = Http(URL3+"/patterns/"+pattern5).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER4AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid3+"/patterns/"+pattern5+" - patch the public attribute to true again") {
    val jsonInput = """{ "public": true }"""
    //info("jsonInput: "+jsonInput)
    val response = Http(URL3+"/patterns/"+pattern5).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER4AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid2+"/patterns/"+pattern4+" - patch the public attribute to true not IBM - should fail") {
    val jsonInput = """{ "public": true }"""
    //info("jsonInput: "+jsonInput)
    val response = Http(URL2+"/patterns/"+pattern4).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER3AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PATCH /orgs/"+orgid3+"/patterns/"+pattern5+" - orgtype changed and pattern no longer can be public -- should fail") {
    val orgInput = """{ "orgType": "test" }"""
    val orgResp = Http(URL3).postData(orgInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    assert(orgResp.code === HttpCode.PUT_OK)
    val jsonInput = """{ "public": true }"""
    //info("jsonInput: "+jsonInput)
    val response = Http(URL3+"/patterns/"+pattern5).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER4AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val orgInput2 = """{ "orgType": "IBM" }"""
    val orgResp2 = Http(URL3).postData(orgInput2).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    assert(orgResp2.code === HttpCode.PUT_OK)
  }


  // IBM pattern tests ==============================================

  test("POST /orgs/IBM/services - add IBM service so patterns can reference it") {
    val input = PostPutServiceRequest("IBMTestSvc", Some("desc"), public = true, None, ibmSvcUrl, ibmSvcVersion, ibmSvcArch, "single", None, None, None, "{\"services\":{}}", "a", None)
    val response = Http(IBMURL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/IBM/patterns/"+ibmPattern+" - add "+ibmPattern+" as root") {
    val input = PostPutPatternRequest(ibmPattern, None, Some(true),
      List( PServices(ibmSvcUrl, "IBM", ibmSvcArch, None, List(PServiceVersions(ibmSvcVersion, None, None, None, None)), None, None )),
      None, None
    )
    val response = Http(IBMURL+"/patterns/"+ibmPattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("pattern '"+ibmOrgPattern+"' created"))
  }

  test("GET /orgs/IBM/patterns") {
    val response: HttpResponse[String] = Http(IBMURL+"/patterns").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    //assert(respObj.patterns.size === 2)  // cant test this because there could be other patterns in the IBM org

    assert(respObj.patterns.contains(ibmOrgPattern))
    val pt = respObj.patterns(ibmOrgPattern)
    assert(pt.label === ibmPattern)
  }

  test("GET /orgs/IBM/patterns/"+ibmPattern+" - as user") {
    val response: HttpResponse[String] = Http(IBMURL+"/patterns/"+ibmPattern).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 1)

    assert(respObj.patterns.contains(ibmOrgPattern))
    val pt = respObj.patterns(ibmOrgPattern)     // the 2nd get turns the Some(val) into val
    assert(pt.label === ibmPattern)
  }

  // the test to try to get an IBM pattern that doesn't exist is at the end when we are cleaning up

  test("POST /orgs/"+orgid+"/services - add "+svcid2+" so pattern can reference it") {
    val input = PostPutServiceRequest("test-service", None, public = false, None, SDRSPEC_URL, svcversion2, svcarch2, "multiple", None, None, None, "", "", None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/services - add "+svcid3+" so pattern can reference it") {
    val input = PostPutServiceRequest("test-service", None, public = false, None, NETSPEEDSPEC_URL, svcversion3, svcarch3, "multiple", None, None, None, "", "", None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/services - add "+svcid4+" so pattern can reference it") {
    val input = PostPutServiceRequest("test-service", None, public = false, None, PWSSPEC_URL, svcversion4, svcarch4, "multiple", None, None, None, "", "", None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/services - add "+svcid5+" so pattern can reference it") {
    val input = PostPutServiceRequest("test-service", None, public = false, None, SDRSPEC_URL, svcversion5, svcarch5, "multiple", None, None, None, "", "", None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+" - adding pattern so nodes can reference it") {
    val input = PostPutPatternRequest(patid, None, None,
      List(
        // Reference both services in the pattern so we can search on both later on
        PServices(SDRSPEC_URL, orgid, svcarch2, None, List(PServiceVersions(svcversion2, None, None, None, None)), None, None ),
        PServices(NETSPEEDSPEC_URL, orgid, svcarch3, Some(true), List(PServiceVersions(svcversion3, None, None, None, None)), None, None )
      ),
      None, None
    )
    val response = Http(URL+"/patterns/"+patid).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid2+" - adding pattern so nodes can reference it") {
    val input = PostPutPatternRequest(patid2, None, None,
      List(
        // Reference both services in the pattern so we can search on both later on
        PServices(SDRSPEC_URL, orgid, svcarch2, None, List(PServiceVersions(svcversion2, None, None, None, None)), None, None ),
        PServices(NETSPEEDSPEC_URL, orgid, svcarch3, Some(true), List(PServiceVersions(svcversion3, None, None, None, None)), None, None ),
        PServices(PWSSPEC_URL, orgid, svcarch4, None, List(PServiceVersions(svcversion4, None, None, None, None)), None, None ),
      ),
      None, None
    )
    val response = Http(URL+"/patterns/"+patid2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid3+" - adding pattern with 2 services of different arch's so nodes can reference it") {
    val input = PostPutPatternRequest(patid3, None, None,
      List(
        // Reference both services in the pattern so we can search on both later on
        PServices(SDRSPEC_URL, orgid, svcarch2, None, List(PServiceVersions(svcversion2, None, None, None, None)), None, None ),
        PServices(SDRSPEC_URL, orgid, svcarch5, None, List(PServiceVersions(svcversion5, None, None, None, None)), None, None ),

      ),
      None, None
    )
    val response = Http(URL+"/patterns/"+patid3).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeIdSearchTest1+" - add normal node as user") {
    val input = PutNodesRequest(nodeTokenSearchTest1, "rpi"+nodeIdSearchTest1+"-norm", compositePatid,
      Some(List(
        RegService(PWSSPEC,1,Some("active"),"{json policy for "+nodeIdSearchTest1+" pws}",List(
          Prop("arch","arm","string","in"),
          Prop("version","1.0.0","version","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("dataVerification","true","boolean","="))),
        RegService(NETSPEEDSPEC,1,Some("active"),"{json policy for "+nodeIdSearchTest1+" netspeed}",List(
          Prop("arch","arm","string","in"),
          Prop("cpus","2","int",">="),
          Prop("version","1.0.0","version","in")))
      )),
      Some(List( OneUserInputService(orgid, SDRSPEC_URL, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      None, Some(Map("horizon"->"3.2.3")), "NODEABC", None)
    val response = Http(URL+"/nodes/"+nodeIdSearchTest1).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId2SearchTest2+" - node with higher memory 400, and version 2.0.0") {
    val input = PutNodesRequest(nodeToken2SearchTest2, "rpi"+nodeId2SearchTest2+"-mem-400-vers-2", compositePatid, Some(List(RegService(SDRSPEC,1,Some("active"),"{json policy for "+nodeId2SearchTest2+" sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","400","int",">="),
      Prop("version","2.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","="))))), None, None, None, "NODE2ABC", Some("amd64"))
    val response = Http(URL+"/nodes/"+nodeId2SearchTest2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId2SearchTest2+" - node with no arch") {
    val input = PutNodesRequest(nodeToken2SearchTest2, "rpi"+nodeId2SearchTest2+"-mem-400-vers-2", compositePatid, Some(List(RegService(SDRSPEC,1,Some("active"),"{json policy for "+nodeId2SearchTest2+" sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","400","int",">="),
      Prop("version","2.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","="))))), None, None, None, "NODE2ABC", Some("amd64"))
    val response = Http(URL+"/nodes/"+nodeId2SearchTest2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - as agbot") {
    val input = PostPatternSearchRequest(SDRSPEC, Some(List(orgid,orgid2)), 86400, 0, 0, None)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 2)
    info(nodes.count(d => d.id == orgnodeIdSearchTest1 || d.id == orgnodeId2SearchTest2).toString)
    assert(nodes.count(d => d.id==orgnodeIdSearchTest1 || d.id==orgnodeId2SearchTest2) === 2)
    val dev = nodes.find(d => d.id == orgnodeIdSearchTest1).get // the 2nd get turns the Some(val) into val
    val dev2 = nodes.find(d => d.id == orgnodeId2SearchTest2).get // the 2nd get turns the Some(val) into val
    assert(dev.publicKey === "NODEABC")
    assert(dev2.publicKey === "NODE2ABC")
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId3SearchTest3+" - node with no arch") {
    val input = PutNodesRequest(nodeToken3SearchTest3, "rpi"+nodeId3SearchTest3+"-mem-400-vers-2", compositePatid, Some(List(RegService(SDRSPEC,1,Some("active"),"{json policy for "+nodeId3SearchTest3+" sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","400","int",">="),
      Prop("version","2.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","="))))), None, None, None, "NODE3ABC", None)
    val response = Http(URL+"/nodes/"+nodeId3SearchTest3).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }


  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - agbot should find node with no arch") {
    val input = PostPatternSearchRequest(SDRSPEC, Some(List(orgid,orgid2)), 86400, 0, 0, None)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 3)
    assert(nodes.count(d => d.id==orgnodeIdSearchTest1 || d.id==orgnodeId2SearchTest2 || d.id==orgnodeId3SearchTest3) === 3)
    val dev = nodes.find(d => d.id == orgnodeIdSearchTest1).get // the 2nd get turns the Some(val) into val
    val dev2 = nodes.find(d => d.id == orgnodeId2SearchTest2).get // the 2nd get turns the Some(val) into val
    val dev3 = nodes.find(d => d.id == orgnodeId3SearchTest3).get // the 2nd get turns the Some(val) into val
    assert(dev.publicKey === "NODEABC")
    assert(dev2.publicKey === "NODE2ABC")
    assert(dev3.publicKey === "NODE3ABC")
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId4SearchTest4+" - node with "+PWSSPEC+" Service") {
    val input = PutNodesRequest(nodeToken4SearchTest4, "rpi"+nodeId4SearchTest4+"-mem-400-vers-2", compositePatid2, Some(List(RegService(PWSSPEC,1,Some("active"),"{json policy for "+nodeId4SearchTest4+" sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","400","int",">="),
      Prop("version","1.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","="))))), None, None, None, "NODE4ABC", None)
    val response = Http(URL+"/nodes/"+nodeId4SearchTest4).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId4SearchTest4+" - by agbot just to see what the node is") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId4SearchTest4).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
  }

  test("GET /orgs/"+orgid+"/patterns/"+patid2+" - by agbot just to see what the pattern is") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+patid2).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid2+"/search - as agbot for "+PWSSPEC+" with no arch") {
    val input = PostPatternSearchRequest(PWSSPEC, Some(List(orgid,orgid2)), 86400, 0, 0, None)
    val response = Http(URL+"/patterns/"+patid2+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 1)
    assert(nodes.count(d => d.id==orgnodeId4SearchTest4) === 1)
    val dev = nodes.find(d => d.id == orgnodeId4SearchTest4).get // the 2nd get turns the Some(val) into val
    assert(dev.publicKey === "NODE4ABC")
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId5SearchTest5+" - node with "+SDRSPEC+" Service arm32") {
    val input = PutNodesRequest(nodeToken5SearchTest5, "rpi"+nodeId5SearchTest5+"-mem-400-vers-2", compositePatid3, Some(List(RegService(SDRSPEC,1,Some("active"),"{json policy for "+nodeId5SearchTest5+" sdr}",List(
      Prop("arch","arm32","string","in"),
      Prop("memory","400","int",">="),
      Prop("version","1.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","="))))), None, None, None, "NODE5ABC", Some("arm32"))
    val response = Http(URL+"/nodes/"+nodeId5SearchTest5).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId6SearchTest6+" - node with "+SDRSPEC+" Service the first one arm32") {
    val input = PutNodesRequest(nodeToken6SearchTest6, "rpi"+nodeId6SearchTest6+"-mem-400-vers-2", compositePatid3, Some(List(RegService(SDRSPEC,1,Some("active"),"{json policy for "+nodeId6SearchTest6+" sdr}",List(
      Prop("arch","amd64","string","in"),
      Prop("memory","400","int",">="),
      Prop("version","1.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","="))))), None, None, None, "NODE6ABC", Some("amd64"))
    val response = Http(URL+"/nodes/"+nodeId6SearchTest6).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/patterns/"+patid3+" - by agbot just to see what the pattern is") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+patid3).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
//    info("body: "+response.body)
    assert(response.code === HttpCode.OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId5SearchTest5+" - by agbot just to see what the node is") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId5SearchTest5).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
//    info("body: "+response.body)
    assert(response.code === HttpCode.OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid3+"/search - as agbot for "+SDRSPEC+" with two different arch's") {
    val input = PostPatternSearchRequest(SDRSPEC, Some(List(orgid,orgid2)), 86400, 0, 0, None)
    val response = Http(URL+"/patterns/"+patid3+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 2)
    assert(nodes.count(d => d.id==orgnodeId5SearchTest5 || d.id==orgnodeId6SearchTest6) === 2)
    val dev = nodes.find(d => d.id == orgnodeId5SearchTest5).get // arm32
    val dev2 = nodes.find(d => d.id == orgnodeId6SearchTest6).get // amd64
    assert(dev.publicKey === "NODE5ABC")
    assert(dev2.publicKey === "NODE6ABC")
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid3+"/search - as agbot for "+SDRSPEC+" with arch=arm32") {
    val input = PostPatternSearchRequest(SDRSPEC, Some(List(orgid,orgid2)), 86400, 0, 0, Some("arm32"))
    val response = Http(URL+"/patterns/"+patid3+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 1)
    assert(nodes.count(d => d.id==orgnodeId5SearchTest5) === 1)
    val dev = nodes.find(d => d.id == orgnodeId5SearchTest5).get // arm32
    assert(dev.publicKey === "NODE5ABC")
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid3+"/search - as agbot for "+SDRSPEC+" with arch=amd64") {
    val input = PostPatternSearchRequest(SDRSPEC, Some(List(orgid,orgid2)), 86400, 0, 0, Some("amd64"))
    val response = Http(URL+"/patterns/"+patid3+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 1)
    assert(nodes.count(d => d.id==orgnodeId6SearchTest6) === 1)
    val dev2 = nodes.find(d => d.id == orgnodeId6SearchTest6).get // amd64
    assert(dev2.publicKey === "NODE6ABC")
  }

  // Clean Up Nodes from search tests
  test("DELETE /orgs/"+orgid+"/nodes/"+nodeIdSearchTest1) {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeIdSearchTest1).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED)
  }

  // Key tests ==============================================
  test("GET /orgs/"+orgid+"/patterns/"+pattern+"/keys - no keys have been created yet - should fail") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern+"/keys").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val resp = parse(response.body).extract[List[String]]
    assert(resp.size === 0)
  }

  test("PUT /orgs/"+orgid+"/patterns/"+pattern+"/keys/"+keyId+" - add "+keyId+" as user") {
    //val input = PutPatternKeyRequest(key)
    val response = Http(URL+"/patterns/"+pattern+"/keys/"+keyId).postData(key).method("put").headers(CONTENTTEXT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/patterns/"+pattern+"/keys/"+keyId2+" - add "+keyId2+" as user") {
    //val input = PutPatternKeyRequest(key2)
    val response = Http(URL+"/patterns/"+pattern+"/keys/"+keyId2).postData(key2).method("put").headers(CONTENTTEXT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+"/keys - should be 2 now") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern+"/keys").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val resp = parse(response.body).extract[List[String]]
    assert(resp.size === 2)
    assert(resp.contains(keyId) && resp.contains(keyId2))
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+"/keys/"+keyId+" - get 1 of the keys and check content") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern+"/keys/"+keyId).headers(ACCEPTTEXT).headers(USERAUTH).asString
    //val response: HttpResponse[Array[Byte]] = Http(URL+"/patterns/"+pattern+"/keys/"+keyId).headers(ACCEPTTEXT).headers(USERAUTH).asBytes
    //val bodyStr = (response.body.map(_.toChar)).mkString
    //info("code: "+response.code+", response.body: "+bodyStr)
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    assert(response.body === key)
  }

  test("DELETE /orgs/"+orgid+"/patterns/"+pattern+"/keys/"+keyId) {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern+"/keys/"+keyId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED)
  }

  test("DELETE /orgs/"+orgid+"/patterns/"+pattern+"/keys/"+keyId+" try deleting it again - should fail") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern+"/keys/"+keyId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+"/keys/"+keyId+" - verify it is gone") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern+"/keys/"+keyId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("DELETE /orgs/"+orgid+"/patterns/"+pattern+"/keys - delete all keys") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern+"/keys").method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+"/keys - all keys should be gone now") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern+"/keys").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val resp = parse(response.body).extract[List[String]]
    assert(resp.size === 0)
  }


  test("DELETE /orgs/"+orgid+"/patterns/"+pattern) {
    val response = Http(URL+"/patterns/"+pattern).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    //val getPatternResp = parse(response.body).extract[GetPatternsResponse]
    //assert(getPatternResp.patterns.size === 0)
  }

  test("DELETE /orgs/"+orgid+"/patterns/"+pattern2+" - so its cache entry will also be deleted") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern2).method("delete").headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED)
  }

  test("DELETE /orgs/"+orgid+"/users/"+user2) {
    val response = Http(URL+"/users/"+user2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern2+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern2).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    //val getPatternResp = parse(response.body).extract[GetPatternsResponse]
    //assert(getPatternResp.patterns.size === 0)
  }

  test("DELETE /orgs/IBM/patterns/"+ibmPattern) {
    val response = Http(IBMURL+"/patterns/"+ibmPattern).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/IBM/patterns/"+ibmPattern+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(IBMURL+"/patterns/"+ibmPattern).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    //assert(response.code === HttpCode.NOT_FOUND)
    //todo: change this to NOT_FOUND when issue anax issue 778 is fixed
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("DELETE /orgs/IBM/services/"+ibmService) {
    val response = Http(IBMURL+"/services/"+ibmService).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  /** Clean up, delete all the test patterns */
  test("Cleanup - DELETE all test patterns") {
    deleteAllUsers()
  }

  /** Delete the org we used for this test */
  test("POST /orgs/"+orgid+" - delete org") {
    // Try deleting it 1st, in case it is left over from previous test
    val response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  /** Delete the non IBM org we used for this test */
  test("POST /orgs/"+orgid2+" - delete org") {
    // Try deleting it 1st, in case it is left over from previous test
    val response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  /** Delete the second IBM org we used for this test */
  test("POST /orgs/"+orgid3+" - delete org") {
    // Try deleting it 1st, in case it is left over from previous test
    val response = Http(URL3).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

}