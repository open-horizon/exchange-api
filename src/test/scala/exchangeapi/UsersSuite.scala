package exchangeapi

import com.horizon.exchangeapi.tables.APattern
import org.scalatest.FunSuite
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scalaj.http._
import org.json4s._
//import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import com.horizon.exchangeapi._
//import com.horizon.exchangeapi.tables._
import scala.collection.immutable._
//import java.time._
import java.util.Base64
//import java.nio.charset.StandardCharsets

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
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val orgid = "UsersSuiteTests"
  val orgid2 = "UsersSuiteTests2"
  val URL = urlRoot+"/v1/orgs/"+orgid
  val URL2 = urlRoot+"/v1/orgs/"+orgid2
  val URLPUBLIC = urlRoot+"/v1/orgs/public"
  val NOORGURL = urlRoot+"/v1"
  val user = "u1"       // this is an admin user
  val orguser = orgid+"/"+user
  val org2user = orgid2+"/"+user
  val pw = user+"pw"
  val creds = orguser+":"+pw
  val USERAUTH = ("Authorization","Basic "+creds)
  val ORG2USERAUTH = ("Authorization","Basic "+org2user+":"+pw)
  val encodedCreds = Base64.getEncoder.encodeToString(creds.getBytes("utf-8"))
  val ENCODEDAUTH = ("Authorization","Basic "+encodedCreds)
  val user2 = "u2"       // this is NOT an admin user
  val orguser2 = orgid+"/"+user2
  val pw2 = user2+"pw"
  val creds2 = orguser2+":"+pw2
  val USERAUTH2 = ("Authorization","Basic "+creds2)
  val user3 = "u3"
  val pw3 = user3+"pw"
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this same root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+rootuser+":"+rootpw)
  val CONNTIMEOUT = HttpOptions.connTimeout(20000)
  val READTIMEOUT = HttpOptions.readTimeout(20000)
  val msBase = "ms"
  val msUrl = "http://" + msBase
  val microservice = msBase + "_1.0.0_arm"
  val wkBase = "work"
  val wkUrl = "http://" + wkBase
  val workload = wkBase + "_1.0.0_arm"
  val ptBase = "pat"
  val ptUrl = "http://" + ptBase
  val pattern = ptBase + "_1.0.0_arm"
  val agbotId = "a1"
  val agbotToken = agbotId+"tok"

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users in both orgs */
  def deleteAllUsers() = {
    for (i <- List(user, user2)) {     // we do not delete the root user because it was created by the config file, not this test suite
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
    for (i <- List(user)) {
      val response = Http(URL2+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
    for (i <- List(user)) {
      val response = Http(URLPUBLIC+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
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
    response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)

    // Try adding an invalid org body
    val badJsonInput = """{
      "labelx": "foo",
      "description": "desc"
    }"""
    response = Http(URL).postData(badJsonInput).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)     // for now this is what is returned when the json-to-scala conversion fails

    // Now add a good org
    var input = PostPutOrgRequest("My Org", "desc")
    response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)

    // Update the org
    input = PostPutOrgRequest("My Org", "desc - updated")
    response = Http(URL).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)

    // Patch the org
    val jsonInput = """{ "description": "desc - patched" }"""
    response = Http(URL).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)

    // Add a 2nd org
    val input2 = PostPutOrgRequest("My Other Org", "desc")
    response = Http(URL2).postData(write(input2)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)

    // Get all orgs
    response = Http(NOORGURL+"/orgs").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    var getOrgsResp = parse(response.body).extract[GetOrgsResponse]
    //assert(getOrgsResp.orgs.size === 2)      // <- there might be other orgs
    assert(getOrgsResp.orgs.contains(orgid))
    assert(getOrgsResp.orgs.contains(orgid2))

    // Get this org
    response = Http(URL).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    getOrgsResp = parse(response.body).extract[GetOrgsResponse]
    assert(getOrgsResp.orgs.size === 1)
    assert(getOrgsResp.orgs.contains(orgid))
    val o = getOrgsResp.orgs.get(orgid).get // the 2nd get turns the Some(val) into val
    assert(o.description === "desc - patched")
  }

  /** Delete all the test users, in case they exist from a previous run */
  test("DELETE all test users") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllUsers()
  }

  /** Try adding an invalid user body */
  test("POST /orgs/"+orgid+"/users/"+user+" - bad format") {
    val badJsonInput = """{
      "token": "foo",
      "email": "user9972-bad-format"
    }"""
    val response = Http(URL+"/users/"+user).postData(badJsonInput).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)     // for now this is what is returned when the json-to-scala conversion fails
  }

  /** Try adding a user not as anonymous - not supported anymore */
  // test("POST /orgs/"+orgid+"/users/"+user+" - not anonymous") {
  //   val input = PutUsersRequest(pw, user+"@gmail.com")
  //   val response = Http(URL+"/users/"+user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
  //   info("code: "+response.code+", response.body: "+response.body)
  //   assert(response.code === HttpCode.NOT_FOUND)
  // }

  test("POST /orgs/"+orgid+"/users/"+user+" - no email - should fail") {
    val input = PostPutUsersRequest(pw, true, "")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  /** Add a normal user */
  test("POST /orgs/"+orgid+"/users/"+user+" - normal") {
    val input = PostPutUsersRequest(pw, false, user+"@hotmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("PUT /orgs/"+orgid+"/users/"+user+" - update his own email") {
    val input = PostPutUsersRequest(pw, false, user+"@gmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+" - even w/o admin "+user+" should be able to read his own org") {
    val response: HttpResponse[String] = Http(URL).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
  }

  test("PUT /orgs/"+orgid+"/users/"+user+" - try to himself admin privilege - should fail") {
    val input = PostPutUsersRequest(pw, true, user+"@msn.com")
    var response = Http(URL+"/users/"+user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)

    // Try to do it via patch
    val jsonInput = """{ "admin": true }"""
    response = Http(URL+"/users/"+user).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("PATCH /orgs/"+orgid+"/users/"+user+" - give user admin privilege - as root") {
    val jsonInput = """{ "admin": true }"""
    val response = Http(URL+"/users/"+user).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs - even with admin "+user+" should NOT be able to read all orgs") {
    val response: HttpResponse[String] = Http(NOORGURL+"/orgs").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("GET /orgs/"+orgid+"/users - as admin user") {
    val response: HttpResponse[String] = Http(URL+"/users").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size === 1)

    assert(getUserResp.users.contains(orguser))
    val u = getUserResp.users.get(orguser).get // the 2nd get turns the Some(val) into val
    assert(u.email === user+"@gmail.com")
  }

  test("GET /orgs/"+orgid+"/users - as admin "+user) {
    val response: HttpResponse[String] = Http(URL+"/users").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    //assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("GET /orgs/"+orgid+"/users/"+user) {
    val response: HttpResponse[String] = Http(URL+"/users/"+user).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size === 1)

    assert(getUserResp.users.contains(orguser))
    val u = getUserResp.users.get(orguser).get // the 2nd get turns the Some(val) into val
    assert(u.email === user+"@gmail.com")
  }

  /** Update the normal user with encoded creds */
  test("PUT /orgs/"+orgid+"/users/"+user+" - update normal with encoded creds") {
    val input = PostPutUsersRequest(pw, true, user+"-updated@gmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ENCODEDAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("POST /orgs/"+orgid+"/users/"+user2+" - as admin user") {
    val input = PostPutUsersRequest(pw2, false, user2+"@hotmail.com")
    val response = Http(URL+"/users/"+user2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("GET /orgs/"+orgid+"/users/"+user2+" - verify 2nd user was created") {
    val response: HttpResponse[String] = Http(URL+"/users/"+user2).headers(ACCEPT).headers(USERAUTH2).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size === 1)
    assert(getUserResp.users.contains(orguser2))
  }

  test("POST /orgs/"+orgid+"/users/"+user3+" - as non-admin user - should fail") {
    val input = PostPutUsersRequest(pw3, false, user3+"@hotmail.com")
    val response = Http(URL+"/users/"+user3).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH2).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  /** Verify the encoded update worked */
  test("GET /orgs/"+orgid+"/users/"+user+" - encoded creds") {
    val response: HttpResponse[String] = Http(URL+"/users/"+user).headers(ACCEPT).headers(ENCODEDAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.contains(orguser))
    val u = getUserResp.users.get(orguser).get // the 2nd get turns the Some(val) into val
    assert(u.email === user+"-updated@gmail.com")
  }

  /** Confirm user/pw for user */
  test("POST /orgs/"+orgid+"/users/"+user+"/confirm") {
    val response = Http(URL+"/users/"+user+"/confirm").method("post").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.OK)
  }

  /** Confirm encoded user/pw for user */
  test("POST /orgs/"+orgid+"/users/"+user+"/confirm - encoded") {
    info("encodedCreds="+encodedCreds+".")
    val response = Http(URL+"/users/"+user+"/confirm").method("post").headers(ACCEPT).headers(ENCODEDAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.OK)
  }

  /** Confirm user/pw for bad pw */
  test("POST /orgs/"+orgid+"/users/"+user+"/confirm - bad pw") {
    val response = Http(URL+"/users/"+user+"/confirm").method("post").headers(ACCEPT).headers(("Authorization","Basic "+user+":badpw")).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BADCREDS)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.BADCREDS)
  }

  /** Change the pw of user using a reset token, then confirm it, then set it back */
  test("POST /orgs/"+orgid+"/users/"+user+"/changepw") {
    // Get a pw reset token, as root
    var response = Http(URL+"/users/"+user+"/reset").method("post").headers(ACCEPT).headers(ROOTAUTH).option(CONNTIMEOUT).option(READTIMEOUT).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    var postResetResp = parse(response.body).extract[ResetPwResponse]

    // Change the pw with the token
    var TOKENAUTH = ("Authorization","Basic "+orguser+":"+postResetResp.token)
    val newpw = "new"+pw
    var input = ChangePwRequest(newpw)
    response = Http(URL+"/users/"+user+"/changepw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(TOKENAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    var postChangePwResp = parse(response.body).extract[ApiResponse]
    assert(postChangePwResp.code === ApiResponseType.OK)

    // Now confirm the new pw
    val NEWAUTH = ("Authorization","Basic "+orguser+":"+newpw)
    response = Http(URL+"/users/"+user+"/confirm").method("post").headers(ACCEPT).headers(NEWAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.OK)

    // Need another pw reset token, since the user's pw is different
    response = Http(URL+"/users/"+user+"/reset").method("post").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    postResetResp = parse(response.body).extract[ResetPwResponse]

    // Now set the pw back to original so it can be used for the rest of the tests
    TOKENAUTH = ("Authorization","Basic "+orguser+":"+postResetResp.token)
    input = ChangePwRequest(pw)
    response = Http(URL+"/users/"+user+"/changepw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(TOKENAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    postChangePwResp = parse(response.body).extract[ApiResponse]
    assert(postChangePwResp.code === ApiResponseType.OK)
  }

  /** Request a reset token be emailed */
  test("POST /orgs/"+orgid+"/users/"+user+"/reset - request email") {
    // Change the email to one we do not mind spamming (and test a put with pw blank)
    val spamEmail = sys.env.get("EXCHANGE_EMAIL2").orNull
    if (spamEmail != null) {
      val jsonInput = """{ "email": """"+spamEmail+"""" }"""
      var response = Http(URL+"/users/"+user).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // verify the email was set and the pw was not
      response = Http(URL+"/users/"+user).headers(ACCEPT).headers(USERAUTH).asString
      info("code: "+response.code)
      // info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.OK)
      val getUserResp = parse(response.body).extract[GetUsersResponse]
      assert(getUserResp.users.size === 1)
      assert(getUserResp.users.contains(orguser))
      val u = getUserResp.users.get(orguser).get // the 2nd get turns the Some(val) into val
      assert(u.email === spamEmail)

      /* Reset as anonymous does not work with orgs...
      response = Http(URL+"/users/"+user+"/reset").method("post").headers(ACCEPT).option(CONNTIMEOUT).option(READTIMEOUT).asString    // Note: no AUTH
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.POST_OK)
      val postResetResp = parse(response.body).extract[ApiResponse]
      assert(postResetResp.code === ApiResponseType.OK)
      */
    } else info("NOTE: skipping pw reset email test because environment variable EXCHANGE_EMAIL2 is not set.")
  }

  /** Delete this user so we can recreate it via root with put */
  test("DELETE /orgs/"+orgid+"/users/"+user) {
    val response = Http(URL+"/users/"+user).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  /** Create the normal user - as root */
  test("PUT /orgs/"+orgid+"/users/"+user+" - create normal - as root") {
    val input = PostPutUsersRequest(pw, true, user+"@gmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/users/"+user+" - after root created it") {
    val response: HttpResponse[String] = Http(URL+"/users/"+user).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size === 1)

    assert(getUserResp.users.contains(orguser))
    val u = getUserResp.users.get(orguser).get // the 2nd get turns the Some(val) into val
    assert(u.email === user+"@gmail.com")
  }

  // Anonymous creates
  test("POST /orgs/"+orgid+"/users/"+user3+" - as anonymous - should fail") {
    val input = PostPutUsersRequest(pw3, false, user3+"@hotmail.com")
    val response = Http(URL+"/users/"+user3).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).asString    // as anonymous
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("POST /orgs/public/users/"+user+" - create admin user as anonymous - should fail") {
    val input = PostPutUsersRequest(pw, true, user+"@hotmail.com")
    val response = Http(NOORGURL+"/orgs/public/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).asString    // as anonymous
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /orgs/public/users/"+user+" - create non-admin user as anonymous") {
    val input = PostPutUsersRequest(pw, false, user+"@hotmail.com")
    val response = Http(NOORGURL+"/orgs/public/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).asString    // as anonymous
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("GET /orgs/public/users/"+user+" - after anonymous created it") {
    val response: HttpResponse[String] = Http(NOORGURL+"/orgs/public/users/"+user).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size === 1)
  }


  /** Add a normal agbot */
  test("PUT /orgs/"+orgid+"/agbots/"+agbotId+" - verify we can add an agbot as root") {
    val input = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-normal", List[APattern](APattern(orgid,"mypattern")), "whisper-id", "ABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }


  test("POST /orgs/"+orgid2+"/users/"+user+" - create user in org2 so we can test cross-org ACLs") {
    val input = PostPutUsersRequest(pw, true, user+"@hotmail.com")
    val response = Http(URL2+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }


  test("POST /orgs/"+orgid+"/microservices - add "+microservice+" as not public in 1st org") {
    val input = PostPutMicroserviceRequest(msBase+" arm", "desc", false, msUrl, "1.0.0", "arm", "single", "", Map(), List(), List())
    val response = Http(URL+"/microservices").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("GET /orgs/"+orgid+"/microservices - as org2 user - should find no public microservices") {
    val response: HttpResponse[String] = Http(URL + "/microservices").headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("GET /orgs/"+orgid+"/microservices/"+microservice+" - as org2 user - should fail") {
    val response: HttpResponse[String] = Http(URL + "/microservices/" + microservice).headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PATCH /orgs/"+orgid+"/microservices/"+microservice+" - to make it public") {
    val jsonInput = """{ "public": true }"""
    val response = Http(URL+"/microservices/"+microservice).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/microservices - as org2 user - this time it should find 1") {
    val response: HttpResponse[String] = Http(URL + "/microservices").headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetMicroservicesResponse]
    assert(respObj.microservices.size === 1)
  }

  test("GET /orgs/"+orgid+"/microservices/"+microservice+" - as org2 user - this time it should work") {
    val response: HttpResponse[String] = Http(URL + "/microservices/" + microservice).headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetMicroservicesResponse]
    assert(respObj.microservices.size === 1)
  }


  test("POST /orgs/"+orgid+"/workloads - add "+workload+" as not public in 1st org") {
    val input = PostPutWorkloadRequest(wkBase+" arm", "desc", false, wkUrl, "1.0.0", "arm", "", List(), List(), List())
    val response = Http(URL+"/workloads").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("GET /orgs/"+orgid+"/workloads - as org2 user - should find no public workloads") {
    val response: HttpResponse[String] = Http(URL + "/workloads").headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("GET /orgs/"+orgid+"/workloads/"+workload+" - as org2 user - should fail") {
    val response: HttpResponse[String] = Http(URL + "/workloads/" + workload).headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PATCH /orgs/"+orgid+"/workloads/"+workload+" - to make it public") {
    val jsonInput = """{ "public": true }"""
    val response = Http(URL+"/workloads/"+workload).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/workloads - as org2 user - this time it should find 1") {
    val response: HttpResponse[String] = Http(URL + "/workloads").headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetWorkloadsResponse]
    assert(respObj.workloads.size === 1)
  }

  test("GET /orgs/"+orgid+"/workloads/"+workload+" - as org2 user - this time it should work") {
    val response: HttpResponse[String] = Http(URL + "/workloads/" + workload).headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetWorkloadsResponse]
    assert(respObj.workloads.size === 1)
  }


  test("POST /orgs/"+orgid+"/patterns/"+pattern+" - add "+pattern+" as not public in 1st org") {
    val input = PostPutPatternRequest("Pattern", "desc", false, List(), List[Map[String,String]]() )
    val response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  test("GET /orgs/"+orgid+"/patterns - as org2 user - should find no public patterns") {
    val response: HttpResponse[String] = Http(URL + "/patterns").headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+" - as org2 user - should fail") {
    val response: HttpResponse[String] = Http(URL + "/patterns/" + pattern).headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PATCH /orgs/"+orgid+"/patterns/"+pattern+" - to make it public") {
    val jsonInput = """{ "public": true }"""
    val response = Http(URL+"/patterns/"+pattern).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /orgs/"+orgid+"/patterns - as org2 user - this time it should find 1") {
    val response: HttpResponse[String] = Http(URL + "/patterns").headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 1)
  }

  test("GET /orgs/"+orgid+"/patterns/"+pattern+" - as org2 user - this time it should work") {
    val response: HttpResponse[String] = Http(URL + "/patterns/" + pattern).headers(ACCEPT).headers(ORG2USERAUTH).asString
    info("code: " + response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    assert(respObj.patterns.size === 1)
  }


  /** Clean up, delete all the test users */
  test("Cleanup 1 - DELETE all test users") {
    deleteAllUsers()
  }

  /** Delete the orgs we used for this test */
  test("DELETE /orgs/"+orgid+" - delete orgs") {
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
    response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

}
