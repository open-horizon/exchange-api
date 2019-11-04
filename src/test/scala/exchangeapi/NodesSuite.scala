package exchangeapi

import java.time._
import java.util.Base64

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
 * This class is a test suite for the methods in object FunSets. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class NodesSuite extends FunSuite {

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val orgid = "NodesSuiteTests"
  val authpref=orgid+"/"
  val URL = urlRoot+"/v1/orgs/"+orgid
  val orgid2 = "NodesSuiteTests2"
  val authpref2=orgid2+"/"
  val URL2 = urlRoot+"/v1/orgs/"+orgid2
  val orgnotthere = orgid+"NotThere"
  val NOORGURL = urlRoot+"/v1"
  val SDRSPEC_URL = "bluehorizon.network.sdr"
  val SDRSPEC = orgid+"/"+SDRSPEC_URL
  val NETSPEEDSPEC_URL = "bluehorizon.network.netspeed"
  val NETSPEEDSPEC = orgid+"/"+NETSPEEDSPEC_URL
  val PWSSPEC_URL = "bluehorizon.network.pws"
  val PWSSPEC = orgid+"/"+PWSSPEC_URL
  val NOTTHERESPEC_URL = "bluehorizon.network.notthere"
  val NOTTHERESPEC = orgid+"/"+NOTTHERESPEC_URL
  val user = "u1"
  val orguser = authpref+user
  val org2user = authpref2+user
  val pw = user+"pw"
  val USERAUTH = ("Authorization","Basic "+orguser+":"+pw)
  val USERAUTH2 = ("Authorization","Basic "+org2user+":"+pw)
  val BADAUTH = ("Authorization","Basic "+orguser+":"+pw+"x")
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+rootuser+":"+rootpw)
  val nodeId = "n1"     // the 1st node created, that i will use to run some rest methods
  val orgnodeId = authpref+nodeId
  val org2nodeId = authpref2+nodeId
  val nodeToken = "mytok"
  val NODEAUTH = ("Authorization","Basic "+orgnodeId+":"+nodeToken)
  val nodeId2 = "n2"
  val orgnodeId2 = authpref+nodeId2
  val nodeToken2 = "my tok"   // intentionally adding a space in the token
  val NODE2AUTH = ("Authorization","Basic "+orgnodeId2+":"+nodeToken2)
  val nodeId3 = "n3"
  val orgnodeId3 = authpref+nodeId3
  val nodeId4 = "n4"
  val orgnodeId4 = authpref+nodeId4
  val nodeId5 = "n5"      // not ever successfully created
  val orgnodeId5 = authpref+nodeId5
  val nodeId6 = "n6"
  val orgnodeId6 = authpref+nodeId6
  val nodeId7 = "n7"
  val nodePubKey = "NODEABC"
  val patid = "p1"
  val businessPolicySdr = "mybuspolsdr"
  val businessPolicySdr2 = "mybuspolsdr2"
  val businessPolicyNS = "mybuspolnetspeed"
  val compositePatid = orgid+"/"+patid
  val svcid = "bluehorizon.network-services-sdr_1.0.0_amd64"
  //val svcurl = SDRSPEC
  val svcarch = "amd64"
  val svcversion = "1.0.0"
  val svcid2 = "bluehorizon.network-services-netspeed_1.0.0_amd64"
  //val svcurl2 = NETSPEEDSPEC
  val svcarch2 = "amd64"
  val svcversion2 = "1.0.0"
  val agreementId = "agr1"
  val agreementId2 = "agr2"   // for the node in the 2nd org
  val creds = authpref+nodeId+":"+nodeToken
  val encodedCreds = Base64.getEncoder.encodeToString(creds.getBytes("utf-8"))
  val ENCODEDAUTH = ("Authorization","Basic "+encodedCreds)
  val agbotId = "a1"      // need to use a different id than AgbotsSuite.scala, because all of the suites run concurrently
  val orgagbotId = authpref+agbotId
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+orgagbotId+":"+agbotToken)
  val agbotId2 = "a2"      // need to use a different id than AgbotsSuite.scala, because all of the suites run concurrently
  val orgagbotId2 = authpref+agbotId2
  val agbotToken2 = agbotId2+"tok"
  val AGBOT2AUTH = ("Authorization","Basic "+orgagbotId2+":"+agbotToken2)
  val agProto = "ExchangeAutomatedTest"    // using this to avoid db entries from real users and predefined ones
  val ALL_VERSIONS = "[0.0.0,INFINITY)"
  val ibmService = "TestIBMService"

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  // Operators: test, ignore, pending

  /** Delete all the test orgs */
  def deleteAllOrgs() = {
    for (u <- List(URL, URL2)) {
      val response = Http(u).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+u+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  def patchNodePublicKey(nodeid: String, publicKey: String): Unit = {
    val jsonInput = """{ "publicKey": """"+publicKey+"""" }"""
    val response = Http(URL + "/nodes/" + nodeid).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("PATCH "+nodeid+", code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  def patchNodePattern(nodeid: String, pattern: String): Unit = {
    val jsonInput = """{ "pattern": """"+pattern+"""" }"""
    val response = Http(URL + "/nodes/" + nodeid).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("PATCH "+nodeid+", code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Patches all of the nodes to have a pattern or blank out the pattern (for business policy and node health searches) */
  def patchAllNodePatterns(pattern: String): Unit = {
    // Can not change the pattern when the publicKey is set, so have to blank it first, and then restore it afterward
    for (i <- List(nodeId,nodeId2,nodeId3,nodeId4)) {
      patchNodePublicKey(i, "")
    }

    for (i <- List(nodeId,nodeId2,nodeId3,nodeId4)) {
      patchNodePattern(i, pattern)
    }

    for (i <- List(nodeId,nodeId2,nodeId3,nodeId4)) {
      patchNodePublicKey(i, nodePubKey)
    }
  }

  /** Calculated the changedSince arg for business pol search, given seconds ago. */
  def changedSinceAgo(secondsAgo: Int) = { ApiTime.nowSeconds - secondsAgo }

  //~~~~~ Create org, user, service, pattern ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  // Delete all the test orgs (and everything under them), in case they exist from a previous run.
  test("Begin - DELETE all test orgs") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllOrgs()
  }

  test("POST /orgs/"+orgid+" - create org to use for this test suite") {
    val input = PostPutOrgRequest(None, "My Org", "desc", None)
    val response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid2+" - create 2nd org to use for this test suite") {
    val input = PostPutOrgRequest(None, "My 2nd Org", "desc", None)
    val response = Http(URL2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/users/"+user+" - normal") {
    val input = PostPutUsersRequest(pw, admin = false, user+"@hotmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid2+"/users/"+user+" - normal") {
    val input = PostPutUsersRequest(pw, admin = false, user+"@hotmail.com")
    val response = Http(URL2+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+" - before pattern exists - should fail") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId+"-norm", compositePatid,
      None,
      None, None, Some(Map("horizon"->"3.2.3")), nodePubKey, None)
    val response = Http(URL+"/nodes/"+nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/services - add "+svcid+" so pattern can reference it") {
    val input = PostPutServiceRequest("test-service", None, public = false, None, SDRSPEC_URL, svcversion, svcarch, "multiple", None, None, None, "", "", None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/services - add "+svcid2+" so pattern can reference it") {
    val input = PostPutServiceRequest("test-service", None, public = false, None, NETSPEEDSPEC_URL, svcversion2, svcarch2, "multiple", None, None, None, "", "", None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/IBM/services - add "+ibmService+" to be used in search later") {
    val input = PostPutServiceRequest("test-service", None, public = false, None, ibmService, svcversion2, svcarch2, "multiple", None, None, None, "", "", None)
    val response = Http(urlRoot+"/v1/orgs/IBM/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+" - so nodes can reference it") {
    val input = PostPutPatternRequest(patid, None, None,
      List(
        // Reference both services in the pattern so we can search on both later on
        PServices(SDRSPEC_URL, orgid, svcarch, None, List(PServiceVersions(svcversion, None, None, None, None)), None, None ),
        PServices(NETSPEEDSPEC_URL, orgid, svcarch2, Some(true), List(PServiceVersions(svcversion2, None, None, None, None)), None, None )
      ),
      None, None
    )
    val response = Http(URL+"/patterns/"+patid).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicySdr+" - add "+businessPolicySdr+" as user") {
    val input = PostPutBusinessPolicyRequest(businessPolicySdr, Some("desc"),
      BService(SDRSPEC_URL, orgid, "*", List(BServiceVersions(svcversion, None, None)), None ),
      None, Some(List(OneProperty("purpose",None,"location"))), Some(List("a == b"))
    )
    val response = Http(URL+"/business/policies/"+businessPolicySdr).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicySdr2+" - add "+businessPolicySdr2+" as user") {
    val input = PostPutBusinessPolicyRequest(businessPolicySdr2, Some("desc"),
      BService(SDRSPEC_URL, orgid, "", List(BServiceVersions(svcversion, None, None)), None ),
      None, Some(List(OneProperty("purpose",None,"location"))), Some(List("a == b"))
    )
    val response = Http(URL+"/business/policies/"+businessPolicySdr2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("DELETE /orgs/"+orgid+"/business/policies/"+businessPolicySdr2+" - delete "+businessPolicySdr2+" to not interfere with tests") {
    val response = Http(URL+"/business/policies/"+businessPolicySdr2).method("delete").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicyNS+" - add "+businessPolicyNS+" as user") {
    val input = PostPutBusinessPolicyRequest(businessPolicyNS, Some("desc"),
      BService(NETSPEEDSPEC_URL, orgid, "amd64", List(BServiceVersions(svcversion, None, None)), None ),
      None, Some(List(OneProperty("purpose",None,"location"))), Some(List("a == b"))
    )
    val response = Http(URL+"/business/policies/"+businessPolicyNS).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  //~~~~~ Create nodes ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  ExchConfig.load()

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+" - add node with invalid svc ref in userInput") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId+"-norm", compositePatid, None,
      Some(List( OneUserInputService(orgid, SDRSPEC_URL, None, Some("[9.9.9,9.9.9]"), List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      None, None, nodePubKey, None)
    val response = Http(URL+"/nodes/"+nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+" - add normal node as user, but with no pattern yet") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId+"-norm", "",
      Some(List(
        RegService(PWSSPEC,1,Some("active"),"{json policy for "+nodeId+" pws}",List(
          Prop("arch","arm","string","in"),
          Prop("version","1.0.0","version","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("dataVerification","true","boolean","="))),
        RegService(NETSPEEDSPEC,1,Some("active"),"{json policy for "+nodeId+" netspeed}",List(
          Prop("arch","arm","string","in"),
          Prop("cpus","2","int",">="),
          Prop("version","1.0.0","version","in")))
      )),
      Some(List( OneUserInputService(orgid, SDRSPEC_URL, None, None, List( OneUserInputValue("UI_STRING","mystr"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      None, Some(Map("horizon"->"3.2.3")), nodePubKey, None)
    info(write(input))
    val response = Http(URL+"/nodes/"+nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+" - try to set pattern when publicKey already exists - should fail") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId+"-norm", compositePatid,
      None, None, None, None, nodePubKey, None)
    val response = Http(URL+"/nodes/"+nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+" - normal - update as user") {
    patchNodePublicKey(nodeId, "")   // 1st blank the publicKey so we are allowed to set the pattern
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId+"-normal-user", compositePatid,
      Some(List(
        RegService(PWSSPEC,1,Some("active"),"{json policy for "+nodeId+" pws}",List(
          Prop("arch","arm","string","in"),
          Prop("version","1.0.0","version","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("dataVerification","true","boolean","="))),
        RegService(NETSPEEDSPEC,1,Some("active"),"{json policy for "+nodeId+" netspeed}",List(
          Prop("arch","arm","string","in"),
          Prop("cpus","2","int",">="),
          Prop("version","1.0.0","version","in")))
      )),
      Some(List( OneUserInputService(orgid, SDRSPEC_URL, Some(svcarch), Some(ALL_VERSIONS), List( OneUserInputValue("UI_STRING","mystr - updated"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      None, Some(Map("horizon"->"3.2.3")), "OLDNODEABC", None)
    val response = Http(URL+"/nodes/"+nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  // this is the last update of nodeId before the GET checks
  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+" - normal update - as node") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId+"-normal", compositePatid,
      Some(List(
        RegService(SDRSPEC,1,Some("active"),"{json policy for "+nodeId+" sdr}",List(
          Prop("arch","arm","string","in"),
          Prop("memory","300","int",">="),
          Prop("version","1.0.0","version","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("dataVerification","true","boolean","="))),
        RegService(NETSPEEDSPEC,1,None,"{json policy for "+nodeId+" netspeed}",List(  // intentionally setting configState to None to make sure GET displays the default
          Prop("arch","arm","string","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("version","1.0.0","version","in")))
      )),
      Some(List( OneUserInputService(orgid, SDRSPEC_URL, Some(svcarch), Some(ALL_VERSIONS), List( OneUserInputValue("UI_STRING","mystr - updated"), OneUserInputValue("UI_INT",5), OneUserInputValue("UI_BOOLEAN",true) )) )),
      Some(""), Some(Map("horizon"->"3.2.1")), nodePubKey, Some("amd64"))
    val response = Http(URL+"/nodes/"+nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid2+"/nodes/"+nodeId+" - add node in 2nd org") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId+"-norm", compositePatid, None, None, None, None, nodePubKey, None)
    val response = Http(URL2+"/nodes/"+nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH2).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId2+" - node with higher memory 400, and version 2.0.0") {
    val input = PutNodesRequest(nodeToken2, "rpi"+nodeId2+"-mem-400-vers-2", compositePatid, Some(List(RegService(SDRSPEC,1,Some("active"),"{json policy for "+nodeId2+" sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","400","int",">="),
      Prop("version","2.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","="))))), None, None, None, nodePubKey, Some("amd64"))
    val response = Http(URL+"/nodes/"+nodeId2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId3+" - netspeed-amd64, but no publicKey at 1st") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId3+"-netspeed-amd64", compositePatid, Some(List(RegService(NETSPEEDSPEC,1,Some("active"),"{json policy for "+nodeId3+" netspeed}",List(
      Prop("arch","amd64","string","in"),
      Prop("memory","300","int",">="),
      Prop("version","1.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","="))))), None, None, None, "", Some("amd64"))
    val response = Http(URL+"/nodes/"+nodeId3).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId4+" - bad integer property") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId4+"-bad-int", compositePatid, Some(List(RegService(SDRSPEC,1,Some("active"),"{json policy for "+nodeId4+" sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","400MB","int",">="),
      Prop("version","2.0.0","version","in"),
      Prop("dataVerification","true","boolean","="))))), None, None, None, nodePubKey, Some("arm"))
    val response = Http(URL+"/nodes/"+nodeId4).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val putDevResp = parse(response.body).extract[ApiResponse]
    assert(putDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId4+" - bad body format") {
    val badJsonInput = """{
      "token": "foo",
      "xname": "rpi-bad-format",
      "xregisteredServices": [
        {
          "url": """"+SDRSPEC+"""",
          "numAgreements": 1,
          "policy": "{json policy for sdr}",
          "properties": [
            {
              "name": "arch",
              "value": "arm",
              "propType": "string",
              "op": "in"
            }
          ]
        }
      ],
      "softwareVersions": {}
    }"""
    val response = Http(URL+"/nodes/"+nodeId4).postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)     // for now this is what is returned when the json-to-scala conversion fails
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId4+" - bad svc url, but this is currently allowed") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId4+"-bad-url", compositePatid, Some(List(RegService(NOTTHERESPEC,1,Some("active"),"{json policy for "+nodeId4+" sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","400","int",">="),
      Prop("version","2.0.0","version","in"),
      Prop("dataVerification","true","boolean","="))))), None, None, None, nodePubKey, Some("arm"))
    val response = Http(URL+"/nodes/"+nodeId4).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+" - add an agbot so we can test it viewing nodes") {
    val input = PutAgbotsRequest(agbotToken, agbotId+"name", None, "AGBOTABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  //~~~~~ Get nodes (and some post configState) ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("GET /orgs/"+orgid+"/nodes") {
    // val response: HttpResponse[String] = Http(URL+"/v1/nodes").headers(("Accept","application/json")).param("id","a").param("token","a").asString
    val response: HttpResponse[String] = Http(URL+"/nodes").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    //info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    assert(response.body.contains("arch"))
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 4)

    assert(getDevResp.nodes.contains(orgnodeId))
    var dev = getDevResp.nodes(orgnodeId)
    assert(dev.name === "rpi"+nodeId+"-normal")
    assert(dev.softwareVersions.size === 1)
    assert(dev.softwareVersions.contains("horizon"))
    assert(dev.softwareVersions("horizon") === "3.2.1")
    assert(dev.arch === "amd64")
    assert(dev.registeredServices.length === 2)
    // sdr reg svc
    var svc: RegService = dev.registeredServices.find(m => m.url == SDRSPEC).orNull
    assert(svc !== null)
    assert(svc.url === SDRSPEC)
    assert(svc.configState === Some("active"))
    assert(svc.policy === "{json policy for "+nodeId+" sdr}")
    var archProp = svc.properties.find(p => p.name=="arch").orNull
    assert((archProp !== null) && (archProp.name === "arch"))
    assert(archProp.value === "arm")
    var memProp = svc.properties.find(p => p.name=="memory").orNull
    assert((memProp !== null) && (memProp.value === "300"))
    // netspeed reg svc
    svc = dev.registeredServices.find(m => m.url==NETSPEEDSPEC).orNull
    assert(svc !== null)
    assert(svc.configState === Some("active"))
    assert(svc.properties.find(p => p.name=="cpus") === None)
    assert(svc.properties.find(p => p.name=="agreementProtocols") !== None)
    assert(dev.registeredServices.find(m => m.url==PWSSPEC) === None)
    // userInput section
    val uis = dev.userInput
    val uisElem = uis.head
    assert(uisElem.serviceUrl === SDRSPEC_URL)
    assert(uisElem.serviceArch.getOrElse("") === svcarch)
    assert(uisElem.serviceVersionRange.getOrElse("") === ALL_VERSIONS)
    val inp = uisElem.inputs
    var inpElem = inp.find(u => u.name=="UI_STRING").orNull
    assert((inpElem !== null) && (inpElem.value === "mystr - updated"))
    inpElem = inp.find(u => u.name=="UI_INT").orNull
    assert((inpElem !== null) && (inpElem.value === 5))
    inpElem = inp.find(u => u.name=="UI_BOOLEAN").orNull
    assert((inpElem !== null) && (inpElem.value === true))

    assert(getDevResp.nodes.contains(orgnodeId2))
    dev = getDevResp.nodes(orgnodeId2)
    assert(dev.name === "rpi"+nodeId2+"-mem-400-vers-2")
    assert(dev.registeredServices.length === 1)
    svc = dev.registeredServices.head
    assert(svc.url === SDRSPEC)
    assert(svc.policy === "{json policy for "+nodeId2+" sdr}")
    memProp = svc.properties.find(p => p.name=="memory").get
    assert(memProp.value === "400")
    memProp = svc.properties.find(p => p.name=="version").get
    assert(memProp.value === "2.0.0")
    assert(dev.softwareVersions.size === 0)
    assert(dev.arch === "amd64")

    assert(getDevResp.nodes.contains(orgnodeId3))
    dev = getDevResp.nodes(orgnodeId3)
    assert(dev.name === "rpi"+nodeId3+"-netspeed-amd64")
    assert(dev.registeredServices.length === 1)
    svc = dev.registeredServices.head
    assert(svc.url === NETSPEEDSPEC)
    archProp = svc.properties.find(p => p.name=="arch").get
    assert(archProp.value === "amd64")
    assert(dev.arch === "amd64")

  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId3+" - update arch to test") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId3+"-netspeed-amd64", compositePatid, Some(List(RegService(NETSPEEDSPEC,1,Some("active"),"{json policy for "+nodeId3+" netspeed}",List(
      Prop("arch","amd64","string","in"),
      Prop("memory","300","int",">="),
      Prop("version","1.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","="))))), None, None, None, "", Some("test"))
    val response = Http(URL+"/nodes/"+nodeId3).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId3+" - verify arch updated to test") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId3).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.contains(orgnodeId3))
    val dev = getDevResp.nodes(orgnodeId3)
    assert(dev.arch === "test")
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId3+" - update arch to amd64") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId3+"-netspeed-amd64", compositePatid, Some(List(RegService(NETSPEEDSPEC,1,Some("active"),"{json policy for "+nodeId3+" netspeed}",List(
      Prop("arch","amd64","string","in"),
      Prop("memory","300","int",">="),
      Prop("version","1.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","="))))), None, None, None, "", Some("amd64"))
    val response = Http(URL+"/nodes/"+nodeId3).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId3+" - verify arch updated to amd64") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId3).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.contains(orgnodeId3))
    val dev = getDevResp.nodes(orgnodeId3)
    assert(dev.arch === "amd64")
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/services_configstate - invalid config state - should fail") {
    val input = PostNodeConfigStateRequest(orgid, SDRSPEC_URL, "foo")
    val response = Http(URL+"/nodes/"+nodeId+"/services_configstate").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/services_configstate - nonexistant url - should return not found") {
    val input = PostNodeConfigStateRequest(orgid, NOTTHERESPEC_URL, "suspended")
    val response = Http(URL+"/nodes/"+nodeId+"/services_configstate").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/services_configstate - nonexistant org - should return not found") {
    val input = PostNodeConfigStateRequest(orgnotthere, SDRSPEC_URL, "suspended")
    val response = Http(URL+"/nodes/"+nodeId+"/services_configstate").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+" - verify none of the bad POSTs above changed the node") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.contains(orgnodeId))
    val dev = getDevResp.nodes(orgnodeId)
    assert(dev.registeredServices.exists(m => m.url == SDRSPEC && m.configState.contains("active")))
    assert(dev.registeredServices.exists(m => m.url == NETSPEEDSPEC && m.configState.contains("active")))
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/services_configstate - change config state of sdr reg svc") {
    val input = PostNodeConfigStateRequest(orgid, SDRSPEC_URL, "suspended")
    info(write(input))
    val response = Http(URL+"/nodes/"+nodeId+"/services_configstate").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+" - verify sdr reg svc was suspended") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.contains(orgnodeId))
    val dev = getDevResp.nodes(orgnodeId)
    assert(dev.registeredServices.exists(m => m.url == SDRSPEC && m.configState.contains("suspended")))
    assert(dev.registeredServices.exists(m => m.url == NETSPEEDSPEC && m.configState.contains("active")))
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/services_configstate - change config state of netspeed reg svc") {
    val input = PostNodeConfigStateRequest("", NETSPEEDSPEC_URL, "suspended")
    val response = Http(URL+"/nodes/"+nodeId+"/services_configstate").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+" - verify netspeed reg svc was suspended") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.contains(orgnodeId))
    val dev = getDevResp.nodes(orgnodeId)
    assert(dev.registeredServices.exists(m => m.url == SDRSPEC && m.configState.contains("suspended")))
    assert(dev.registeredServices.exists(m => m.url == NETSPEEDSPEC && m.configState.contains("suspended")))
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/services_configstate - change config state of all reg svcs back to active") {
    val input = PostNodeConfigStateRequest("", "", "active")
    val response = Http(URL+"/nodes/"+nodeId+"/services_configstate").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+" - verify all reg svcs back to active") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.contains(orgnodeId))
    val dev = getDevResp.nodes(orgnodeId)
    assert(dev.registeredServices.exists(m => m.url == SDRSPEC && m.configState.contains("active")))
    assert(dev.registeredServices.exists(m => m.url == NETSPEEDSPEC && m.configState.contains("active")))
  }

  test("GET /orgs/"+orgid+"/nodes - filter owner and name") {
    val response: HttpResponse[String] = Http(URL+"/nodes").headers(ACCEPT).headers(USERAUTH).param("owner",orgid+"/"+user).param("name","rpi%netspeed%amd64").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 1)
    assert(getDevResp.nodes.contains(orgnodeId3))
  }

  test("GET /orgs/"+orgid+"/nodes - filter owner and idfilter") {
    val response: HttpResponse[String] = Http(URL+"/nodes").headers(ACCEPT).headers(USERAUTH).param("owner",orgid+"/"+user).param("idfilter",orgid+"/n%").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 4)
    assert(getDevResp.nodes.contains(orgnodeId))
    assert(getDevResp.nodes.contains(orgnodeId2))
    assert(getDevResp.nodes.contains(orgnodeId3))
    assert(getDevResp.nodes.contains(orgnodeId4))
  }

  test("GET /orgs/"+orgid+"/nodes - bad creds") {
    // val response: HttpResponse[String] = Http(URL+"/v1/nodes").headers(("Accept","application/json")).param("id","a").param("token","a").asString
    val response: HttpResponse[String] = Http(URL+"/nodes").headers(ACCEPT).headers(BADAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BADCREDS)
  }

  test("GET /orgs/"+orgid+"/nodes - by agbot") {
    val response: HttpResponse[String] = Http(URL+"/nodes").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 4)
  }

  test("GET /orgs/"+orgid+" - "+nodeId+" should be able to read his own org") {
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/heartbeat") {
    val response = Http(URL+"/nodes/"+nodeId+"/heartbeat").method("post").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val devResp = parse(response.body).extract[ApiResponse]
    assert(devResp.code === ApiResponseType.OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId) {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 1)

    assert(getDevResp.nodes.contains(orgnodeId))
    val dev = getDevResp.nodes(orgnodeId)
    assert(dev.name === "rpi"+nodeId+"-normal")

    // Verify the lastHeartbeat from the POST heartbeat above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastHb = ZonedDateTime.parse(dev.lastHeartbeat).toEpochSecond
    assert(now - lastHb <= 3)    // should not now be more than 3 seconds from the time the heartbeat was done above

    assert(dev.registeredServices.length === 2)
    val svc: RegService = dev.registeredServices.find(m => m.url==SDRSPEC).orNull
    assert(svc !== null)
    assert(svc.url === SDRSPEC)
    assert(svc.policy === "{json policy for "+nodeId+" sdr}")
    var archProp = svc.properties.find(p => p.name=="arch").orNull
    assert((archProp !== null) && (archProp.name === "arch"))
    assert(archProp.value === "arm")
    var memProp = svc.properties.find(p => p.name=="memory").orNull
    assert((memProp !== null) && (memProp.value === "300"))

    assert(dev.registeredServices.find(m => m.url==NETSPEEDSPEC) !== None)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+" - as node") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 1)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+" - as node - encoded") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId).headers(ACCEPT).headers(ENCODEDAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 1)
    assert(getDevResp.nodes.contains(orgnodeId))
    val dev = getDevResp.nodes(orgnodeId)
    assert(dev.name === "rpi"+nodeId+"-normal")
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+" - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 1)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+" - as node, with token in URL parms, but no id") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId+"?token="+nodeToken).headers(ACCEPT).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 1)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+" - as user in the URL params") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId+"?id="+user+"&token="+pw).headers(ACCEPT).asString
    info("code: "+response.code)
    //info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 1)
  }

  test("PATCH /orgs/"+orgid+"/nodes/"+nodeId+" - userInput with an invalid svc ref") {
    val jsonInput = """{ "userInput": [{ "serviceOrgid": """"+orgid+"""", "serviceUrl": """"+SDRSPEC_URL+"""", "serviceArch": "fooarch", "serviceVersionRange": """"+ALL_VERSIONS+"""", "inputs": [{"name":"UI_STRING","value":"mystr - updated"}, {"name":"UI_INT","value": 7}, {"name":"UI_BOOLEAN","value": true}] }] }"""
    val response = Http(URL+"/nodes/"+nodeId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PATCH /orgs/"+orgid+"/nodes/"+nodeId+" - userInput without actually specifying 'userInput' ") {
    val jsonInput = """[{"inputs": [{"name": "var1","value": "someString"}, {"name": "var2", "value": 5},{"name": "var3", "value": 22.2}], "serviceArch": "amd64", "serviceOrgid": "IBM", "serviceUrl": "ibm.gps", "serviceVersionRange": "[2.2.0,INFINITY)"}]""".stripMargin
    val response = Http(URL+"/nodes/"+nodeId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    assert(response.body.contains("invalid input"))
  }

  test("PATCH /orgs/"+orgid+"/nodes/"+nodeId+" - try to go from blank pattern to nonblank pattern - should fail") {
    patchNodePattern(nodeId, "")    // First blank out the pattern

    // Now try to set the pattern - should fail
    val jsonInput = """{ "pattern": """"+compositePatid+"""" }"""
    val response = Http(URL+"/nodes/"+nodeId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)

    // Restore the pattern
    patchNodePublicKey(nodeId, "")
    patchNodePattern(nodeId, compositePatid)
    patchNodePublicKey(nodeId, nodePubKey)
  }

  test("PATCH /orgs/"+orgid+"/nodes/"+nodeId+" - as node") {
    var jsonInput = """{ "publicKey": """"+nodePubKey+"""" }"""
    var response = Http(URL+"/nodes/"+nodeId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)

    jsonInput = """{ "userInput": [{ "serviceOrgid": """"+orgid+"""", "serviceUrl": """"+SDRSPEC_URL+"""", "serviceArch": """"+svcarch+"""", "serviceVersionRange": """"+ALL_VERSIONS+"""", "inputs": [{"name":"UI_STRING","value":"mystr - updated"}, {"name":"UI_INT","value": 7}, {"name":"UI_BOOLEAN","value": true}] }] }"""
    response = Http(URL+"/nodes/"+nodeId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+" - as node, check patch by getting that 1 attr") {
    var response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId+"?attribute=publicKey").headers(ACCEPT).headers(NODEAUTH).asString
    //info("code: "+response.code)
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getNodeResp = parse(response.body).extract[GetNodeAttributeResponse]
    assert(getNodeResp.attribute === "publicKey")
    assert(getNodeResp.value === nodePubKey)

    response = Http(URL+"/nodes/"+nodeId).headers(ACCEPT).headers(NODEAUTH).param("attribute","userInput").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetNodeAttributeResponse]
    assert(respObj.attribute === "userInput")
    val uis = parse(respObj.value).extract[List[OneUserInputService]]
    val uisElem = uis.head
    assert(uisElem.serviceUrl === SDRSPEC_URL)
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

  test("GET /orgs/"+orgid+"/nodes/"+nodeId4) {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId4).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
  }

  //~~~~~ Pattern search and nodehealth ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - as agbot - should not find "+nodeId3+" because no publicKey") {
    val input = PostPatternSearchRequest(SDRSPEC, Some(List(orgid,orgid2)), 86400, 0, 0, None)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 4)
    assert(nodes.count(d => d.id==orgnodeId || d.id==org2nodeId || d.id==orgnodeId2 || d.id==orgnodeId4) === 4)
    val dev = nodes.find(d => d.id == orgnodeId).get // the 2nd get turns the Some(val) into val
    assert(dev.publicKey === nodePubKey)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+PWSSPEC+" which is not in the pattern, so should fail") {
    val input = PostPatternSearchRequest(PWSSPEC, None, 86400, 0, 0, None)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    //info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/nodehealth - as agbot, with blank time, and both orgs - should find all nodes") {
    val input = PostNodeHealthRequest("", Some(List(orgid,orgid2)))
    val response = Http(URL+"/patterns/"+patid+"/nodehealth").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postResp = parse(response.body).extract[PostNodeHealthResponse]
    val nodes = postResp.nodes
    assert(nodes.size === 5)
    assert(nodes.contains(orgnodeId) && nodes.contains(orgnodeId2) && nodes.contains(orgnodeId3) && nodes.contains(orgnodeId4))
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/nodehealth - as agbot, with current time - should get no nodes") {
    //Thread.sleep(500)    // delay 0.5 seconds so no agreements will be current
    val currentTime = ApiTime.futureUTC(100000)   // sometimes there is a mismatch between the exch svr time and this client's time
    info("currentTime: "+currentTime)
    val input = PostNodeHealthRequest(currentTime, None)
    val response = Http(URL+"/patterns/"+patid+"/nodehealth").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    //info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val postResp = parse(response.body).extract[PostNodeHealthResponse]
    val nodes = postResp.nodes
    assert(nodes.size === 0)
  }

  //~~~~~ Business policy search ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("PATCH /orgs/"+orgid+"/nodes/"+nodeId3+" - add publicKey so it will be found") {
    val jsonInput = """{ "publicKey": "NODE3ABC" }"""
    val response = Http(URL + "/nodes/" + nodeId3).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicySdr+"/search - all nodes (no agreements yet)") {
    patchAllNodePatterns("")      // remove pattern from nodes so we can search for services
    val input = PostBusinessPolicySearchRequest(None, 0, None, None)
    val response = Http(URL+"/business/policies/"+businessPolicySdr+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostBusinessPolicySearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 4)     // we created 4 nodes in this org
    assert(nodes.count(d => d.id==orgnodeId || d.id==orgnodeId2 || d.id==orgnodeId3 || d.id==orgnodeId4) === 4)
    val dev = nodes.find(d => d.id == orgnodeId).get
    assert(dev.publicKey === nodePubKey)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicyNS+"/search - as agbot") {
    val input = PostBusinessPolicySearchRequest(None, 0, None, None)
    val response = Http(URL+"/business/policies/"+businessPolicyNS+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostBusinessPolicySearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 3)     // we created 4 nodes in this org, but nodeId4 is arm
  }

  //~~~~~ Node health search ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("POST /orgs/"+orgid+"/search/nodehealth - as agbot, with blank time - should find all nodes") {
    val input = PostNodeHealthRequest("", None)
    val response = Http(URL+"/search/nodehealth").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postResp = parse(response.body).extract[PostNodeHealthResponse]
    val nodes = postResp.nodes
    assert(nodes.size === 4)
    assert(nodes.contains(orgnodeId) && nodes.contains(orgnodeId2) && nodes.contains(orgnodeId3) && nodes.contains(orgnodeId4))
  }

  test("POST /orgs/"+orgid+"/search/nodehealth - as agbot, with current time - should get no nodes") {
    //Thread.sleep(500)    // delay 0.5 seconds so no agreements will be current
    val input = PostNodeHealthRequest(ApiTime.futureUTC(100000), None)
    val response = Http(URL+"/search/nodehealth").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val postResp = parse(response.body).extract[PostNodeHealthResponse]
    val nodes = postResp.nodes
    assert(nodes.size === 0)
  }

  //~~~~~ Node status ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/status - as node") {
    val oneService = OneService("agreementid", "testService", orgid, "0.0.1", "arm", List[ContainerStatus]())
    val input = PutNodeStatusRequest(Map[String,Boolean]("images.bluehorizon.network" -> true), List[OneService](oneService))
    val response = Http(URL+"/nodes/"+nodeId+"/status").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/status - as node") {
    val response = Http(URL+"/nodes/"+nodeId+"/status").method("get").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getResp = parse(response.body).extract[NodeStatus]
    assert(getResp.connectivity("images.bluehorizon.network") === true)
    // runningServices should look like : |NodesSuiteTests/testService_0.0.1_arm|
    assert(getResp.runningServices.contains("|"+orgid+"/testService_0.0.1_arm|"))
  }

  test("DELETE /orgs/"+orgid+"/nodes/"+nodeId+"/status - as node") {
    val response = Http(URL+"/nodes/"+nodeId+"/status").method("delete").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/status - as node - should not be there") {
    val response = Http(URL+"/nodes/"+nodeId+"/status").method("get").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  //~~~~~ Node errors ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/errors - as node") {
    val input = """{ "errors": [{ "record_id":"1", "message":"test error 1", "event_code":"500", "hidden":false, "workload":{"url":"myservice"}, "timestamp":"yesterday" }] }"""
    val response = Http(URL+"/nodes/"+nodeId+"/errors").postData(input).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("POST DATA: " + write(input))
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/errors - as node") {
    val response = Http(URL+"/nodes/"+nodeId+"/errors").method("get").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getResp = parse(response.body).extract[NodeError]
    assert(getResp.errors.size === 1)
    // Note: we could do introspection on getResp and query specific fields, but don't have time to do that right now
    assert(response.body.contains(""""record_id":"1""""))
    assert(response.body.contains(""""message":"test error 1""""))
    assert(response.body.contains(""""workload":{"url":"myservice"}"""))
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/errors - as node with 2 errors") {
    val input = """{ "errors": [{ "record_id":"1", "message":"test error 1", "event_code":"500", "hidden":false, "workload":{"url":"myservice"}, "timestamp":"yesterday" }, { "record_id":"2", "message":"test error 2", "event_code":"404", "hidden":true, "workload":{"url":"myservice2"}, "timestamp":"yesterday" }] }"""
    val response = Http(URL+"/nodes/"+nodeId+"/errors").postData(input).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("POST DATA: " + write(input))
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/errors - as node with 2 errors") {
    val response = Http(URL+"/nodes/"+nodeId+"/errors").method("get").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getResp = parse(response.body).extract[NodeError]
    info(getResp.errors.size.toString)
    assert(getResp.errors.size === 2)
    assert(response.body.contains(""""record_id":"1""""))
    assert(response.body.contains(""""message":"test error 1""""))
    assert(response.body.contains(""""workload":{"url":"myservice"}"""))
    assert(response.body.contains(""""record_id":"2""""))
    assert(response.body.contains(""""message":"test error 2""""))
    assert(response.body.contains(""""workload":{"url":"myservice2"}"""))
  }

  test("POST /orgs/"+orgid+"/search/nodes/error/ - as agbot") {
    val input = PostNodeErrorRequest()
    val response = Http(URL+"/search/nodes/error").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    info("response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postResp = parse(response.body).extract[PostNodeErrorResponse]
    assert(postResp.nodes.size === 1)
    assert(postResp.nodes.head === "NodesSuiteTests/n1")
  }

  test("DELETE /orgs/"+orgid+"/nodes/"+nodeId+"/errors - as node") {
    val response = Http(URL+"/nodes/"+nodeId+"/errors").method("delete").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/errors - as node - should not be there") {
    val response = Http(URL+"/nodes/"+nodeId+"/errors").method("get").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("POST /orgs/"+orgid+"/search/nodes/error/ - as agbot, no errors") {
    val input = PostNodeErrorRequest()
    val response = Http(URL+"/search/nodes/error").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    info("response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    assert(response.body.isEmpty)
  }

  test("POST /orgs/"+orgid+"/search/nodes/error/ - as agbot, no input body, also no errors") {
    val response = Http(URL+"/search/nodes/error").method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    info("response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    assert(response.body.isEmpty)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/errors - as node, empty list as errors") {
    val input = """{ "errors": [] }"""
    val response = Http(URL+"/nodes/"+nodeId+"/errors").postData(input).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("POST DATA: " + write(input))
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/search/nodes/error/ - as agbot, no errors, even where errors is empty list") {
    val input = PostNodeErrorRequest()
    val response = Http(URL+"/search/nodes/error").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    info("response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    assert(response.body.isEmpty)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/errors - as node, adding the error again") {
    val input = """{ "errors": [{ "record_id":"1", "message":"test error 1", "event_code":"500", "hidden":false, "workload":{"url":"myservice"}, "timestamp":"yesterday" }] }"""
    val response = Http(URL+"/nodes/"+nodeId+"/errors").postData(input).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("POST DATA: " + write(input))
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId2+"/errors - add error to another node") {
    val input = """{ "errors": [{ "record_id":"2", "message":"test error 2", "event_code":"500", "hidden":false, "workload":{"url":"myservice"}, "timestamp":"yesterday" }] }"""
    val response = Http(URL+"/nodes/"+nodeId2+"/errors").postData(input).method("put").headers(CONTENT).headers(ACCEPT).headers(NODE2AUTH).asString
    info("POST DATA: " + write(input))
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/search/nodes/error/ - as agbot, list should have 2 nodes") {
    val input = PostNodeErrorRequest()
    val response = Http(URL+"/search/nodes/error").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    info("response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postResp = parse(response.body).extract[PostNodeErrorResponse]
    assert(postResp.nodes.size === 2)
    assert(postResp.nodes.contains("NodesSuiteTests/n1"))
    assert(postResp.nodes.contains("NodesSuiteTests/n2"))
  }

  test("POST /orgs/"+orgid+"/search/nodes/error/ - as agbot, list should have 2 nodes, no input body") {
    val response = Http(URL+"/search/nodes/error").method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    info("response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postResp = parse(response.body).extract[PostNodeErrorResponse]
    assert(postResp.nodes.size === 2)
    assert(postResp.nodes.contains("NodesSuiteTests/n1"))
    assert(postResp.nodes.contains("NodesSuiteTests/n2"))
  }

  test("DELETE /orgs/"+orgid+"/nodes/"+nodeId+"/errors - as first node again") {
    val response = Http(URL+"/nodes/"+nodeId+"/errors").method("delete").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("DELETE /orgs/"+orgid+"/nodes/"+nodeId2+"/errors - as second node") {
    val response = Http(URL+"/nodes/"+nodeId2+"/errors").method("delete").headers(CONTENT).headers(ACCEPT).headers(NODE2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("POST /orgs/"+orgid+"/search/nodes/error/ - verify no more errors") {
    val input = PostNodeErrorRequest()
    val response = Http(URL+"/search/nodes/error").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    info("response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    assert(response.body.isEmpty)
  }

  //~~~~~ Node policy ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/policy - as node") {
    val input = PutNodePolicyRequest(Some(List(OneProperty("purpose",None,"testing"))), Some(List("a == b")))
    val response = Http(URL+"/nodes/"+nodeId+"/policy").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/policy - as node") {
    val response = Http(URL+"/nodes/"+nodeId+"/policy").method("get").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getResp = parse(response.body).extract[NodePolicy]
    assert(getResp.properties.size === 1)
    assert(getResp.properties.head.name === "purpose")
  }

  test("DELETE /orgs/"+orgid+"/nodes/"+nodeId+"/policy - as node") {
    val response = Http(URL+"/nodes/"+nodeId+"/policy").method("delete").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/policy - as node - should not be there") {
    val response = Http(URL+"/nodes/"+nodeId+"/policy").method("get").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  //~~~~~ Node agreements, and more searches and nodehealth ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" - create sdr agreement, as node") {
    val input = PutNodeAgreementRequest(Some(List(NAService(orgid,SDRSPEC_URL))), Some(NAgrService(orgid,patid,SDRSPEC)), "signed")
    val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" - update sdr agreement as node") {
    val input = PutNodeAgreementRequest(Some(List(NAService(orgid,SDRSPEC_URL))), Some(NAgrService(orgid,patid,SDRSPEC)), "finalized")
    val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" - update sdr agreement as user") {
    val input = PutNodeAgreementRequest(Some(List(NAService(orgid,SDRSPEC_URL))), Some(NAgrService(orgid,patid,SDRSPEC)), "negotiating")
    val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - with "+nodeId+" in agreement") {
    patchAllNodePatterns(compositePatid)      // put pattern back in nodes so we can search for pattern nodes
    val input = PostPatternSearchRequest(SDRSPEC, Some(List(orgid,orgid2)), 86400, 0, 0, None)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    //info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 4)
    assert(nodes.count(d => d.id==org2nodeId || d.id==orgnodeId2 || d.id==orgnodeId3 || d.id==orgnodeId4) === 4)
  }

  test("PUT /orgs/"+orgid2+"/nodes/"+nodeId+"/agreements/"+agreementId2+" - create agreement for node in 2nd org, with short old style url") {
    val input = PutNodeAgreementRequest(Some(List(NAService(orgid,SDRSPEC_URL))), Some(NAgrService(orgid,patid,SDRSPEC_URL)), "signed")
    val response = Http(URL2+"/nodes/"+nodeId+"/agreements/"+agreementId2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH2).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - with "+org2nodeId+" in agreement") {
    //patchNodePattern(compositePatid)      // put pattern back in nodes so we can search for pattern nodes
    val input = PostPatternSearchRequest(SDRSPEC, Some(List(orgid,orgid2)), 86400, 0, 0, None)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    //info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 3)
    assert(nodes.count(d => d.id==orgnodeId2 || d.id==orgnodeId3 || d.id==orgnodeId4) === 3)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/nodehealth - as agbot, with blank time - should find all nodes in both orgs and 1 agreement for "+nodeId+" in each org") {
    val input = PostNodeHealthRequest("", Some(List(orgid,orgid2)))
    val response = Http(URL+"/patterns/"+patid+"/nodehealth").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postResp = parse(response.body).extract[PostNodeHealthResponse]
    val nodes = postResp.nodes
    assert(nodes.size === 5)
    assert(nodes.contains(orgnodeId) && nodes.contains(org2nodeId) && nodes.contains(orgnodeId2) && nodes.contains(orgnodeId3) && nodes.contains(orgnodeId4))
    var dev = nodes(orgnodeId)
    assert(dev.agreements.contains(agreementId))
    dev = nodes(org2nodeId)
    assert(dev.agreements.contains(agreementId2))
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/nodehealth - as agbot, with blank time - should find all nodes in the 1st orgs and 1 agreement for "+nodeId) {
    val input = PostNodeHealthRequest("", None)
    val response = Http(URL+"/patterns/"+patid+"/nodehealth").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postResp = parse(response.body).extract[PostNodeHealthResponse]
    val nodes = postResp.nodes
    assert(nodes.size === 4)
    assert(nodes.contains(orgnodeId) && nodes.contains(orgnodeId2) && nodes.contains(orgnodeId3) && nodes.contains(orgnodeId4))
    val dev = nodes(orgnodeId)
    assert(dev.agreements.contains(agreementId))
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicySdr+"/search - 1 node in sdr agreement") {
    patchAllNodePatterns("")      // remove pattern from nodes so we can search for services
    val input = PostBusinessPolicySearchRequest(None, 0, None, None)
    val response = Http(URL+"/business/policies/"+businessPolicySdr+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostBusinessPolicySearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 3)
    assert(nodes.count(d => d.id==orgnodeId2 || d.id==orgnodeId3 || d.id==orgnodeId4) === 3)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicyNS+"/search - 1 node in sdr agreement, but that shouldn't affect this") {
    val input = PostBusinessPolicySearchRequest(None, 0, None, None)
    val response = Http(URL+"/business/policies/"+businessPolicyNS+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostBusinessPolicySearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 3)
    assert(nodes.count(d => d.id==orgnodeId || d.id==orgnodeId2 || d.id==orgnodeId3) === 3)
  }

  test("POST /orgs/"+orgid+"/search/nodehealth - as agbot, with blank time - should find all nodes and 1 agreement for "+nodeId) {
    val input = PostNodeHealthRequest("", None)
    val response = Http(URL+"/search/nodehealth").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postResp = parse(response.body).extract[PostNodeHealthResponse]
    val nodes = postResp.nodes
    assert(nodes.size === 4)
    assert(nodes.contains(orgnodeId) && nodes.contains(orgnodeId2) && nodes.contains(orgnodeId3) && nodes.contains(orgnodeId4))
    val dev = nodes(orgnodeId)
    assert(dev.agreements.contains(agreementId))
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/9951 - add 2nd agreement - pws - as node") {
    val input = PutNodeAgreementRequest(Some(List(NAService(orgid,"pws"))), Some(NAgrService(orgid,patid,"pws")), "signed")
    val response = Http(URL+"/nodes/"+nodeId+"/agreements/9951").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/agreements - verify node agreement") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId+"/agreements").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetNodeAgreementsResponse]
    assert(getAgResp.agreements.size === 2)

    assert(getAgResp.agreements.contains(agreementId))
    val ag = getAgResp.agreements(agreementId)
    assert(ag.services === List[NAService](NAService(orgid,SDRSPEC_URL)))
    assert(ag.state === "negotiating")
    assert(getAgResp.agreements.contains("9951"))
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId) {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetNodeAgreementsResponse]
    assert(getAgResp.agreements.size === 1)

    assert(getAgResp.agreements.contains(agreementId))
    val ag = getAgResp.agreements(agreementId)
    assert(ag.services === List[NAService](NAService(orgid,SDRSPEC_URL)))
    assert(ag.state === "negotiating")

    info("GET /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" output verified")
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" - as node") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetNodeAgreementsResponse]
    assert(getAgResp.agreements.size === 1)

    info("GET /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" as node output verified")
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - with "+nodeId+" in agreement, should get same result as before") {
    patchAllNodePatterns(compositePatid)      // put pattern back in nodes so we can search for pattern nodes
    val input = PostPatternSearchRequest(SDRSPEC, None, 86400, 0, 0, None)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    info(response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 3)
    assert(nodes.count(d => d.id==orgnodeId2 || d.id==orgnodeId3 || d.id==orgnodeId4) === 3)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicySdr+"/search - the pws agreement shouldn't affect this") {
    patchAllNodePatterns("")      // remove pattern from nodes so we can search for services
    val input = PostBusinessPolicySearchRequest(None, 0, None, None)
    val response = Http(URL+"/business/policies/"+businessPolicySdr+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostBusinessPolicySearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 3)
    assert(nodes.count(d => d.id==orgnodeId2 || d.id==orgnodeId3 || d.id==orgnodeId4) === 3)
  }

  test("DELETE /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" - sdr") {
    val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).method("delete").headers(ACCEPT).headers(NODEAUTH).asString
    info("DELETE "+agreementId+", code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" - netspeed") {
    val input = PutNodeAgreementRequest(Some(List(NAService(orgid,NETSPEEDSPEC_URL))), Some(NAgrService(orgid,patid,NETSPEEDSPEC)), "signed")
    val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+NETSPEEDSPEC+" - with "+nodeId+" in agreement") {
    patchAllNodePatterns(compositePatid)      // put pattern back in nodes so we can search for pattern nodes
    val input = PostPatternSearchRequest(NETSPEEDSPEC, None, 86400, 0, 0, None)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 3)
    assert(nodes.count(d => d.id==orgnodeId2 || d.id==orgnodeId3 || d.id==orgnodeId4) === 3)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - should find all nodes again") {
    val input = PostPatternSearchRequest(SDRSPEC, None, 86400, 0, 0, None)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 4)
    assert(nodes.count(d => d.id==orgnodeId || d.id==orgnodeId2 || d.id==orgnodeId3 || d.id==orgnodeId4) === 4)
    val dev = nodes.find(d => d.id == orgnodeId).get // the 2nd get turns the Some(val) into val
    assert(dev.publicKey === nodePubKey)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicyNS+"/search - the netspeed agreement means we should return 1 less node") {
    patchAllNodePatterns("")      // remove pattern from nodes so we can search for services
    val input = PostBusinessPolicySearchRequest(None, 0, None, None)
    val response = Http(URL+"/business/policies/"+businessPolicyNS+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostBusinessPolicySearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 2)
    assert(nodes.count(d => d.id==orgnodeId2 || d.id==orgnodeId3) === 2)
  }

  //~~~~~ Staleness tests ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - all nodes stale") {
    patchAllNodePatterns(compositePatid)      // put pattern back in nodes so we can search for pattern nodes
    Thread.sleep(1100)    // delay 1.1 seconds so all nodes will be stale
    val input = PostPatternSearchRequest(SDRSPEC, None, 1, 0, 0, None)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 0)
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/heartbeat - so this node won't be stale for pattern search") {
    //nodeHealthLastTime = ApiTime.nowUTC     // saving this for the nodehealth call in the next test
    val response = Http(URL+"/nodes/"+nodeId+"/heartbeat").method("post").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val devResp = parse(response.body).extract[ApiResponse]
    assert(devResp.code === ApiResponseType.OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/nodehealth - as agbot, after heartbeat - should find 1 node and 1 agreement for "+nodeId) {
    // The time sync between exch svr and this client is not reliable, so get the actual last update time of the node we are after
    //Thread.sleep(500)    // delay 0.5 seconds so no agreements will be current
    var response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.contains(orgnodeId))
    val node = getDevResp.nodes(orgnodeId)
    val nodeHealthLastTime = node.lastHeartbeat

    val input = PostNodeHealthRequest(nodeHealthLastTime, None)
    response = Http(URL+"/patterns/"+patid+"/nodehealth").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postResp = parse(response.body).extract[PostNodeHealthResponse]
    val nodes = postResp.nodes
    assert(nodes.size === 1)
    assert(nodes.contains(orgnodeId))
    val dev = nodes(orgnodeId)
    assert(dev.agreements.contains(agreementId))
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - 1 node not stale") {
    val input = PostPatternSearchRequest(SDRSPEC, None, 1, 0, 0, None)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 1)
    assert(nodes.count(d => d.id==orgnodeId) === 1)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicySdr+"/search - with a sleep, so all nodes are stale") {
    patchAllNodePatterns("")      // remove pattern from nodes so we can search for services
    Thread.sleep(2100)    // delay 2.1 seconds so all nodes will be stale
    val input = PostBusinessPolicySearchRequest(None, ApiTime.nowSeconds, None, None)
    val response = Http(URL+"/business/policies/"+businessPolicySdr+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val postSearchDevResp = parse(response.body).extract[PostBusinessPolicySearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 0)
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId2+"/heartbeat - so this node won't be stale for non-pattern search") {
    val response = Http(URL+"/nodes/"+nodeId2+"/heartbeat").method("post").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val devResp = parse(response.body).extract[ApiResponse]
    assert(devResp.code === ApiResponseType.OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/policy - so this node won't be stale either") {
    val input = PutNodePolicyRequest(Some(List(OneProperty("purpose",None,"testing"))), Some(List("a == b")))
    val response = Http(URL+"/nodes/"+nodeId+"/policy").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+businessPolicySdr+"/search - now 2 nodes not stales") {
    val input = PostBusinessPolicySearchRequest(None, changedSinceAgo(2), None, None)
    val response = Http(URL+"/business/policies/"+businessPolicySdr+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    //info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostBusinessPolicySearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 2)
    assert(nodes.count(d => d.id==orgnodeId || d.id==orgnodeId2) === 2)
  }

  test("DELETE /orgs/"+orgid+"/nodes/"+nodeId3+" - explicit delete of "+nodeId3) {
    var response = Http(URL+"/nodes/"+nodeId3).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)

    response = Http(URL+"/nodes/"+nodeId3).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/9952 - Try to add a 3rd agreement with low maxAgreements") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      // ExchConfig.load  <-- already do this earlier
      val origMaxAgreements = ExchConfig.getInt("api.limits.maxAgreements")

      // Change the maxAgreements config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxAgreements", "1")
      var response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // Now try adding another agreement - expect it to be rejected
      val input = PutNodeAgreementRequest(Some(List(NAService(orgid,"netspeed"))), Some(NAgrService(orgid,patid,"netspeed")), "signed")
      response = Http(URL+"/nodes/"+nodeId+"/agreements/9952").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
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

  test("DELETE /orgs/"+orgid+"/nodes/"+nodeId+"/agreements - all agreements") {
    val response = Http(URL+"/nodes/"+nodeId+"/agreements").method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("DELETE agreements, code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/agreements - verify all agreements gone") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId+"/agreements").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val getAgResp = parse(response.body).extract[GetNodeAgreementsResponse]
    assert(getAgResp.agreements.size === 0)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId5+" - with low maxNodes") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      // ExchConfig.load  <-- already do this earlier
      val origMaxNodes = ExchConfig.getInt("api.limits.maxNodes")

      // Change the maxNodes config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxNodes", "2")
      var response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // Now try adding another node - expect it to be rejected
      val input = PutNodesRequest(nodeToken, "rpi"+nodeId5+"-netspeed", compositePatid, Some(List(RegService(NETSPEEDSPEC,1,Some("active"),"{json policy for "+nodeId5+" netspeed}",List(
        Prop("arch","arm","string","in"),
        Prop("version","1.0.0","version","in"),
        Prop("agreementProtocols",agProto,"list","in"))))), None, None, None, nodePubKey, None)
      response = Http(URL+"/nodes/"+nodeId5).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED)
      val respObj = parse(response.body).extract[ApiResponse]
      assert(respObj.msg.contains("Access Denied"))

      // Restore the maxNodes config value in the svr
      configInput = AdminConfigRequest("api.limits.maxNodes", origMaxNodes.toString)
      response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)
    }
  }

  //~~~~~ Node messages ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("PUT /orgs/"+orgid+"/agbots/"+agbotId2+" - add a 2nd agbot so we can test msgs") {
    val input = PutAgbotsRequest(agbotToken2, agbotId2+"name", None, "AGBOT2ABC")
    val response = Http(URL+"/agbots/"+agbotId2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"foo/msgs - Send a msg from agbot1 to nonexistant node, should fail") {
    val input = PostNodesMsgsRequest("{msg1 from agbot1 to node1}", 300)
    val response = Http(URL+"/nodes/"+nodeId+"foo/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/msgs - Send a msg from agbot1 to node1") {
    val input = PostNodesMsgsRequest("{msg1 from agbot1 to node1}", 300)
    val response = Http(URL+"/nodes/"+nodeId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/msgs - short ttl so it will expire") {
    val input = PostNodesMsgsRequest("{msg1 from agbot1 to node1 with 1 second ttl}", 1)
    val response = Http(URL+"/nodes/"+nodeId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/msgs - 2nd msg from agbot1 to node1") {
    val input = PostNodesMsgsRequest("{msg2 from agbot1 to node1}", 300)
    val response = Http(URL+"/nodes/"+nodeId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/msgs - from agbot2 to node1") {
    val input = PostNodesMsgsRequest("{msg1 from agbot2 to node1}", 300)
    val response = Http(URL+"/nodes/"+nodeId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId2+"/msgs - from agbot2 to node2") {
    val input = PostNodesMsgsRequest("{msg1 from agbot2 to node2}", 300)
    val response = Http(URL+"/nodes/"+nodeId2+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+"/msgs") {
    Thread.sleep(1100)    // delay 1.1 seconds so 1 of the msgs will expire
    val response = Http(URL+"/nodes/"+nodeId+"/msgs").method("get").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val resp = parse(response.body).extract[GetNodeMsgsResponse]
    assert(resp.messages.size === 3)
    var msg = resp.messages.find(m => m.message=="{msg1 from agbot1 to node1}").orNull
    assert(msg !== null)
    assert(msg.agbotId === orgagbotId)
    assert(msg.agbotPubKey === "AGBOTABC")

    msg = resp.messages.find(m => m.message=="{msg2 from agbot1 to node1}").orNull
    assert(msg !== null)
    assert(msg.agbotId === orgagbotId)
    assert(msg.agbotPubKey === "AGBOTABC")

    msg = resp.messages.find(m => m.message=="{msg1 from agbot2 to node1}").orNull
    assert(msg !== null)
    assert(msg.agbotId === orgagbotId2)
    assert(msg.agbotPubKey === "AGBOT2ABC")
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId2+"/msgs - then delete and get again") {
    var response = Http(URL+"/nodes/"+nodeId2+"/msgs").method("get").headers(ACCEPT).headers(NODE2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val resp = parse(response.body).extract[GetNodeMsgsResponse]
    assert(resp.messages.size === 1)
    val msg = resp.messages.find(m => m.message == "{msg1 from agbot2 to node2}").orNull
    assert(msg !== null)
    assert(msg.agbotId === orgagbotId2)
    assert(msg.agbotPubKey === "AGBOT2ABC")
    val msgId = msg.msgId

    response = Http(URL+"/nodes/"+nodeId2+"/msgs/"+msgId).method("delete").headers(ACCEPT).headers(NODE2AUTH).asString
    info("DELETE "+msgId+", code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)

    response = Http(URL+"/nodes/"+nodeId2+"/msgs").method("get").headers(ACCEPT).headers(NODE2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val resp2 = parse(response.body).extract[GetNodeMsgsResponse]
    assert(resp2.messages.size === 0)
  }


  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"foo/msgs from node1 to nonexistant agbot, should fail") {
    val input = PostAgbotsMsgsRequest("{msg1 from node1 to agbot1}", 300)
    val response = Http(URL+"/agbots/"+agbotId+"foo/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/msgs from node1 to agbot1") {
    val input = PostAgbotsMsgsRequest("{msg1 from node1 to agbot1}", 300)
    val response = Http(URL+"/agbots/"+agbotId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/msgs - short ttl so it will expire") {
    val input = PostAgbotsMsgsRequest("{msg1 from node1 to agbot1 with 1 second ttl}", 1)
    val response = Http(URL+"/agbots/"+agbotId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/msgs - 2nd msg from node1 to agbot1") {
    val input = PostAgbotsMsgsRequest("{msg2 from node1 to agbot1}", 300)
    val response = Http(URL+"/agbots/"+agbotId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/msgs - from node2 to agbot1") {
    val input = PostAgbotsMsgsRequest("{msg1 from node2 to agbot1}", 300)
    val response = Http(URL+"/agbots/"+agbotId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODE2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId2+"/msgs - from node2 to agbot2") {
    val input = PostAgbotsMsgsRequest("{msg1 from node2 to agbot2}", 300)
    val response = Http(URL+"/agbots/"+agbotId2+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODE2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/msgs") {
    Thread.sleep(1100)    // delay 1.1 seconds so 1 of the msgs will expire
    val response = Http(URL+"/agbots/"+agbotId+"/msgs").method("get").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val resp = parse(response.body).extract[GetAgbotMsgsResponse]
    assert(resp.messages.size === 3)
    var msg = resp.messages.find(m => m.message=="{msg1 from node1 to agbot1}").orNull
    assert(msg !== null)
    assert(msg.nodeId === orgnodeId)
    assert(msg.nodePubKey === nodePubKey)

    msg = resp.messages.find(m => m.message=="{msg2 from node1 to agbot1}").orNull
    assert(msg !== null)
    assert(msg.nodeId === orgnodeId)
    assert(msg.nodePubKey === nodePubKey)

    msg = resp.messages.find(m => m.message=="{msg1 from node2 to agbot1}").orNull
    assert(msg !== null)
    assert(msg.nodeId === orgnodeId2)
    assert(msg.nodePubKey === nodePubKey)
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId2+"/msgs - then delete and get again") {
    var response = Http(URL+"/agbots/"+agbotId2+"/msgs").method("get").headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val resp = parse(response.body).extract[GetAgbotMsgsResponse]
    assert(resp.messages.size === 1)
    val msg = resp.messages.find(m => m.message == "{msg1 from node2 to agbot2}").orNull
    assert(msg !== null)
    assert(msg.nodeId === orgnodeId2)
    assert(msg.nodePubKey === nodePubKey)
    val msgId = msg.msgId

    response = Http(URL+"/agbots/"+agbotId2+"/msgs/"+msgId).method("delete").headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("DELETE "+msgId+", code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)

    response = Http(URL+"/agbots/"+agbotId2+"/msgs").method("get").headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val resp2 = parse(response.body).extract[GetAgbotMsgsResponse]
    assert(resp2.messages.size === 0)
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/msgs - with low maxMessagesInMailbox") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      // ExchConfig.load  <-- already do this earlier
      val origMaxMessagesInMailbox = ExchConfig.getInt("api.limits.maxMessagesInMailbox")

      // Change the maxMessagesInMailbox config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxMessagesInMailbox", "3")
      var response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // Now try adding another msg - expect it to be rejected
      var input = PostAgbotsMsgsRequest("{msg1 from node1 to agbot1}", 300)
      response = Http(URL+"/agbots/"+agbotId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED)
      var apiResp = parse(response.body).extract[ApiResponse]
      assert(apiResp.msg.contains("Access Denied"))

      // But we should still be able to send a msg to agbot2, because his mailbox isn't full yet
      input = PostAgbotsMsgsRequest("{msg1 from node1 to agbot2}", 300)
      response = Http(URL+"/agbots/"+agbotId2+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.POST_OK)
      apiResp = parse(response.body).extract[ApiResponse]
      assert(apiResp.code === ApiResponseType.OK)

      response = Http(URL+"/agbots/"+agbotId2+"/msgs").method("get").headers(ACCEPT).headers(AGBOT2AUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.OK)
      val resp = parse(response.body).extract[GetAgbotMsgsResponse]
      assert(resp.messages.size === 1)

      // Restore the maxMessagesInMailbox config value in the svr
      configInput = AdminConfigRequest("api.limits.maxMessagesInMailbox", origMaxMessagesInMailbox.toString)
      response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)
    }
  }

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/msgs - with low maxMessagesInMailbox") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      // ExchConfig.load  <-- already do this earlier
      val origMaxMessagesInMailbox = ExchConfig.getInt("api.limits.maxMessagesInMailbox")

      // Change the maxMessagesInMailbox config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxMessagesInMailbox", "3")
      var response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // Now try adding another msg - expect it to be rejected
      var input = PostNodesMsgsRequest("{msg1 from agbot1 to node1}", 300)
      response = Http(URL+"/nodes/"+nodeId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED)
      var apiResp = parse(response.body).extract[ApiResponse]
      assert(apiResp.msg.contains("Access Denied"))

      // But we should still be able to send a msg to node2, because his mailbox isn't full yet
      input = PostNodesMsgsRequest("{msg1 from agbot1 to node2}", 300)
      response = Http(URL+"/nodes/"+nodeId2+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.POST_OK)
      apiResp = parse(response.body).extract[ApiResponse]
      assert(apiResp.code === ApiResponseType.OK)

      response = Http(URL+"/nodes/"+nodeId2+"/msgs").method("get").headers(ACCEPT).headers(NODE2AUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.OK)
      val resp = parse(response.body).extract[GetNodeMsgsResponse]
      assert(resp.messages.size === 1)

      // Restore the maxMessagesInMailbox config value in the svr
      configInput = AdminConfigRequest("api.limits.maxMessagesInMailbox", origMaxMessagesInMailbox.toString)
      response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)
    }
  }

  // ~~~~~ POST /orgs/{orgid}/search/nodes/service tests ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  // Step 1: get a service running on more than one node
  // add a pattern that references a service
  val searchPattern = "SearchPattern"
  val compositeSearchPattern = orgid+"/"+searchPattern
  val nid1 = "SearchTestsNode1"
  val ntoken1 = "SeachTestsNode1Token"

  test("POST /orgs/"+orgid+"/patterns/"+searchPattern+" - so nodes can reference it") {
    val input = PostPutPatternRequest(searchPattern, None, None,
      List(
        PServices(SDRSPEC_URL, orgid, svcarch, None, List(PServiceVersions(svcversion, None, None, None, None)), None, None ),
      ),
      None, None
    )
    val response = Http(URL+"/patterns/"+searchPattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/status - update running services to search later") {
    val oneService = OneService("agreementid", SDRSPEC_URL, orgid, svcversion, svcarch, List[ContainerStatus]())
    val oneService2 = OneService("agreementid2", NETSPEEDSPEC_URL, orgid, svcversion2, svcarch2, List[ContainerStatus]())
    val input = PutNodeStatusRequest(Map[String,Boolean]("images.bluehorizon.network" -> true), List[OneService](oneService, oneService2))
    val response = Http(URL+"/nodes/"+nodeId+"/status").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId2+"/status - update running services to search later") {
    val oneService = OneService("agreementid", SDRSPEC_URL, orgid, svcversion, svcarch, List[ContainerStatus]())
    val oneService2 = OneService("agreementid2", NETSPEEDSPEC_URL, orgid, svcversion2, svcarch2, List[ContainerStatus]())
    val input = PutNodeStatusRequest(Map[String,Boolean]("images.bluehorizon.network" -> true), List[OneService](oneService, oneService2))
    val response = Http(URL+"/nodes/"+nodeId2+"/status").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODE2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  // test that the search route returns a list of more than one node
  test("POST /orgs/"+orgid+"/search/nodes/service - should find " + SDRSPEC_URL + " running on 2 nodes") {
    val input = PostServiceSearchRequest(orgid, SDRSPEC_URL, svcversion, svcarch)
    val response = Http(URL+"/search/nodes/service").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    assert(response.body.contains(nodeId))
    assert(response.body.contains(nodeId2))
  }

  test("POST /orgs/"+orgid+"/search/nodes/service - should find " + NETSPEEDSPEC_URL + " running on 2 nodes") {
    val input = PostServiceSearchRequest(orgid, NETSPEEDSPEC_URL, svcversion2, svcarch2)
    val response = Http(URL+"/search/nodes/service").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    assert(response.body.contains(nodeId))
    assert(response.body.contains(nodeId2))
  }

  // test a service that no node is running has an empty resp
  test("POST /orgs/"+orgid+"/search/nodes/service - should find " + PWSSPEC_URL + " running on 0 nodes") {
    val input = PostServiceSearchRequest(orgid, PWSSPEC_URL, svcarch, svcversion)
    val response = Http(URL+"/search/nodes/service").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    assert(response.body.isEmpty)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId2+"/status - add "+ibmService+" to search on later") {
    val oneService = OneService("agreementid", SDRSPEC_URL, orgid, svcversion, svcarch, List[ContainerStatus]())
    val oneService2 = OneService("agreementid2", ibmService, "IBM", svcversion2, svcarch2, List[ContainerStatus]())
    val input = PutNodeStatusRequest(Map[String,Boolean]("images.bluehorizon.network" -> true), List[OneService](oneService, oneService2))
    val response = Http(URL+"/nodes/"+nodeId2+"/status").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODE2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  // test that search can find an org node running an IBM service
  test("POST /orgs/"+orgid+"/search/nodes/service - should find " + ibmService + " running on 1 node") {
    val input = PostServiceSearchRequest("IBM", ibmService, svcversion2, svcarch2)
    val response = Http(URL+"/search/nodes/service").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    assert(response.body.contains(nodeId2))
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/status - add \"+ibmService+\" to search on later") {
    val oneService = OneService("agreementid", ibmService, "IBM", svcversion2, svcarch2, List[ContainerStatus]())
    val oneService2 = OneService("agreementid2", NETSPEEDSPEC_URL, orgid, svcversion2, svcarch2, List[ContainerStatus]())
    val input = PutNodeStatusRequest(Map[String,Boolean]("images.bluehorizon.network" -> true), List[OneService](oneService, oneService2))
    val response = Http(URL+"/nodes/"+nodeId+"/status").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/search/nodes/service - should find " + ibmService + " running on 2 nodes") {
    val input = PostServiceSearchRequest("IBM", ibmService, svcversion2, svcarch2)
    val response = Http(URL+"/search/nodes/service").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    assert(response.body.contains(nodeId))
    assert(response.body.contains(nodeId2))
  }

  test("POST /orgs/"+orgid+"/search/nodes/service - should find " + SDRSPEC_URL + " running on 1 node") {
    val input = PostServiceSearchRequest(orgid, SDRSPEC_URL, svcversion, svcarch)
    val response = Http(URL+"/search/nodes/service").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    assert(response.body.contains(nodeId2))
    assert(!response.body.contains(nodeId))
  }

  test("POST /orgs/"+orgid+"/search/nodes/service - should find " + NETSPEEDSPEC_URL + " running on 1 node") {
    val input = PostServiceSearchRequest(orgid, NETSPEEDSPEC_URL, svcversion2, svcarch2)
    info(write(input))
    val response = Http(URL+"/search/nodes/service").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    assert(response.body.contains(nodeId))
    assert(!response.body.contains(nodeId2))
  }

  test("POST /orgs/"+orgid+"/search/nodes/service - should find " + PWSSPEC_URL + " running on 0 nodes still") {
    val input = PostServiceSearchRequest(orgid, PWSSPEC_URL, svcarch, svcversion)
    val response = Http(URL+"/search/nodes/service").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    assert(response.body.isEmpty)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/status - change org of "+NETSPEEDSPEC_URL+" to test later") {
    val oneService = OneService("agreementid", ibmService, "IBM", svcversion2, svcarch2, List[ContainerStatus]())
    val oneService2 = OneService("agreementid2", NETSPEEDSPEC_URL, "FakeOrganization", svcversion2, svcarch2, List[ContainerStatus]())
    val input = PutNodeStatusRequest(Map[String,Boolean]("images.bluehorizon.network" -> true), List[OneService](oneService, oneService2))
    val response = Http(URL+"/nodes/"+nodeId+"/status").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/search/nodes/service - should find " + NETSPEEDSPEC_URL + " running on 0 nodes") {
    val input = PostServiceSearchRequest(orgid, NETSPEEDSPEC_URL, svcversion2, svcarch2)
    val response = Http(URL+"/search/nodes/service").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    assert(response.body.isEmpty)
  }

  //~~~~~ Break down ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("DELETE /orgs/IBM/services/"+ibmService+"_"+svcversion2+"_"+svcarch2) {
    val response = Http(urlRoot+"/v1/orgs/IBM/services/"+ibmService+"_"+svcversion2+"_"+svcarch2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }
  test("Cleanup - DELETE everything and confirm they are gone") {
    deleteAllOrgs()
  }
}
