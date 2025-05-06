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
class TestAgbotAgreements extends AnyFunSuite {

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


  /** Try to confirm the agreement that's not there for agbot 9930 */
  test("POST /orgs/"+orgid+"/agreements/confirm - not there") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiRespType.NOT_FOUND)
  }

  /** Try to confirm the agreement that's not there for agbot 9930, as user */
  test("POST /orgs/"+orgid+"/agreements/confirm - not there - as user") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiRespType.NOT_FOUND)
  }

  /** Add agbot2 */
  test("PUT /orgs/"+orgid+"/agbots/"+agbot2Id+" - normal") {
    val input = PutAgbotsRequest(agbot2Token, "agbot"+agbot2Id+"-norm", None, "ABC")
    val response = Http(URL+"/agbots/"+agbot2Id).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  /** Try to confirm the agreement that's not there for agbot 9930, as agbot2 */
  test("POST /orgs/"+orgid+"/agreements/confirm - not there - as agbot2") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiRespType.NOT_FOUND)
  }

  /** Add an agreement for agbot 9930 - as the agbot */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId+" - as agbot") {
    val input = PutAgbotAgreementRequest(AAService(orgid, pattern, "sdr"), "signed")
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + agbotId + " agreement was added and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == agbotId) && (y.operation == ResChangeOperation.CREATEDMODIFIED.toString) && (y.resource == "agbotagreements")}))
  }

  test("POST /orgs/"+orgid+"/changes - verify " + agbotId + " agreement creation not seen by agbots") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(!parsedBody.changes.exists(y => {(y.id == agbotId) && (y.operation == ResChangeOperation.CREATEDMODIFIED.toString) && (y.resource == "agbotagreements")}))
    assert(!parsedBody.changes.exists(y => {(y.operation == ResChangeOperation.CREATEDMODIFIED.toString) && (y.resource == "agbotagreements")}))
  }

  /** Update an agreement for agbot 9930 - as the agbot */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId+" - update as agbot") {
    val input = PutAgbotAgreementRequest(AAService(orgid, pattern, "sdr"), "finalized")
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  /** Update the agreement for agbot 9930 - as user */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId+" - as user") {
    val input = PutAgbotAgreementRequest(AAService(orgid, pattern, "sdr"), "negotiating")
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  /** Add a 2nd agreement for agbot 9930 - as the agbot */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/9951 - 2nd agreement as agbot") {
    val input = PutAgbotAgreementRequest(AAService(orgid, pattern, "netspeed"), "signed")
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/9951").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  /** Try to add a 3rd agreement, when max agreements is below that. */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/9952 - with low maxAgreements") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      Configuration.reload()
      val origMaxAgreements = Configuration.getConfig.getInt("api.limits.maxAgreements")

      // Change the maxAgreements config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxAgreements", "1")
      var response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)

      // Now try adding another agreement - expect it to be rejected
      val input = PutAgbotAgreementRequest(AAService(orgid, pattern, "netspeed"), "signed")
      response = Http(URL+"/agbots/"+agbotId+"/agreements/9952").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      val respObj = parse(response.body).extract[ApiResponse]
      assert(respObj.msg.contains("Access Denied: you are over the limit of 1 agreements for this agbot"))

      // Restore the maxAgreements config value in the svr
      configInput = AdminConfigRequest("api.limits.maxAgreements", origMaxAgreements.toString)
      response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)
    }
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/agreements - verify agbot agreement") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 2)

    assert(getAgResp.agreements.contains(agreementId))
    val ag = getAgResp.agreements(agreementId) // the 2nd get turns the Some(val) into val
    assert(ag.service.url === "sdr")
    assert(ag.state === "negotiating")
    assert(getAgResp.agreements.contains("9951"))
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId) {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 1)

    assert(getAgResp.agreements.contains(agreementId))
    val ag = getAgResp.agreements(agreementId) // the 2nd get turns the Some(val) into val
    assert(ag.service.url === "sdr")
    assert(ag.state === "negotiating")

    info("GET /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId+" output verified")
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId+" - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 1)
  }

  /** Confirm the agreement for agbot 9930 */
  test("POST /orgs/"+orgid+"/agreements/confirm") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiRespType.OK)
  }

  /** Confirm the agreement for agbot 9930 */
  test("POST /orgs/"+orgid+"/agreements/confirm - as user") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiRespType.OK)
  }

  /** Confirm the agreement for agbot 9930 */
  test("POST /orgs/"+orgid+"/agreements/confirm - as agbot2") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiRespType.OK)
  }

  /** Delete the 1st agreement for agbot 9930 */
  test("DELETE /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId+" - as agbot") {
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).method("delete").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("DELETE "+agreementId+", code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + agbotId + " agreement was deleted and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == agbotId) && (y.operation == ResChangeOperation.DELETED.toString) && (y.resource == "agbotagreements")}))
  }

  /** Confirm 1 agreement was deleted */
  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/agreements - as agbot, confirm 1 gone") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 1)
  }

  /** Delete the all agreements for agbot 9930 */
  test("DELETE /orgs/"+orgid+"/agbots/"+agbotId+"/agreements - as agbot") {
    val response = Http(URL+"/agbots/"+agbotId+"/agreements").method("delete").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("DELETE "+agreementId+", code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + agbotId + " all agreements were deleted and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == agbotId) && (y.operation == ResChangeOperation.DELETED.toString) && (y.resource == "agbotagreements")}))
  }

  /** Confirm all agreements were deleted */
  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/agreements - as agbot, confirm all gone") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 0)
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