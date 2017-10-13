package exchangeapi

//import java.time._
//import java.util.Base64

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

/** Test the support for a front end authenticator */
@RunWith(classOf[JUnitRunner])
class FrontEndSuite extends FunSuite {

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val SDRSPEC = "https://bluehorizon.network/workloads/sdr"
  val orgid = "FrontEndSuiteTests"
  val ORGHEAD = ("orgid",orgid)
  val ISSUERHEAD = ("issuer","IBM_ID")
  val authpref=orgid+"/"
  val URL = urlRoot+"/v1/orgs/"+orgid
  val NOORGURL = urlRoot+"/v1"
  val user = "feuser"
  val orguser = authpref+user
  val pw = user+"pw"
  val IDUSER = ("id",user)
  val TYPEUSER = ("type","person")
  val apikey = "feapikey"
  val IDAPIKEY = ("id",apikey)
  val TYPEAPIKEY = ("type","app")
  val nodeId = "fenode"
  val orgnodeId = authpref+nodeId
  val nodeToken = "mytok"
  val IDNODE = ("id",nodeId)
  val TYPENODE = ("type","dev")
  val agbotId = "agbot1"      // need to use a different id than AgbotsSuite.scala, because all of the suites run concurrently
  val orgagbotId = authpref+agbotId
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+orgagbotId+":"+agbotToken)
  val agreementId = "agreement1"
  val agProto = "ExchangeAutomatedTest"
  val msBase = "ms1"
  val msUrl = "http://" + msBase
  val microservice = msBase + "_1.0.0_arm"
  val orgmicroservice = authpref+microservice
  val wkBase = "wk1"
  val wkUrl = "http://" + wkBase
  val workload = wkBase + "_1.0.0_arm"
  val orgworkload = authpref+workload
  val ptBase = "pat1"
  val pattern = ptBase
  val orgpattern = authpref+pattern

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers() = {
    for (i <- List(user)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test nodes - this is not longer used because deleting the user deletes these too */
  def deleteAllNodes() = {
    for (i <- List(nodeId)) {
      val response = Http(URL+"/nodes/"+i).method("delete").headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test agreements - this is no longer used because deleting the user deletes these too */
  def deleteAllAgreements() = {
    for (i <- List(agreementId)) {
      val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+i).method("delete").headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test agbots - this is not longer used because deleting the user deletes these too */
  def deleteAllAgbots() = {
    for (i <- List(agbotId)) {
      val response = Http(URL+"/agbots/"+i).method("delete").headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Create an org to use for this test */
  test("POST /orgs/"+orgid+" - create org") {
    // Try deleting it 1st, in case it is left over from previous test
    var response = Http(URL).method("delete").headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)

    val input = PostPutOrgRequest("My Org", "desc")
    response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the nodes, agbots, and
   *  agreements, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    deleteAllUsers()
  }

  test("POST /orgs/"+orgid+"/users/"+user+" - create user") {
    val input = PostPutUsersRequest(pw, false, user+"@hotmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+" - create node") {
    val input = PutNodesRequest(nodeToken, nodeId+"-normal", orgpattern,
      List(
        RegMicroservice(SDRSPEC,1,"{json policy for "+nodeId+" pws}",List(
          Prop("arch","arm","string","in"),
          Prop("version","1.0.0","version","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("dataVerification","true","boolean","=")))
      ),
      "whisper-id", Map("horizon"->"3.2.3"), "NODEABC")
    val response = Http(URL+"/nodes/"+nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+" - update node") {
    val input = PutNodesRequest(nodeToken, nodeId+"-update", orgpattern,
      List(
        RegMicroservice(SDRSPEC,1,"{json policy for "+nodeId+" pws}",List(
          Prop("arch","arm","string","in"),
          Prop("version","1.0.0","version","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("dataVerification","true","boolean","=")))
      ),
      "whisper-id", Map("horizon"->"3.2.3"), "NODEABC")
    val response = Http(URL+"/nodes/"+nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes") {
    val response: HttpResponse[String] = Http(URL+"/nodes").headers(ACCEPT).headers(TYPEAPIKEY).headers(IDAPIKEY).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 1)
    assert(getDevResp.nodes.contains(orgnodeId))
    val node = getDevResp.nodes.get(orgnodeId).get
    assert(node.name === nodeId+"-update")
  }

  test("PATCH /orgs/"+orgid+"/nodes/"+nodeId+" - as node") {
    val jsonInput = """{
      "publicKey": "NODEABC"
    }"""
    val response = Http(URL+"/nodes/"+nodeId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(TYPENODE).headers(IDNODE).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId) {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId).headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 1)
    assert(getDevResp.nodes.contains(orgnodeId))
    val node = getDevResp.nodes.get(orgnodeId).get
    assert(node.publicKey === "NODEABC")
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/heartbeat") {
    val response = Http(URL+"/nodes/"+nodeId+"/heartbeat").method("post").headers(ACCEPT).headers(TYPEAPIKEY).headers(IDAPIKEY).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern+"/search - for "+SDRSPEC) {
    val input = PostPatternSearchRequest(SDRSPEC, 86400, 0, 0)
    val response = Http(URL+"/patterns/"+pattern+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 1)
    assert(nodes.count(d => d.id==orgnodeId) === 1)
  }

  test("PATCH /orgs/"+orgid+"/nodes/"+nodeId+" - remove pattern from node so we can search for microservices") {
    val jsonInput = """{ "pattern": "" }"""
    val response = Http(URL+"/nodes/"+nodeId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/search/nodes/ - for "+SDRSPEC) {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","2","int",">="),
      Prop("version","*","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 1)     // we created 2 arm nodes
    assert(nodes.count(d => d.id==orgnodeId) === 1)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" - create node agreement") {
    val input = PutNodeAgreementRequest(List[NAMicroservice](NAMicroservice(orgid,SDRSPEC)), NAWorkload("","",""), "signed")
    val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(TYPENODE).headers(IDNODE).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" - update node agreement") {
    val input = PutNodeAgreementRequest(List[NAMicroservice](NAMicroservice(orgid,SDRSPEC)), NAWorkload("","",""), "finalized")
    val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(TYPENODE).headers(IDNODE).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/agreements - verify node agreement") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId+"/agreements").headers(ACCEPT).headers(TYPEAPIKEY).headers(IDAPIKEY).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetNodeAgreementsResponse]
    assert(getAgResp.agreements.size === 1)
    assert(getAgResp.agreements.contains(agreementId))
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId) {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).headers(ACCEPT).headers(TYPENODE).headers(IDNODE).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetNodeAgreementsResponse]
    assert(getAgResp.agreements.size === 1)
    assert(getAgResp.agreements.contains(agreementId))
  }

  test("DELETE /orgs/"+orgid+"/nodes/"+nodeId+"/agreements - all agreements") {
    val response = Http(URL+"/nodes/"+nodeId+"/agreements").method("delete").headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("DELETE agreements, code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+" - create agbot") {
    val input = PutAgbotsRequest(agbotToken, agbotId+"name", /*List[APattern](),*/ "whisper-id", "AGBOTABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId) {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId).headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getAgbotResp = parse(response.body).extract[GetAgbotsResponse]
    assert(getAgbotResp.agbots.size === 1)
    assert(getAgbotResp.agbots.contains(orgagbotId))
    val agbot = getAgbotResp.agbots.get(orgagbotId).get
    assert(agbot.name === agbotId+"name")
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/msgs - Send a msg from agbot1 to node1") {
    val input = PostNodesMsgsRequest("{msg1 from agbot1 to node1}", 300)
    val response = Http(URL+"/nodes/"+nodeId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/msgs - Get msgs for node1") {
    Thread.sleep(1100)    // delay 1.1 seconds so 1 of the msgs will expire
    val response = Http(URL+"/nodes/"+nodeId+"/msgs").method("get").headers(ACCEPT).headers(TYPENODE).headers(IDNODE).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val resp = parse(response.body).extract[GetNodeMsgsResponse]
    assert(resp.messages.size === 1)
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/msgs - Send a msg from node1 to agbot1") {
    val input = PostAgbotsMsgsRequest("{msg1 from node1 to agbot1}", 300)
    val response = Http(URL+"/agbots/"+agbotId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(TYPENODE).headers(IDNODE).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/msgs - Get msgs for agbot1") {
    Thread.sleep(1100)    // delay 1.1 seconds so 1 of the msgs will expire
    val response = Http(URL+"/agbots/"+agbotId+"/msgs").method("get").headers(ACCEPT).headers(TYPEAPIKEY).headers(IDAPIKEY).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val resp = parse(response.body).extract[GetAgbotMsgsResponse]
    assert(resp.messages.size === 1)
  }

  test("POST /orgs/"+orgid+"/microservices - create "+microservice) {
    val input = PostPutMicroserviceRequest(msBase+" arm", "desc", false, msUrl, "1.0.0", "arm", "singleton", "", Map("usbNodeIds" -> "1546:01a7"), List(Map("name" -> "foo")), List(Map("deployment" -> "{\"services\":{}}")))
    val response = Http(URL+"/microservices").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("microservice '"+orgmicroservice+"' created"))
  }

  test("GET /orgs/"+orgid+"/microservices") {
    val response: HttpResponse[String] = Http(URL+"/microservices").headers(ACCEPT).headers(TYPEAPIKEY).headers(IDAPIKEY).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetMicroservicesResponse]
    assert(respObj.microservices.size === 1)
    assert(respObj.microservices.contains(orgmicroservice))
  }

  test("POST /orgs/"+orgid+"/workloads - create "+workload) {
    val input = PostPutWorkloadRequest(wkBase+" arm", "desc", false, wkUrl, "1.0.0", "arm", "", List(Map("specRef" -> "https://msurl")), List(Map("name" -> "foo")), List(Map("deployment" -> "{\"services\":{}}")))
    val response = Http(URL+"/workloads").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("workload '"+orgworkload+"' created"))
  }

  test("GET /orgs/"+orgid+"/workloads/"+workload+" - as user") {
    val response: HttpResponse[String] = Http(URL + "/workloads/" + workload).headers(ACCEPT).headers(TYPEAPIKEY).headers(IDAPIKEY).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetWorkloadsResponse]
    assert(respObj.workloads.size === 1)
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern+" - create "+pattern) {
    val input = PostPutPatternRequest(ptBase, "desc", false,
      List( PWorkloads("https://wkurl", "myorg", "", List(PWorkloadVersions("", "", "", Map("priority_value" -> 50), Map("lifecycle" -> "immediate"))), PDataVerification(false, "", "", "", 0, 0, Map[String,Any]()), Map("check_agreement_status" -> 120) )),
      List[Map[String,String]]()
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("pattern '"+orgpattern+"' created"))
  }

  test("GET /orgs/"+orgid+"/patterns") {
    val response: HttpResponse[String] = Http(URL + "/patterns").headers(ACCEPT).headers(TYPEAPIKEY).headers(IDAPIKEY).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 1)
  }

  test("Cleanup - DELETE everything and confirm they are gone") {
    deleteAllUsers()
  }

  /** Delete the org we used for this test */
  test("POST /orgs/"+orgid+" - delete org") {
    val response = Http(URL).method("delete").headers(ACCEPT).headers(TYPEUSER).headers(IDUSER).headers(ORGHEAD).headers(ISSUERHEAD).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }
}
