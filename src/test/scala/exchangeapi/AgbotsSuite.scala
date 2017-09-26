package exchangeapi

import org.scalatest.FunSuite

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scalaj.http._
import org.json4s._
//import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import com.horizon.exchangeapi._
import com.horizon.exchangeapi.tables._
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
class AgbotsSuite extends FunSuite {

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val orgid = "AgbotsSuiteTests"
  val authpref=orgid+"/"
  val URL = urlRoot+"/v1/orgs/"+orgid
  val NOORGURL = urlRoot+"/v1"
  val user = "9990"
  val pw = user+"pw"
  val USERAUTH = ("Authorization","Basic "+authpref+user+":"+pw)
  val BADAUTH = ("Authorization","Basic "+authpref+user+":"+pw+"x")
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+rootuser+":"+rootpw)
  val agbotId = "9930"
  val orgagbotId = authpref+agbotId
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+orgagbotId+":"+agbotToken)
  val agbot2Id = "9931"
  val orgagbot2Id = authpref+agbot2Id
  val agbot2Token = agbot2Id+"tok"
  val AGBOT2AUTH = ("Authorization","Basic "+orgagbot2Id+":"+agbot2Token)
  val agbot3Id = "9932"
  val orgagbot3Id = authpref+agbot3Id
  val agbot3Token = agbot3Id+"tok"
  val AGBOT3AUTH = ("Authorization","Basic "+orgagbot3Id+":"+agbot3Token)
  val agreementId = "9950"
  val pattern = "mypattern"
  val workload = "myworkload"
  val micro = "mymicro"
  val nodeId = "mynode"
  val nodeToken = nodeId+"tok"
  val NODEAUTH = ("Authorization","Basic "+authpref+nodeId+":"+nodeToken)

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers() = {
    for (i <- List(user)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test agbots - this is not longer used because deleting the user deletes these too */
  def deleteAllAgbots() = {
    for (i <- List(agbotId, agbot2Id)) {
      val response = Http(URL+"/agbots/"+i).method("delete").headers(ACCEPT).headers(USERAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test agreements - this is no longer used because deleting the user deletes these too */
  def deleteAllAgreements() = {
    for (i <- List(agreementId)) {
      val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+i).method("delete").headers(ACCEPT).headers(USERAUTH).asString
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

    val input = PostPutOrgRequest("My Org", "desc")
    response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the agbots and
   *  agreements, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllUsers()
  }

  /** Add a normal user */
  test("POST /orgs/"+orgid+"/users/"+user+" - normal") {
    val input = PostPutUsersRequest(pw, false, user+"@hotmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /** Add a normal agbot */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+" - normal") {
    val input = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-norm", List[APattern](APattern(orgid,"mypattern")), "whisper-id", "ABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Update normal agbot as user */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+" - normal - as user") {
    val input = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-normal-user", List[APattern](APattern(orgid,"mypattern")), "whisper-id", "ABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Update the agbot as the agbot */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+" - normal - as agbot") {
    val input = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-normal", List[APattern](APattern(orgid,"mypattern-normal")), "whisper-id", "ABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
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
    assert(response.code === HttpCode.BAD_INPUT)     // for now this is what is returned when the json-to-scala conversion fails
  }

  /** Try adding an agbot with bad creds */
  test("PUT /orgs/"+orgid+"/agbots/9932 - bad creds") {
    val input = PutAgbotsRequest("mytok", "agbot9932-badcreds", List[APattern](APattern(orgid,"mypattern")), "whisper-id", "ABC")
    val response = Http(URL+"/agbots/9932").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(BADAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BADCREDS)
  }

  test("GET /orgs/"+orgid+"/agbots") {
    val response: HttpResponse[String] = Http(URL+"/agbots").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getAgbotResp = parse(response.body).extract[GetAgbotsResponse]
    // assert(getAgbotResp.agbots.size === 1)   // since the other test suites are creating some of these too, we can not know how many there are right now

    assert(getAgbotResp.agbots.contains(orgagbotId))
    val dev = getAgbotResp.agbots.get(orgagbotId).get // the 2nd get turns the Some(val) into val
    assert(dev.name === "agbot"+agbotId+"-normal")
    val pat = dev.patterns.head
    assert(pat.pattern === "mypattern-normal")
  }

  test("GET /orgs/"+orgid+"/agbots - filter owner, idfilter, and name") {
    val response: HttpResponse[String] = Http(URL+"/agbots").headers(ACCEPT).headers(USERAUTH).param("owner",orgid+"/"+user).param("idfilter",orgid+"/993%").param("name","agbot993%-normal").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getAgbotResp = parse(response.body).extract[GetAgbotsResponse]
    assert(getAgbotResp.agbots.size === 1)
    assert(getAgbotResp.agbots.contains(orgagbotId))
  }

  /** Heartbeat for agbot 9930 */
  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/heartbeat") {
    val response = Http(URL+"/agbots/"+agbotId+"/heartbeat").method("post").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postHeartbeatResp = parse(response.body).extract[ApiResponse]
    assert(postHeartbeatResp.code === ApiResponseType.OK)
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId) {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getAgbotResp = parse(response.body).extract[GetAgbotsResponse]
    assert(getAgbotResp.agbots.size === 1)

    assert(getAgbotResp.agbots.contains(orgagbotId))
    val agbot = getAgbotResp.agbots.get(orgagbotId).get // the 2nd get turns the Some(val) into val
    assert(agbot.name === "agbot"+agbotId+"-normal")

    // Verify the lastHeartbeat from the POST heartbeat above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastHb = ZonedDateTime.parse(agbot.lastHeartbeat).toEpochSecond
    assert(now - lastHb <= 3)    // should not now be more than 3 seconds from the time the heartbeat was done above
  }

  /** Update 1 attr of the agbot as the agbot */
  test("PATCH /orgs/"+orgid+"/agbots/"+agbotId+" - as agbot") {
    var jsonInput = """{
      "publicKey": "newAGBOTABCDEF"
    }"""
    var response = Http(URL+"/agbots/"+agbotId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)

    jsonInput = """{
      "patterns": [{ "orgid": "myorg", "pattern": "mypattern-patched" }]
    }"""
    response = Http(URL+"/agbots/"+agbotId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+" - as agbot, check patch by getting that 1 attr") {
    var response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"?attribute=publicKey").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getAgbotResp = parse(response.body).extract[GetAgbotAttributeResponse]
    assert(getAgbotResp.attribute === "publicKey")
    assert(getAgbotResp.value === "newAGBOTABCDEF")

    // Also check the patched pattern, but we have to get the whole resource for that
    response = Http(URL+"/agbots/"+agbotId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getAgbotResp2 = parse(response.body).extract[GetAgbotsResponse]
    assert(getAgbotResp2.agbots.size === 1)

    assert(getAgbotResp2.agbots.contains(orgagbotId))
    val agbot = getAgbotResp2.agbots.get(orgagbotId).get // the 2nd get turns the Some(val) into val
    val pat = agbot.patterns.head
    assert(pat.pattern === "mypattern-patched")
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern+" - add "+pattern+" and check that agbot can read it") {
    val input = PostPutPatternRequest(pattern, "desc", false,
      List( PWorkloads("https://wkurl", orgid, "", List(PWorkloadVersions("", "", "", Map(), Map())), PDataVerification(false, "", "", "", 0, 0, Map[String,Any]()) )),
      List[Map[String,String]]()
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)

    val response2: HttpResponse[String] = Http(URL+"/patterns/"+pattern).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response2.code)
    assert(response2.code === HttpCode.OK)
    val respObj = parse(response2.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 1)
  }
  // Note: when we delete the org, this pattern will get deleted

  test("GET /orgs/"+orgid+"/patterns/"+pattern+" - as IBM agbot (if it exists) and also msg between orgs") {
    val ibmAgbotAuth = sys.env.getOrElse("EXCHANGE_AGBOTAUTH", "")
    val ibmAgbotId = """^[^:]+""".r.findFirstIn(ibmAgbotAuth).getOrElse("")     // get the id before the :
    if (ibmAgbotAuth != "") {
      val IBMAGBOTAUTH = ("Authorization", "Basic IBM/" + ibmAgbotAuth)
      val response: HttpResponse[String] = Http(URL + "/patterns/" + pattern).headers(ACCEPT).headers(IBMAGBOTAUTH).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.OK)
      val respObj = parse(response.body).extract[GetPatternsResponse]
      assert(respObj.patterns.size === 1)

      if (ibmAgbotId != "") {
        // Also create a node to make sure they can msg each other
        val input = PutNodesRequest(nodeToken, "rpi" + nodeId + "-norm", orgid + "/" + pattern, List(), "", Map(), "NODEABC")
        var response2 = Http(URL + "/nodes/" + nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
        info("code: " + response2.code)
        assert(response2.code === HttpCode.PUT_OK)

        val input2 = PostNodesMsgsRequest("{msg from IBM agbot to node in this org}", 300)
        response2 = Http(URL + "/nodes/" + nodeId + "/msgs").postData(write(input2)).method("post").headers(CONTENT).headers(ACCEPT).headers(IBMAGBOTAUTH).asString
        info("code: " + response2.code + ", response.body: " + response2.body)
        assert(response2.code === HttpCode.POST_OK)

        val input3 = PostAgbotsMsgsRequest("{msg from node in this org to IBM agbot}", 300)
        response2 = Http(NOORGURL + "/orgs/IBM/agbots/" + ibmAgbotId + "/msgs").postData(write(input3)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
        info("code: " + response2.code + ", response.body: " + response2.body)
        assert(response2.code === HttpCode.POST_OK)
      }
    }
  }

  test("POST /orgs/"+orgid+"/workloads - add "+workload+" and check that agbot can read it") {
    val input = PostPutWorkloadRequest(workload+" arm", "desc", false, "https://wkurl", "1.0.0", "arm", "", List(Map()), List(Map()), List(Map()))
    val response = Http(URL+"/workloads").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)

    val response2: HttpResponse[String] = Http(URL+"/workloads").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response2.code)
    assert(response2.code === HttpCode.OK)
    val respObj = parse(response2.body).extract[GetWorkloadsResponse]
    assert(respObj.workloads.size === 1)
  }
  // Note: when we delete the org, this pattern will get deleted

  test("POST /orgs/"+orgid+"/microservices - add "+micro+" and check that agbot can read it") {
    val input = PostPutMicroserviceRequest(micro+" arm", "desc", false, "https://msurl", "1.0.0", "arm", "singleton", "", Map(), List(Map()), List(Map()))
    val response = Http(URL+"/microservices").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)

    val response2: HttpResponse[String] = Http(URL+"/microservices").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response2.code)
    assert(response2.code === HttpCode.OK)
    val respObj = parse(response2.body).extract[GetMicroservicesResponse]
    assert(respObj.microservices.size === 1)
  }
  // Note: when we delete the org, this pattern will get deleted

  /** Try to confirm the agreement that's not there for agbot 9930 */
  test("POST /orgs/"+orgid+"/agreements/confirm - not there") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.NOT_FOUND)
  }

  /** Try to confirm the agreement that's not there for agbot 9930, as user */
  test("POST /orgs/"+orgid+"/agreements/confirm - not there - as user") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.NOT_FOUND)
  }

  /** Add agbot2 */
  test("PUT /orgs/"+orgid+"/agbots/"+agbot2Id+" - normal") {
    val input = PutAgbotsRequest(agbot2Token, "agbot"+agbot2Id+"-norm", List[APattern](APattern(orgid,"mypattern2")), "whisper-id", "ABC")
    val response = Http(URL+"/agbots/"+agbot2Id).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Try to confirm the agreement that's not there for agbot 9930, as agbot2 */
  test("POST /orgs/"+orgid+"/agreements/confirm - not there - as agbot2") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.NOT_FOUND)
  }

  /** Add an agreement for agbot 9930 - as the agbot */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId+" - as agbot") {
    val input = PutAgbotAgreementRequest(AAWorkload(orgid, pattern, "sdr"), "signed")
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Update an agreement for agbot 9930 - as the agbot */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId+" - update as agbot") {
    val input = PutAgbotAgreementRequest(AAWorkload(orgid, pattern, "sdr"), "finalized")
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Update the agreement for agbot 9930 - as user */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId+" - as user") {
    val input = PutAgbotAgreementRequest(AAWorkload(orgid, pattern, "sdr"), "negotiating")
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Add a 2nd agreement for agbot 9930 - as the agbot */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/9951 - 2nd agreement as agbot") {
    val input = PutAgbotAgreementRequest(AAWorkload(orgid, pattern, "netspeed"), "signed")
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/9951").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Try to add a 3rd agreement, when max agreements is below that. */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/9952 - with low maxAgreements") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      ExchConfig.load()
      val origMaxAgreements = ExchConfig.getInt("api.limits.maxAgreements")

      // Change the maxAgreements config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxAgreements", "1")
      var response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // Now try adding another agreement - expect it to be rejected
      val input = PutAgbotAgreementRequest(AAWorkload(orgid, pattern, "netspeed"), "signed")
      response = Http(URL+"/agbots/"+agbotId+"/agreements/9952").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED)
      val respObj = parse(response.body).extract[ApiResponse]
      assert(respObj.msg.contains("Access Denied"))

      // Restore the maxAgreements config value in the svr
      configInput = AdminConfigRequest("api.limits.maxAgreements", origMaxAgreements.toString)
      response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)
    }
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/agreements - verify agbot agreement") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 2)

    assert(getAgResp.agreements.contains(agreementId))
    val ag = getAgResp.agreements.get(agreementId).get // the 2nd get turns the Some(val) into val
    assert(ag.workload.url === "sdr")
    assert(ag.state === "negotiating")
    assert(getAgResp.agreements.contains("9951"))
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId) {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 1)

    assert(getAgResp.agreements.contains(agreementId))
    val ag = getAgResp.agreements.get(agreementId).get // the 2nd get turns the Some(val) into val
    assert(ag.workload.url === "sdr")
    assert(ag.state === "negotiating")

    info("GET /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId+" output verified")
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId+" - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 1)
  }

  /** Confirm the agreement for agbot 9930 */
  test("POST /orgs/"+orgid+"/agreements/confirm") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.OK)
  }

  /** Confirm the agreement for agbot 9930 */
  test("POST /orgs/"+orgid+"/agreements/confirm - as user") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.OK)
  }

  /** Confirm the agreement for agbot 9930 */
  test("POST /orgs/"+orgid+"/agreements/confirm - as agbot2") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.OK)
  }

  /** Delete the 1st agreement for agbot 9930 */
  test("DELETE /orgs/"+orgid+"/agbots/"+agbotId+"/agreements/"+agreementId+" - as agbot") {
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).method("delete").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("DELETE "+agreementId+", code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  /** Confirm 1 agreement was deleted */
  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/agreements - as agbot, confirm 1 gone") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 1)
  }

  /** Delete the all agreements for agbot 9930 */
  test("DELETE /orgs/"+orgid+"/agbots/"+agbotId+"/agreements - as agbot") {
    val response = Http(URL+"/agbots/"+agbotId+"/agreements").method("delete").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("DELETE "+agreementId+", code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  /** Confirm all agreements were deleted */
  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/agreements - as agbot, confirm all gone") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 0)
  }

  /** Try to add agbot3, when max agbots is below that. */
  test("PUT /orgs/"+orgid+"/agbots/"+agbot3Id+" - with low maxAgbots") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      // ExchConfig.load  <-- done up above
      val origMaxAgbots = ExchConfig.getInt("api.limits.maxAgbots")

      // Change the maxAgbots config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxAgbots", "1")
      var response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // Now try adding another agbot - expect it to be rejected
      val input = PutAgbotsRequest(agbot3Token, "agbot"+agbot3Id+"-norm", List[APattern](APattern(orgid,"mypattern2")), "whisper-id", "ABC")
      response = Http(URL+"/agbots/"+agbot3Id).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED)
      val respObj = parse(response.body).extract[ApiResponse]
      assert(respObj.msg.contains("Access Denied"))

      // Restore the maxAgbots config value in the svr
      configInput = AdminConfigRequest("api.limits.maxAgbots", origMaxAgbots.toString)
      response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)
    }
  }

  /** Explicit delete of agbot */
  test("DELETE /orgs/"+orgid+"/agbots/"+agbotId+" - as user") {
    var response = Http(URL+"/agbots/"+agbotId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)

    response = Http(URL+"/agbots/"+agbotId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  // Note: testing of msgs is in NodesSuite.scala

  /** Clean up, delete all the test agbots */
  test("Cleanup - DELETE all test agbots and agreements") {
    // deleteAllAgreements   <- these now get deleted when the user is deleted
    // deleteAllAgbots
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