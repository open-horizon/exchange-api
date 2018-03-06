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
  val SDRSPEC = "https://bluehorizon.network/documentation/sdr-node-api"
  val NETSPEEDSPEC = "https://bluehorizon.network/documentation/netspeed-node-api/"     // test the trailing / for this one
  val PWSSPEC = "https://bluehorizon.network/documentation/pws-node-api"
  val NOTTHERESPEC = "https://bluehorizon.network/documentation/notthere-node-api"
  val orgid = "NodesSuiteTests"
  val authpref=orgid+"/"
  val URL = urlRoot+"/v1/orgs/"+orgid
  val NOORGURL = urlRoot+"/v1"
  val user = "9980"
  val orguser = authpref+user
  val pw = user+"pw"
  val USERAUTH = ("Authorization","Basic "+orguser+":"+pw)
  val BADAUTH = ("Authorization","Basic "+orguser+":"+pw+"x")
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+rootuser+":"+rootpw)
  val nodeId = "9900"     // the 1st node created, that i will use to run some rest methods
  val orgnodeId = authpref+nodeId
  val nodeToken = "mytok"
  val NODEAUTH = ("Authorization","Basic "+orgnodeId+":"+nodeToken)
  val nodeId2 = "9901"
  val orgnodeId2 = authpref+nodeId2
  val nodeToken2 = "mytok"
  val NODE2AUTH = ("Authorization","Basic "+orgnodeId2+":"+nodeToken2)
  val nodeId3 = "9902"
  val orgnodeId3 = authpref+nodeId3
  val nodeId4 = "9903"
  val orgnodeId4 = authpref+nodeId4
  val patid = "mypat"
  val compositePatid = orgid+"/"+patid
  val workid = "bluehorizon.network-workloads-netspeed_1.0.0_amd64"
  val workurl = "https://bluehorizon.network/workloads/netspeed"
  val workarch = "amd64"
  val workversion = "1.0.0"
  val agreementId = "9950"
  val creds = authpref+nodeId+":"+nodeToken
  val encodedCreds = Base64.getEncoder.encodeToString(creds.getBytes("utf-8"))
  val ENCODEDAUTH = ("Authorization","Basic "+encodedCreds)
  //var numExistingNodes = 0    // this will be set later
  val agbotId = "9940"      // need to use a different id than AgbotsSuite.scala, because all of the suites run concurrently
  val orgagbotId = authpref+agbotId
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+orgagbotId+":"+agbotToken)
  val agbotId2 = "9941"      // need to use a different id than AgbotsSuite.scala, because all of the suites run concurrently
  val orgagbotId2 = authpref+agbotId2
  val agbotToken2 = agbotId2+"tok"
  val AGBOT2AUTH = ("Authorization","Basic "+orgagbotId2+":"+agbotToken2)
  val agProto = "ExchangeAutomatedTest"    // using this to avoid db entries from real users and predefined ones

  //var nodeHealthLastTime = ""     // used to store lastTime between calls to nodehealth

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  // Operators: test, ignore, pending

  /** Delete all the test users */
  def deleteAllUsers() = {
    for (i <- List(user)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test nodes - this is not longer used because deleting the user deletes these too */
  def deleteAllNodes() = {
    for (i <- List(nodeId,nodeId2,nodeId3,nodeId4)) {
      val response = Http(URL+"/nodes/"+i).method("delete").headers(ACCEPT).headers(USERAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test agreements - this is no longer used because deleting the user deletes these too */
  def deleteAllAgreements() = {
    for (i <- List(agreementId)) {
      val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+i).method("delete").headers(ACCEPT).headers(USERAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test agbots - this is not longer used because deleting the user deletes these too */
  def deleteAllAgbots() = {
    for (i <- List(agbotId)) {
      val response = Http(URL+"/agbots/"+i).method("delete").headers(ACCEPT).headers(USERAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Patches all of the nodes to have a pattern or blank out the pattern (for node and node health searches) */
  def patchNodePattern(pattern: String): Unit = {
    val jsonInput = """{ "pattern": """"+pattern+"""" }"""
    for (i <- List(nodeId,nodeId2,nodeId3,nodeId4)) {
      val response = Http(URL + "/nodes/" + i).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
      info("PATCH "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)
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

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the nodes, agbots, and
   *  agreements, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllUsers()
  }

  /** Add a normal user */
  test("POST /orgs/"+orgid+"/users/"+user+" - normal") {
    val input = PostPutUsersRequest(pw, admin = false, user+"@hotmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+" - before pattern exists - should fail") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId+"-norm", compositePatid,
      List(),
      "whisper-id", Map("horizon"->"3.2.3"), "NODEABC")
    val response = Http(URL+"/nodes/"+nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/workloads - add "+workid+" so pattern can reference it") {
    val input = PostPutWorkloadRequest("test-workload", "desc", public = false, workurl, workversion, workarch, None, List(), List(Map()), List())
    val response = Http(URL+"/workloads").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }
  // Note: when we delete the org, this workload will get deleted

  test("POST /orgs/"+orgid+"/patterns/"+patid+" - so nodes can reference it") {
    val input = PostPutPatternRequest(patid, "desc", public = false,
      Some(List( PWorkloads(workurl, orgid, workarch, List(PServiceVersions(workversion, "", "", Map(), Map())), Some(Map("enabled"->false, "URL"->"", "user"->"", "password"->"", "interval"->0, "check_rate"->0, "metering"->Map[String,Any]())), Some(Map("check_agreement_status" -> 120)) ))),
      None, List[Map[String,String]]()
    )
    val response = Http(URL+"/patterns/"+patid).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }
  // Note: when we delete the org, this pattern will get deleted

  ExchConfig.load()
  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+" - add normal node") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId+"-norm", compositePatid,
      List(
        RegMicroservice(PWSSPEC,1,"{json policy for "+nodeId+" pws}",List(
          Prop("arch","arm","string","in"),
          Prop("version","1.0.0","version","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("dataVerification","true","boolean","="))),
        RegMicroservice(NETSPEEDSPEC,1,"{json policy for "+nodeId+" netspeed}",List(
          Prop("arch","arm","string","in"),
          Prop("cpus","2","int",">="),
          Prop("version","1.0.0","version","in")))
      ),
      "whisper-id", Map("horizon"->"3.2.3"), "NODEABC")
    val response = Http(URL+"/nodes/"+nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Update a normal node as user */
  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+" - normal - update") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId+"-normal-user", compositePatid,
      List(
        RegMicroservice(PWSSPEC,1,"{json policy for "+nodeId+" pws}",List(
          Prop("arch","arm","string","in"),
          Prop("version","1.0.0","version","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("dataVerification","true","boolean","="))),
        RegMicroservice(NETSPEEDSPEC,1,"{json policy for "+nodeId+" netspeed}",List(
          Prop("arch","arm","string","in"),
          Prop("cpus","2","int",">="),
          Prop("version","1.0.0","version","in")))
      ),
      "", Map("horizon"->"3.2.3"), "OLDNODEABC")
    val response = Http(URL+"/nodes/"+nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Update the normal node as the node */
  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+" - normal - as node") {
    val input = PutNodesRequest(nodeToken, "rpi"+nodeId+"-normal", compositePatid,
      List(
        RegMicroservice(SDRSPEC,1,"{json policy for "+nodeId+" sdr}",List(
          Prop("arch","arm","string","in"),
          Prop("memory","300","int",">="),
          Prop("version","1.0.0","version","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("dataVerification","true","boolean","="))),
        RegMicroservice(NETSPEEDSPEC,1,"{json policy for "+nodeId+" netspeed}",List(
          Prop("arch","arm","string","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("version","1.0.0","version","in")))
      ),
      "", Map("horizon"->"3.2.1"), "NODEABC")
    val response = Http(URL+"/nodes/"+nodeId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Add a node with higher memory and version */
  test("PUT /orgs/"+orgid+"/nodes/"+nodeId2+" - memory 400, version 2.0.0") {
    val input = PutNodesRequest("mytok", "rpi9901-mem-400-vers-2", compositePatid, List(RegMicroservice(SDRSPEC,1,"{json policy for 9901 sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","400","int",">="),
      Prop("version","2.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","=")))), "whisper-id", Map(), "NODE2ABC")
    val response = Http(URL+"/nodes/"+nodeId2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId3+" - netspeed-amd64, but no publicKey at 1st") {
    val input = PutNodesRequest("mytok", "rpi9902-netspeed-amd64", compositePatid, List(RegMicroservice(NETSPEEDSPEC,1,"{json policy for 9902 netspeed}",List(
      Prop("arch","amd64","string","in"),
      Prop("memory","300","int",">="),
      Prop("version","1.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","=")))), "whisper-id", Map(), "")
    val response = Http(URL+"/nodes/"+nodeId3).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Try adding a node with invalid integer property */
  test("PUT /orgs/"+orgid+"/nodes/"+nodeId4+" - bad integer property") {
    val input = PutNodesRequest("mytok", "rpi9903-bad-int", compositePatid, List(RegMicroservice(SDRSPEC,1,"{json policy for 9903 sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","400MB","int",">="),
      Prop("version","2.0.0","version","in"),
      Prop("dataVerification","true","boolean","=")))), "whisper-id", Map(), "NODE4ABC")
    val response = Http(URL+"/nodes/"+nodeId4).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val putDevResp = parse(response.body).extract[ApiResponse]
    assert(putDevResp.code === ApiResponseType.BAD_INPUT)
  }

  /** Try adding an invalid node body */
  test("PUT /orgs/"+orgid+"/nodes/"+nodeId4+" - bad format") {
    val badJsonInput = """{
      "token": "foo",
      "xname": "rpi9903-bad-format",
      "xregisteredMicroservices": [
        {
          "url": """"+SDRSPEC+"""",
          "numAgreements": 1,
          "policy": "{json policy for 9903 sdr}",
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
      "msgEndPoint": "whisper-id",
      "softwareVersions": {}
    }"""
    val response = Http(URL+"/nodes/"+nodeId4).postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)     // for now this is what is returned when the json-to-scala conversion fails
  }

  /** Try adding a node with invalid micro url -  */
  test("PUT /orgs/"+orgid+"/nodes/"+nodeId4+" - bad micro url - this currently allowed") {
    val input = PutNodesRequest("mytok", "rpi9903-bad-url", compositePatid, List(RegMicroservice(NOTTHERESPEC,1,"{json policy for 9903 sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","400","int",">="),
      Prop("version","2.0.0","version","in"),
      Prop("dataVerification","true","boolean","=")))), "whisper-id", Map(), "NODE4ABC")
    val response = Http(URL+"/nodes/"+nodeId4).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Add an agbot so we can test it viewing nodes */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId) {
    val input = PutAgbotsRequest(agbotToken, agbotId+"name", /*List[APattern](),*/ "whisper-id", "AGBOTABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes") {
    // val response: HttpResponse[String] = Http(URL+"/v1/nodes").headers(("Accept","application/json")).param("id","a").param("token","a").asString
    val response: HttpResponse[String] = Http(URL+"/nodes").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 4)

    assert(getDevResp.nodes.contains(orgnodeId))
    var dev = getDevResp.nodes(orgnodeId)
    assert(dev.name === "rpi"+nodeId+"-normal")
    assert(dev.registeredMicroservices.length === 2)
    var micro: RegMicroservice = dev.registeredMicroservices.find(m => m.url == SDRSPEC).orNull
    assert(micro !== null)
//    var micro: RegMicroservice = dev.registeredMicroservices.find(m => m.url==SDRSPEC) match {
//      case Some(m) => m
//      case None => assert(condition = false); null
//    }
    assert(micro.url === SDRSPEC)
    assert(micro.policy === "{json policy for "+nodeId+" sdr}")
    var archProp = micro.properties.find(p => p.name=="arch").orNull
    assert((archProp !== null) && (archProp.name === "arch"))
    assert(archProp.value === "arm")
    var memProp = micro.properties.find(p => p.name=="memory").orNull
    assert((memProp !== null) && (memProp.value === "300"))
    assert(dev.softwareVersions.size === 1)
    assert(dev.softwareVersions.contains("horizon"))
    assert(dev.softwareVersions("horizon") === "3.2.1")
    micro = dev.registeredMicroservices.find(m => m.url==NETSPEEDSPEC).orNull
    assert(micro !== null)
    assert(micro.properties.find(p => p.name=="cpus") === None)
    assert(micro.properties.find(p => p.name=="agreementProtocols") !== None)
    assert(dev.registeredMicroservices.find(m => m.url==PWSSPEC) === None)

    assert(getDevResp.nodes.contains(orgnodeId2))
    dev = getDevResp.nodes(orgnodeId2)
    assert(dev.name === "rpi9901-mem-400-vers-2")
    assert(dev.registeredMicroservices.length === 1)
    micro = dev.registeredMicroservices.head
    assert(micro.url === SDRSPEC)
    assert(micro.policy === "{json policy for 9901 sdr}")
    memProp = micro.properties.find(p => p.name=="memory").get
    assert(memProp.value === "400")
    memProp = micro.properties.find(p => p.name=="version").get
    assert(memProp.value === "2.0.0")
    assert(dev.softwareVersions.size === 0)

    assert(getDevResp.nodes.contains(orgnodeId3))
    dev = getDevResp.nodes(orgnodeId3)
    assert(dev.name === "rpi9902-netspeed-amd64")
    assert(dev.registeredMicroservices.length === 1)
    micro = dev.registeredMicroservices.head
    assert(micro.url === NETSPEEDSPEC)
    archProp = micro.properties.find(p => p.name=="arch").get
    assert(archProp.value === "amd64")
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
    val response: HttpResponse[String] = Http(URL+"/nodes").headers(ACCEPT).headers(USERAUTH).param("owner",orgid+"/"+user).param("idfilter",orgid+"/990%").asString
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
//    val getDevResp = parse(response.body).extract[GetNodesResponse]
    // assert(getDevResp.nodes.size === 3)     // since the other test suites are creating some of these too, we can not know how many there are right now
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
    // assert(getDevResp.nodes.size === 1)    // since the other test suites are creating some of these too, we can not know how many there are right now

    assert(getDevResp.nodes.contains(orgnodeId))
    val dev = getDevResp.nodes(orgnodeId)
    assert(dev.name === "rpi"+nodeId+"-normal")

    // Verify the lastHeartbeat from the POST heartbeat above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastHb = ZonedDateTime.parse(dev.lastHeartbeat).toEpochSecond
    assert(now - lastHb <= 3)    // should not now be more than 3 seconds from the time the heartbeat was done above

    assert(dev.registeredMicroservices.length === 2)
    val micro: RegMicroservice = dev.registeredMicroservices.find(m => m.url==SDRSPEC).orNull
    assert(micro !== null)
    assert(micro.url === SDRSPEC)
    assert(micro.policy === "{json policy for "+nodeId+" sdr}")
    var archProp = micro.properties.find(p => p.name=="arch").orNull
    assert((archProp !== null) && (archProp.name === "arch"))
    assert(archProp.value === "arm")
    var memProp = micro.properties.find(p => p.name=="memory").orNull
    assert((memProp !== null) && (memProp.value === "300"))

    assert(dev.registeredMicroservices.find(m => m.url==NETSPEEDSPEC) !== None)
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

  /* not supported anymore...
  test("GET /orgs/"+orgid+"/nodes/"+nodeId+" - as node, with token in URL parms, but no id") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId+"?token="+nodeToken).headers(ACCEPT).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 1)
  }
  */

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+" - as user in the URL params") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId+"?id="+user+"&token="+pw).headers(ACCEPT).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === 1)
  }

  /** Update 1 attr of the node, as the node */
  test("PATCH /orgs/"+orgid+"/nodes/"+nodeId+" - as node") {
    val jsonInput = """{ "publicKey": "NODEABC" }"""
    val response = Http(URL+"/nodes/"+nodeId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/nodes/"+nodeId+" - as node, check patch by getting that 1 attr") {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId+"?attribute=publicKey").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getNodeResp = parse(response.body).extract[GetNodeAttributeResponse]
    assert(getNodeResp.attribute === "publicKey")
    assert(getNodeResp.value === "NODEABC")
}

  test("GET /orgs/"+orgid+"/nodes/"+nodeId4) {
    val response: HttpResponse[String] = Http(URL+"/nodes/"+nodeId4).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - as agbot - should not find "+nodeId3+" because no publicKey") {
    val input = PostPatternSearchRequest(SDRSPEC, 86400, 0, 0)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 3)
    assert(nodes.count(d => d.id==orgnodeId || d.id==orgnodeId2 || d.id==orgnodeId4) === 3)
    val dev = nodes.find(d => d.id == orgnodeId).get // the 2nd get turns the Some(val) into val
    assert(dev.publicKey === "NODEABC")
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/nodehealth - as agbot, with blank time - should find all nodes") {
    val input = PostNodeHealthRequest("")
    val response = Http(URL+"/patterns/"+patid+"/nodehealth").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postResp = parse(response.body).extract[PostNodeHealthResponse]
    val nodes = postResp.nodes
    assert(nodes.size === 4)
    assert(nodes.contains(orgnodeId) && nodes.contains(orgnodeId2) && nodes.contains(orgnodeId3) && nodes.contains(orgnodeId4))
    //val dev = nodes.get(orgnodeId).get // the 2nd get turns the Some(val) into val
    //assert(dev.agreements.contains(agreementId))
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/nodehealth - as agbot, with current time - should get no nodes") {
    //Thread.sleep(500)    // delay 0.5 seconds so no agreements will be current
    val currentTime = ApiTime.futureUTC(100000)   // sometimes there is a mismatch between the exch svr time and this client's time
    info("currentTime: "+currentTime)
    val input = PostNodeHealthRequest(currentTime)
    val response = Http(URL+"/patterns/"+patid+"/nodehealth").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    //info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val postResp = parse(response.body).extract[PostNodeHealthResponse]
    val nodes = postResp.nodes
    assert(nodes.size === 0)
  }

  test("PATCH /orgs/"+orgid+"/nodes/"+nodeId3+" - add publicKey") {
    val jsonInput = """{ "publicKey": "NODE3ABC" }"""
    val response = Http(URL + "/nodes/" + nodeId3).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/search/nodes - all arm nodes") {
    patchNodePattern("")      // remove pattern from nodes so we can search for microservices
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","2","int",">="),
      Prop("version","*","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 2)     // we created 2 arm nodes
    assert(nodes.count(d => d.id==orgnodeId || d.id==orgnodeId2) === 2)
    val dev = nodes.find(d => d.id == orgnodeId).get // the 2nd get turns the Some(val) into val
    assert(dev.name === "rpi"+nodeId+"-normal")
    assert(dev.microservices.length === 1)
    val micro = dev.microservices.head
    assert(micro.url === SDRSPEC)
    assert(micro.policy === "{json policy for "+nodeId+" sdr}")
    var archProp = micro.properties.find(p => p.name=="arch").orNull
    assert((archProp !== null) && (archProp.name === "arch"))
    assert(archProp.value === "arm")
  }

  test("POST /orgs/"+orgid+"/search/nodes - netspeed arch amd64 - as agbot") {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(NETSPEEDSPEC,List(
      Prop("arch","amd64","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","[1.0.0,2.0.0]","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 1)
    assert(nodes.count(d => d.id==orgnodeId3) === 1)
  }

  test("POST /orgs/"+orgid+"/search/nodes - netspeed arch * - as agbot") {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(NETSPEEDSPEC,List(
      Prop("arch","*","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","[1.0.0,2.0.0]","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 2)
    assert(nodes.count(d => d.id==orgnodeId) === 1)
    assert(nodes.count(d => d.id==orgnodeId3) === 1)
  }

  test("POST /orgs/"+orgid+"/search/nodes - netspeed and sdr - as agbot") {
    val input = PostSearchNodesRequest(List(
      RegMicroserviceSearch(NETSPEEDSPEC,List(
        Prop("arch","*","string","in"),
        Prop("memory","*","int",">="),
        Prop("version","[1.0.0,2.0.0]","version","in"),
        Prop("agreementProtocols",agProto,"list","in"),
        Prop("dataVerification","","wildcard","="))),
      RegMicroserviceSearch(SDRSPEC,List(
        Prop("arch","arm","string","in"),
        Prop("memory","2","int",">="),
        Prop("version","*","version","in"),
        Prop("agreementProtocols",agProto,"list","in"),
        Prop("dataVerification","","wildcard","=")))
    ), 86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 1)
    assert(nodes.count(d => d.id==orgnodeId) === 1)  // this confirms it did get nodeId (2 MSs) and did not get nodeId2 (only 1 of the MSs)
    val dev = nodes.find(d => d.id == orgnodeId).get // the 2nd get turns the Some(val) into val
    assert(dev.microservices.length === 2)
    assert(dev.microservices.count(m => m.url==SDRSPEC) === 1)
    assert(dev.microservices.count(m => m.url==NETSPEEDSPEC) === 1)
  }

  test("POST /orgs/"+orgid+"/search/nodes - arch list, mem 400, version 2.0.0") {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","arm,amd64","list","in"),
      Prop("memory","400","int",">="),
      Prop("version","2.0.0,3.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 1)
    assert(nodes.count(d => d.id==orgnodeId2) === 1)
  }

  /** Do not expect any matches on this search */
  test("POST /orgs/"+orgid+"/search/nodes - data verification false") {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","","wildcard","in"),
      Prop("memory","","wildcard",">="),
      Prop("version","0","version","in"),     // in osgi version format 0 means lower bound is 0 and upper bound infinity
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","false","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    assert(postSearchDevResp.nodes.length === 0)
  }

  test("POST /orgs/"+orgid+"/search/nodes - invalid propType") {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","","stringx","in"),
      Prop("memory","","int",">="),
      Prop("version","","version","in"),
      Prop("dataVerification","","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/search/nodes - invalid op") {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","","string","inx"),
      Prop("memory","","int",">="),
      Prop("version","","version","in"),
      Prop("dataVerification","","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/search/nodes - invalid version") {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","*","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","1.2.3.4","version","in"),
      Prop("dataVerification","*","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/search/nodes - invalid boolean/op combo") {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","","string","in"),
      Prop("memory","","int",">="),
      Prop("version","","version","in"),
      Prop("dataVerification","","boolean","in")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/search/nodes - invalid string/op combo") {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","","string","="),
      Prop("memory","","int",">="),
      Prop("version","","version","in"),
      Prop("dataVerification","","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/search/nodes - invalid int/op combo") {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","","string","in"),
      Prop("memory","","int","in"),
      Prop("version","","version","in"),
      Prop("dataVerification","","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/search/nodes - invalid version/op combo") {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","","string","in"),
      Prop("memory","","int",">="),
      Prop("version","","version",">="),
      Prop("dataVerification","","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/search/nodehealth - as agbot, with blank time - should find all nodes") {
    val input = PostNodeHealthRequest("")
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
    val input = PostNodeHealthRequest(ApiTime.futureUTC(100000))
    val response = Http(URL+"/search/nodehealth").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val postResp = parse(response.body).extract[PostNodeHealthResponse]
    val nodes = postResp.nodes
    assert(nodes.size === 0)
  }


  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/status - as node") {
    val input = PutNodeStatusRequest(Map[String,Boolean]("images.bluehorizon.network" -> true), List[OneMicroservice](), List[OneWorkload]())
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


  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" - create agreement, as node") {
    val input = PutNodeAgreementRequest(List[NAMicroservice](NAMicroservice(orgid,SDRSPEC)), NAWorkload(orgid,patid,SDRSPEC), "signed")
    val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" - update agreement as node") {
    val input = PutNodeAgreementRequest(List[NAMicroservice](NAMicroservice(orgid,SDRSPEC)), NAWorkload(orgid,patid,SDRSPEC), "finalized")
    val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" - update agreement as user") {
    val input = PutNodeAgreementRequest(List[NAMicroservice](NAMicroservice(orgid,SDRSPEC)), NAWorkload(orgid,patid,SDRSPEC), "negotiating")
    val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - with "+nodeId+" in agreement") {
    patchNodePattern(compositePatid)      // put pattern back in nodes so we can search for pattern nodes
    val input = PostPatternSearchRequest(SDRSPEC, 86400, 0, 0)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    //info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 3)
    assert(nodes.count(d => d.id==orgnodeId2 || d.id==orgnodeId3 || d.id==orgnodeId4) === 3)
  }

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/nodehealth - as agbot, with blank time - should find all nodes and 1 agreement for "+nodeId) {
    val input = PostNodeHealthRequest("")
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

  test("POST /orgs/"+orgid+"/search/nodes - netspeed and sdr - now no nodes, since 1 agreement made") {
    patchNodePattern("")      // remove pattern from nodes so we can search for microservices
    val input = PostSearchNodesRequest(List(
      RegMicroserviceSearch(NETSPEEDSPEC,List(
        Prop("arch","*","string","in"),
        Prop("memory","*","int",">="),
        Prop("version","[1.0.0,2.0.0]","version","in"),
        Prop("agreementProtocols",agProto,"list","in"),
        Prop("dataVerification","","wildcard","="))),
      RegMicroserviceSearch(SDRSPEC,List(
        Prop("arch","arm","string","in"),
        Prop("memory","2","int",">="),
        Prop("version","*","version","in"),
        Prop("agreementProtocols",agProto,"list","in"),
        Prop("dataVerification","","wildcard","=")))
    ), 86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 0)
  }

  test("POST /orgs/"+orgid+"/search/nodehealth - as agbot, with blank time - should find all nodes and 1 agreement for "+nodeId) {
    val input = PostNodeHealthRequest("")
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

  /** Add a 2nd agreement for node 9900 - as the node */
  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/9951 - as node") {
    val input = PutNodeAgreementRequest(List[NAMicroservice](NAMicroservice(orgid,"pws")), NAWorkload(orgid,patid,"pws"), "signed")
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
    assert(ag.microservices === List[NAMicroservice](NAMicroservice(orgid,SDRSPEC)))
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
    assert(ag.microservices === List[NAMicroservice](NAMicroservice(orgid,SDRSPEC)))
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

  /** Should get the same result as before, the 2nd workload in agreement on nodeId doesn't affect nodeId still being omitted */
  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - with "+nodeId+" in agreement for same reason") {
    patchNodePattern(compositePatid)      // put pattern back in nodes so we can search for pattern nodes
    val input = PostPatternSearchRequest(SDRSPEC, 86400, 0, 0)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 3)
    assert(nodes.count(d => d.id==orgnodeId2 || d.id==orgnodeId3 || d.id==orgnodeId4) === 3)
  }

  /** Run /search/nodes again and we should get 1 less result, because 9900 is in contract */
  test("POST /orgs/"+orgid+"/search/nodes - all arm nodes, "+nodeId+" in agreement") {
    patchNodePattern("")      // remove pattern from nodes so we can search for microservices
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","","wildcard","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 1 || nodes.length === 2)     // UsersSuite may have created 1
    assert(nodes.count(d => d.id==orgnodeId2) === 1)
  }

  /** We should still find the netspeed MS on 9900, even though the sdr MS on 9900 is in agreement */
  test("POST /orgs/"+orgid+"/search/nodes - netspeed arch arm, "+nodeId+" sdr in agreement - as agbot") {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(NETSPEEDSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","[1.0.0,2.0.0]","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 1)
    assert(nodes.count(d => d.id==orgnodeId) === 1)
  }

  /** Delete the agreement for node 9900 */
  test("DELETE /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" - sdr") {
    val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).method("delete").headers(ACCEPT).headers(NODEAUTH).asString
    info("DELETE "+agreementId+", code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  /** Add an agreement for node 9900 for netspeed */
  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/"+agreementId+" - netspeed") {
    val input = PutNodeAgreementRequest(List[NAMicroservice](NAMicroservice(orgid,NETSPEEDSPEC)), NAWorkload(orgid,patid,NETSPEEDSPEC), "signed")
    val response = Http(URL+"/nodes/"+nodeId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Should not find nodeId for a different reason now. */
  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+NETSPEEDSPEC+" - with "+nodeId+" in agreement") {
    patchNodePattern(compositePatid)      // put pattern back in nodes so we can search for pattern nodes
    val input = PostPatternSearchRequest(NETSPEEDSPEC, 86400, 0, 0)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 3)
    assert(nodes.count(d => d.id==orgnodeId2 || d.id==orgnodeId3 || d.id==orgnodeId4) === 3)
  }

  /* But now we should find all nodes when searching for SDRSPEC */
  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - should find all nodes again") {
    val input = PostPatternSearchRequest(SDRSPEC, 86400, 0, 0)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 4)
    assert(nodes.count(d => d.id==orgnodeId || d.id==orgnodeId2 || d.id==orgnodeId3 || d.id==orgnodeId4) === 4)
    val dev = nodes.find(d => d.id == orgnodeId).get // the 2nd get turns the Some(val) into val
    assert(dev.publicKey === "NODEABC")
  }

  /** Make sure we do not find the netspeed MS on 9900 now */
  test("POST /orgs/"+orgid+"/search/nodes - netspeed arch arm, "+nodeId+" netspeed in agreement - as agbot") {
    patchNodePattern("")      // remove pattern from nodes so we can search for microservices
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(NETSPEEDSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","[1.0.0,2.0.0]","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 0)
  }

  /** We should still find the sdr MS on 9900, even though the netspeed MS on 9900 is in agreement */
  test("POST /orgs/"+orgid+"/search/nodes - sdr arch arm, "+nodeId+" netspeed in agreement - as agbot") {
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","[1.0.0,2.0.0]","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    // info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 2 || nodes.length === 3)      // UsersSuite creates 1 too
    assert(nodes.count(d => d.id==orgnodeId) === 1)
  }

  //todo: add tests for searching for multiple MS URLs in 1 call

  //~~~~~ Staleness tests ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("POST /orgs/"+orgid+"/patterns/"+patid+"/search - for "+SDRSPEC+" - all nodes stale") {
    patchNodePattern(compositePatid)      // put pattern back in nodes so we can search for pattern nodes
    Thread.sleep(1100)    // delay 1.1 seconds so all nodes will be stale
    val input = PostPatternSearchRequest(SDRSPEC, 1, 0, 0)
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

    val input = PostNodeHealthRequest(nodeHealthLastTime)
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
    val input = PostPatternSearchRequest(SDRSPEC, 1, 0, 0)
    val response = Http(URL+"/patterns/"+patid+"/search").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    //info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostPatternSearchResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 1)
    assert(nodes.count(d => d.id==orgnodeId) === 1)
  }



  test("POST /orgs/"+orgid+"/search/nodes - all arm nodes, but all stale") {
    patchNodePattern("")      // remove pattern from nodes so we can search for microservices
    Thread.sleep(1100)    // delay 1.1 seconds so all nodes will be stale
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","2","int",">="),
      Prop("version","*","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      1, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 0)
}

  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/heartbeat - so this node won't be stale for non-pattern search") {
    val response = Http(URL+"/nodes/"+nodeId+"/heartbeat").method("post").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val devResp = parse(response.body).extract[ApiResponse]
    assert(devResp.code === ApiResponseType.OK)
  }

  test("POST /orgs/"+orgid+"/search/nodes - all arm nodes, 1 not stale") {
    val secondsNotStale = 1
    info("secondsNotStale: "+secondsNotStale)
    val input = PostSearchNodesRequest(List(RegMicroserviceSearch(SDRSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","2","int",">="),
      Prop("version","*","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      secondsNotStale, List[String](""), 0, 0)
    val response = Http(URL+"/search/nodes").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    //info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchNodesResponse]
    val nodes = postSearchDevResp.nodes
    assert(nodes.length === 1)
    assert(nodes.count(d => d.id==orgnodeId) === 1)
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

  /** Try to add a 3rd agreement, when max agreements is below that. */
  test("PUT /orgs/"+orgid+"/nodes/"+nodeId+"/agreements/9952 - with low maxAgreements") {
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
      val input = PutNodeAgreementRequest(List[NAMicroservice](NAMicroservice(orgid,"netspeed")), NAWorkload(orgid,patid,"netspeed"), "signed")
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

  /** Delete all agreements for node 9900 */
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

  /** Try to add node3, when max nodes is below that. */
  test("PUT /orgs/"+orgid+"/nodes/9904 - with low maxNodes") {
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
      val input = PutNodesRequest("mytok", "rpi9904-netspeed", compositePatid, List(RegMicroservice(NETSPEEDSPEC,1,"{json policy for 9904 netspeed}",List(
        Prop("arch","arm","string","in"),
        Prop("version","1.0.0","version","in"),
        Prop("agreementProtocols",agProto,"list","in")))), "whisper-id", Map(), "NODE4ABC")
      response = Http(URL+"/nodes/9904").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
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

  /** Add a 2nd agbot so we can test msgs */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId2) {
    val input = PutAgbotsRequest(agbotToken2, agbotId2+"name", /*List[APattern](),*/ "whisper-id", "AGBOT2ABC")
    val response = Http(URL+"/agbots/"+agbotId2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Send a msg from agbot1 to node1 */
  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/msgs") {
    val input = PostNodesMsgsRequest("{msg1 from agbot1 to node1}", 300)
    val response = Http(URL+"/nodes/"+nodeId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  /** Send a msg from agbot1 to node1 with a very short ttl so it will expire */
  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/msgs - short ttl") {
    val input = PostNodesMsgsRequest("{msg1 from agbot1 to node1 with 1 second ttl}", 1)
    val response = Http(URL+"/nodes/"+nodeId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  /** Send a 2nd msg from agbot1 to node1 */
  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/msgs - 2nd msg") {
    val input = PostNodesMsgsRequest("{msg2 from agbot1 to node1}", 300)
    val response = Http(URL+"/nodes/"+nodeId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  /** Send a msg from agbot2 to node1 */
  test("POST /orgs/"+orgid+"/nodes/"+nodeId+"/msgs - from agbot2") {
    val input = PostNodesMsgsRequest("{msg1 from agbot2 to node1}", 300)
    val response = Http(URL+"/nodes/"+nodeId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  /** Send a msg from agbot2 to node2 */
  test("POST /orgs/"+orgid+"/nodes/"+nodeId2+"/msgs - from agbot2 to node2") {
    val input = PostNodesMsgsRequest("{msg1 from agbot2 to node2}", 300)
    val response = Http(URL+"/nodes/"+nodeId2+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  /** Get msgs for node1 */
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

  /** Get msgs for node2, delete the msg, and get again to verify */
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


  /** Send a msg from node1 to agbot1 */
  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/msgs") {
    val input = PostAgbotsMsgsRequest("{msg1 from node1 to agbot1}", 300)
    val response = Http(URL+"/agbots/"+agbotId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  /** Send a msg from node1 to agbot1 with a very short ttl so it will expire */
  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/msgs - short ttl") {
    val input = PostAgbotsMsgsRequest("{msg1 from node1 to agbot1 with 1 second ttl}", 1)
    val response = Http(URL+"/agbots/"+agbotId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  /** Send a 2nd msg from node1 to agbot1 */
  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/msgs - 2nd msg") {
    val input = PostAgbotsMsgsRequest("{msg2 from node1 to agbot1}", 300)
    val response = Http(URL+"/agbots/"+agbotId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  /** Send a msg from node2 to agbot1 */
  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/msgs - from node2") {
    val input = PostAgbotsMsgsRequest("{msg1 from node2 to agbot1}", 300)
    val response = Http(URL+"/agbots/"+agbotId+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODE2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  /** Send a msg from node2 to agbot2 */
  test("POST /orgs/"+orgid+"/agbots/"+agbotId2+"/msgs - from node2 to agbot2") {
    val input = PostAgbotsMsgsRequest("{msg1 from node2 to agbot2}", 300)
    val response = Http(URL+"/agbots/"+agbotId2+"/msgs").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODE2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val resp = parse(response.body).extract[ApiResponse]
    assert(resp.code === ApiResponseType.OK)
  }

  /** Get msgs for agbot1 */
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
    assert(msg.nodePubKey === "NODEABC")

    msg = resp.messages.find(m => m.message=="{msg2 from node1 to agbot1}").orNull
    assert(msg !== null)
    assert(msg.nodeId === orgnodeId)
    assert(msg.nodePubKey === "NODEABC")

    msg = resp.messages.find(m => m.message=="{msg1 from node2 to agbot1}").orNull
    assert(msg !== null)
    assert(msg.nodeId === orgnodeId2)
    assert(msg.nodePubKey === "NODE2ABC")
  }

  /** Get msgs for agbot2, delete the msg, and get again to verify */
  test("GET /orgs/"+orgid+"/agbots/"+agbotId2+"/msgs - then delete and get again") {
    var response = Http(URL+"/agbots/"+agbotId2+"/msgs").method("get").headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val resp = parse(response.body).extract[GetAgbotMsgsResponse]
    assert(resp.messages.size === 1)
    val msg = resp.messages.find(m => m.message == "{msg1 from node2 to agbot2}").orNull
    assert(msg !== null)
    assert(msg.nodeId === orgnodeId2)
    assert(msg.nodePubKey === "NODE2ABC")
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

  /** Try to add a 4th msg to agbot1, when max msgs is below that. */
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

  /** Try to add a 4th msg to node1, when max msgs is below that. */
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

  /** Clean up, delete all the test nodes */
  test("Cleanup - DELETE everything and confirm they are gone") {
    deleteAllUsers()

    /*
    val response: HttpResponse[String] = Http(URL+"/nodes").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetNodesResponse]
    assert(getDevResp.nodes.size === numExistingNodes)
    */
  }

  /** Delete the org we used for this test */
  test("POST /orgs/"+orgid+" - delete org") {
    // Try deleting it 1st, in case it is left over from previous test
    val response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }
}
