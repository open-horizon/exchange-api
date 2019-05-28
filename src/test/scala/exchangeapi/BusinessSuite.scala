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
import scalaj.http._

import scala.collection.immutable._

/**
 * Tests for the /business/policies routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class BusinessSuite extends FunSuite {

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val ACCEPTTEXT = ("Accept","text/plain")
  val CONTENT = ("Content-Type","application/json")
  val CONTENTTEXT = ("Content-Type","text/plain")
  val orgid = "BusinessSuiteTests"
  val authpref=orgid+"/"
  val URL = urlRoot+"/v1/orgs/"+orgid
  val orgid2 = "BusinessSuiteTests2"
  val authpref2=orgid2+"/"
  val URL2 = urlRoot+"/v1/orgs/"+orgid2
  val user = "9999"
  val orguser = authpref+user
  val org2user = authpref2+user
  val pw = user+"pw"
  val USERAUTH = ("Authorization","Basic "+orguser+":"+pw)
  val USERAUTH2 = ("Authorization","Basic "+org2user+":"+pw)
  val user2 = "10000"
  val orguser2 = authpref+user2
  val pw2 = user2+"pw"
  val USER2AUTH = ("Authorization","Basic "+orguser2+":"+pw2)
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+rootuser+":"+rootpw)
  val nodeId = "9913"     // the 1st node created, that i will use to run some rest methods
  val nodeToken = nodeId+"tok"
  val NODEAUTH = ("Authorization","Basic "+authpref+nodeId+":"+nodeToken)
  val agbotId = "9948"
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+authpref+agbotId+":"+agbotToken)
  val svcurl = "ibm.netspeed"
  val svcarch = "amd64"
  val svcversion = "1.0.0"
  val businessPolicy = "mybuspol"
  val orgBusinessPolicy = authpref+businessPolicy
  val businessPolicy2 = "mybuspol2"
  val orgBusinessPolicy2 = authpref+businessPolicy2
  val svcurl2 = "ibm.pws"
  val svcversion2 = "9.7.5"
  val svcarch2 = "arm"
  val service2 = svcurl2 + "_" + svcversion2 + "_" + svcarch2
  val org2Service = "IBM/"+service2

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers() = {
    for (i <- List(user,user2)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  //~~~~~ Clean up from previous run, and create orgs, users, node, agbot ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("POST /orgs/"+orgid+" - create org") {
    // Try deleting it 1st, in case it is left over from previous test
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)

    var input = PostPutOrgRequest(None, "My Org", "desc", None)
    response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)

    // Try deleting it 1st, in case it is left over from previous test
    response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)

    input = PostPutOrgRequest(None, "My Org2", "desc", None)
    response = Http(URL2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the business policies, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllUsers()
  }

  test("Add users, node, agbot for future tests") {
    var userInput = PostPutUsersRequest(pw, admin = false, user + "@hotmail.com")
    var userResponse = Http(URL + "/users/" + user).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + userResponse.code + ", userResponse.body: " + userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)

    userInput = PostPutUsersRequest(pw2, admin = false, user2 + "@hotmail.com")
    userResponse = Http(URL + "/users/" + user2).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + userResponse.code + ", userResponse.body: " + userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)

    userInput = PostPutUsersRequest(pw, admin = false, user + "@hotmail.com")
    userResponse = Http(URL2 + "/users/" + user).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + userResponse.code + ", userResponse.body: " + userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)

    val devInput = PutNodesRequest(nodeToken, "bc dev test", "", Some(List(RegService("foo", 1, None, "{}", List(
      Prop("arch", "arm", "string", "in"),
      Prop("version", "2.0.0", "version", "in"),
      Prop("blockchainProtocols", "agProto", "list", "in"))))), None, None, "NODEABC", None)
    val devResponse = Http(URL + "/nodes/" + nodeId).postData(write(devInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + devResponse.code)
    assert(devResponse.code === HttpCode.PUT_OK)

    val agbotInput = PutAgbotsRequest(agbotToken, "agbot" + agbotId + "-norm", None, "ABC")
    val agbotResponse = Http(URL + "/agbots/" + agbotId).postData(write(agbotInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + agbotResponse.code + ", agbotResponse.body: " + agbotResponse.body)
    assert(agbotResponse.code === HttpCode.PUT_OK)
  }

  //~~~~~ Create and update business policies ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicy+" - add "+businessPolicy+" before service exists - should fail") {
    val input = PostPutBusinessPolicyRequest(businessPolicy, None,
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None ),
      None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("Add service for future tests") {
    val svcInput = PostPutServiceRequest("test-service", None, public = false, None, svcurl, svcversion, svcarch, "multiple", None, None, Some(List(Map("name" -> "foo"))), "{\"services\":{}}","a",None)
    val svcResponse = Http(URL+"/services").postData(write(svcInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+svcResponse.code+", response.body: "+svcResponse.body)
    assert(svcResponse.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/business/policies/"+businessPolicy+" - update business policy that is not there yet - should fail") {
    val input = PostPutBusinessPolicyRequest("Bad BusinessPolicy", None,
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicy+" - add "+businessPolicy+" as user") {
    val input = PostPutBusinessPolicyRequest(businessPolicy, Some("desc"),
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, Some(Map("priority_value" -> 50)), Some(Map("lifecycle" -> "immediate")))), Some(Map("check_agreement_status" -> 120)) ),
      Some(List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )),
      Some(List(OneProperty("purpose",None,"location"))), Some(List("a == b"))
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("business policy '"+orgBusinessPolicy+"' created"))
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicy+" - add "+businessPolicy+" again - should fail") {
    val input = PostPutBusinessPolicyRequest("Bad BusinessPolicy", None,
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ALREADY_EXISTS)
  }

  test("PUT /orgs/"+orgid+"/business/policies/"+businessPolicy+" - update as same user, w/o priority, upgradePolicy, nodeHealth") {
    val input = PostPutBusinessPolicyRequest(businessPolicy, Some("desc updated"),
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      Some(List( OneUserInputValue("UI_STRING","mystr - updated"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )),
      Some(List(OneProperty("purpose",None,"location2"))), Some(List("a == c"))
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/business/policies/"+businessPolicy+" - update as 2nd user - should fail") {
    val input = PostPutBusinessPolicyRequest("Bad BusinessPolicy", Some("desc"),
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/business/policies/"+businessPolicy+" - update as agbot - should fail") {
    val input = PostPutBusinessPolicyRequest("Bad BusinessPolicy", None,
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/business/policies/"+businessPolicy2+" - invalid business policy body") {
    val badJsonInput = """{
      "labelxx": "GPS x86_64"
    }"""
    val response = Http(URL+"/business/policies/"+businessPolicy2).postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicy2+" - add "+businessPolicy2+" as node - should fail") {
    val input = PostPutBusinessPolicyRequest("Bad BusinessPolicy2", None,
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicy2+" - add "+businessPolicy2+" as 2nd user") {
    val input = PostPutBusinessPolicyRequest(businessPolicy2, None,
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  //~~~~~ Get (verify) business policies ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("GET /orgs/"+orgid+"/business/policies") {
    val response: HttpResponse[String] = Http(URL+"/business/policies").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetBusinessPoliciesResponse]
    assert(respObj.businessPolicy.size === 2)

    assert(respObj.businessPolicy.contains(orgBusinessPolicy))
    var bp = respObj.businessPolicy(orgBusinessPolicy)
    assert(bp.label === businessPolicy)
    assert(bp.description === "desc updated")
    assert(bp.owner === orguser)
    assert(bp.properties.head.name === "purpose")
    assert(bp.properties.head.value === "location2")
    assert(bp.constraints.head === "a == c")

    assert(respObj.businessPolicy.contains(orgBusinessPolicy2))
    bp = respObj.businessPolicy(orgBusinessPolicy2)
    assert(bp.label === businessPolicy2)
    assert(bp.owner === orguser2)
  }

  test("GET /orgs/"+orgid+"/business/policies - filter owner and label") {
    val response: HttpResponse[String] = Http(URL+"/business/policies").headers(ACCEPT).headers(USERAUTH).param("owner",orguser).param("label",businessPolicy+"%").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetBusinessPoliciesResponse]
    assert(respObj.businessPolicy.size === 1)
    assert(respObj.businessPolicy.contains(orgBusinessPolicy))
  }

  test("GET /orgs/"+orgid+"/business/policies - filter by label") {
    val response: HttpResponse[String] = Http(URL+"/business/policies").headers(ACCEPT).headers(USERAUTH).param("label",businessPolicy).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetBusinessPoliciesResponse]
    assert(respObj.businessPolicy.size === 1)
    assert(respObj.businessPolicy.contains(orgBusinessPolicy))
  }

  test("GET /orgs/"+orgid+"/business/policies - as node") {
    val response: HttpResponse[String] = Http(URL+"/business/policies").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetBusinessPoliciesResponse]
    assert(respObj.businessPolicy.size === 2)
  }

  test("GET /orgs/"+orgid+"/business/policies - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/business/policies").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetBusinessPoliciesResponse]
    assert(respObj.businessPolicy.size === 2)
  }

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy+" - as user") {
    val response: HttpResponse[String] = Http(URL+"/business/policies/"+businessPolicy).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetBusinessPoliciesResponse]
    assert(respObj.businessPolicy.size === 1)

    assert(respObj.businessPolicy.contains(orgBusinessPolicy))
    val pt = respObj.businessPolicy(orgBusinessPolicy)     // the 2nd get turns the Some(val) into val
    assert(pt.label === businessPolicy)
    val ui = pt.userInput
    var uiElem = ui.find(u => u.name=="UI_STRING").orNull
    assert((uiElem !== null) && (uiElem.value === "mystr - updated"))
    uiElem = ui.find(u => u.name=="UI_INT").orNull
    assert((uiElem !== null) && (uiElem.value === 5))
    uiElem = ui.find(u => u.name=="UI_BOOLEAN").orNull
    assert((uiElem !== null) && (uiElem.value === true))

    // Verify the lastUpdated from the PUT above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastUp = ZonedDateTime.parse(pt.lastUpdated).toEpochSecond
    assert(now - lastUp <= 5)    // should not be more than 3 seconds from the time the put was done above
  }
  // the test to try to get an business policy that doesnt exist is at the end when we are cleaning up

  //~~~~~ Patch and get (verify) business policies ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - the description and userInput as user") {
    var jsonInput = """{ "description": "this is now patched" }"""
    var response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)

    jsonInput = """{ "userInput": [{ "name": "UI_INT", "value": 7 }] }"""
    response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - as user2 - should fail") {
    val jsonInput = """{
      "description": "bad patch"
    }"""
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy+" - as agbot, check patch by getting 1 attr at a time") {
    var response: HttpResponse[String] = Http(URL+"/business/policies/"+businessPolicy).headers(ACCEPT).headers(AGBOTAUTH).param("attribute","description").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    var respObj = parse(response.body).extract[GetBusinessPolicyAttributeResponse]
    assert(respObj.attribute === "description")
    assert(respObj.value === "this is now patched")

    response = Http(URL+"/business/policies/"+businessPolicy).headers(ACCEPT).headers(AGBOTAUTH).param("attribute","userInput").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    respObj = parse(response.body).extract[GetBusinessPolicyAttributeResponse]
    assert(respObj.attribute === "userInput")
    val ui = parse(respObj.value).extract[List[OneUserInputValue]]
    info("ui: "+ui.toString())
    val uiElem = ui.find(u => u.name=="UI_INT").orNull
    assert((uiElem !== null) && (uiElem.value === 7))
  }

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy+"notthere - as user - should fail") {
    val response: HttpResponse[String] = Http(URL+"/business/policies/"+businessPolicy+"notthere").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - the properties") {
    val jsonInput = """{ "properties": [{"name":"purpose", "value":"location3"}] }"""
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - the constraints") {
    val jsonInput = """{ "constraints": ["a == d"] }"""
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy+" - to verify properties and constraints patches") {
    val response: HttpResponse[String] = Http(URL+"/business/policies/"+businessPolicy).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetBusinessPoliciesResponse]
    assert(respObj.businessPolicy.size === 1)
    assert(respObj.businessPolicy.contains(orgBusinessPolicy))
    val bp = respObj.businessPolicy(orgBusinessPolicy)
    assert(bp.label === businessPolicy)
    assert(bp.properties.head.name === "purpose")
    assert(bp.properties.head.value === "location3")
    assert(bp.constraints.head === "a == d")
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - patch the service") {
    val input = BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None)
    val jsonInput = """{ "service": """ + write(input) + " }"
    //info("jsonInput: "+jsonInput)
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - patch with a nonexistent service - should fail") {
    val input = BService("foo", orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None )
    val jsonInput = """{ "services": """ + write(input) + " }"
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  //~~~~~ Create create service in org2 and update business policy to reference it ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("POST /orgs/"+orgid2+"/services - add "+orgid2+" service so business policies can reference it") {
    val input = PostPutServiceRequest("IBMTestSvc", Some("desc"), public = true, None, svcurl2, svcversion2, svcarch2, "singleton", None, None, None, "{\"services\":{}}", "a", None)
    val response = Http(URL2+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH2).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/business/policies/"+businessPolicy2+" - update "+businessPolicy2+" referencing service in other org") {
    val input = PostPutBusinessPolicyRequest(businessPolicy2, None,
      BService(svcurl2, orgid2, svcarch2, List(BServiceVersions(svcversion2, None, None)), None),
      None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  //~~~~~ Clean up ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("DELETE /orgs/"+orgid+"/business/policies/"+businessPolicy) {
    val response = Http(URL+"/business/policies/"+businessPolicy).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/business/policies/"+businessPolicy).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("DELETE /orgs/"+orgid+"/users/"+user2+" - which should also delete businessPolicy2") {
    val response = Http(URL+"/users/"+user2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy2+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/business/policies/"+businessPolicy2).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("DELETE /orgs/"+orgid2+"/services/"+service2) {
    val response = Http(URL2+"/services/"+service2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("Cleanup - DELETE all test business policies") {
    deleteAllUsers()
  }

  /** Delete the orgs we used for this test */
  test("DELETE orgs") {
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
    response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

}