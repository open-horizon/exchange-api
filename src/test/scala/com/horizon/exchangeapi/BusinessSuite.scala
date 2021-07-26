package com.horizon.exchangeapi

import java.time._

import com.horizon.exchangeapi._
import com.horizon.exchangeapi.tables._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import scalaj.http._

import scala.collection.immutable._
import scala.collection.mutable.ListBuffer

/**
 * Tests for the /business/policies routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class BusinessSuite extends AnyFunSuite {

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
  val USERAUTH = ("Authorization","Basic "+ApiUtils.encode(orguser+":"+pw))
  val USERAUTH2 = ("Authorization","Basic "+ApiUtils.encode(org2user+":"+pw))
  val user2 = "10000"
  val orguser2 = authpref+user2
  val pw2 = user2+"pw"
  val USER2AUTH = ("Authorization","Basic "+ApiUtils.encode(orguser2+":"+pw2))
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+ApiUtils.encode(rootuser+":"+rootpw))
  val nodeId = "9913"     // the 1st node created, that i will use to run some rest methods
  val nodeToken = nodeId+"tok"
  val NODEAUTH = ("Authorization","Basic "+ApiUtils.encode(authpref+nodeId+":"+nodeToken))
  val agbotId = "9948"
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+ApiUtils.encode(authpref+agbotId+":"+agbotToken))
  val svcurl = "ibm.netspeed"
  val svcarch = "amd64"
  val svcversion = "1.0.0"
  val businessPolicy = "mybuspol"
  val orgBusinessPolicy = authpref+businessPolicy
  val businessPolicy2 = "mybuspol2"
  val orgBusinessPolicy2 = authpref+businessPolicy2
  val businessPolicy3 = "mybuspol3"
  val orgBusinessPolicy3 = authpref+businessPolicy3
  val businessPolicy4 = "mybuspol4"
  val orgBusinessPolicy4 = authpref+businessPolicy4
  val svcurl2 = "ibm.pws"
  val svcversion2 = "9.7.5"
  val svcarch2 = "arm"
  val service2 = svcurl2 + "_" + svcversion2 + "_" + svcarch2
  val ALL_VERSIONS = "[0.0.0,INFINITY)"
  val NOORGURL = urlRoot+"/v1"
  val maxRecords = 10000
  val secondsAgo = 120
  val orgsList = new ListBuffer[String]()

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers() = {
    for (i <- List(user,user2)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    }
  }

  //~~~~~ Clean up from previous run, and create orgs, users, node, agbot ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("POST /orgs/"+orgid+" - create org") {
    // Try deleting it 1st, in case it is left over from previous test
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)

    var input = PostPutOrgRequest(None, "My Org", "desc", None, None, None)
    response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    orgsList+=orgid

    // Try deleting it 1st, in case it is left over from previous test
    response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)

    input = PostPutOrgRequest(None, "My Org2", "desc", None, None, None)
    response = Http(URL2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    orgsList+=orgid2
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the business policies, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllUsers()
  }

  test("Add users, node, agbot for future tests") {
    var userInput = PostPutUsersRequest(pw, admin = false, Some(false), user + "@hotmail.com")
    var userResponse = Http(URL + "/users/" + user).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + userResponse.code + ", userResponse.body: " + userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK.intValue)

    userInput = PostPutUsersRequest(pw2, admin = false, Some(false), user2 + "@hotmail.com")
    userResponse = Http(URL + "/users/" + user2).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + userResponse.code + ", userResponse.body: " + userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK.intValue)

    userInput = PostPutUsersRequest(pw, admin = false, Some(false), user + "@hotmail.com")
    userResponse = Http(URL2 + "/users/" + user).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + userResponse.code + ", userResponse.body: " + userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK.intValue)

    val devInput = PutNodesRequest(nodeToken, "bc dev test", None, "", Some(List(RegService("foo", 1, None, "{}", List(
      Prop("arch", "arm", "string", "in"),
      Prop("version", "2.0.0", "version", "in"),
      Prop("blockchainProtocols", "agProto", "list", "in")), ""))), None, None, None, "NODEABC", None, None)
    val devResponse = Http(URL + "/nodes/" + nodeId).postData(write(devInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + devResponse.code)
    assert(devResponse.code === HttpCode.PUT_OK.intValue)

    val agbotInput = PutAgbotsRequest(agbotToken, "agbot" + agbotId + "-norm", None, "ABC")
    val agbotResponse = Http(URL + "/agbots/" + agbotId).postData(write(agbotInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + agbotResponse.code + ", agbotResponse.body: " + agbotResponse.body)
    assert(agbotResponse.code === HttpCode.PUT_OK.intValue)
  }

  //~~~~~ Create and update business policies ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicy+" - add "+businessPolicy+" before service exists - should fail") {
    val input = PostPutBusinessPolicyRequest(businessPolicy, None,
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None ),
      None, None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("Add service for future tests") {
    val svcInput = PostPutServiceRequest("test-service", None, public = false, None, svcurl, svcversion, svcarch, "multiple", None, None, Some(List(Map("name" -> "foo"))), Some("{\"services\":{}}"),Some("a"),None, None, None)
    val svcResponse = Http(URL+"/services").postData(write(svcInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+svcResponse.code+", response.body: "+svcResponse.body)
    assert(svcResponse.code === HttpCode.POST_OK.intValue)
  }

  test("PUT /orgs/"+orgid+"/business/policies/"+businessPolicy+" - update business policy that is not there yet - should fail") {
    val input = PostPutBusinessPolicyRequest("Bad BusinessPolicy", None,
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      None, None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    info("headers: "+response.headers)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("PUT /orgs/"+orgid+"/business/policies/"+businessPolicy+" - with no service versions - should fail") {
    val input = PostPutBusinessPolicyRequest("Bad BusinessPolicy", None,
      BService(svcurl, orgid, svcarch, List(), None),
      None, None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicy+" - add "+businessPolicy+" with invalid svc ref in userInput") {
    val input = PostPutBusinessPolicyRequest(businessPolicy, Some("desc"),
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None ),
      Some(List( OneUserInputService(orgid, svcurl, None, Some("[9.9.9,9.9.9]"), List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicy+" - add "+businessPolicy+" as user") {
    val input = PostPutBusinessPolicyRequest(businessPolicy, Some("desc"),
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, Some(Map("priority_value" -> 50)), Some(Map("lifecycle" -> "immediate")))), Some(Map("check_agreement_status" -> 120)) ),
      Some(List( OneUserInputService(orgid, svcurl, Some(svcarch), Some(svcversion), List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      Some(List( OneSecretBindingService(orgid,svcurl, Some(svcarch), Some(svcversion), List(Map("servicesecret1"->"vaultsecret1"))))),
      Some(List(OneProperty("purpose",None,"location"))), Some(List("a == b"))
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("business policy '"+orgBusinessPolicy+"' created"))
  }

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy+" check patch if secrets are set") {
    val response = Http(URL+"/business/policies/"+businessPolicy).headers(ACCEPT).headers(USERAUTH).param("attribute","secretBinding").asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetBusinessPolicyAttributeResponse]
    assert(respObj.attribute === "secretBinding")
    val uis = parse(respObj.value).extract[List[OneSecretBindingService]]
    //info("ui: "+ui.toString())
    val uisElem = uis.head
    assert(uisElem.serviceUrl === svcurl)
    assert(uisElem.serviceArch.getOrElse("") === svcarch)
    assert(uisElem.serviceVersionRange.getOrElse("") === svcversion)
    val inp = uisElem.secrets
    var inpElem = inp.head.get("servicesecret1")
    assert((inpElem !== null) && (inpElem === Some("vaultsecret1")))
  }

  test("POST /orgs/"+orgid+"/business/policies/BusPolNoService - add BusPolNoService as user -- test if service field required") {
    val input = """{
                      "label":"BusPolNoService",
                      "description":"Test buspol with no service section to see if this is possible",
                      "userInput":[{"serviceOrgid":"BusinessSuiteTests","serviceUrl":"ibm.netspeed","inputs":[{"name":"UI_STRING","value":"mystr"},{"name":"UI_INT","value":5},{"name":"UI_BOOLEAN","value":true}]}],
                      "properties":[{"name":"purpose","value":"location"}],
                      "constraints":["a == b"]
                  }""".stripMargin
    val response = Http(URL+"/business/policies/BusPolNoService").postData(input).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    //assert(response.body.contains("No usable value for service"))
  }

  test("POST /orgs/"+orgid+"/changes - verify " + businessPolicy + " was created and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == businessPolicy) && (y.operation == ResChangeOperation.CREATED.toString) && (y.resource == "policy")}))
  }

  test("POST /orgs/"+orgid+"/business/policies/BusPolNoService2 - add BusPolNoService as user -- test if service field required") {
    val input = """{
                      "label":"BusPolNoService2",
                      "description":"Test buspol with empty service section to see if this is possible",
                      "service":{},
                      "userInput":[{"serviceOrgid":"BusinessSuiteTests","serviceUrl":"ibm.netspeed","inputs":[{"name":"UI_STRING","value":"mystr"},{"name":"UI_INT","value":5},{"name":"UI_BOOLEAN","value":true}]}],
                      "properties":[{"name":"purpose","value":"location"}],
                      "constraints":["a == b"]
                  }""".stripMargin
    val response = Http(URL+"/business/policies/BusPolNoService2").postData(input).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    //assert(response.body.contains("No usable value for service"))
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicy3+" - add "+businessPolicy3+" as user with service.arch=\"\"") {
    val input = PostPutBusinessPolicyRequest(businessPolicy3, Some("desc"),
      BService(svcurl, orgid, "", List(BServiceVersions(svcversion, Some(Map("priority_value" -> 50)), Some(Map("lifecycle" -> "immediate")))), Some(Map("check_agreement_status" -> 120)) ),
      Some(List( OneUserInputService(orgid, svcurl, Some(svcarch), Some(svcversion), List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      Some(List( OneSecretBindingService(orgid,svcurl, Some(svcarch), Some(svcversion), List(Map("servicesecret"->"vaultsecret"))))),
      Some(List(OneProperty("purpose",None,"location"))), Some(List("a == b"))
    )
    val response = Http(URL+"/business/policies/"+businessPolicy3).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("business policy '"+orgBusinessPolicy3+"' created"))
  }

  test("GET /orgs/"+orgid2+"/business/policies/"+businessPolicy3+" check patch 2 if secrets are set") {
    val response = Http(URL+"/business/policies/"+businessPolicy3).headers(ACCEPT).headers(USERAUTH).param("attribute","secretBinding").asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetBusinessPolicyAttributeResponse]
    assert(respObj.attribute === "secretBinding")
    val uis = parse(respObj.value).extract[List[OneSecretBindingService]]
    //info("ui: "+ui.toString())
    val uisElem = uis.head
    assert(uisElem.serviceUrl === svcurl)
    assert(uisElem.serviceArch.getOrElse("") === svcarch)
    assert(uisElem.serviceVersionRange.getOrElse("") === svcversion)
    val inp = uisElem.secrets
    var inpElem = inp.head.get("servicesecret")
    assert((inpElem !== null) && (inpElem === Some("vaultsecret")))
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicy4+" - add "+businessPolicy4+" as user with service.arch=\"*\"") {
    val input = PostPutBusinessPolicyRequest(businessPolicy4, Some("desc"),
      BService(svcurl, orgid, "*", List(BServiceVersions(svcversion, Some(Map("priority_value" -> 50)), Some(Map("lifecycle" -> "immediate")))), Some(Map("check_agreement_status" -> 120)) ),
      Some(List( OneUserInputService(orgid, svcurl, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      Some(List( OneSecretBindingService(orgid,svcurl, None, None, List(Map("service-secret1"->"vault-secret1"))))),
      Some(List(OneProperty("purpose",None,"location"))), Some(List("a == b"))
    )
    val response = Http(URL+"/business/policies/"+businessPolicy4).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("business policy '"+orgBusinessPolicy4+"' created"))
  }

  test("DELETE /orgs/"+orgid+"/business/policies/"+businessPolicy3) {
    val response = Http(URL+"/business/policies/"+businessPolicy3).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + businessPolicy3 + " was deleted and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == businessPolicy3) && (y.operation == ResChangeOperation.DELETED.toString) && (y.resource == "policy")}))
  }

  test("DELETE /orgs/"+orgid+"/business/policies/"+businessPolicy4) {
    val response = Http(URL+"/business/policies/"+businessPolicy4).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicy+" - add "+businessPolicy+" again - should fail") {
    val input = PostPutBusinessPolicyRequest("Bad BusinessPolicy", None,
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      None, None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ALREADY_EXISTS.intValue)
  }

  test("PUT /orgs/"+orgid+"/business/policies/"+businessPolicy+" - update as same user, w/o priority, upgradePolicy, nodeHealth, secretbinding ") {
    val input = PostPutBusinessPolicyRequest(businessPolicy, Some("desc updated"),
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      Some(List( OneUserInputService(orgid, svcurl, Some(svcarch), Some(ALL_VERSIONS), List( OneUserInputValue("UI_STRING","mystr - updated"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      Some(List( OneSecretBindingService(orgid,svcurl, None, None, List(Map("servicesecret1"->"vaultsecretupdated"))))),
      Some(List(OneProperty("purpose",None,"location2"))), Some(List("a == c"))
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/"+orgid2+"/business/policies/"+businessPolicy+" check patch 2 if secrets are set") {
    val response = Http(URL+"/business/policies/"+businessPolicy).headers(ACCEPT).headers(USERAUTH).param("attribute","secretBinding").asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetBusinessPolicyAttributeResponse]
    assert(respObj.attribute === "secretBinding")
    val uis = parse(respObj.value).extract[List[OneSecretBindingService]]
    //info("ui: "+ui.toString())
    val uisElem = uis.head
    val inp = uisElem.secrets
    var inpElem = inp.head.get("servicesecret1")
    assert((inpElem !== null) && (inpElem === Some("vaultsecretupdated")))
  }

  test("POST /orgs/"+orgid+"/changes - verify " + businessPolicy + " was updated and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == businessPolicy) && (y.operation == ResChangeOperation.CREATEDMODIFIED.toString) && (y.resource == "policy")}))
  }

  test("PUT /orgs/"+orgid+"/business/policies/"+businessPolicy+" - update as 2nd user - should fail") {
    val input = PostPutBusinessPolicyRequest("Bad BusinessPolicy", Some("desc"),
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      None, None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PUT /orgs/"+orgid+"/business/policies/"+businessPolicy+" - update as agbot - should fail") {
    val input = PostPutBusinessPolicyRequest("Bad BusinessPolicy", None,
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      None, None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PUT /orgs/"+orgid+"/business/policies/"+businessPolicy2+" - invalid business policy body") {
    val badJsonInput = """{
      "labelxx": "GPS x86_64"
    }"""
    val response = Http(URL+"/business/policies/"+businessPolicy2).postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicy2+" - add "+businessPolicy2+" as node - should fail") {
    val input = PostPutBusinessPolicyRequest("Bad BusinessPolicy2", None,
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      None, None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicy2+" - add "+businessPolicy2+" as 2nd user") {
    val input = PostPutBusinessPolicyRequest(businessPolicy2, None,
      BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None),
      None, None, None, None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  //~~~~~ Get (verify) business policies ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("GET /orgs/"+orgid+"/business/policies") {
    val response: HttpResponse[String] = Http(URL+"/business/policies").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
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
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetBusinessPoliciesResponse]
    assert(respObj.businessPolicy.size === 1)
    assert(respObj.businessPolicy.contains(orgBusinessPolicy))
  }

  test("GET /orgs/"+orgid+"/business/policies - filter by label") {
    val response: HttpResponse[String] = Http(URL+"/business/policies").headers(ACCEPT).headers(USERAUTH).param("label",businessPolicy).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetBusinessPoliciesResponse]
    assert(respObj.businessPolicy.size === 1)
    assert(respObj.businessPolicy.contains(orgBusinessPolicy))
  }

  test("GET /orgs/"+orgid+"/business/policies - as node") {
    val response: HttpResponse[String] = Http(URL+"/business/policies").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetBusinessPoliciesResponse]
    assert(respObj.businessPolicy.size === 2)
  }

  test("GET /orgs/"+orgid+"/business/policies - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/business/policies").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetBusinessPoliciesResponse]
    assert(respObj.businessPolicy.size === 2)
  }

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy+" - as user") {
    val response: HttpResponse[String] = Http(URL+"/business/policies/"+businessPolicy).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetBusinessPoliciesResponse]
    assert(respObj.businessPolicy.size === 1)

    assert(respObj.businessPolicy.contains(orgBusinessPolicy))
    val pt = respObj.businessPolicy(orgBusinessPolicy)     // the 2nd get turns the Some(val) into val
    assert(pt.label === businessPolicy)
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
    assert(now - lastUp <= 5)    // should not be more than 3 seconds from the time the put was done above
  }
  // the test to try to get an business policy that doesnt exist is at the end when we are cleaning up

  //~~~~~ Patch and get (verify) business policies ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - no service org - should fail") {
    val jsonInput = """{ "service": { "name": """"+svcurl+"""", "arch": """"+svcarch+"""", "serviceVersions": [{ "version": "1.2.3" }] } }"""
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - no service versions - should fail") {
    val jsonInput = """{ "service": { "org": """"+orgid+"""", "name": """"+svcurl+"""", "arch": """"+svcarch+"""", "serviceVersions": [] } }"""
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - userInput with an invalid service ref - should fail") {
    val jsonInput = """{ "userInput": [{ "serviceOrgid": """"+orgid+"""", "serviceUrl": """"+svcurl+"""", "serviceArch": "fooarch", "serviceVersionRange": """"+ALL_VERSIONS+"""", "inputs": [{"name":"UI_STRING","value":"mystr - updated"}, {"name":"UI_INT","value": 7}, {"name":"UI_BOOLEAN","value": true}] }] }"""
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - the description and userInput as user") {
    var jsonInput = """{ "description": "this is now patched" }"""
    var response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)

    jsonInput = """{ "userInput": [{ "serviceOrgid": """"+orgid+"""", "serviceUrl": """"+svcurl+"""", "serviceArch": """"+svcarch+"""", "serviceVersionRange": """"+ALL_VERSIONS+"""", "inputs": [{"name":"UI_STRING","value":"mystr - updated"}, {"name":"UI_INT","value": 7}, {"name":"UI_BOOLEAN","value": true}] }] }"""
    response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + businessPolicy + " was updated and stored via PATCH") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == businessPolicy) && (y.operation == ResChangeOperation.MODIFIED.toString) && (y.resource == "policy")}))
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - userInput but without heading so invalid input") {
    val jsonInput = """[{ "serviceOrgid": """"+orgid+"""", "serviceUrl": """"+svcurl+"""", "serviceArch": """"+svcarch+"""", "serviceVersionRange": """"+ALL_VERSIONS+"""", "inputs": [{"name":"UI_STRING","value":"mystr - updated"}, {"name":"UI_INT","value": 7}, {"name":"UI_BOOLEAN","value": true}] }]"""
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    //assert(response.body.contains("invalid input"))
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - with whitespace") {
    var jsonInput = """   { "description": "this is now patched" }    """
    var response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)

    jsonInput =
      """
        { "userInput": [{ "serviceOrgid": """"+orgid+"""", "serviceUrl": """"+svcurl+"""", "serviceArch": """"+svcarch+"""", "serviceVersionRange": """"+ALL_VERSIONS+
        """", "inputs": [{"name":"UI_STRING","value":"mystr - updated"}, {"name":"UI_INT","value": 7}, {"name":"UI_BOOLEAN","value": true}] }] }

          """
    response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - as user2 - should fail") {
    val jsonInput = """{
      "description": "bad patch"
    }"""
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy+" - as agbot, check patch by getting 1 attr at a time") {
    var response: HttpResponse[String] = Http(URL+"/business/policies/"+businessPolicy).headers(ACCEPT).headers(AGBOTAUTH).param("attribute","description").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    var respObj = parse(response.body).extract[GetBusinessPolicyAttributeResponse]
    assert(respObj.attribute === "description")
    assert(respObj.value === "this is now patched")

    response = Http(URL+"/business/policies/"+businessPolicy).headers(ACCEPT).headers(AGBOTAUTH).param("attribute","userInput").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    respObj = parse(response.body).extract[GetBusinessPolicyAttributeResponse]
    assert(respObj.attribute === "userInput")
    val uis = parse(respObj.value).extract[List[OneUserInputService]]
    //info("ui: "+ui.toString())
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

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy+"notthere - as user - should fail") {
    val response: HttpResponse[String] = Http(URL+"/business/policies/"+businessPolicy+"notthere").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - the properties") {
    val jsonInput = """{ "properties": [{"name":"purpose", "value":"location3"}] }"""
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - the constraints") {
    val jsonInput = """{ "constraints": ["a == d"] }"""
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy+" - to verify properties and constraints patches") {
    val response: HttpResponse[String] = Http(URL+"/business/policies/"+businessPolicy).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
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
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - patch with a nonexistent service - should fail") {
    val input = BService("foo", orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None )
    val jsonInput = """{ "services": """ + write(input) + " }"
    val response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  //~~~~~ Create create service in org2 and update business policy to reference it ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("POST /orgs/"+orgid2+"/services - add "+orgid2+" service so business policies can reference it") {
    val input = PostPutServiceRequest("TestSvc", Some("desc"), public = true, None, svcurl2, svcversion2, svcarch2, "singleton", None, None, None, Some("{\"services\":{}}"), Some("a"), None, None, None)
    val response = Http(URL2+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH2).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("PUT /orgs/"+orgid+"/business/policies/"+businessPolicy2+" - update "+businessPolicy2+" referencing service in other org") {
    val input = PostPutBusinessPolicyRequest(businessPolicy2, None,
      BService(svcurl2, orgid2, svcarch2, List(BServiceVersions(svcversion2, None, None)), None),
      None, None, None ,None
    )
    val response = Http(URL+"/business/policies/"+businessPolicy2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }
  
  test("POST /orgs/"+orgid+"/business/policies/buspol - add buspol Business Policy -- test the bad request body") {
    val input = """{
                      "label":"buspol",
                      "description":"Test buspol with bad response body",
                      "service":{"name":"""+svcurl+""","arch":"""+svcarch+""","org":"""+orgid+""","serviceVersions":[{"version":"""+svcversion+"""}]},
                      "userInput":[{"serviceOrgid":"BusinessSuiteTests","serviceUrl":"ibm.netspeed","inputs":[{"name":"UI_STRING","value":"mystr"},{"name":"UI_INT","value":5},{"name":"UI_BOOLEAN","value":true}]}],
                      "secretBinding": [{ "serviceOrgid":"BusinessSuiteTests","serviceUrl":"ibm.netspeed","serviceVersionRange": "x.y.z", "secrets":  { "FirstSecret": "secret1","Foo": "Bar" }}],
                      "properties":[{"name":"purpose","value":"location"}],
                      "constraints":["a == b"]
                  }""".stripMargin
    val response = Http(URL+"/business/policies/buspol").postData(input).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PUT /orgs/"+orgid+"/business/policies/SB3 - add SB3 Business Policy -- test the bad request body") {
    val input = """{
                      "label":"SB1",
                      "description":"Test buspol with bad response body",
                      "service":{"name":"""+svcurl+""","arch":"""+svcarch+""","org":"""+orgid+""","serviceVersions":[{"version":"""+svcversion+"""}]},
                      "userInput":[{"serviceOrgid":"BusinessSuiteTests","serviceUrl":"ibm.netspeed","inputs":[{"name":"UI_STRING","value":"mystr"},{"name":"UI_INT","value":5},{"name":"UI_BOOLEAN","value":true}]}],
                      "secretBinding": [{ "serviceOrgid":"BusinessSuiteTests","serviceUrl":"ibm.netspeed","serviceVersionRange": "x.y.z", "secrets":  { "FirstSecret": "secret1","Foo": "Bar" }}],
                      "properties":[{"name":"purpose","value":"location"}],
                      "constraints":["a == b"]
                  }""".stripMargin
    val response = Http(URL+"/business/policies/SB3").postData(input).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

    test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - the secretBinding") {
    var jsonInput = """{"secretBinding": [{ "serviceOrgid":"BusinessSuiteTests","serviceUrl":"ibm.netspeed","serviceVersionRange": "x.y.z", "secrets": [{"secret1": "vaultsecret1"},{"secret2": "vaultsecret2"}]}]}"""
    var response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy+" check if secrets are set in case of patch request") {
    val response = Http(URL+"/business/policies/"+businessPolicy).headers(ACCEPT).headers(USERAUTH).param("attribute","secretBinding").asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetBusinessPolicyAttributeResponse]
    assert(respObj.attribute === "secretBinding")
    val uis = parse(respObj.value).extract[List[OneSecretBindingService]]
    //info("ui: "+ui.toString())
    val uisElem = uis.head
    val inp = uisElem.secrets
    var inpElem = inp.head.get("secret1")
    assert((inpElem !== null) && (inpElem === Some("vaultsecret1")))
  }

  test("PATCH /orgs/"+orgid+"/business/policies/"+businessPolicy+" - the secretBinding in invalid format") {
    var jsonInput = """{"secretBinding": [{ "serviceOrgid":"BusinessSuiteTests","serviceUrl":"ibm.netspeed","serviceVersionRange": "x.y.z", "secrets":  { "FirstSecret": "secret1","Foo": "Bar" }}]}"""
    var response = Http(URL+"/business/policies/"+businessPolicy).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  /*
  someday: when all test suites are run at the same time, there are sometimes timing problems them all setting config values...
  ExchConfig.load()
  test("POST /orgs/"+orgid+"/business/policies/anotherOne - with low maxMessagesInMailbox") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      // ExchConfig.load  <-- already do this earlier
      val origMaxBusinessPolicies = ExchConfig.getInt("api.limits.maxBusinessPolicies")
      info(origMaxBusinessPolicies.toString)
      // Change the maxMessagesInMailbox config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxBusinessPolicies", "1")
      var response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)

      // Now try adding another 2 buspol - expect the second one to be rejected
      var input = PostPutBusinessPolicyRequest("anotherOne", Some("desc"),
        BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, Some(Map("priority_value" -> 50)), Some(Map("lifecycle" -> "immediate")))), Some(Map("check_agreement_status" -> 120)) ),
        Some(List( OneUserInputService(orgid, svcurl, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
        Some(List(OneProperty("purpose",None,"location"))), Some(List("a == b"))
      )
      response = Http(URL+"/business/policies/anotherOne").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.POST_OK.intValue)

      input = PostPutBusinessPolicyRequest("anotherOne2", Some("desc"),
        BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, Some(Map("priority_value" -> 50)), Some(Map("lifecycle" -> "immediate")))), Some(Map("check_agreement_status" -> 120)) ),
        Some(List( OneUserInputService(orgid, svcurl, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
        Some(List(OneProperty("purpose",None,"location"))), Some(List("a == b"))
      )
      response = Http(URL+"/business/policies/anotherOne2").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      val respObj = parse(response.body).extract[ApiResponse]
      assert(respObj.msg.contains("Access Denied: you are over the limit of 1 business policies"))

      // Restore the maxMessagesInMailbox config value in the svr
      configInput = AdminConfigRequest("api.limits.maxBusinessPolicies", origMaxBusinessPolicies.toString)
      response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)
    }
  }

   */

  //~~~~~ Clean up ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("DELETE /orgs/"+orgid+"/business/policies/"+businessPolicy) {
    val response = Http(URL+"/business/policies/"+businessPolicy).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/business/policies/"+businessPolicy).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("DELETE /orgs/"+orgid+"/business/policies/"+businessPolicy2+" - so owner cache will also be deleted") {
    val response = Http(URL+"/business/policies/"+businessPolicy2).method("delete").headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("DELETE /orgs/"+orgid+"/users/"+user2) {
    val response = Http(URL+"/users/"+user2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("GET /orgs/"+orgid+"/business/policies/"+businessPolicy2+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/business/policies/"+businessPolicy2).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("DELETE /orgs/"+orgid2+"/services/"+service2) {
    val response = Http(URL2+"/services/"+service2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("Cleanup - DELETE all test business policies") {
    deleteAllUsers()
  }

  /** Delete the orgs we used for this test */
  test("DELETE orgs") {
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
    response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("Cleanup -- DELETE org changes") {
    for (org <- orgsList){
      val input = DeleteOrgChangesRequest(List())
      val response = Http(urlRoot+"/v1/orgs/"+org+"/changes/cleanup").postData(write(input)).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue)
    }
  }
}