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
 * Tests for the /bctypes routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class BlockchainsSuite extends FunSuite {

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val orgid = "BlockchainsSuiteTests"
  val authpref=orgid+"/"
  val URL = urlRoot+"/v1/orgs/"+orgid
  val NOORGURL = urlRoot+"/v1"
  val user = "9995"
  val orguser = authpref+user
  val pw = user+"pw"
  val USERAUTH = ("Authorization","Basic "+orguser+":"+pw)
  val user2 = "9996"
  val orguser2 = authpref+user2
  val pw2 = user2+"pw"
  val USER2AUTH = ("Authorization","Basic "+orguser2+":"+pw2)
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+rootuser+":"+rootpw)
  val nodeId = "9910"     // the 1st node created, that i will use to run some rest methods
  val orgnodeId = authpref+nodeId
  val nodeToken = nodeId+"tok"
  val NODEAUTH = ("Authorization","Basic "+orgnodeId+":"+nodeToken)
  val agbotId = "9945"
  val orgagbotId = authpref+agbotId
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+orgagbotId+":"+agbotToken)
  val bctype = "9920"
  val orgbctype = authpref+bctype
  val bctype2 = "9921"
  val orgbctype2 = authpref+bctype2
  val bctype3 = "9922"
  val bctype4 = "9923"
  val bcname = "9925"
  val bcname2 = "9926"
  val bcname3 = "9927"
  //var numExistingBctypes = 0    // this will be set later

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers() = {
    for (i <- List(user,user2)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
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

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the bctypes and
   *  blockchains, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    deleteAllUsers()
  }

  /** Add users, node, bctype for future tests */
  test("Add users, node, bctype for future tests") {
    var userInput = PostPutUsersRequest(pw, false, user+"@hotmail.com")
    var userResponse = Http(URL+"/users/"+user).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+userResponse.code+", userResponse.body: "+userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)

    userInput = PostPutUsersRequest(pw2, false, user2+"@hotmail.com")
    userResponse = Http(URL+"/users/"+user2).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+userResponse.code+", userResponse.body: "+userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)

    val devInput = PutNodesRequest(nodeToken, "bc dev test", "myorg/mypat", List(RegMicroservice("foo",1,"{}",List(
      Prop("arch","arm","string","in"),
      Prop("version","2.0.0","version","in"),
      Prop("blockchainProtocols","agProto","list","in")))), "whisper-id", Map(), "NODEABC")
    val devResponse = Http(URL+"/nodes/"+nodeId).postData(write(devInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+devResponse.code)
    assert(devResponse.code === HttpCode.PUT_OK)

    val agbotInput = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-norm", List[APattern](), "whisper-id", "ABC")
    val agbotResponse = Http(URL+"/agbots/"+agbotId).postData(write(agbotInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+agbotResponse.code+", agbotResponse.body: "+agbotResponse.body)
    assert(agbotResponse.code === HttpCode.PUT_OK)
  }

  /*
  test("GET /orgs/"+orgid+"/bctypes - get initial number of bctypes in the db") {
    val response: HttpResponse[String] = Http(URL+"/bctypes").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getBctypeResp = parse(response.body).extract[GetBctypesResponse]
    numExistingBctypes = getBctypeResp.bctypes.size
    info("initially "+numExistingBctypes+" bctypes")
  }
  */

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype+" - add as user") {
    val input = PutBctypeRequest(bctype+" desc", "json escaped string")
    val response = Http(URL+"/bctypes/"+bctype).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype+" - update as same user") {
    val input = PutBctypeRequest(bctype+" new desc", "json escaped string")
    val response = Http(URL+"/bctypes/"+bctype).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype+" - update as 2nd user - should fail") {
    val input = PutBctypeRequest(bctype+" yet another desc", "json escaped string")
    val response = Http(URL+"/bctypes/"+bctype).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype2+" - invalid bctype body") {
    val badJsonInput = """{
      "description": "foo"
    }"""
    val response = Http(URL+"/bctypes/"+bctype2).postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)     // for now this is what is returned when the json-to-scala conversion fails
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype2+" - as node - should fail") {
    val input = PutBctypeRequest(bctype2+" desc", "json escaped string")
    val response = Http(URL+"/bctypes/"+bctype2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype2+" - as agbot - should fail") {
    val input = PutBctypeRequest(bctype2+" desc", "json escaped string")
    val response = Http(URL+"/bctypes/"+bctype2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype2+" - add bctype2 as 2nd user") {
    val input = PutBctypeRequest(bctype2+" desc", "json escaped string")
    val response = Http(URL+"/bctypes/"+bctype2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype3+" - with low maxBlockchains - should fail") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      ExchConfig.load()
      val origMaxBlockchains = ExchConfig.getInt("api.limits.maxBlockchains")

      // Change the maxBlockchains config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxBlockchains", "1")    // user only owns 1 currently
      var response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // Add 1 bctype - should succeed
      var input = PutBctypeRequest(bctype3+" desc", "json escaped string")
      response = Http(URL+"/bctypes/"+bctype3).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // Now try adding another bctype - expect it to be rejected
      input = PutBctypeRequest(bctype4+" desc", "json escaped string")
      response = Http(URL+"/bctypes/"+bctype4).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED)
      val respObj = parse(response.body).extract[ApiResponse]
      assert(respObj.msg.contains("Access Denied"))

      // Delete the one that succeeded
      response = Http(URL+"/bctypes/"+bctype3).method("delete").headers(ACCEPT).headers(USERAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED)

      // Restore the maxBlockchains config value in the svr
      configInput = AdminConfigRequest("api.limits.maxBlockchains", origMaxBlockchains.toString)
      response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)
    }
  }

  test("GET /orgs/"+orgid+"/bctypes") {
    val response: HttpResponse[String] = Http(URL+"/bctypes").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getBctypeResp = parse(response.body).extract[GetBctypesResponse]
    assert(getBctypeResp.bctypes.size === 2)

    assert(getBctypeResp.bctypes.contains(orgbctype))
    var bt = getBctypeResp.bctypes.get(orgbctype).get     // the 2nd get turns the Some(val) into val
    assert(bt.description === bctype+" new desc")
    assert(bt.definedBy === orguser)

    assert(getBctypeResp.bctypes.contains(orgbctype2))
    bt = getBctypeResp.bctypes.get(orgbctype2).get     // the 2nd get turns the Some(val) into val
    assert(bt.description === bctype2+" desc")
    assert(bt.definedBy === orguser2)
  }

  test("GET /orgs/"+orgid+"/bctypes - filter owner and description") {
    val response: HttpResponse[String] = Http(URL+"/bctypes").headers(ACCEPT).headers(USERAUTH).param("owner",orguser2).param("description",bctype2+"%").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getBctypeResp = parse(response.body).extract[GetBctypesResponse]
    assert(getBctypeResp.bctypes.size === 1)
    assert(getBctypeResp.bctypes.contains(orgbctype2))
  }

  test("GET /orgs/"+orgid+"/bctypes - as node") {
    val response: HttpResponse[String] = Http(URL+"/bctypes").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getBctypeResp = parse(response.body).extract[GetBctypesResponse]
    assert(getBctypeResp.bctypes.size === 2)
  }

  test("GET /orgs/"+orgid+"/bctypes - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/bctypes").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getBctypeResp = parse(response.body).extract[GetBctypesResponse]
    assert(getBctypeResp.bctypes.size === 2)
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+" - as user") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getBctypeResp = parse(response.body).extract[GetBctypesResponse]
    assert(getBctypeResp.bctypes.size === 1)

    assert(getBctypeResp.bctypes.contains(orgbctype))
    val bt = getBctypeResp.bctypes.get(orgbctype).get     // the 2nd get turns the Some(val) into val
    assert(bt.description === bctype+" new desc")

    // Verify the lastHeartbeat from the POST heartbeat above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastUp = ZonedDateTime.parse(bt.lastUpdated).toEpochSecond
    assert(now - lastUp <= 3)    // should not now be more than 3 seconds from the time the heartbeat was done above
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+" - as node") {       // will do agbot soon
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getBctypeResp = parse(response.body).extract[GetBctypesResponse]
    assert(getBctypeResp.bctypes.size === 1)
  }

  test("PATCH /orgs/"+orgid+"/bctypes/"+bctype+" - as user") {
    val newDesc = "\""+bctype+" patched desc\""
    val jsonInput = """{
      "description": """+newDesc+"""
    }"""
    val response = Http(URL+"/bctypes/"+bctype).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid+"/bctypes/"+bctype+" - as user2 - should fail") {
    val newDesc = "\""+bctype+" patched2 desc\""
    val jsonInput = """{
      "description": """+newDesc+"""
    }"""
    val response = Http(URL+"/bctypes/"+bctype).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+" - as agbot, check patch by getting that 1 attr") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype).headers(ACCEPT).headers(AGBOTAUTH).param("attribute","description").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getBctypeResp = parse(response.body).extract[GetBctypeAttributeResponse]
    assert(getBctypeResp.attribute === "description")
    assert(getBctypeResp.value === bctype+" patched desc")
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+"notthere - as user - should fail") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype+"notthere").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getBctypeResp = parse(response.body).extract[GetBctypesResponse]
    assert(getBctypeResp.bctypes.size === 0)
  }

  //======== Blockchain tests ==============

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains - none there yet") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype+"/blockchains").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val getBcResp = parse(response.body).extract[GetBlockchainsResponse]
    assert(getBcResp.blockchains.size === 0)
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - not there yet") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val getBcResp = parse(response.body).extract[GetBlockchainsResponse]
    assert(getBcResp.blockchains.size === 0)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - add as user") {
    val input = PutBlockchainRequest(bctype+"-"+bcname+" desc", true, "json escaped string")
    val response = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - update as same user") {
    val input = PutBlockchainRequest(bctype+"-"+bcname+" new desc", true, "json escaped string")
    val response = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - update as 2nd user - should fail") {
    val input = PutBlockchainRequest(bctype+"-"+bcname+" yet another desc", true, "json escaped string")
    val response = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname2+" - invalid blockchain body") {
    val badJsonInput = """{
      "descriptionx": "foo",
      "details": "json escaped string"
    }"""
    val response = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname2).postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname2+" - as node - should fail") {
    val input = PutBlockchainRequest(bctype+"-"+bcname2+" desc", true, "json escaped string")
    val response = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname2+" - as agbot - should fail") {
    val input = PutBlockchainRequest(bctype+"-"+bcname2+" desc", true, "json escaped string")
    val response = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname2+" - add bcname2 as user2") {
    val input = PutBlockchainRequest(bctype+"-"+bcname2+" desc", true, "json escaped string")
    val response = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype2+"/blockchains/"+bcname2+" - as user2 - and duplicate bcname should be ok") {
    val input = PutBlockchainRequest(bctype2+"-"+bcname2+" desc", true, "json escaped string")
    val response = Http(URL+"/bctypes/"+bctype2+"/blockchains/"+bcname2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname3+" - with low maxBlockchains - should fail") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      // ExchConfig.load  <-- done earlier
      val origMaxBlockchains = ExchConfig.getInt("api.limits.maxBlockchains")

      // Change the maxBlockchains config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxBlockchains", "1")    // user owns 2 currently
      var response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // Now try adding another blockchain - expect it to be rejected
      val input = PutBlockchainRequest(bctype+"-"+bcname3+" desc", true, "json escaped string")
      response = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname3).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED)
      val respObj = parse(response.body).extract[ApiResponse]
      assert(respObj.msg.contains("Access Denied"))

      // Restore the maxBlockchains config value in the svr
      configInput = AdminConfigRequest("api.limits.maxBlockchains", origMaxBlockchains.toString)
      response = Http(NOORGURL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)
    }
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype+"/blockchains").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getBcResp = parse(response.body).extract[GetBlockchainsResponse]
    assert(getBcResp.blockchains.size === 2)

    assert(getBcResp.blockchains.contains(bcname))
    var bc = getBcResp.blockchains.get(bcname).get     // the 2nd get turns the Some(val) into val
    assert(bc.description === bctype+"-"+bcname+" new desc")
    assert(bc.definedBy === orguser)

    assert(getBcResp.blockchains.contains(bcname2))
    bc = getBcResp.blockchains.get(bcname2).get     // the 2nd get turns the Some(val) into val
    assert(bc.description === bctype+"-"+bcname2+" desc")
    assert(bc.definedBy === orguser2)
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains - filter owner and description") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype+"/blockchains").headers(ACCEPT).headers(USERAUTH).param("owner",orguser2).param("description",bctype2+"%").asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getBcResp = parse(response.body).extract[GetBlockchainsResponse]
    assert(getBcResp.blockchains.size === 2)
    assert(getBcResp.blockchains.contains(bcname2))
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains - as node") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype+"/blockchains").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getBcResp = parse(response.body).extract[GetBlockchainsResponse]
    assert(getBcResp.blockchains.size === 2)
  }

  // GET /orgs/"+orgid+"/bctypes/{bctype}/blockchains - as agbot is done below

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - as user") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getBcResp = parse(response.body).extract[GetBlockchainsResponse]
    assert(getBcResp.blockchains.size === 1)

    assert(getBcResp.blockchains.contains(bcname))
    val bc = getBcResp.blockchains.get(bcname).get // the 2nd get turns the Some(val) into val
    assert(bc.description === bctype+"-"+bcname+" new desc")

    // Verify the lastHeartbeat from the POST heartbeat above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastUp = ZonedDateTime.parse(bc.lastUpdated).toEpochSecond
    assert(now - lastUp <= 3)    // should not now be more than 3 seconds from the time the heartbeat was done above
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - as node") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getBcResp = parse(response.body).extract[GetBlockchainsResponse]
    assert(getBcResp.blockchains.size === 1)
  }

  test("PATCH /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - as user") {
    val newDesc = "\""+bctype+"-"+bcname+" patched desc\""
    val jsonInput = """{
      "description": """+newDesc+"""
    }"""
    val response = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - as user2 - should fail") {
    val newDesc = "\""+bctype+"-"+bcname+" patched2 desc\""
    val jsonInput = """{
      "description": """+newDesc+"""
    }"""
    val response = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - as agbot, check patch by getting that 1 attr") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).headers(ACCEPT).headers(AGBOTAUTH).param("attribute","description").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getBcResp = parse(response.body).extract[GetBlockchainAttributeResponse]
    assert(getBcResp.attribute === "description")
    assert(getBcResp.value === bctype+"-"+bcname+" patched desc")
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+"notthere - as user - should fail") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname+"notthere").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getBcResp = parse(response.body).extract[GetBlockchainsResponse]
    assert(getBcResp.blockchains.size === 0)
  }

  test("DELETE /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - as node - should fail") {
    val response = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).method("delete").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("DELETE /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - as user2 - should fail") {
    val response = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).method("delete").headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - as agbot - verify still there") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getBcResp = parse(response.body).extract[GetBlockchainsResponse]
    assert(getBcResp.blockchains.size === 1)
  }

  test("DELETE /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - as user") {
    val response = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname+" - as node - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val getBcResp = parse(response.body).extract[GetBlockchainsResponse]
    assert(getBcResp.blockchains.size === 0)
  }

  test("DELETE /orgs/"+orgid+"/bctypes/"+bctype+" - which should also delete bcname2") {
    val response = Http(URL+"/bctypes/"+bctype).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+"/blockchains/"+bcname2+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype+"/blockchains/"+bcname2).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val getBcResp = parse(response.body).extract[GetBlockchainsResponse]
    assert(getBcResp.blockchains.size === 0)
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getBctypeResp = parse(response.body).extract[GetBctypesResponse]
    assert(getBctypeResp.bctypes.size === 0)
  }

  test("DELETE /orgs/"+orgid+"/users/"+user2+" - which should also delete bctype2 and bcname2") {
    val response = Http(URL+"/users/"+user2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype2+"/blockchains/"+bcname2+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype2+"/blockchains/"+bcname2).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val getBcResp = parse(response.body).extract[GetBlockchainsResponse]
    assert(getBcResp.blockchains.size === 0)
  }

  test("GET /orgs/"+orgid+"/bctypes/"+bctype2+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/bctypes/"+bctype2).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getBctypeResp = parse(response.body).extract[GetBctypesResponse]
    assert(getBctypeResp.bctypes.size === 0)
  }

  /** Clean up, delete all the test bctypes */
  test("Cleanup - DELETE all test bctypes and blockchains") {
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