package org.openhorizon.exchangeapi.route.agreementbot

import org.openhorizon.exchangeapi.route.administration.{AdminConfigRequest, DeleteIBMChangesRequest, DeleteOrgChangesRequest}
import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import scalaj.http._
import org.json4s._
import org.openhorizon.exchangeapi.auth.Role
import org.openhorizon.exchangeapi.route.agreementbot.{GetAgbotAgreementsResponse, GetAgbotAttributeResponse, GetAgbotBusinessPolsResponse, GetAgbotPatternsResponse, GetAgbotsResponse, PostAgbotBusinessPolRequest, PostAgbotPatternRequest, PostAgbotsMsgsRequest, PostAgreementsConfirmRequest, PutAgbotAgreementRequest, PutAgbotsRequest}
import org.openhorizon.exchangeapi.route.deploymentpattern.{GetPatternsResponse, PostPutPatternRequest}
import org.openhorizon.exchangeapi.route.deploymentpolicy.PostPutBusinessPolicyRequest
import org.openhorizon.exchangeapi.route.node.{PostNodesMsgsRequest, PutNodesRequest}
import org.openhorizon.exchangeapi.route.organization.{MaxChangeIdResponse, PostPutOrgRequest, ResourceChangesRequest, ResourceChangesRespObject}
import org.openhorizon.exchangeapi.route.service.{GetServicesResponse, PostPutServiceRequest}
import org.openhorizon.exchangeapi.route.user.PostPutUsersRequest
import org.openhorizon.exchangeapi.table.agreementbot.AAService
import org.openhorizon.exchangeapi.table.deploymentpattern.{PServiceVersions, PServices}
import org.openhorizon.exchangeapi.table.deploymentpolicy.{BService, BServiceVersions}
import org.openhorizon.exchangeapi.table.resourcechange.ResChangeOperation
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ApiUtils, Configuration, HttpCode}

import scala.collection.mutable.ListBuffer
//import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import org.openhorizon.exchangeapi._
import org.openhorizon.exchangeapi.table._
import scala.collection.immutable._
import java.time._

@RunWith(classOf[JUnitRunner])
class TestAgbotBusinessPols extends AnyFunSuite {

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val orgid = "AgbotsSuiteTests"
  val authpref=orgid+"/"
  val URL = urlRoot+"/v1/orgs/"+orgid
  val NOORGURL = urlRoot+"/v1"
  val orgid2 = "AgbotsSuiteTests2"
  val authpref2=orgid2+"/"
  val URL2 = urlRoot+"/v1/orgs/"+orgid2
  val user = "9990"
  val pw = user+"pw"
  val USERAUTH = ("Authorization","Basic "+ApiUtils.encode(authpref+user+":"+pw))
  val USERAUTH2 = ("Authorization","Basic "+ApiUtils.encode(authpref2+user+":"+pw))
  val BADAUTH = ("Authorization","Basic "+ApiUtils.encode(authpref+user+":"+pw+"x"))
  val rootuser = Role.superUser
  val rootpw = (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+ApiUtils.encode(rootuser+":"+rootpw))
  val agbotId = "9930"
  val orgagbotId = authpref+agbotId
  val agbotToken = agbotId+"TokAbcDefGhi"
  val AGBOTAUTH = ("Authorization","Basic "+ApiUtils.encode(orgagbotId+":"+agbotToken))
  val agbot2Id = "9931"
  val orgagbot2Id = authpref+agbot2Id
  val agbot2Token = agbot2Id+" TokAbcDefGhi"   // intentionally adding a space in the token
  val AGBOT2AUTH = ("Authorization","Basic "+ApiUtils.encode(orgagbot2Id+":"+agbot2Token))
  val agbot3Id = "9932"
  val orgagbot3Id = authpref+agbot3Id
  val agbot3Token = agbot3Id+"TokAbcDefGhi"
  val AGBOT3AUTH = ("Authorization","Basic "+ApiUtils.encode(orgagbot3Id+":"+agbot3Token))
  val agreementId = "9950"
  val pattern = "mypattern"
  val patId = orgid + "_" + pattern + "_" + orgid
  val pattern2 = "mypattern2"
  val patId2 = orgid2 + "_" + pattern2 + "_" + orgid
  val pattern3 = "mypattern3"
  val patId3 = orgid + "_" + pattern3 + "_" + orgid
  val businessPol = "mybuspol"
  val busPolId = orgid + "_" + businessPol + "_" + orgid
  val businessPol2 = "*"
  val busPolId2 = orgid2 + "_" + businessPol2 + "_" + orgid2
  val svcid = "horizon-services-netspeed_1.0.0_amd64"
  val svcurl = "https://horizon/services/netspeed"
  val svcarch = "amd64"
  val svcversion = "1.0.0"
  val nodeId = "mynode"
  val nodeToken = nodeId+"TokAbcDefGh1"
  val NODEAUTH = ("Authorization","Basic "+ApiUtils.encode(authpref+nodeId+":"+nodeToken))
  val maxRecords = 10000
  val secondsAgo = 120
  val orgsList = new ListBuffer[String]()

