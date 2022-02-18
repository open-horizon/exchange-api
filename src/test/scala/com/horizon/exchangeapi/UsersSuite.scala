package com.horizon.exchangeapi

import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import scalaj.http._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import scala.collection.mutable.ListBuffer
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
class UsersSuite extends AnyFunSuite {

  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080")
  val ACCEPT = ("Accept", "application/json")
  val CONTENT = ("Content-Type", "application/json")
  val orgid = "UsersSuiteTests"
  val orgid2 = "UsersSuiteTests2"
  val orgid3 = "UsersSuiteTests3"
  val orgid4 = "UsersSuiteTests4"
  val URL = urlRoot + "/v1/orgs/" + orgid
  val URL2 = urlRoot + "/v1/orgs/" + orgid2
  val URL3 = urlRoot + "/v1/orgs/" + orgid3
  val URL4 = urlRoot + "/v1/orgs/" + orgid4
  val urlRootOrg = urlRoot + "/v1/orgs/root"
  val cloudorg = "UsersSuiteTestsCloud"
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
  val iamUIToken = sys.env.getOrElse("EXCHANGE_IAM_UI_TOKEN", "")
  val iamUser = sys.env.getOrElse("EXCHANGE_IAM_EMAIL", "")
  // specify only 1 of these 2 env vars (only 1 case can be tested at a time)
  val ocpAccountId = sys.env.getOrElse("EXCHANGE_MULT_ACCOUNT_ID", "") // indicates we should test OCP Multitenancy
  val iamAccountId = sys.env.getOrElse("EXCHANGE_IAM_ACCOUNT_ID", "") // indicates we should test ibm public cloud instead of OCP
  val iamOtherKey = sys.env.getOrElse("EXCHANGE_IAM_OTHER_KEY", "")
  val iamOtherAccountId = sys.env.getOrElse("EXCHANGE_IAM_OTHER_ACCOUNT_ID", "")
  val iamUserUIId = sys.env.getOrElse("EXCHANGE_IAM_UI_ID", "")
  val IAMAUTH = { org: String => ("Authorization", "Basic " + ApiUtils.encode(s"$org/iamapikey:$iamKey")) }
  val IAMUITOKENAUTH = { org: String => ("Authorization", "Basic " + ApiUtils.encode(s"$org/iamtoken:$iamUIToken")) }
  val IAMBADAUTH = { org: String => ("Authorization", "Basic " + ApiUtils.encode(s"$org/iamapikey:${iamKey}bad")) }
  val IAMOTHERAUTH = { org: String => ("Authorization", "Basic " + ApiUtils.encode(s"$org/iamapikey:$iamOtherKey")) }
  val hubadmin = "UsersSuiteHubAdmin"
  val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("root/"+hubadmin+":"+pw))
  val orgadmin = "orgadmin"
  val orgsList = ListBuffer(orgid, orgid2)

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
    response = Http(CLOUDURL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
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

    /* No longer needed because https://github.com/open-horizon/exchange-api/issues/176 is fixed.
    // Clear auth cache because we deleted orgs and the cloud users won't automatically get recreated in the db if they are still in the cache.
    var response = Http(NOORGURL+"/admin/clearauthcaches").method("post").headers(ACCEPT).headers(ROOTAUTH).asString
    info("CLEAR CACHE code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue) */

    // Try adding an invalid org body
    val badJsonInput = """{
      "labelx": "foo",
      "description": "desc"
    }"""
    var response = Http(URL).postData(badJsonInput).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.BAD_INPUT.intValue) // for now this is what is returned when the json-to-scala conversion fails

    // Now add a good org
    var input = PostPutOrgRequest(Some("IBM"), "My Org", "desc", Some(Map("tagName" -> "test")), None, None)
    response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    // Update the org
    input = PostPutOrgRequest(Some("IBM"), "My Org", "desc - updated", None, None, Some(NodeHeartbeatIntervals(5,15,2)))
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
    val input2 = PostPutOrgRequest(None, "My Other Org", "desc", None, None, Some(NodeHeartbeatIntervals(5,15,2)))
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

  test("POST /orgs/" + orgid + "/users/" + user + " - add user with invalid body - should fail") {
    val badJsonInput = """{
      "token": "foo",
      "email": "user9972-bad-format"
    }"""
    val response = Http(URL + "/users/" + user).postData(badJsonInput).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.BAD_INPUT.intValue) // for now this is what is returned when the json-to-scala conversion fails
  }

  test("POST /orgs/" + orgid + "/users/" + user + " - add a normal user") {
    val input = PostPutUsersRequest(pw, admin = false, Some(false), user + "@hotmail.com")
    val response = Http(URL + "/users/" + user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("PUT /orgs/" + orgid + "/users/" + user + " - update as user with empty email") {
    val input = PostPutUsersRequest(pw, admin = false, Some(false), "")
    val response = Http(URL + "/users/" + user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PUT /orgs/" + orgid + "/users/" + user + " - update his own email") {
    val input = PostPutUsersRequest(pw, admin = false, Some(false), user + "@gmail.com")
    val response = Http(URL + "/users/" + user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/" + orgid + " - even w/o admin " + user + " should be able to read his own org") {
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code)
    assert(response.code === HttpCode.OK.intValue)
  }


  test("PUT /orgs/" + orgid + "/users/" + user + " - try to give himself admin privilege - should fail") {
    val input = PostPutUsersRequest(pw, admin = true, Some(false), user + "@msn.com")
    var response = Http(URL + "/users/" + user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)

    // Try to do it via patch - should fail
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
    val input = PostPutOrgRequest(Some("foo"), "My Org", "desc", Some(Map("tagName" -> "test")), None, None)
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
    var input = PostPutOrgRequest(Some("IBM"), "My Org", "desc", Some(Map("tagName" -> "test")), None, None)
    var response = Http(URL).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)

    // But now have root really set the orgType back to IBM for the rest of the tests
    input = PostPutOrgRequest(Some("IBM"), "My Org", "desc", Some(Map("tagName" -> "test")), None, None)
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


  test("GET /orgs/" + orgid + "/status") {
    val response: HttpResponse[String] = Http(URL + "/status").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetOrgStatusResponse]
    assert(getUserResp.numberOfUsers === 1)
    assert(getUserResp.numberOfNodes === 0)
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
    val input = PostPutUsersRequest(pw, admin = true, Some(false), user + "-updated@gmail.com")
    val response = Http(URL + "/users/" + user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/" + orgid + "/users/" + user2 + " - as admin user") {
    val input = PostPutUsersRequest(pw2, admin = false, Some(false), user2 + "@hotmail.com")
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
    val input = PostPutUsersRequest(pw3, admin = false, Some(false), user3 + "@hotmail.com")
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
    val input = PostPutUsersRequest(pw, admin = true, Some(false), user + "@gmail.com")
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
    val input = PostPutUsersRequest(pw3, admin = false, Some(false), user3 + "@hotmail.com")
    val response = Http(URL + "/users/" + user3).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).asString // as anonymous
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
  }

  // Tests on updatedBy field added to user resource
  test("POST /orgs/" + orgid + "/users/" + user4 + " - adding new user for testing updatedBy field") {
    val input = PostPutUsersRequest(pw4, admin = false, Some(false), user4 + "@hotmail.com")
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
    assert(u.updatedBy.nonEmpty)
    assert(u.updatedBy.contentEquals("root/root"))
  }

  test("PUT /orgs/" + orgid + "/users/" + user4 + " - update email - to test updatedBy") {
    val input = PostPutUsersRequest(pw4, admin = false, Some(false), user4 + "@gmail.com")
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
    val input = PatchUsersRequest(None, None, None, Some(user4 + "@hotmail.com"))
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
    assert(u.updatedBy.nonEmpty)
    assert(!u.updatedBy.contentEquals("UsersSuiteTests/u4"))
    assert(u.updatedBy.contentEquals("UsersSuiteTests/u1"))
  }

  test("PUT /orgs/" + orgid + "/users/" + user4 + " - update email - to test updatedBy via PUT again") {
    val input = PostPutUsersRequest(pw4, admin = false, Some(false), user4 + "@gmail.com")
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
    val input = PatchUsersRequest(None, None, None, Some(user4 + "@hotmail.com"))
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
    assert(u.updatedBy.nonEmpty)
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
    val input = PostPutUsersRequest(pw, admin = true, Some(false), user + "@hotmail.com")
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
    val input = PostPutServiceRequest(svcBase+" arm", None, public = false, None, svcurl, svcversion, svcarch, "multiple", None, None, None, Some(""), Some(""), None, None, None)
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

  // Hub Admin Tests
  test("POST /orgs/root/users/" + hubadmin + " - create hub admin as root") {
    val input = PostPutUsersRequest(pw, admin = false, Some(true), hubadmin + "@none.com")
    val response = Http(urlRootOrg + "/users/" + hubadmin).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("PUT /orgs/root/users/" + hubadmin + " - update hub admin as himself") {
    val input = PostPutUsersRequest(pw, admin = false, Some(true), hubadmin + "@hotmail.com")
    val response = Http(urlRootOrg + "/users/" + hubadmin).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/root/users/" + hubadmin + " - hub admin view himself") {
    val response = Http(urlRootOrg + "/users/" + hubadmin).method("get").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size == 1)
    assert(getUserResp.users.contains("root/" + hubadmin))
    assert(getUserResp.users("root/" + hubadmin).hubAdmin)
    assert(getUserResp.users("root/" + hubadmin).email === hubadmin + "@hotmail.com")
  }

  test("POST /orgs/" + orgid + "/users/" + orgadmin + " creating orgadmin by hubadmin" ) {
    val input = PostPutUsersRequest(pw2, admin = true, Some(false), orgadmin + "@hotmail.com")
    val response = Http(URL + "/users/" + orgadmin).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("GET /orgs/" + orgid + "/users/" + orgadmin + " - view org admin by hub admin") {
    val response = Http(URL + "/users/" + orgadmin).method("get").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size == 1)
    assert(getUserResp.users.contains(orgid + "/" + orgadmin))
    assert(getUserResp.users(orgid + "/" + orgadmin).admin)
  }

  test("GET /orgs/" + orgid + "/users/" + user4 + " - view regular user by hub admin - should fail") {
    val response = Http(URL + "/users/" + user4).method("get").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("GET /orgs/" + orgid + "/users - view all users by hub admin, should return just the admins") {
    val response = Http(URL + "/users").method("get").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size === 2)
    assert(getUserResp.users.contains(orgid + "/" + user))
    assert(getUserResp.users.contains(orgid + "/" + orgadmin))
    assert(getUserResp.users(orgid + "/" + orgadmin).admin)
  }

  test("PUT /orgs/" + orgid + "/users/" + orgadmin + " updating orgadmin by hubadmin" ) {
    val input = PostPutUsersRequest(pw2, admin = true, Some(false), orgadmin + "1@hotmail.com")
    val response = Http(URL + "/users/" + orgadmin).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PUT /orgs/" + orgid + "/users/" + orgadmin + " upgrading orgadmin to hubadmin by hubadmin -- should fail" ) {
    val input = PostPutUsersRequest(pw2, admin = true, Some(true), orgadmin + "1@hotmail.com")
    val response = Http(URL + "/users/" + orgadmin).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PUT /orgs/" + orgid + "/users/" + orgadmin + " upgrading orgadmin to hubadmin by root -- should fail" ) {
    val input = PostPutUsersRequest(pw2, admin = true, Some(true), orgadmin + "1@hotmail.com")
    val response = Http(URL + "/users/" + orgadmin).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PATCH /orgs/" + orgid + "/users/" + orgadmin + " updating orgadmin by hubadmin" ) {
    val jsonInput = """{ "admin": true }"""
    val response = Http(URL + "/users/" + orgadmin).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("PUT /orgs/" + orgid + "/users/" + user + " update non-admin user by hubadmin -- should fail" ) {
    val input = PostPutUsersRequest(pw2, admin = false, Some(false), user + "1@hotmail.com")
    val response = Http(URL + "/users/" + user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assert(response.body.contains("Hub Admin users can only create or update hub admin or org admin users"))
  }

  test("PATCH /orgs/" + orgid + "/users/" + user2 + " patch non-admin user by hubadmin -- should fail" ) {
    val input = PatchUsersRequest(None, None, None, Some("fakefake@hotmail.com"))
    val response = Http(URL + "/users/" + user2).postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assert(response.body.contains("Hub Admin users can only create or update hub admin or org admin users"))
  }

  test("DELETE /orgs/root/users/root - should fail") {
    val response = Http(urlRootOrg + "/users/root").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("GET /orgs/" + orgid + "/agbots/" + agbotId + " - view agbot by hub admin") {
    val response = Http(URL + "/agbots/" + agbotId).method("get").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val getAgbotResp = parse(response.body).extract[GetAgbotsResponse]
    assert(getAgbotResp.agbots.contains(orgid+"/"+agbotId))
    val dev = getAgbotResp.agbots(orgid+"/"+agbotId) // the 2nd get turns the Some(val) into val
    assert(dev.name === "agbot"+agbotId+"-normal")
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/patterns - as hubadmin") {
    val input = PostAgbotPatternRequest(orgid, pattern, None)
    val response = Http(URL+"/agbots/"+agbotId+"/patterns").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/business/policies/"+"mybuspol"+" - add "+"mybuspol"+" as user") {
    val input = PostPutBusinessPolicyRequest("mybuspol", None, BService(svcurl, orgid, svcarch, List(BServiceVersions(svcversion, None, None)), None ), None, None, None )
    val response = Http(URL+"/business/policies/"+"mybuspol").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/agbots/"+agbotId+"/businesspols - as hubadmin") {
    val input = PostAgbotBusinessPolRequest(orgid, "mybuspol", None)
    val response = Http(URL+"/agbots/"+agbotId+"/businesspols").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("POST /orgs/root/users/" + hubadmin + " - create user that is hub admin and org admin as root -- should fail") {
    val input = PostPutUsersRequest(pw, admin = true, Some(true), hubadmin + "@none.com")
    val response = Http(urlRootOrg + "/users/" + hubadmin).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
    assert(response.body.contains("User cannot be admin and hubAdmin at the same time"))
  }

  test("PATCH /orgs/root/users/" + hubadmin + " updating hubadmin to have admin=false -- just validating the patch isn't rejected by req body" ) {
    val jsonInput = """{ "admin": false }"""
    val response = Http(urlRootOrg + "/users/" + hubadmin).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId+"/patterns -- by hubadmin") { //API call made in hzn exchange agbot listpattern
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/patterns").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
  }

  test("GET /orgs/"+orgid+"/agbots -- by hubadmin") { //API call made in hzn exchange agbot listpattern
    val response: HttpResponse[String] = Http(URL+"/agbots").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
  }

  test("GET /orgs/"+orgid+"/agbots/"+agbotId +" -- by hubadmin") { //API call made in hzn exchange agbot listpattern
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/patterns").headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
  }

  test("IAM login") {
    // these tests will perform authentication with IBM cloud and will only run
    // if the IAM info is provided in the env vars EXCHANGE_IAM_KEY (iamKey), EXCHANGE_IAM_EMAIL (iamUser), and EXCHANGE_MULT_ACCOUNT_ID (ocpAccountId) or EXCHANGE_IAM_ACCOUNT_ID (iamAccountId)
    if (iamKey.nonEmpty && iamUser.nonEmpty && (ocpAccountId.nonEmpty || iamAccountId.nonEmpty)) {
      assert(!(ocpAccountId.nonEmpty && iamAccountId.nonEmpty)) // can't test both at the same time

      val tagMap = if (ocpAccountId.nonEmpty) Map("cloud_id" -> ocpAccountId) else Map("ibmcloud_id" -> iamAccountId)
      info("Add cloud org with tag: " + tagMap)
      val input = PostPutOrgRequest(None, "Cloud Org", "desc", Some(tagMap), None, None)
      var response = Http(CLOUDURL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)

      info("authenticating to cloud with iam api key and GETing " + CLOUDURL)
      response = Http(CLOUDURL).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("GET " + CLOUDURL + " code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.OK.intValue)

      info("authenticate as a cloud user and view org, ensuring cached user works")
      response = Http(CLOUDURL).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.OK.intValue)

      if (iamUIToken.nonEmpty) {
        info("authenticating to cloud with UI iam token and GETing " + CLOUDURL)
        response = Http(CLOUDURL).headers(ACCEPT).headers(IAMUITOKENAUTH(cloudorg)).asString
        info("GET " + CLOUDURL + " code: " + response.code)
        assert(response.code === HttpCode.OK.intValue)

        info("ensuring cached user works: authenticating to cloud with UI iam token and GETing " + CLOUDURL)
        response = Http(CLOUDURL).headers(ACCEPT).headers(IAMUITOKENAUTH(cloudorg)).asString
        info("GET " + CLOUDURL + " code: " + response.code)
        assert(response.code === HttpCode.OK.intValue)
      } else info("Skipping UI token login test")

      info("authenticate as a cloud user and view this user")
      response = Http(CLOUDURL + "/users/" + iamUser).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.OK.intValue)
      var getUserResp = parse(response.body).extract[GetUsersResponse]
      assert(getUserResp.users.size === 1)
      assert(getUserResp.users.contains(cloudorg + "/" + iamUser))
      var u = getUserResp.users(cloudorg + "/" + iamUser)
      assert(u.email === iamUser)

      info("run special case of authenticate as a cloud user and view your own user")
      response = Http(CLOUDURL + "/users/iamapikey").headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.OK.intValue)
      getUserResp = parse(response.body).extract[GetUsersResponse]
      assert(getUserResp.users.size === 1)
      assert(getUserResp.users.contains(cloudorg + "/" + iamUser))
      u = getUserResp.users(cloudorg + "/" + iamUser)
      assert(u.email === iamUser)

      info("ensure user does not have admin auth by trying to get other users")
      response = Http(CLOUDURL + "/users/" + user).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)

      info("ensure user can't view other org (action they are not authorized for)")
      response = Http(URL2).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)

      info("ensure user has auth to view patterns")
      response = Http(CLOUDURL + "/patterns").headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.OK.intValue || response.code === HttpCode.NOT_FOUND.intValue)

      info("ensure bad iam api key returns 401")
      response = Http(CLOUDURL + "/patterns").headers(ACCEPT).headers(IAMBADAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.BADCREDS.intValue)

      // Can only add resources to an ibm public cloud org that we created (the icp org is the one in the cluster)
      if (iamAccountId.nonEmpty) {
        // ensure we can add a service to check acls to other objects
        val inputSvc = PostPutServiceRequest("testSvc", Some("desc"), public = false, None, "s1", "1.2.3", "amd64", "single", None, None, None, Some("a"), Some("b"), None, None, None)
        response = Http(CLOUDURL + "/services").postData(write(inputSvc)).method("post").headers(CONTENT).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.POST_OK.intValue)

        // Only for ibm public cloud: ensure we can add a node to check acls to other objects
        val inputNode = PutNodesRequest("abc", "my node", None, "", None, None, None, None, "ABC", None, None)
        response = Http(CLOUDURL + "/nodes/n1").postData(write(inputNode)).method("put").headers(CONTENT).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.PUT_OK.intValue)
      } else {
        // ICP case - ensure using the cloud creds with a different org prepended fails
        //response = Http(CLOUDURL + "/patterns").headers(ACCEPT).headers(IAMAUTH(orgid)).asString
        response = Http(CLOUDURL + "/patterns").headers(ACCEPT).headers(IAMAUTH(orgid2)).asString
        info("code: " + response.code)
        assert(response.code === HttpCode.BADCREDS.intValue)
      }

      // Test a 2nd org associated with the ibm cloud account
      if (iamAccountId.nonEmpty && iamOtherKey.nonEmpty && iamOtherAccountId.nonEmpty) {
        // remove created user
        response = Http(CLOUDURL + "/users/" + iamUser).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
        info("DELETE " + iamUser + ", code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)

        /* this is no longer needed because https://github.com/open-horizon/exchange-api/issues/176 is fixed
        response = Http(NOORGURL+"/admin/clearauthcaches").method("post").headers(ACCEPT).headers(ROOTAUTH).asString
        info("CLEAR CACHE code: "+response.code+", response.body: "+response.body)
        assert(response.code === HttpCode.POST_OK.intValue) */

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

  test("Multitenancy Pathway") {
    //todo: ICP_EXTERNAL_MGMT_INGRESS is not used in these tests, so we should not require it be set
    if((sys.env.getOrElse("ICP_EXTERNAL_MGMT_INGRESS", "") != "") && ocpAccountId.nonEmpty && iamKey.nonEmpty && iamUser.nonEmpty){
      info("Try deleting the test org first in case it stuck around")
      val responseOrg = Http(URL3).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+responseOrg.code+", response.body: "+responseOrg.body)
      assert(responseOrg.code === HttpCode.DELETED.intValue || responseOrg.code === HttpCode.NOT_FOUND.intValue)

      info("Creating new org with cloud_id")
      val input = PostPutOrgRequest(None, "", "Desc", Some(Map("cloud_id" -> ocpAccountId)), None, None)
      var response = Http(URL3).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)
      orgsList+=orgid3

      info("Cloud user with apikey should be able to access the org")
      response = Http(URL3).headers(ACCEPT).headers(IAMAUTH(orgid3)).asString
      info("GET " + URL3 + " code: " + response.code)
      assert(response.code === HttpCode.OK.intValue)

      if (iamUIToken.nonEmpty){
        // we can authenticate as a UI user
        info("Cloud (UI) user with token should be able to access the org")
        response = Http(URL3).headers(ACCEPT).headers(IAMUITOKENAUTH(orgid3)).asString
        info("GET " + URL3 + " code: " + response.code)
        assert(response.code === HttpCode.OK.intValue)
      } else info ("Skipping UI login tests 1")

      info("Cloud user with apikey should not be able to access org without accountID")
      //response = Http(URL).headers(ACCEPT).headers(IAMAUTH(orgid)).asString
      response = Http(URL).headers(ACCEPT).headers(IAMAUTH(orgid2)).asString
      info("GET " + URL + " code: " + response.code)
      info("GET " + URL + " body: " + response.body)
      assert(response.code === HttpCode.BADCREDS.intValue)

      if (iamUIToken.nonEmpty){
        // we can authenticate as a UI user
        info("Cloud user with token should not be able to access org without accountID")
        response = Http(URL).headers(ACCEPT).headers(IAMUITOKENAUTH(orgid)).asString
        info("GET " + URL + " code: " + response.code)
        info("GET " + URL + " body: " + response.body)
        assert(response.code === HttpCode.BADCREDS.intValue)
      } else info ("Skipping UI login tests 2")

      info("Try deleting the second test org first in case it stuck around")
      val responseOrg2 = Http(URL4).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+responseOrg2.code+", response.body: "+responseOrg2.body)
      assert(responseOrg2.code === HttpCode.DELETED.intValue || responseOrg2.code === HttpCode.NOT_FOUND.intValue)

      info("Creating new org with the same cloud_id")
      response = Http(URL4).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)
      orgsList+=orgid4

      info("Creating basic user in root org with hubAdmin=true and same userid as cloud user")
      val userInput = PostPutUsersRequest("", admin=false, hubAdmin=Some(true), "")
      response = Http(urlRootOrg+ "/users/" + iamUser).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)

      info("Cloud user who is now hubadmin should be able to access all orgs with apikey")
      response = Http(urlRoot + "/v1/orgs").headers(ACCEPT).headers(IAMAUTH("root")).asString
      info("GET " + urlRoot + "/v1/orgs" + " code: " + response.code)
      assert(response.code === HttpCode.OK.intValue)

      if (iamUIToken.nonEmpty && iamUserUIId.nonEmpty){
        // we can authenticate as a UI user
        info("Creating basic user in root org with hubAdmin=true and same userid as cloud user")
        response = Http(urlRootOrg+ "/users/" + iamUserUIId).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.PUT_OK.intValue)

        info("Cloud user who is now hubadmin should be able to access all orgs with token")
        response = Http(urlRoot + "/v1/orgs").headers(ACCEPT).headers(IAMUITOKENAUTH("root")).asString
        info("GET " + urlRoot + "/v1/orgs" + " code: " + response.code)
        assert(response.code === HttpCode.OK.intValue)
      } else info ("Skipping UI login tests 2")

      info("CLEANUP -- delete the test org")
      response = Http(URL3).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.DELETED.intValue)

      info("CLEANUP -- delete the second test org")
      response = Http(URL4).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.DELETED.intValue)

      info("CLEANUP -- delete the first hub admin")
      response = Http(urlRootOrg+ "/users/" + iamUser).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.DELETED.intValue)

      if (iamUIToken.nonEmpty && iamUserUIId.nonEmpty){
        info("CLEANUP -- delete the second hub admin if we made it")
        response = Http(urlRootOrg+ "/users/" + iamUserUIId).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.DELETED.intValue)
      }

    } else info("Skipping multitenancy pathway tests")
  }

  /** Clean up, delete all the test users */
  test("Cleanup 1 - DELETE all test users") {
    deleteAllUsers()
  }

  test("DELETE /orgs/root/users/" + hubadmin ) {
    val response = Http(urlRootOrg + "/users/" + hubadmin).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  /** Delete the orgs we used for this test */
  test("DELETE orgs") {
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
    response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)

    if (iamKey.nonEmpty && iamUser.nonEmpty && (ocpAccountId.nonEmpty || iamAccountId.nonEmpty)) {
      response = Http(CLOUDURL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.DELETED.intValue)
    }
  }

  test("Cleanup -- DELETE org changes") {
    for (org <- orgsList){
      val input = DeleteOrgChangesRequest(List())
      val response = Http(urlRoot+"/v1/orgs/"+org+"/changes/cleanup").postData(write(input)).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue)
    }
  }

}
