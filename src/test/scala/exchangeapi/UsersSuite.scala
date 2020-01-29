package exchangeapi

import org.scalatest.FunSuite
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scalaj.http._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import com.horizon.exchangeapi._
//import com.horizon.exchangeapi.tables.NodeHeartbeatIntervals
import com.horizon.exchangeapi.tables._
import scala.collection.immutable._

/**
 * Tests for the /orgs and /orgs/"+orgid+"/users routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class UsersSuite extends FunSuite {

  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080")
  val ACCEPT = ("Accept", "application/json")
  val CONTENT = ("Content-Type", "application/json")
  val orgid = "UsersSuiteTests"
  val orgid2 = "UsersSuiteTests2"
  val URL = urlRoot + "/v1/orgs/" + orgid
  val URL2 = urlRoot + "/v1/orgs/" + orgid2
  val cloudorg = sys.env.get("EXCHANGE_IAM_ACCOUNT_ID") match {
    case Some(_) => orgid   // we are allowed to use our org as a public cloud org
    case None => sys.env.getOrElse("ICP_CLUSTER_NAME", "")  // for icp we have to point to the real org
  }
  val CLOUDURL = urlRoot + "/v1/orgs/" + cloudorg
  val NOORGURL = urlRoot + "/v1"
  val user = "u1" // this is an admin user
  val orguser = orgid + "/" + user
  val org2user = orgid2 + "/" + user
  val pw = user + "pw"
  val creds = orguser + ":" + pw
  val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(creds))
  val ORG2USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(org2user + ":" + pw))
  //val encodedCreds = Base64.getEncoder.encodeToString(creds.getBytes("utf-8"))
  val BADUSERAUTH = ("Authorization", "Basic " + ApiUtils.encode(s"bad$orguser:$pw"))
  val USERAUTHBAD = ("Authorization", "Basic " + ApiUtils.encode(s"$orguser:${pw}bad"))
  val user2 = "u2" // this is NOT an admin user
  val orguser2 = orgid + "/" + user2
  val pw2 = user2 + " pw" // intentionally adding a space in the pw
  val creds2 = orguser2 + ":" + pw2
  val USERAUTH2 = ("Authorization", "Basic " + ApiUtils.encode(creds2))
  val pw2new = user2 + "pwnew"
  val creds2new = orguser2 + ":" + pw2new
  val USERAUTH2NEW = ("Authorization", "Basic " + ApiUtils.encode(creds2new))
  val user3 = "u3"
  val pw3 = user3 + "pw"
  val user4 = "u4" // this is NOT an admin user
  val orguser4 = orgid + "/" + user4
  val pw4 = user4 + " pw" // intentionally adding a space in the pw
  val creds4 = orguser4 + ":" + pw4
  val USERAUTH4 = ("Authorization", "Basic " + ApiUtils.encode(creds4))
  val pw4new = user4 + "pwnew"
  val creds4new = orguser4 + ":" + pw4new
  val USERAUTH4NEW = ("Authorization", "Basic " + ApiUtils.encode(creds4new))
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "") // need to put this same root pw in config.json
  val ROOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(rootuser + ":" + rootpw))
  val CONNTIMEOUT = HttpOptions.connTimeout(20000)
  val READTIMEOUT = HttpOptions.readTimeout(20000)
  val svcBase = "svc"
  val svcurl = "http://" + svcBase
  val svcarch = "arm"
  val svcversion = "1.0.0"
  val service = svcBase + "_" + svcversion + "_" + svcarch
  val ptBase = "pat"
  val ptUrl = "http://" + ptBase
  val pattern = ptBase + "_1.0.0_arm"
  val agbotId = "a1"
  val agbotToken = agbotId + "tok"
  val iamKey = sys.env.getOrElse("EXCHANGE_IAM_KEY", "")
  val iamUser = sys.env.getOrElse("EXCHANGE_IAM_EMAIL", "")
  val iamAccountId = sys.env.getOrElse("EXCHANGE_IAM_ACCOUNT_ID", "") // this indicates it is ibm cloud instead of ICP
  val iamOtherKey = sys.env.getOrElse("EXCHANGE_IAM_OTHER_KEY", "")
  val iamOtherAccountId = sys.env.getOrElse("EXCHANGE_IAM_OTHER_ACCOUNT_ID", "")
  val IAMAUTH = { org: String => ("Authorization", "Basic " + ApiUtils.encode(s"$org/iamapikey:$iamKey")) }
  val IAMOTHERAUTH = { org: String => ("Authorization", "Basic " + ApiUtils.encode(s"$org/iamapikey:$iamOtherKey")) }

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  //todo: figure out how to run https client requests and add those to all the test suites

  /** Delete the orgs we used for this test */
  def deleteAllOrgs() = {
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
  }

  /** Delete all the test users in both orgs */
  def deleteAllUsers() = {
    for (i <- List(user, user2)) { // we do not delete the root user because it was created by the config file, not this test suite
      val response = Http(URL + "/users/" + i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE " + i + ", code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    }
    for (i <- List(user)) {
      val response = Http(URL2 + "/users/" + i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE " + i + ", code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    }
  }

  /** Create an org to use for this test */
  test("POST /orgs/" + orgid + " - create org") {
    // Try deleting it 1st, in case it is left over from previous test
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllOrgs()

    // Try adding an invalid org body
    val badJsonInput = """{
      "labelx": "foo",
      "description": "desc"
    }"""
    var response = Http(URL).postData(badJsonInput).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.BAD_INPUT.intValue) // for now this is what is returned when the json-to-scala conversion fails

    // Now add a good org
    var input = PostPutOrgRequest(Some("IBM"), "My Org", "desc", Some(Map("tagName" -> "test")), None)
    response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    // Update the org
    input = PostPutOrgRequest(Some("IBM"), "My Org", "desc - updated", None, Some(NodeHeartbeatIntervals(5,15,2)))
    response = Http(URL).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
    assert(!response.body.contains("tags"))

    // Patch the org description
    val jsonInput = """{ "description": "desc - patched" }"""
    response = Http(URL).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)

    // Patch the org heartbeatIntervals
    val hbInput = """{ "heartbeatIntervals": { "minInterval": 6, "maxInterval": 15, "intervalAdjustment": 2 } }"""
    response = Http(URL).postData(hbInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)

    // Patch the org, updating a tag
    var tagInput = """{ "tags": {"tagName": "patchedTag"} }"""
    response = Http(URL).postData(tagInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)

    // Ensure tag is updated
    response = Http(NOORGURL + s"/orgs/$orgid").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.OK.intValue)
    var tagResponse = parse(response.body).extract[GetOrgsResponse]
    assert(tagResponse.orgs(orgid).tags.get("tagName") === "patchedTag")

    // Ensure tags work when retrieving a single attribute
    response = Http(NOORGURL + s"/orgs/$orgid").headers(ACCEPT).headers(ROOTAUTH).params("attribute" -> "tags").asString
    info("code: " + response.code)
    assert(response.code === HttpCode.OK.intValue)
    val attrResponse = parse(response.body).extract[Map[String, String]]
    assert(attrResponse("value") === """{"tagName":"patchedTag"}""")

    // Patch the org, deleting a tag
    tagInput = """{ "tags": {"tagName": null} }"""
    response = Http(URL).postData(tagInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)

    // Ensure tag is deleted
    response = Http(NOORGURL + s"/orgs/$orgid").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.OK.intValue)
    tagResponse = parse(response.body).extract[GetOrgsResponse]
    assert(!tagResponse.orgs(orgid).tags.get.contains("tagName"))

    // Add a 2nd org, no tags to make sure it is optional
    val input2 = PostPutOrgRequest(None, "My Other Org", "desc", None, Some(NodeHeartbeatIntervals(5,15,2)))
    response = Http(URL2).postData(write(input2)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    // Get all orgs
    response = Http(NOORGURL + "/orgs").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.OK.intValue)
    var getOrgsResp = parse(response.body).extract[GetOrgsResponse]
    //assert(getOrgsResp.orgs.size === 2)      // <- there might be other orgs
    assert(getOrgsResp.orgs.contains(orgid))
    assert(getOrgsResp.orgs.contains(orgid2))

    // Get this org
    response = Http(URL).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.OK.intValue)
    getOrgsResp = parse(response.body).extract[GetOrgsResponse]
    assert(getOrgsResp.orgs.size === 1)
    assert(getOrgsResp.orgs.contains(orgid))
    val o = getOrgsResp.orgs(orgid)
    assert(o.description === "desc - patched")
    assert(o.orgType === "IBM")
    assert(o.heartbeatIntervals.minInterval === 6)
  }

  test("GET / and GET /notthere - ensure unsupported routes are handled correctly") {
    var response = Http(NOORGURL + "/notthere").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.NOT_FOUND.intValue)

    response = Http(NOORGURL).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  /** Try adding an invalid user body */
  test("POST /orgs/" + orgid + "/users/" + user + " - bad format") {
    val badJsonInput = """{
      "token": "foo",
      "email": "user9972-bad-format"
    }"""
    val response = Http(URL + "/users/" + user).postData(badJsonInput).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.BAD_INPUT.intValue) // for now this is what is returned when the json-to-scala conversion fails
  }

  test("POST /orgs/" + orgid + "/users/" + user + " - no email - should fail") {
    val input = PostPutUsersRequest(pw, admin = true, "")
    val response = Http(URL + "/users/" + user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  /** Add a normal user */
  test("POST /orgs/" + orgid + "/users/" + user + " - normal") {
    val input = PostPutUsersRequest(pw, admin = false, user + "@hotmail.com")
    val response = Http(URL + "/users/" + user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("PUT /orgs/" + orgid + "/users/" + user + " - update his own email") {
    val input = PostPutUsersRequest(pw, admin = false, user + "@gmail.com")
    val response = Http(URL + "/users/" + user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/" + orgid + " - even w/o admin " + user + " should be able to read his own org") {
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.OK.intValue)
  }

  test("PUT /orgs/" + orgid + "/users/" + user + " - try to himself admin privilege - should fail") {
    val input = PostPutUsersRequest(pw, admin = true, user + "@msn.com")
    var response = Http(URL + "/users/" + user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)

    // Try to do it via patch
    val jsonInput = """{ "admin": true }"""
    response = Http(URL + "/users/" + user).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PATCH /orgs/" + orgid + "/users/" + user + " - give user admin privilege - as root") {
    val jsonInput = """{ "admin": true }"""
    val response = Http(URL + "/users/" + user).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/" + orgid + "/users/" + user + " - give user admin privilege - as root with whitespace") {
    val jsonInput = """    { "admin": true }    """
    val response = Http(URL + "/users/" + user).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/" + orgid + "/users/" + user + " - give user admin privilege - as root with newlines") {
    val jsonInput =
      """
        { "admin": true }
        """
    val response = Http(URL + "/users/" + user).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/" + orgid + "/users/" + user + " - invalid input") {
    val jsonInput = """["true"]"""
    val response = Http(URL + "/users/" + user).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assert(response.body.contains("Cannot deserialize"))
  }

  test("GET /orgs - even with admin " + user + " should NOT be able to read all orgs") {
    val response: HttpResponse[String] = Http(NOORGURL + "/orgs").headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PUT /orgs/" + orgid + " - admin user set orgType to foo") {
    val input = PostPutOrgRequest(Some("foo"), "My Org", "desc", Some(Map("tagName" -> "test")), None)
    var response = Http(URL).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)

    // Ensure orgType is updated
    response = Http(URL).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.OK.intValue)
    val getResponse = parse(response.body).extract[GetOrgsResponse]
    assert(getResponse.orgs(orgid).orgType === "foo")
  }

  test("PUT /orgs/" + orgid + " - admin user try to set orgType to IBM - should fail") {
    var input = PostPutOrgRequest(Some("IBM"), "My Org", "desc", Some(Map("tagName" -> "test")), None)
    var response = Http(URL).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)

    // But now have root really set the orgType back to IBM for the rest of the tests
    input = PostPutOrgRequest(Some("IBM"), "My Org", "desc", Some(Map("tagName" -> "test")), None)
    response = Http(URL).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/" + orgid + "/users - as admin user") {
    val response: HttpResponse[String] = Http(URL + "/users").headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size === 1)

    assert(getUserResp.users.contains(orguser))
    val u = getUserResp.users(orguser) // the 2nd get turns the Some(val) into val
    assert(u.email === user + "@gmail.com")
  }

  test("GET /orgs/" + orgid + "/users - as admin " + user) {
    val response: HttpResponse[String] = Http(URL + "/users").headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    //assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + orgid + "/users/" + user) {
    val response: HttpResponse[String] = Http(URL + "/users/" + user).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size === 1)

    assert(getUserResp.users.contains(orguser))
    val u = getUserResp.users(orguser) // the 2nd get turns the Some(val) into val
    assert(u.email === user + "@gmail.com")
  }

  test("PUT /orgs/" + orgid + "/users/" + user + " - update normal with creds") {
    val input = PostPutUsersRequest(pw, admin = true, user + "-updated@gmail.com")
    val response = Http(URL + "/users/" + user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/" + orgid + "/users/" + user2 + " - as admin user") {
    val input = PostPutUsersRequest(pw2, admin = false, user2 + "@hotmail.com")
    val response = Http(URL + "/users/" + user2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("GET /orgs/" + orgid + "/users/" + user2 + " - verify 2nd user was created") {
    val response: HttpResponse[String] = Http(URL + "/users/" + user2).headers(ACCEPT).headers(USERAUTH2).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size === 1)
    assert(getUserResp.users.contains(orguser2))
  }

  test("POST /orgs/" + orgid + "/users/" + user3 + " - as non-admin user - should fail") {
    val input = PostPutUsersRequest(pw3, admin = false, user3 + "@hotmail.com")
    val response = Http(URL + "/users/" + user3).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH2).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + orgid + "/users/" + user + " - with creds") {
    val response: HttpResponse[String] = Http(URL + "/users/" + user).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.contains(orguser))
    val u = getUserResp.users(orguser) // the 2nd get turns the Some(val) into val
    assert(u.email === user + "-updated@gmail.com")
  }

  test("POST /orgs/" + orgid + "/users/" + user + "/confirm") {
    val response = Http(URL + "/users/" + user + "/confirm").method("post").headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiRespType.OK)
  }

  test("POST /orgs/" + orgid + "/users/" + user + "/confirm - bad user") {
    val response = Http(URL + "/users/" + user + "/confirm").method("post").headers(ACCEPT).headers(BADUSERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiRespType.BADCREDS)
  }

  test("POST /orgs/" + orgid + "/users/" + user + "/confirm - bad pw") {
    val response = Http(URL + "/users/" + user + "/confirm").method("post").headers(ACCEPT).headers(USERAUTHBAD).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiRespType.BADCREDS)
  }

  // Test changing passwords =====================================================================

  test("POST /orgs/" + orgid + "/users/" + user + "/changepw - non-admin user try to change pw of another user in the org - should fail") {
    // Have an non-admin user try to change the pw
    val input = ChangePwRequest("doesnt-matter-will-fail")
    var response = Http(URL + "/users/" + user + "/changepw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH2).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)

    // Confirm the pw was not changed
    response = Http(URL + "/users/" + user + "/confirm").method("post").headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/" + orgid + "/users/" + user2 + "/changepw - non-admin user change his own pw") {
    // Change own pw
    val input = ChangePwRequest(pw2new)
    var response = Http(URL + "/users/" + user2 + "/changepw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH2).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val postChangePwResp = parse(response.body).extract[ApiResponse]
    assert(postChangePwResp.code === ApiRespType.OK)

    // Now confirm the new pw
    response = Http(URL + "/users/" + user2 + "/confirm").method("post").headers(ACCEPT).headers(USERAUTH2NEW).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/" + orgid + "/users/" + user2 + "/changepw - admin user change pw of another user in the org back to original") {
    // Have an admin user change the pw
    val input = ChangePwRequest(pw2)
    var response = Http(URL + "/users/" + user2 + "/changepw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val postChangePwResp = parse(response.body).extract[ApiResponse]
    assert(postChangePwResp.code === ApiRespType.OK)

    // Now confirm the pw is back to original
    response = Http(URL + "/users/" + user2 + "/confirm").method("post").headers(ACCEPT).headers(USERAUTH2).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  /** Delete this user so we can recreate it via root with put */
  test("DELETE /orgs/" + orgid + "/users/" + user) {
    val response = Http(URL + "/users/" + user).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  /** Create the normal user - as root */
  test("POST /orgs/" + orgid + "/users/" + user + " - create normal - as root") {
    val input = PostPutUsersRequest(pw, admin = true, user + "@gmail.com")
    val response = Http(URL + "/users/" + user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("GET /orgs/" + orgid + "/users/" + user + " - after root created it") {
    val response: HttpResponse[String] = Http(URL + "/users/" + user).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size === 1)

    assert(getUserResp.users.contains(orguser))
    val u = getUserResp.users(orguser) // the 2nd get turns the Some(val) into val
    assert(u.email === user + "@gmail.com")
  }

  // Anonymous creates
  test("POST /orgs/" + orgid + "/users/" + user3 + " - as anonymous - should fail") {
    val input = PostPutUsersRequest(pw3, admin = false, user3 + "@hotmail.com")
    val response = Http(URL + "/users/" + user3).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).asString // as anonymous
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
  }

  // Tests on updatedBy field added to user resource
  test("POST /orgs/" + orgid + "/users/" + user4 + " - adding new user for testing updatedBy field") {
    val input = PostPutUsersRequest(pw4, admin = false, user4 + "@hotmail.com")
    val response = Http(URL + "/users/" + user4).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("GET /orgs/" + orgid + "/users - as admin user to verify updatedBy there") {
    val response: HttpResponse[String] = Http(URL + "/users").headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetUsersResponse]

    assert(getUserResp.users.contains(orguser4))
    val u = getUserResp.users(orguser4) // the 2nd get turns the Some(val) into val
    assert(u.email === user4 + "@hotmail.com")
    assert(!u.updatedBy.isEmpty)
    assert(u.updatedBy.contentEquals("root/root"))
  }

  test("PUT /orgs/" + orgid + "/users/" + user4 + " - update email - to test updatedBy") {
    val input = PostPutUsersRequest(pw4, admin = false, user4 + "@gmail.com")
    val response = Http(URL + "/users/" + user4).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH4).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/" + orgid + "/users - as admin user to verify updatedBy changed after PUT email") {
    val response: HttpResponse[String] = Http(URL + "/users").headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetUsersResponse]

    assert(getUserResp.users.contains(orguser4))
    val u = getUserResp.users(orguser4) // the 2nd get turns the Some(val) into val
    assert(u.email === user4 + "@gmail.com")
    assert(u.updatedBy.contentEquals("UsersSuiteTests/u4"))
  }

  test("PATCH /orgs/" + orgid + "/users/" + user4 + " - update email via patch - to test updatedBy") {
    val input = PatchUsersRequest(None, None, Some(user4 + "@hotmail.com"))
    val response = Http(URL + "/users/" + user4).postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/" + orgid + "/users - as admin user to verify updatedBy changed after PATCH email") {
    val response: HttpResponse[String] = Http(URL + "/users").headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetUsersResponse]

    assert(getUserResp.users.contains(orguser4))
    val u = getUserResp.users(orguser4) // the 2nd get turns the Some(val) into val
    assert(u.email === user4 + "@hotmail.com")
    assert(!u.updatedBy.isEmpty)
    assert(!u.updatedBy.contentEquals("UsersSuiteTests/u4"))
    assert(u.updatedBy.contentEquals("UsersSuiteTests/u1"))
  }

  test("PUT /orgs/" + orgid + "/users/" + user4 + " - update email - to test updatedBy via PUT again") {
    val input = PostPutUsersRequest(pw4, admin = false, user4 + "@gmail.com")
    val response = Http(URL + "/users/" + user4).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH4).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/" + orgid + "/users - as admin user to verify updatedBy changed after PUT email the second time") {
    val response: HttpResponse[String] = Http(URL + "/users").headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetUsersResponse]

    assert(getUserResp.users.contains(orguser4))
    val u = getUserResp.users(orguser4) // the 2nd get turns the Some(val) into val
    assert(u.email === user4 + "@gmail.com")
    assert(!u.updatedBy.contentEquals("UsersSuiteTests/u1"))
    assert(u.updatedBy.contentEquals("UsersSuiteTests/u4"))
  }

  test("PATCH /orgs/" + orgid + "/users/" + user4 + " - update email via patch - to test updatedBy via PATCH again") {
    val input = PatchUsersRequest(None, None, Some(user4 + "@hotmail.com"))
    val response = Http(URL + "/users/" + user4).postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/" + orgid + "/users - as admin user to verify updatedBy changed after PATCH email the second time") {
    val response: HttpResponse[String] = Http(URL + "/users").headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetUsersResponse]

    assert(getUserResp.users.contains(orguser4))
    val u = getUserResp.users(orguser4) // the 2nd get turns the Some(val) into val
    assert(u.email === user4 + "@hotmail.com")
    assert(!u.updatedBy.isEmpty)
    assert(!u.updatedBy.contentEquals("UsersSuiteTests/u4"))
    assert(u.updatedBy.contentEquals("UsersSuiteTests/u1"))
  }

  // Add a normal agbot
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+" - verify we can add an agbot as root") {
    val input = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-normal", None, "ABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/" + orgid2 + "/users/" + user + " - create user in org2 so we can test cross-org ACLs") {
    val input = PostPutUsersRequest(pw, admin = true, user + "@hotmail.com")
    val response = Http(URL2 + "/users/" + user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("GET /orgs - as org2 user - should failed") {
    val response = Http(NOORGURL + "/orgs").headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs?orgtype=IBM - as org2 user - should find just the IBM type orgs") {
    val response = Http(NOORGURL + "/orgs").headers(ACCEPT).headers(ORG2USERAUTH).param("orgtype", "IBM").asString
    info("code: " + response.code)
    assert(response.code === HttpCode.OK.intValue)
    val getOrgsResp = parse(response.body).extract[GetOrgsResponse]
    assert(getOrgsResp.orgs.size >= 2) // the 1 we created + the standard IBM org + whatever CatalogSuite created
    assert(getOrgsResp.orgs.contains(orgid))
    assert(getOrgsResp.orgs.contains("IBM"))
    assert(getOrgsResp.orgs.contains("UsersSuiteTests"))
    for (org <- getOrgsResp.orgs) {
      assert(org._2.orgType == "IBM")
    }
  }

  test("POST /orgs/"+orgid+"/services - add "+service+" as not public in 1st org") {
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcurl, svcversion, svcarch, "multiple", None, None, None, "", "", None)
    val response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("GET /orgs/"+orgid+"/services - as org2 user - should find no public services") {
    val response: HttpResponse[String] = Http(URL + "/services").headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("GET /orgs/"+orgid+"/services/"+service+" - as org2 user - should fail") {
    val response: HttpResponse[String] = Http(URL + "/services/" + service).headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PATCH /orgs/"+orgid+"/services/"+service+" - to make it public") {
    val jsonInput = """{ "public": true }"""
    val response = Http(URL+"/services/"+service).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/"+orgid+"/services - as org2 user - this time it should find 1") {
    val response: HttpResponse[String] = Http(URL + "/services").headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 1)
  }

  test("GET /orgs/"+orgid+"/services/"+service+" - as org2 user - this time it should work") {
    val response: HttpResponse[String] = Http(URL + "/services/" + service).headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetServicesResponse]
    assert(respObj.services.size === 1)
  }


  test("POST /orgs/"+orgid+"/patterns/"+pattern+" - add "+pattern+" as not public in 1st org") {
    val input = PostPutPatternRequest("Pattern", None, None, List(PServices(svcurl, orgid, svcarch, None, List(PServiceVersions(svcversion,None,None,None,None)), None, None)), None, None )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("GET /orgs/"+orgid+"/patterns - as org2 user - should find no public patterns") {
    val response: HttpResponse[String] = Http(URL + "/patterns").headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+" - as org2 user - should fail") {
    val response: HttpResponse[String] = Http(URL + "/patterns/" + pattern).headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PATCH /orgs/"+orgid+"/patterns/"+pattern+" - to make it public") {
    val jsonInput = """{ "public": true }"""
    val response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/"+orgid+"/patterns - as org2 user - this time it should find 1") {
    val response: HttpResponse[String] = Http(URL + "/patterns").headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 1)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+" - as org2 user - this time it should work") {
    val response: HttpResponse[String] = Http(URL + "/patterns/" + pattern).headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 1)
  }

  test("IAM login") {
    // these tests will perform authentication with IBM cloud and will only run
    // if the IAM info is provided in the env vars EXCHANGE_IAM_KEY, EXCHANGE_IAM_EMAIL, and EXCHANGE_IAM_ACCOUNT_ID
    if (iamKey.nonEmpty && iamUser.nonEmpty) {
      if (iamAccountId.nonEmpty) {
        // Add ibmcloud_id to org. Not needed for ICP
        val tagInput = s"""{ "tags": {"ibmcloud_id": "$iamAccountId"} }"""
        val response = Http(CLOUDURL).postData(tagInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.PUT_OK.intValue)
      }

      // authenticate as a cloud user and view org (action they are authorized for)
      info("authenticating to ibm cloud with " + IAMAUTH(cloudorg) + " and GETing " + CLOUDURL)
      var response = Http(CLOUDURL).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("GET " + CLOUDURL + " code: " + response.code)
      assert(response.code === HttpCode.OK.intValue)

      // authenticate as a cloud user and view org, ensuring cached user works
      response = Http(CLOUDURL).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.OK.intValue)

      // authenticate as a cloud user and view this user
      response = Http(CLOUDURL + "/users/" + iamUser).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.OK.intValue)
      var getUserResp = parse(response.body).extract[GetUsersResponse]
      assert(getUserResp.users.size === 1)
      assert(getUserResp.users.contains(cloudorg + "/" + iamUser))
      var u = getUserResp.users(cloudorg + "/" + iamUser)
      assert(u.email === iamUser)

      // run special case of authenticate as a cloud user and view your own user
      response = Http(CLOUDURL + "/users/iamapikey").headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.OK.intValue)
      getUserResp = parse(response.body).extract[GetUsersResponse]
      assert(getUserResp.users.size === 1)
      assert(getUserResp.users.contains(cloudorg + "/" + iamUser))
      u = getUserResp.users(cloudorg + "/" + iamUser)
      assert(u.email === iamUser)

      // ensure user does not have admin auth by trying to get other users
      response = Http(CLOUDURL + "/users/" + user).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)

      // ensure user can't view other org (action they are not authorized for)
      response = Http(URL2).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)

      // ensure user has auth to view patterns
      response = Http(CLOUDURL + "/patterns").headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.OK.intValue || response.code === HttpCode.NOT_FOUND.intValue)

      // Can only add resources to an ibm public cloud org that we created (the icp org is the one in the cluster)
      if (iamAccountId.nonEmpty) {
        // ensure we can add a service to check acls to other objects
        val inputSvc = PostPutServiceRequest("testSvc", Some("desc"), public = false, None, "s1", "1.2.3", "amd64", "single", None, None, None, "a", "b", None)
        response = Http(CLOUDURL + "/services").postData(write(inputSvc)).method("post").headers(CONTENT).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.POST_OK.intValue)

        // Only for ibm public cloud: ensure we can add a node to check acls to other objects
        val inputNode = PutNodesRequest("abc", "my node", "", None, None, None, None, "ABC", None, None)
        response = Http(CLOUDURL + "/nodes/n1").postData(write(inputNode)).method("put").headers(CONTENT).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.PUT_OK.intValue)
      } else {
        // ICP case - ensure using the cloud creds with a different org prepended fails
        response = Http(CLOUDURL + "/patterns").headers(ACCEPT).headers(IAMAUTH(orgid)).asString
        info("code: " + response.code)
        assert(response.code === HttpCode.BADCREDS.intValue)
      }

      // Test a 2nd org associated with the ibm cloud account
      if (iamAccountId.nonEmpty && iamOtherKey.nonEmpty && iamOtherAccountId.nonEmpty) {
        // remove created user
        response = Http(CLOUDURL + "/users/" + iamUser).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
        info("DELETE " + iamUser + ", code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)

        // clear auth cache
        response = Http(NOORGURL+"/admin/clearauthcaches").method("post").headers(ACCEPT).headers(ROOTAUTH).asString
        info("CLEAR CACHE code: "+response.code+", response.body: "+response.body)
        assert(response.code === HttpCode.POST_OK.intValue)

        // add ibmcloud_id to different org
        var tagInput = s"""{ "tags": {"ibmcloud_id": "$iamAccountId"} }"""
        response = Http(URL2).postData(tagInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.PUT_OK.intValue)

        // authenticating with wrong org should notify user
        response = Http(CLOUDURL).headers(ACCEPT).headers(IAMOTHERAUTH(cloudorg)).asString
        info("test for api key not part of this org: code: " + response.code + ", response.body: " + response.body)
        //info("code: "+response.code)
        assert(response.code === HttpCode.BADCREDS.intValue)
        var errorMsg = s"the iamapikey or iamtoken specified can not be used with org '$cloudorg' prepended to it, because the iamapikey or iamtoken is not associated with that org."
        assert(parse(response.body).extract[Map[String, String]].apply("msg").startsWith(errorMsg))

        // remove ibmcloud_id from org
        tagInput = """{ "tags": {"ibmcloud_id": null} }"""
        response = Http(CLOUDURL).postData(tagInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.PUT_OK.intValue)

        response = Http(CLOUDURL).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
        info("code: "+response.code)
        assert(response.code === HttpCode.BADCREDS.intValue)
        errorMsg = s"the iamapikey or iamtoken specified can not be used with org '$cloudorg' prepended to it, because the iamapikey or iamtoken is not associated with that org"
        assert(parse(response.body).extract[Map[String, String]].apply("msg").startsWith(errorMsg))
      } else {
        info("Skipping IAM public cloud tests tests")
      }

      /* remove ibmcloud_id from org - do not need to do this, because we delete both orgs at the end
      tagInput = """{ "tags": {"ibmcloud_id": null} }"""
      response = Http(URL2).postData(tagInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)
      */
    } else {
      info("Skipping all IAM login tests")
    }
  }

  /** Clean up, delete all the test users */
  test("Cleanup 1 - DELETE all test users") {
    deleteAllUsers()
  }

  /** Delete the orgs we used for this test */
  test("DELETE orgs") {
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
    response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

}