  implicit val formats: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers() = {
    for (i <- List(user)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue.intValue)
    }
  }

  /** Delete all the test agbots - this is not longer used because deleting the user deletes these too */
  def deleteAllAgbots() = {
    for (i <- List(agbotId, agbot2Id)) {
      val response = Http(URL+"/agbots/"+i).method("delete").headers(ACCEPT).headers(USERAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    }
  }

  /** Delete all the test agreements - this is no longer used because deleting the user deletes these too */
  def deleteAllAgreements() = {
    for (i <- List(agreementId)) {
      val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+i).method("delete").headers(ACCEPT).headers(USERAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    }
  }
  
  // NOTE: The original test suite relies on sequential execution and shared state between tests,
// which causes tight coupling and makes it hard to maintain or parallelize. 
// As a temporary measure, this test consolidates the creation of dependent resources 
// (orgs, users, agbots) into one setup block to reduce redundancy and avoid early failures.
// The plan is to refactor this into proper beforeAll/afterAll lifecycle hooks in the future.
test("Setup: create orgs, users, agbots") {
  if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")

  // Delete orgs if exist (cleanup)
  var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
  info("DELETE org1 code: " + response.code)
  assert(response.code == HttpCode.DELETED.intValue || response.code == HttpCode.NOT_FOUND.intValue)

  response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
  info("DELETE org2 code: " + response.code)
  assert(response.code == HttpCode.DELETED.intValue || response.code == HttpCode.NOT_FOUND.intValue)

  // Create orgs
  val org1Req = PostPutOrgRequest(None, "My Org", "desc", None, None, None)
  response = Http(URL).postData(write(org1Req)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
  info("Create org1 code: " + response.code)
  assert(response.code == HttpCode.POST_OK.intValue)
  orgsList += orgid

  val org2Req = PostPutOrgRequest(None, "My Org2", "desc", None, None, None)
  response = Http(URL2).postData(write(org2Req)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
  info("Create org2 code: " + response.code)
  assert(response.code == HttpCode.POST_OK.intValue)
  orgsList += orgid2

  // Delete test user (in case it exists)
  val delUserResp = Http(URL + "/users/" + user).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
  info("DELETE user code: " + delUserResp.code)
  assert(delUserResp.code == HttpCode.DELETED.intValue || delUserResp.code == HttpCode.NOT_FOUND.intValue)

  // Create users in both orgs
  val userReq = PostPutUsersRequest(pw, admin = false, Some(false), user + "@hotmail.com")
  response = Http(URL + "/users/" + user).postData(write(userReq)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
  info("Create user in org1 code: " + response.code)
  assert(response.code == HttpCode.POST_OK.intValue)

  response = Http(URL2 + "/users/" + user).postData(write(userReq)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
  info("Create user in org2 code: " + response.code)
  assert(response.code == HttpCode.POST_OK.intValue)

  // Create agbot (includes PATCH publicKey value)
  val agbotReq = PutAgbotsRequest(agbotToken, "agbot" + agbotId + "-norm", Some("newAGBOTABCDEF"), "ABC")
  response = Http(URL + "/agbots/" + agbotId).postData(write(agbotReq)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
  info("Create agbot code: " + response.code)
  assert(response.code == HttpCode.PUT_OK.intValue)

  // Optional: test heartbeat just to verify it lives
  response = Http(URL + "/agbots/" + agbotId + "/heartbeat").method("post").headers(ACCEPT).headers(AGBOTAUTH).asString
  info("Heartbeat agbot code: " + response.code)
  assert(response.code == HttpCode.POST_OK.intValue)
  val postHeartbeatResp = parse(response.body).extract[ApiResponse]
  assert(postHeartbeatResp.code == ApiRespType.OK)
}


  test("POST /orgs/"+orgid+"/services - add "+svcid+" and check that agbot can read it") {
    val input = PostPutServiceRequest("test-service", None, public = false, None, svcurl, svcversion, svcarch, "multiple", None, None, None, Some(""), Some(""), None, None, None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    val response2: HttpResponse[String] = Http(URL+"/services").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response2.code)
    assert(response2.code === HttpCode.OK.intValue)
    val respObj = parse(response2.body).extract[GetServicesResponse]
    assert(respObj.services.size === 1)
  }
  // Note: when we delete the org, this service will get deleted


  //~~~~~ Test the business policy sub-resources ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("POST /orgs/"+orgid+"/business/policies/"+businessPol+" - add "+businessPol+" as user") {
    val input = PostPutBusinessPolicyRequest(businessPol, None, BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None, None), None, None, None, None )
    val response = Http(URL+"/business/policies/"+businessPol).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/businesspols - as user") {
    val input = PostAgbotBusinessPolRequest(orgid, businessPol, None)
    val response = Http(URL+"/agbots/"+agbotId+"/businesspols").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + agbotId + " businesspols was added and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == agbotId) && (y.operation == ResChangeOperation.CREATED.toString) && (y.resource == "agbotbusinesspols")}))
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/businesspols - already exists, should get 409") {
    val input = PostAgbotBusinessPolRequest(orgid, businessPol, None)
    val response = Http(URL+"/agbots/"+agbotId+"/businesspols").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ALREADY_EXISTS2.intValue)
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/businesspols - node org different from business policy org - should fail") {
    val input = PostAgbotBusinessPolRequest(orgid2, "*", Some(orgid))
    val response = Http(URL+"/agbots/"+agbotId+"/businesspols").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/businesspols - add business policy of '*' in other org") {
    val input = PostAgbotBusinessPolRequest(orgid2, businessPol2, Some(orgid2))
    val response = Http(URL+"/agbots/"+agbotId+"/businesspols").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/businesspols - add business policy that does not exist - should fail") {
    val input = PostAgbotBusinessPolRequest(orgid, "notthere", None)
    val response = Http(URL+"/agbots/"+agbotId+"/businesspols").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/businesspols - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/businesspols").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getAgbotResp = parse(response.body).extract[GetAgbotBusinessPolsResponse]
    assert(getAgbotResp.businessPols.size === 2)
    assert(getAgbotResp.businessPols.contains(busPolId))
    assert(getAgbotResp.businessPols.contains(busPolId2))
    val bp = getAgbotResp.businessPols(busPolId)
    assert(bp.businessPol === businessPol)
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/businesspols/"+busPolId2+" - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/businesspols/"+busPolId2).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getAgbotResp = parse(response.body).extract[GetAgbotBusinessPolsResponse]
    assert(getAgbotResp.businessPols.size === 1)

    assert(getAgbotResp.businessPols.contains(busPolId2))
    val bp = getAgbotResp.businessPols(busPolId2)
    assert(bp.businessPol === businessPol2)
  }

  test("DELETE /orgs/"+orgid+"/agbots/"+agbotId+"/businesspols/"+busPolId2+" - delete wildcard business policy") {
    val response = Http(URL+"/agbots/"+agbotId+"/businesspols/"+busPolId2).method("delete").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + agbotId + " businesspols was deleted and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == agbotId) && (y.operation == ResChangeOperation.DELETED.toString) && (y.resource == "agbotbusinesspols")}))
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/businesspols - as agbot - should be 1 less") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/businesspols").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getAgbotResp = parse(response.body).extract[GetAgbotBusinessPolsResponse]
    assert(getAgbotResp.businessPols.size === 1)
    assert(getAgbotResp.businessPols.contains(busPolId))
  }

  test("DELETE /orgs/"+orgid+"/agbots/"+agbotId+"/businesspols - delete all as user") {
    val response = Http(URL+"/agbots/"+agbotId+"/businesspols").method("delete").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + agbotId + " all businesspols were deleted and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == agbotId) && (y.operation == ResChangeOperation.DELETED.toString) && (y.resource == "agbotbusinesspols")}))
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/businesspols - as agbot - should be all gone") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/businesspols").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val getAgbotResp = parse(response.body).extract[GetAgbotBusinessPolsResponse]
    assert(getAgbotResp.businessPols.size === 0)
  }


  // Note: testing of msgs is in NodesSuite.scala

  /** Clean up, delete all the test agbots */
  test("Cleanup - DELETE all test agbots and agreements") {
    // deleteAllAgreements   <- these now get deleted when the user is deleted
    // deleteAllAgbots
    deleteAllUsers()
  }

  test("DELETE IBM changes") {
    val ibmAgbotAuth = sys.env.getOrElse("EXCHANGE_AGBOTAUTH", "")
    if(ibmAgbotAuth != ""){
      val ibmAgbotId = """^[^:]+""".r.findFirstIn(ibmAgbotAuth).getOrElse("")     // get the id before the :
      val res = List(ibmAgbotId)
      val input = DeleteIBMChangesRequest(res)
      val response = Http(urlRoot+"/v1/orgs/IBM/changes/cleanup").postData(write(input)).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue)
    }
  }


  /** Delete the org we used for this test */
  test("POST /orgs/"+orgid+" - delete org") {
    // Try deleting it 1st, in case it is left over from previous test
    val response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("POST /orgs/"+orgid2+" - delete org2") {
    // Try deleting it 1st, in case it is left over from previous test
    val response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("DELETE org changes") {
    for (org <- orgsList){
      val input = DeleteOrgChangesRequest(List())
      val response = Http(urlRoot+"/v1/orgs/"+org+"/changes/cleanup").postData(write(input)).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue)
    }
  }
}