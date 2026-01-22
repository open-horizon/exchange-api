package org.openhorizon.exchangeapi.agreementbot

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
//import java.util.Properties

/**
 * Tests for the /agbots routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class TestAgbotRoute extends AnyFunSuite {

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

  /** Create orgs to use for this test */
  test("POST /orgs/"+orgid+" - create org") {
    // Try deleting it 1st, in case it is left over from previous test
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)

    val input = PostPutOrgRequest(None, "My Org", "desc", None, None, None)
    response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    orgsList+=orgid
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/"+orgid2+" - create org2") {
    // Try deleting it 1st, in case it is left over from previous test
    var response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)

    val input = PostPutOrgRequest(None, "My Org2", "desc", None, None, None)
    response = Http(URL2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    orgsList+=orgid2
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the agbots and
   *  agreements, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllUsers()
  }

  /** Add a normal user */
  test("POST /orgs/"+orgid+"/users/"+user+" - normal") {
    val input = PostPutUsersRequest(pw, admin = false, Some(false), user+"@hotmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/"+orgid2+"/users/"+user+" - normal") {
    val input = PostPutUsersRequest(pw, admin = false, Some(false), user+"@hotmail.com")
    val response = Http(URL2+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  /** Add a normal agbot */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+" - normal") {
    val input = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-norm", None, "ABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + agbotId + " was created and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == agbotId) && (y.operation == ResChangeOperation.CREATEDMODIFIED.toString)}))
  }

  test("POST /orgs/"+orgid+"/changes - verify " + agbotId + " can call notification framework") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, Some(List(orgid)))
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == agbotId) && (y.operation == ResChangeOperation.CREATEDMODIFIED.toString)}))
  }

  /** Update normal agbot as user */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+" - normal - as user") {
    val input = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-normal-user", None, "ABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  /** Update the agbot as the agbot */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+" - normal - as agbot") {
    val input = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-normal", None, "ABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  /** Try adding an invalid agbot body */
  test("PUT /orgs/"+orgid+"/agbots/9932 - bad format") {
    val badJsonInput = """{
      "token": "foo",
      "xname": "agbot9932-bad-format",
      "msgEndPoint": "whisper-id"
    }"""
    val response = Http(URL+"/agbots/9932").postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT.intValue)     // for now this is what is returned when the json-to-scala conversion fails
  }

  /** Try adding an agbot with bad creds */
  test("PUT /orgs/"+orgid+"/agbots/9932 - bad creds") {
    val input = PutAgbotsRequest("mytok", "agbot9932-badcreds", None, "ABC")
    val response = Http(URL+"/agbots/9932").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(BADAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
  }

  test("GET /orgs/"+orgid+"/agbots") {
    val response: HttpResponse[String] = Http(URL+"/agbots").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getAgbotResp = parse(response.body).extract[GetAgbotsResponse]
    // assert(getAgbotResp.agbots.size === 1)   // since the other test suites are creating some of these too, we can not know how many there are right now

    assert(getAgbotResp.agbots.contains(orgagbotId))
    val dev = getAgbotResp.agbots(orgagbotId) // the 2nd get turns the Some(val) into val
    assert(dev.name === "agbot"+agbotId+"-normal")
    //val pat = dev.patterns.head
    //assert(pat.pattern === "mypattern-normal")
  }

  test("GET /orgs/"+orgid+"/agbots - filter owner, idfilter, and name") {
    val response: HttpResponse[String] = Http(URL+"/agbots").headers(ACCEPT).headers(USERAUTH).param("owner",orgid+"/"+user).param("idfilter",orgid+"/993%").param("name","agbot993%-normal").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getAgbotResp = parse(response.body).extract[GetAgbotsResponse]
    assert(getAgbotResp.agbots.size === 1)
    assert(getAgbotResp.agbots.contains(orgagbotId))
  }

  /** Heartbeat for agbot 9930 */
  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/heartbeat") {
    val response = Http(URL+"/agbots/"+agbotId+"/heartbeat").method("post").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val postHeartbeatResp = parse(response.body).extract[ApiResponse]
    assert(postHeartbeatResp.code === ApiRespType.OK)
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId) {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getAgbotResp = parse(response.body).extract[GetAgbotsResponse]
    assert(getAgbotResp.agbots.size === 1)

    assert(getAgbotResp.agbots.contains(orgagbotId))
    val agbot = getAgbotResp.agbots(orgagbotId) // the 2nd get turns the Some(val) into val
    assert(agbot.name === "agbot"+agbotId+"-normal")

    // Verify the lastHeartbeat from the POST heartbeat above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastHb = ZonedDateTime.parse(agbot.lastHeartbeat).toEpochSecond
    assert(now - lastHb <= 3)    // should not now be more than 3 seconds from the time the heartbeat was done above
  }

  /** Update 1 attr of the agbot as the agbot */
  test("PATCH /orgs/"+orgid+"/agbots/"+agbotId+" - as agbot") {
    val jsonInput = """{
      "publicKey": "newAGBOTABCDEF"
    }"""
    val response = Http(URL+"/agbots/"+agbotId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)

    /*
    jsonInput = """{
      "patterns": [{ "orgid": "myorg", "pattern": "mypattern-patched" }]
    }"""
    response = Http(URL+"/agbots/"+agbotId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
    */
  }

  test("POST /orgs/"+orgid+"/changes - verify " + agbotId + " was updated and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == agbotId) && (y.operation == ResChangeOperation.MODIFIED.toString)}))
  }

  test("PATCH /orgs/"+orgid+"/agbots/"+agbotId+" - as agbot -- invalid input") {
    val jsonInput = "badinput"
    val response = Http(URL+"/agbots/"+agbotId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    //assert(response.body.contains("invalid input"))
  }

  test("PATCH /orgs/"+orgid+"/agbots/"+agbotId+" - with whitespace still works") {
    // spaces in the beginning and tabs at the end
    val jsonInput = """   {
      "publicKey": "newAGBOTABCDEF"
    }   """
    val response = Http(URL+"/agbots/"+agbotId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/"+orgid+"/agbots/"+agbotId+" - with whitespace as a newline still works") {
    // spaces in the beginning and tabs at the end
    val jsonInput = """
                   {
      "publicKey": "newAGBOTABCDEF"
    }
    """
    val response = Http(URL+"/agbots/"+agbotId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+" - as agbot, check patch by getting that 1 attr") {
    var response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"?attribute=publicKey").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getAgbotResp = parse(response.body).extract[GetAgbotAttributeResponse]
    assert(getAgbotResp.attribute === "publicKey")
    assert(getAgbotResp.value === "newAGBOTABCDEF")

    response = Http(URL+"/agbots/"+agbotId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getAgbotResp2 = parse(response.body).extract[GetAgbotsResponse]
    assert(getAgbotResp2.agbots.size === 1)

    assert(getAgbotResp2.agbots.contains(orgagbotId))
    //val agbot = getAgbotResp2.agbots.get(orgagbotId).get // the 2nd get turns the Some(val) into val
    //val pat = agbot.patterns.head
    //assert(pat.pattern === "mypattern-patched")
  }


 






















  /** Explicit delete of agbot */
  test("DELETE /orgs/"+orgid+"/agbots/"+agbotId+" - as user") {
    var response = Http(URL+"/agbots/"+agbotId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)

    response = Http(URL+"/agbots/"+agbotId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + agbotId + " was deleted and stored") {
    val time = ApiTime.pastUTC(60)
    val input = ResourceChangesRequest(0L, Some(time), maxRecords, None)
    val response = Http(URL+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == agbotId) && (y.operation == ResChangeOperation.DELETED.toString) && (y.resource == "agbot")}))
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