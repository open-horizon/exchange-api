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
import scalaj.http._

import scala.collection.immutable._

/**
 * Tests for the /resources routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class ResourcesSuite extends FunSuite {

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val ACCEPTTEXT = ("Accept","text/plain")
  val CONTENT = ("Content-Type","application/json")
  val CONTENTTEXT = ("Content-Type","text/plain")
  val orgid = "ResourcesSuiteTests"
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
  val nodeId = "9912"     // the 1st node created, that i will use to run some rest methods
  val nodeToken = nodeId+"tok"
  val NODEAUTH = ("Authorization","Basic "+authpref+nodeId+":"+nodeToken)
  val agbotId = "9947"
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+authpref+agbotId+":"+agbotToken)
  val resName = "res9920"
  val resDoc = "http://doc-" + resName
  val resUrl = "http://" + resName
  val resVersion = "1.0.0"
  val resArch = "arm"
  val resource = resName + "_" + resVersion
  val orgresource = authpref+resource
  val resName2 = "res9921"
  val resUrl2 = "http://" + resName2
  val resVersion2 = "2.0.0"
  val resource2 = resName2 + "_" + resVersion2
  val orgresource2 = authpref+resource2
  val keyId = "mykey.pem"
  val key = "abcdefghijk"
  val keyId2 = "mykey2.pem"
  val key2 = "lnmopqrstuvwxyz"
  val authUsername = "iamapikey"
  val authToken = "tok1"
  val authUsername2 = "token"
  val authToken2 = "tok2"

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

    val input = PostPutOrgRequest("My Org", "desc", None)
    response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the resources, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllUsers()
  }

  /** Add users, node, agbot, resources for future tests */
  test("Add users, resource for future tests") {
    var userInput = PostPutUsersRequest(pw, admin = false, user+"@hotmail.com")
    var userResponse = Http(URL+"/users/"+user).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+userResponse.code+", userResponse.body: "+userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)

    userInput = PostPutUsersRequest(pw2, admin = false, user2+"@hotmail.com")
    userResponse = Http(URL+"/users/"+user2).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+userResponse.code+", userResponse.body: "+userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)

    val devInput = PutNodesRequest(nodeToken, "bc dev test", "", None, "", Map(), "")
    val devResponse = Http(URL+"/nodes/"+nodeId).postData(write(devInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+devResponse.code)
    assert(devResponse.code === HttpCode.PUT_OK)

    val agbotInput = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-norm", /*List[APattern](),*/ "whisper-id", "ABC")
    val agbotResponse = Http(URL+"/agbots/"+agbotId).postData(write(agbotInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+agbotResponse.code+", agbotResponse.body: "+agbotResponse.body)
    assert(agbotResponse.code === HttpCode.PUT_OK)
  }

  test("PUT /orgs/"+orgid+"/resources/"+resource+" - update resource that is not there yet - should fail") {
    val input = PostPutResourceRequest(resName, None, public = false, None, resVersion, Some(resArch), Map("url" -> resUrl))
    val response = Http(URL+"/resources/"+resource).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("POST /orgs/"+orgid+"/resources - add "+resource) {
    val input = PostPutResourceRequest(resName, None, public = false, None, resVersion, Some(resArch), Map("url" -> resUrl))
    val response = Http(URL+"/resources").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("resource '"+orgresource+"' created"))
  }

  test("POST /orgs/"+orgid+"/resources - add "+resource+" again - should fail") {
    val input = PostPutResourceRequest(resName, None, public = false, None, resVersion, Some(resArch), Map("url" -> resUrl))
    val response = Http(URL+"/resources").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ALREADY_EXISTS)
  }

  test("PUT /orgs/"+orgid+"/resources/"+resource+" - update changing version - should fail") {
    val input = PostPutResourceRequest(resName, None, public = false, None, "1.2.3", Some(resArch), Map("url" -> resUrl))
    val response = Http(URL+"/resources/"+resource).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PUT /orgs/"+orgid+"/resources/"+resource+" - update as 2nd user - should fail") {
    val input = PostPutResourceRequest(resName, None, public = false, None, resVersion, Some(resArch), Map("url" -> resUrl))
    val response = Http(URL+"/resources/"+resource).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/resources/"+resource+" - update as agbot - should fail") {
    val input = PostPutResourceRequest(resName, None, public = false, None, resVersion, Some(resArch), Map("url" -> resUrl))
    val response = Http(URL+"/resources/"+resource).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /orgs/"+orgid+"/resources/"+resource2+" - invalid resource body") {
    val badJsonInput = """{
      "labelxx": "GPS x86_64"
    }"""
    val response = Http(URL+"/resources/"+resource2).postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/"+orgid+"/resources - add "+resource2+" as node - should fail") {
    val input = PostPutResourceRequest(resName2, None, public = true, None, resVersion2, None, Map("url" -> resUrl2))
    val response = Http(URL+"/resources").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("POST /orgs/"+orgid+"/resources - add "+resource2+" as 2nd user") {
    val input = PostPutResourceRequest(resName2, None, public = true, None, resVersion2, None, Map("url" -> resUrl2))
    val response = Http(URL+"/resources").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("GET /orgs/"+orgid+"/resources") {
    val response: HttpResponse[String] = Http(URL+"/resources").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    //info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetResourcesResponse]
    assert(respObj.resources.size === 2)

    assert(respObj.resources.contains(orgresource))
    var wk = respObj.resources(orgresource)     // the 2nd get turns the Some(val) into val
    assert(wk.name === resName)
    assert(wk.owner === orguser)

    assert(respObj.resources.contains(orgresource2))
    wk = respObj.resources(orgresource2)     // the 2nd get turns the Some(val) into val
    assert(wk.name === resName2)
    assert(wk.owner === orguser2)
  }

  test("GET /orgs/"+orgid+"/resources - filter owner and name") {
    val response: HttpResponse[String] = Http(URL+"/resources").headers(ACCEPT).headers(USERAUTH).param("owner",orguser2).param("name",resName2).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetResourcesResponse]
    assert(respObj.resources.size === 1)
    assert(respObj.resources.contains(orgresource2))
  }

  test("GET /orgs/"+orgid+"/resources - filter by public setting") {
    // Find the public==true resources
    var response: HttpResponse[String] = Http(URL+"/resources").headers(ACCEPT).headers(USERAUTH).param("public","true").asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    var respObj = parse(response.body).extract[GetResourcesResponse]
    assert(respObj.resources.size === 1)
    assert(respObj.resources.contains(orgresource2))

    // Find the public==false resources
    response = Http(URL+"/resources").headers(ACCEPT).headers(USERAUTH).param("public","false").asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    respObj = parse(response.body).extract[GetResourcesResponse]
    assert(respObj.resources.size === 1)
    assert(respObj.resources.contains(orgresource))
  }

  test("GET /orgs/"+orgid+"/resources - as node") {
    val response: HttpResponse[String] = Http(URL+"/resources").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetResourcesResponse]
    assert(respObj.resources.size === 2)
  }

  test("GET /orgs/"+orgid+"/resources - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/resources").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetResourcesResponse]
    assert(respObj.resources.size === 2)
  }

  test("GET /orgs/"+orgid+"/resources/"+resource+" - as user") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetResourcesResponse]
    assert(respObj.resources.size === 1)

    assert(respObj.resources.contains(orgresource))
    val wk = respObj.resources(orgresource)     // the 2nd get turns the Some(val) into val
    assert(wk.name === resName)

    // Verify the lastUpdated from the PUT above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastUp = ZonedDateTime.parse(wk.lastUpdated).toEpochSecond
    assert(now - lastUp <= 5)    // should not be more than 3 seconds from the time the put was done above
  }

  test("PATCH /orgs/"+orgid+"/resources/"+resource+" - change name - should fail") {
    val jsonInput = """{ "name": "foobar" }"""
    val response = Http(URL+"/resources/"+resource).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PATCH /orgs/"+orgid+"/resources/"+resource+" - as user") {
    val jsonInput = """{ "documentation": "https://mysite.com/mymodel" }"""
    val response = Http(URL+"/resources/"+resource).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /orgs/"+orgid+"/resources/"+resource+" - as user2 - should fail") {
    val jsonInput = """{ "documentation": "https://mysite.com/mymodel" }"""
    val response = Http(URL+"/resources/"+resource).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("GET /orgs/"+orgid+"/resources/"+resource+" - as agbot, check patch by getting that 1 attr") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource).headers(ACCEPT).headers(AGBOTAUTH).param("attribute","documentation").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetResourceAttributeResponse]
    assert(respObj.attribute === "documentation")
    assert(respObj.value === "https://mysite.com/mymodel")
  }

  test("GET /orgs/"+orgid+"/resources/"+resource+"notthere - as user - should fail") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource+"notthere").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    //val getResourceResp = parse(response.body).extract[GetResourcesResponse]
    //assert(getResourceResp.resources.size === 0)
  }


  // Key tests ==============================================
  test("GET /orgs/"+orgid+"/resources/"+resource+"/keys - no keys have been created yet - should fail") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource+"/keys").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val resp = parse(response.body).extract[List[String]]
    assert(resp.size === 0)
  }

  test("PUT /orgs/"+orgid+"/resources/"+resource+"/keys/"+keyId+" - add "+keyId+" as user") {
    //val input = PutResourceKeyRequest(key)
    val response = Http(URL+"/resources/"+resource+"/keys/"+keyId).postData(key).method("put").headers(CONTENTTEXT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/resources/"+resource+"/keys/"+keyId2+" - add "+keyId2+" as user") {
    //val input = PutResourceKeyRequest(key2)
    val response = Http(URL+"/resources/"+resource+"/keys/"+keyId2).postData(key2).method("put").headers(CONTENTTEXT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("GET /orgs/"+orgid+"/resources/"+resource+"/keys - should be 2 now") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource+"/keys").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val resp = parse(response.body).extract[List[String]]
    assert(resp.size === 2)
    assert(resp.contains(keyId) && resp.contains(keyId2))
  }

  test("GET /orgs/"+orgid+"/resources/"+resource+"/keys/"+keyId+" - get 1 of the keys and check content") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource+"/keys/"+keyId).headers(ACCEPTTEXT).headers(NODEAUTH).asString
    //info("code: "+response.code+", response.body: "+bodyStr)
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    assert(response.body === key)
  }

  test("DELETE /orgs/"+orgid+"/resources/"+resource+"/keys/"+keyId) {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource+"/keys/"+keyId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED)
  }

  test("DELETE /orgs/"+orgid+"/resources/"+resource+"/keys/"+keyId+" try deleting it again - should fail") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource+"/keys/"+keyId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("GET /orgs/"+orgid+"/resources/"+resource+"/keys/"+keyId+" - verify it is gone") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource+"/keys/"+keyId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("DELETE /orgs/"+orgid+"/resources/"+resource+"/keys - delete all keys") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource+"/keys").method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/resources/"+resource+"/keys - all keys should be gone now") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource+"/keys").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val resp = parse(response.body).extract[List[String]]
    assert(resp.size === 0)
  }


  // Auth tests ==============================================
  test("GET /orgs/"+orgid+"/resources/"+resource+"/auths - no auths have been created yet - should fail") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource+"/auths").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val resp = parse(response.body).extract[List[ResourceAuth]]
    assert(resp.size === 0)
  }

  test("PUT /orgs/"+orgid+"/resources/"+resource+"/auths/1 - try to update before any exist - should fail") {
    val input = PostPutResourceAuthRequest(None, authToken+"-updated")
    val response = Http(URL+"/resources/"+resource+"/auths/1").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("POST /orgs/"+orgid+"/resources/"+resource+"/auths - add a auth as user") {
    val input = PostPutResourceAuthRequest(Some(authUsername), authToken)
    val response = Http(URL+"/resources/"+resource+"/auths").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/resources/"+resource+"/auths - add another auth as user") {
    val input = PostPutResourceAuthRequest(None, authToken2)    // username will default to "token"
    val response = Http(URL+"/resources/"+resource+"/auths").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("POST /orgs/"+orgid+"/resources/"+resource+"/auths - add a duplicate, it should just update the existing") {
    val input = PostPutResourceAuthRequest(None, authToken2)
    val response = Http(URL+"/resources/"+resource+"/auths").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("GET all the auths, PUT one, GET one, DELETE one, and verify") {
    // We have to do this all together and get the id's from the GETs, because they are auto-generated
    info("GET /orgs/"+orgid+"/resources/"+resource+"/auths - as node, should be 2 now")
    var response: HttpResponse[String] = Http(URL+"/resources/"+resource+"/auths").headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val resp = parse(response.body).extract[List[ResourceAuth]]
    assert(resp.size === 2)
    var auth = resp.find(d => d.username == authUsername).orNull
    assert(auth != null)
    val authId = auth.authId
    assert(auth.username === authUsername)
    assert(auth.token === authToken)
    auth = resp.find(d => d.username == "token").orNull
    assert(auth != null)
    //val authId2 = auth.authId  // do not need this
    assert(auth.token === authToken2)

    info("PUT /orgs/"+orgid+"/resources/"+resource+"/auths/"+authId+" - update "+authId+" as user")
    val input = PostPutResourceAuthRequest(Some(authUsername+"-updated"), authToken+"-updated")
    response = Http(URL+"/resources/"+resource+"/auths/"+authId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)

    info("GET /orgs/"+orgid+"/resources/"+resource+"/auths/"+authId+" - and check content")
    response = Http(URL+"/resources/"+resource+"/auths/"+authId).headers(ACCEPT).headers(NODEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    auth = parse(response.body).extract[ResourceAuth]
    assert(auth.authId === authId)
    assert(auth.username === authUsername+"-updated")
    assert(auth.token === authToken+"-updated")

    info("DELETE /orgs/"+orgid+"/resources/"+resource+"/auths/"+authId)
    response = Http(URL+"/resources/"+resource+"/auths/"+authId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED)

    info("DELETE /orgs/"+orgid+"/resources/"+resource+"/auths/"+authId+" try deleting it again - should fail")
    response = Http(URL+"/resources/"+resource+"/auths/"+authId).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)

    info("GET /orgs/"+orgid+"/resources/"+resource+"/auths/"+authId+" - verify it is gone")
    response = Http(URL+"/resources/"+resource+"/auths/"+authId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("DELETE /orgs/"+orgid+"/resources/"+resource+"/auths - delete all keys") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource+"/auths").method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/resources/"+resource+"/auths - all auths should be gone now") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource+"/auths").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.NOT_FOUND)
    val resp = parse(response.body).extract[List[ResourceAuth]]
    assert(resp.size === 0)
  }


  // Start shutting down ==============================================
  test("DELETE /orgs/"+orgid+"/resources/"+resource) {
    val response = Http(URL+"/resources/"+resource).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/resources/"+resource+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    //val getResourceResp = parse(response.body).extract[GetResourcesResponse]
    //assert(getResourceResp.resources.size === 0)
  }

  test("DELETE /orgs/"+orgid+"/users/"+user2+" - which should also delete resource2") {
    val response = Http(URL+"/users/"+user2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /orgs/"+orgid+"/resources/"+resource2+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/resources/"+resource2).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    //val getResourceResp = parse(response.body).extract[GetResourcesResponse]
    //assert(getResourceResp.resources.size === 0)
  }

  /** Clean up, delete all the test resources */
  test("Cleanup - DELETE all test resources") {
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