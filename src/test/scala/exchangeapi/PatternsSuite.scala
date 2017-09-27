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

import scala.collection.immutable._
import scalaj.http._

/**
 * Tests for the /patterns routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class PatternsSuite extends FunSuite {

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val orgid = "PatternsSuiteTests"
  val authpref=orgid+"/"
  val URL = urlRoot+"/v1/orgs/"+orgid
  val user = "9999"
  val orguser = authpref+user
  val pw = user+"pw"
  val USERAUTH = ("Authorization","Basic "+orguser+":"+pw)
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
  val ptBase = "pt9920"
  //val ptUrl = "http://" + ptBase
  val pattern = ptBase
  val orgpattern = authpref+pattern
  val ptBase2 = "pt9921"
  //val ptUrl2 = "http://" + ptBase2
  val pattern2 = ptBase2
  val orgpattern2 = authpref+pattern2
  val ptBase3 = "pt9922"
  //val ptUrl3 = "http://" + ptBase3
  val pattern3 = ptBase3
  //var numExistingPatterns = 0    // this will be set later

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

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the patterns, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllUsers()
  }

  /** Add users, node, pattern for future tests */
  test("Add users, node, pattern for future tests") {
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

  test("PUT /orgs/"+orgid+"/patterns/"+pattern+" - update pattern that is not there yet - should fail") {
    val input = PostPutPatternRequest("Bad Pattern", "desc", false,
      List( PWorkloads("https://wkurl", "myorg", "", List(PWorkloadVersions("", "", "", Map("priority_value" -> 50), Map("lifecycle" -> "immediate"))), PDataVerification(false, "", "", "", 0, 0, Map[String,Any]()) )),
      List[Map[String,String]]()
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern+" - add "+pattern+" as user") {
    val input = PostPutPatternRequest(ptBase, "desc", false,
      List( PWorkloads("https://wkurl", "myorg", "", List(PWorkloadVersions("", "", "", Map("priority_value" -> 50), Map("lifecycle" -> "immediate"))), PDataVerification(false, "", "", "", 0, 0, Map[String,Any]()) )),
      List[Map[String,String]]()
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("pattern '"+orgpattern+"' created"))
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern+" - add "+pattern+" again - should fail") {
    val input = PostPutPatternRequest("Bad Pattern", "desc", false,
      List( PWorkloads("https://wkurl", "myorg", "", List(PWorkloadVersions("", "", "", Map("priority_value" -> 50), Map("lifecycle" -> "immediate"))), PDataVerification(false, "", "", "", 0, 0, Map[String,Any]()) )),
      List[Map[String,String]]()
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ALREADY_EXISTS)
  }

  test("PUT /orgs/"+orgid+"/patterns/"+pattern+" - update as same user") {
    val input = PostPutPatternRequest(ptBase+" amd64", "desc", false,
      List( PWorkloads("https://wkurl", "myorg", "", List(PWorkloadVersions("", "", "", Map("priority_value" -> 50), Map("lifecycle" -> "immediate"))), PDataVerification(false, "", "", "", 0, 0, Map[String,Any]()) )),
      List[Map[String,String]]()
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/patterns/"+pattern+" - update as 2nd user - should fail") {
    val input = PostPutPatternRequest("Bad Pattern", "desc", false,
      List( PWorkloads("https://wkurl", "myorg", "", List(PWorkloadVersions("", "", "", Map("priority_value" -> 50), Map("lifecycle" -> "immediate"))), PDataVerification(false, "", "", "", 0, 0, Map[String,Any]()) )),
      List[Map[String,String]]()
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/patterns/"+pattern+" - update as agbot - should fail") {
    val input = PostPutPatternRequest("Bad Pattern", "desc", false,
      List( PWorkloads("https://wkurl", "myorg", "", List(PWorkloadVersions("", "", "", Map("priority_value" -> 50), Map("lifecycle" -> "immediate"))), PDataVerification(false, "", "", "", 0, 0, Map[String,Any]()) )),
      List[Map[String,String]]()
    )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/patterns/"+pattern2+" - invalid pattern body") {
    val badJsonInput = """{
      "labelxx": "GPS x86_64"
    }"""
    val response = Http(URL+"/patterns/"+pattern2).postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern2+" - add "+pattern2+" as node - should fail") {
    val input = PostPutPatternRequest("Bad Pattern2", "desc", false,
      List( PWorkloads("https://wkurl", "myorg", "", List(PWorkloadVersions("", "", "", Map("priority_value" -> 50), Map("lifecycle" -> "immediate"))), PDataVerification(false, "", "", "", 0, 0, Map[String,Any]()) )),
      List[Map[String,String]]()
    )
    val response = Http(URL+"/patterns/"+pattern2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("POST /orgs/"+orgid+"/patterns/"+pattern2+" - add "+pattern2+" as 2nd user") {
    val input = PostPutPatternRequest(ptBase2+" amd64", "desc", false,
      List( PWorkloads("https://wkurl", "myorg", "", List(PWorkloadVersions("", "", "", Map("priority_value" -> 50), Map("lifecycle" -> "immediate"))), PDataVerification(false, "", "", "", 0, 0, Map[String,Any]()) )),
      List[Map[String,String]]()
    )
    val response = Http(URL+"/patterns/"+pattern2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /*todo: when all test suites are run at the same time, there are sometimes timing problems them all setting config values...
  test("POST /orgs/"+orgid+"/patterns - with low maxPatterns - should fail") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      ExchConfig.load()
      val origMaxPatterns = ExchConfig.getInt("api.limits.maxPatterns")

      // Change the maxPatterns config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxPatterns", "0")    // user only owns 1 currently
      var response = Http(URL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // Now try adding another pattern - expect it to be rejected
      val input = PostPutPatternRequest(wkBase3+" arm", "desc", wkUrl3, "1.0.0", "arm", "", List(Map("specRef" -> "https://msurl")), List(Map("name" -> "foo")), List(Map("deployment" -> "{\"services\":{}}")))
      response = Http(URL+"/patterns").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED)
      val respObj = parse(response.body).extract[ApiResponse]
      assert(respObj.msg.contains("Access Denied"))

      // Restore the maxPatterns config value in the svr
      configInput = AdminConfigRequest("api.limits.maxPatterns", origMaxPatterns.toString)
      response = Http(URL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)
    }
  }
  */

  test("GET /orgs/"+orgid+"/patterns") {
    val response: HttpResponse[String] = Http(URL+"/patterns").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 2)

    assert(respObj.patterns.contains(orgpattern))
    var pt = respObj.patterns.get(orgpattern).get     // the 2nd get turns the Some(val) into val
    assert(pt.label === ptBase+" amd64")
    assert(pt.owner === orguser)

    assert(respObj.patterns.contains(orgpattern2))
    pt = respObj.patterns.get(orgpattern2).get     // the 2nd get turns the Some(val) into val
    assert(pt.label === ptBase2+" amd64")
    assert(pt.owner === orguser2)
  }

  test("GET /orgs/"+orgid+"/patterns - filter owner and patternUrl") {
    val response: HttpResponse[String] = Http(URL+"/patterns").headers(ACCEPT).headers(USERAUTH).param("owner",authpref+"%").param("label",ptBase+"%").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 1)
    assert(respObj.patterns.contains(orgpattern))
  }

  test("GET /orgs/"+orgid+"/patterns - as node") {
    val response: HttpResponse[String] = Http(URL+"/patterns").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 2)
  }

  test("GET /orgs/"+orgid+"/patterns - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/patterns").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 2)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+" - as user") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 1)

    assert(respObj.patterns.contains(orgpattern))
    val pt = respObj.patterns.get(orgpattern).get     // the 2nd get turns the Some(val) into val
    assert(pt.label === ptBase+" amd64")

    // Verify the lastUpdated from the PUT above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastUp = ZonedDateTime.parse(pt.lastUpdated).toEpochSecond
    assert(now - lastUp <= 3)    // should not be more than 3 seconds from the time the put was done above
  }

  test("PATCH /orgs/"+orgid+"/patterns/"+pattern+" - as user") {
    val jsonInput = """{
      "description": "this is now patched"
    }"""
    val response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid+"/patterns/"+pattern+" - as user2 - should fail") {
    val jsonInput = """{
      "description": "bad patch"
    }"""
    val response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+" - as agbot, check patch by getting that 1 attr") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern).headers(ACCEPT).headers(AGBOTAUTH).param("attribute","description").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternAttributeResponse]
    assert(respObj.attribute === "description")
    assert(respObj.value === "this is now patched")
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+"notthere - as user - should fail") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern+"notthere").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getPatternResp = parse(response.body).extract[GetPatternsResponse]
    assert(getPatternResp.patterns.size === 0)
  }

  test("DELETE /orgs/"+orgid+"/patterns/"+pattern) {
    val response = Http(URL+"/patterns/"+pattern).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getPatternResp = parse(response.body).extract[GetPatternsResponse]
    assert(getPatternResp.patterns.size === 0)
  }

  test("DELETE /orgs/"+orgid+"/users/"+user2+" - which should also delete pattern2") {
    val response = Http(URL+"/users/"+user2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern2+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/patterns/"+pattern2).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getPatternResp = parse(response.body).extract[GetPatternsResponse]
    assert(getPatternResp.patterns.size === 0)
  }

  /** Clean up, delete all the test patterns */
  test("Cleanup - DELETE all test patterns") {
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