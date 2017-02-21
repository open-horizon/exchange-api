package exchangeapi

import org.scalatest.FunSuite

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scalaj.http._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import com.horizon.exchangeapi._
import com.horizon.exchangeapi.tables._
import scala.collection.immutable._
import java.time._
import java.util.Base64
import java.nio.charset.StandardCharsets

/**
 * Tests for the /users routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class UsersSuite extends FunSuite {

  val urlRoot = sys.env.get("EXCHANGE_URL_ROOT").getOrElse("http://localhost:8080")
  val URL = urlRoot+"/v1"
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val user = "9970"
  val pw = user+"pw"
  val AUTH = ("Authorization","Basic "+user+":"+pw)
  val creds = user+":"+pw
  val encodedCreds = Base64.getEncoder().encodeToString(creds.getBytes("utf-8"))
  val ENCODEDAUTH = ("Authorization","Basic "+encodedCreds)
  var numExistingUsers = 0    // this will be set later
  val rootuser = "root"
  val rootpw = sys.env.get("EXCHANGE_ROOTPW").getOrElse("Horizon-Rul3s")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+rootuser+":"+rootpw)
  val CONNTIMEOUT = HttpOptions.connTimeout(20000)
  val READTIMEOUT = HttpOptions.readTimeout(20000)

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers = {
    for (i <- List(user)) {     // we do not delete the root user because it was created by the config file, not this test suite
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test users, in case they exist from a previous run */
  test("Begin - DELETE all test users") {
    deleteAllUsers
  }

  // Get the number of existing users so we can later check the number we added
  test("GET number of existing users") {
    val response: HttpResponse[String] = Http(URL+"/users").headers(ACCEPT).headers(ROOTAUTH).asString
    // info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    numExistingUsers = getUserResp.users.size
    info("Set number of existing users")
  }

  /** Try adding an invalid user body */
  test("POST /users/"+user+" - bad format") {
    val badJsonInput = """{
      "token": "foo",
      "email": "user9972-bad-format"
    }"""
    val response = Http(URL+"/users/"+user).postData(badJsonInput).method("post").headers(CONTENT).headers(ACCEPT).asString    // Note: no AUTH
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)     // for now this is what is returned when the json-to-scala conversion fails
  }

  /** Try adding a user not as anonymous */
  // test("POST /users/"+user+" - not anonymous") {
  //   val input = PutUsersRequest(pw, user+"@gmail.com")
  //   val response = Http(URL+"/users/"+user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
  //   info("code: "+response.code+", response.body: "+response.body)
  //   assert(response.code === HttpCode.NOT_FOUND)
  // }

  /** Try adding a user w/o email */
  test("POST /users/"+user+" - no email") {
    val input = PutUsersRequest(pw, "")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).asString    // Note: no AUTH
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  /** Add a normal user */
  test("POST /users/"+user+" - normal") {
    val input = PutUsersRequest(pw, user+"@hotmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).asString    // Note: no AUTH
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /** Update the normal user */
  test("PUT /users/"+user+" - update normal") {
    val input = PutUsersRequest(pw, user+"@msn.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Update the normal user - as root */
  test("PUT /users/"+user+" - update normal - as root") {
    val input = PutUsersRequest(pw, user+"@gmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  // We do not try to update root, because it is not a real user in the db

  /** Get all users (can only be done as root) */
  test("GET /users") {
    val response: HttpResponse[String] = Http(URL+"/users").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    // Both DevicesSuite and AgbotsSuite create 1 user, which may/may not be in numExistingUsers and may/may not exist now
    // assert(getUserResp.users.size === numExistingUsers + 1)

    assert(getUserResp.users.contains(user))
    var u = getUserResp.users.get(user).get     // the 2nd get turns the Some(val) into val
    assert(u.email === user+"@gmail.com")
  }

  test("GET /users - as "+user) {
    val response: HttpResponse[String] = Http(URL+"/users").headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("GET /users/"+user) {
    val response: HttpResponse[String] = Http(URL+"/users/"+user).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size === 1)

    assert(getUserResp.users.contains(user))
    var u = getUserResp.users.get(user).get     // the 2nd get turns the Some(val) into val
    assert(u.email === user+"@gmail.com")
  }

  /** Update the normal user with encoded creds */
  test("PUT /users/"+user+" - update normal with encoded creds") {
    val input = PutUsersRequest(pw, user+"-updated@gmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ENCODEDAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Verify the encoded update worked */
  test("GET /users/"+user+" - encoded creds") {
    val response: HttpResponse[String] = Http(URL+"/users/"+user).headers(ACCEPT).headers(ENCODEDAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.contains(user))
    var u = getUserResp.users.get(user).get     // the 2nd get turns the Some(val) into val
    assert(u.email === user+"-updated@gmail.com")
  }

  /** Confirm user/pw for user */
  test("POST /users/"+user+"/confirm") {
    val response = Http(URL+"/users/"+user+"/confirm").method("post").headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.OK)
  }

  /** Confirm encoded user/pw for user */
  test("POST /users/"+user+"/confirm - encoded") {
    info("encodedCreds="+encodedCreds+".")
    val response = Http(URL+"/users/"+user+"/confirm").method("post").headers(ACCEPT).headers(ENCODEDAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.OK)
  }

  /** Confirm user/pw for bad pw */
  test("POST /users/"+user+"/confirm - bad pw") {
    val response = Http(URL+"/users/"+user+"/confirm").method("post").headers(ACCEPT).headers(("Authorization","Basic "+user+":badpw")).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BADCREDS)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.BADCREDS)
  }

  /** Change the pw of user using a reset token, then confirm it, then set it back */
  test("POST /users/"+user+"/changepw") {
    // Get a pw reset token, as root
    var response = Http(URL+"/users/"+user+"/reset").method("post").headers(ACCEPT).headers(ROOTAUTH).option(CONNTIMEOUT).option(READTIMEOUT).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    var postResetResp = parse(response.body).extract[ResetPwResponse]

    // Change the pw with the token
    var TOKENAUTH = ("Authorization","Basic "+user+":"+postResetResp.token)
    val newpw = "new"+pw
    var input = ChangePwRequest(newpw)
    response = Http(URL+"/users/"+user+"/changepw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(TOKENAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    var postChangePwResp = parse(response.body).extract[ApiResponse]
    assert(postChangePwResp.code === ApiResponseType.OK)

    // Now confirm the new pw
    val NEWAUTH = ("Authorization","Basic "+user+":"+newpw)
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
    TOKENAUTH = ("Authorization","Basic "+user+":"+postResetResp.token)
    input = ChangePwRequest(pw)
    response = Http(URL+"/users/"+user+"/changepw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(TOKENAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    postChangePwResp = parse(response.body).extract[ApiResponse]
    assert(postChangePwResp.code === ApiResponseType.OK)
  }

  /** Request a reset token be emailed */
  test("POST /users/"+user+"/reset - request email") {
    // Change the email to one we do not mind spamming (and test a put with pw blank)
    val spamEmail = sys.env.get("EXCHANGE_EMAIL2").orNull
    if (spamEmail != null) {
      val input = PutUsersRequest("", spamEmail)
      var response = Http(URL+"/users/"+user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // verify the email was set and the pw was not
      response = Http(URL+"/users/"+user).headers(ACCEPT).headers(AUTH).asString
      info("code: "+response.code)
      // info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.OK)
      val getUserResp = parse(response.body).extract[GetUsersResponse]
      assert(getUserResp.users.size === 1)
      assert(getUserResp.users.contains(user))
      var u = getUserResp.users.get(user).get     // the 2nd get turns the Some(val) into val
      assert(u.email === spamEmail)

      response = Http(URL+"/users/"+user+"/reset").method("post").headers(ACCEPT).option(CONNTIMEOUT).option(READTIMEOUT).asString    // Note: no AUTH
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.POST_OK)
      val postResetResp = parse(response.body).extract[ApiResponse]
      assert(postResetResp.code === ApiResponseType.OK)
    } else info("NOTE: skipping pw reset email test because environment variable EXCHANGE_EMAIL2 is not set.")
  }

  /** Delete this user so we can recreate it with via root */
  test("DELETE /users/"+user) {
    val response = Http(URL+"/users/"+user).method("delete").headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  /** Create the normal user - as root */
  test("PUT /users/"+user+" - creat normal - as root") {
    val input = PutUsersRequest(pw, user+"@gmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /users/"+user+" - after root created it") {
    val response: HttpResponse[String] = Http(URL+"/users/"+user).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getUserResp = parse(response.body).extract[GetUsersResponse]
    assert(getUserResp.users.size === 1)

    assert(getUserResp.users.contains(user))
    var u = getUserResp.users.get(user).get     // the 2nd get turns the Some(val) into val
    assert(u.email === user+"@gmail.com")
  }

  /** Clean up, delete all the test users */
  test("Cleanup 1 - DELETE all test users") {
    deleteAllUsers
  }

}
