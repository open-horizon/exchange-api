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
import scala.collection.immutable._
import java.time._

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

  val urlRoot = sys.env.get("EXCHANGE_URL_ROOT").getOrElse("http://localhost:8080")
  val URL = urlRoot+"/v1"
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val user = "9990"
  val pw = user+"pw"
  val AUTH = ("Authorization","Basic "+user+":"+pw)
  val BADAUTH = ("Authorization","Basic "+user+":"+pw+"x")
  val rootuser = "root"
  val rootpw = sys.env.get("EXCHANGE_ROOTPW").getOrElse("Horizon-Rul3s")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+rootuser+":"+rootpw)
  val agbotId = "9930"
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+agbotId+":"+agbotToken)
  val agbot2Id = "9931"
  val agbot2Token = agbot2Id+"tok"
  val AGBOT2AUTH = ("Authorization","Basic "+agbot2Id+":"+agbot2Token)
  val agreementId = "9950"

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers = {
    for (i <- List(user)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED)
    }
  }

  /** Delete all the test agbots - this is not longer used because deleting the user deletes these too */
  def deleteAllAgbots = {
    for (i <- List(agbotId, agbot2Id)) {
      val response = Http(URL+"/agbots/"+i).method("delete").headers(ACCEPT).headers(AUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test agreements - this is no longer used because deleting the user deletes these too */
  def deleteAllAgreements = {
    for (i <- List(agreementId)) {
      val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+i).method("delete").headers(ACCEPT).headers(AUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the agbots and
   *  agreements, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    deleteAllUsers
  }

  /** Add a normal user */
  test("POST /users/"+user+" - normal") {
    val input = PutUsersRequest(pw, user+"@hotmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).asString    // Note: no AUTH
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /** Add a normal agbot */
  test("PUT /agbots/"+agbotId+" - normal") {
    val input = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-norm", "whisper-id", "ABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Update normal agbot as user */
  test("PUT /agbots/"+agbotId+" - normal - as user") {
    val input = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-normal-user", "whisper-id", "ABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Update the agbot as the agbot */
  test("PUT /agbots/"+agbotId+" - normal - as agbot") {
    val input = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-normal", "whisper-id", "ABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Try adding an invalid agbot body */
  test("PUT /agbots/9932 - bad format") {
    val badJsonInput = """{
      "token": "foo",
      "xname": "agbot9932-bad-format",
      "msgEndPoint": "whisper-id"
    }"""
    val response = Http(URL+"/agbots/9932").postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)     // for now this is what is returned when the json-to-scala conversion fails
  }

  /** Try adding aa agbot with bad creds */
  test("PUT /agbots/9932 - bad creds") {
    val input = PutAgbotsRequest("mytok", "agbot9932-badcreds", "whisper-id", "ABC")
    val response = Http(URL+"/agbots/9932").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(BADAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BADCREDS)
  }

  test("GET /agbots") {
    val response: HttpResponse[String] = Http(URL+"/agbots").headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getAgbotResp = parse(response.body).extract[GetAgbotsResponse]
    // assert(getAgbotResp.agbots.size === 1)   // since the other test suites are creating some of these too, we can not know how many there are right now

    assert(getAgbotResp.agbots.contains(agbotId))
    var dev = getAgbotResp.agbots.get(agbotId).get     // the 2nd get turns the Some(val) into val
    assert(dev.name === "agbot"+agbotId+"-normal")

    info("GET /agbots output verified")
  }

  test("GET /agbots - filter owner, idfilter, and name") {
    val response: HttpResponse[String] = Http(URL+"/agbots").headers(ACCEPT).headers(AUTH).param("owner",user).param("idfilter","993%").param("name","agbot993%-normal").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getAgbotResp = parse(response.body).extract[GetAgbotsResponse]
    assert(getAgbotResp.agbots.size === 1)
    assert(getAgbotResp.agbots.contains(agbotId))
  }

  /** Heartbeat for agbot 9930 */
  test("POST /agbots/"+agbotId+"/heartbeat") {
    val response = Http(URL+"/agbots/"+agbotId+"/heartbeat").method("post").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postHeartbeatResp = parse(response.body).extract[ApiResponse]
    assert(postHeartbeatResp.code === ApiResponseType.OK)
  }

  test("GET /agbots/"+agbotId) {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getAgbotResp = parse(response.body).extract[GetAgbotsResponse]
    assert(getAgbotResp.agbots.size === 1)

    assert(getAgbotResp.agbots.contains(agbotId))
    var agbot = getAgbotResp.agbots.get(agbotId).get     // the 2nd get turns the Some(val) into val
    assert(agbot.name === "agbot"+agbotId+"-normal")

    // Verify the lastHeartbeat from the POST heartbeat above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastHb = ZonedDateTime.parse(agbot.lastHeartbeat).toEpochSecond
    assert(now - lastHb <= 3)    // should not now be more than 3 seconds from the time the heartbeat was done above

    info("GET /agbots output verified")
  }

  /** Update 1 attr of the agbot as the agbot */
  test("PATCH /agbots/"+agbotId+" - as agbot") {
    val jsonInput = """{
      "publicKey": "newAGBOTABCDEF"
    }"""
    val response = Http(URL+"/agbots/"+agbotId).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /agbots/"+agbotId+" - as agbot, check patch by getting that 1 attr") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"?attribute=publicKey").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getAgbotResp = parse(response.body).extract[GetAgbotAttributeResponse]
    assert(getAgbotResp.attribute === "publicKey")
    assert(getAgbotResp.value === "newAGBOTABCDEF")
}

  /** Try to confirm the agreement that's not there for agbot 9930 */
  test("POST /agreements/confirm - not there") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.NOT_FOUND)
  }

  /** Try to confirm the agreement that's not there for agbot 9930, as user */
  test("POST /agreements/confirm - not there - as user") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.NOT_FOUND)
  }

  /** Add agbot2 */
  test("PUT /agbots/"+agbot2Id+" - normal") {
    val input = PutAgbotsRequest(agbot2Token, "agbot"+agbot2Id+"-norm", "whisper-id", "ABC")
    val response = Http(URL+"/agbots/"+agbot2Id).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Try to confirm the agreement that's not there for agbot 9930, as agbot2 */
  test("POST /agreements/confirm - not there - as agbot2") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.NOT_FOUND)
  }

  /** Add an agreement for agbot 9930 - as the agbot */
  test("PUT /agbots/"+agbotId+"/agreements/"+agreementId+" - as agbot") {
    val input = PutAgbotAgreementRequest("sdr", "signed")
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Update the agreement for agbot 9930 - as user */
  test("PUT /agbots/"+agbotId+"/agreements/"+agreementId+" - as user") {
    val input = PutAgbotAgreementRequest("sdr", "negotiating")
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Add a 2nd agreement for agbot 9930 - as the agbot */
  test("PUT /agbots/"+agbotId+"/agreements/9951 - 2nd agreement as agbot") {
    val input = PutAgbotAgreementRequest("netspeed", "signed")
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/9951").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /agbots/"+agbotId+"/agreements - verify agbot agreement") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements").headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 2)

    assert(getAgResp.agreements.contains(agreementId))
    var ag = getAgResp.agreements.get(agreementId).get     // the 2nd get turns the Some(val) into val
    assert(ag.workload === "sdr")
    assert(ag.state === "negotiating")
    assert(getAgResp.agreements.contains("9951"))

    info("GET /agbots/"+agbotId+"/agreements output verified")
  }

  test("GET /agbots/"+agbotId+"/agreements/"+agreementId) {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 1)

    assert(getAgResp.agreements.contains(agreementId))
    var ag = getAgResp.agreements.get(agreementId).get     // the 2nd get turns the Some(val) into val
    assert(ag.workload === "sdr")
    assert(ag.state === "negotiating")

    info("GET /agbots/"+agbotId+"/agreements/"+agreementId+" output verified")
  }

  test("GET /agbots/"+agbotId+"/agreements/"+agreementId+" - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 1)
  }

  /** Confirm the agreement for agbot 9930 */
  test("POST /agreements/confirm") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.OK)
  }

  /** Confirm the agreement for agbot 9930 */
  test("POST /agreements/confirm - as user") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.OK)
  }

  /** Confirm the agreement for agbot 9930 */
  test("POST /agreements/confirm - as agbot2") {
    val input = PostAgreementsConfirmRequest(agreementId)
    val response = Http(URL+"/agreements/confirm").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(AGBOT2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postConfirmResp = parse(response.body).extract[ApiResponse]
    assert(postConfirmResp.code === ApiResponseType.OK)
  }

  /** Delete the 1st agreement for agbot 9930 */
  test("DELETE /agbots/"+agbotId+"/agreements/"+agreementId+" - as agbot") {
    val response = Http(URL+"/agbots/"+agbotId+"/agreements/"+agreementId).method("delete").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("DELETE "+agreementId+", code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  /** Confirm 1 agreement was deleted */
  test("GET /agbots/"+agbotId+"/agreements - as agbot, confirm 1 gone") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 1)
  }

  /** Delete the all agreements for agbot 9930 */
  test("DELETE /agbots/"+agbotId+"/agreements - as agbot") {
    val response = Http(URL+"/agbots/"+agbotId+"/agreements").method("delete").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("DELETE "+agreementId+", code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  /** Confirm all agreements were deleted */
  test("GET /agbots/"+agbotId+"/agreements - as agbot, confirm all gone") {
    val response: HttpResponse[String] = Http(URL+"/agbots/"+agbotId+"/agreements").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getAgResp = parse(response.body).extract[GetAgbotAgreementsResponse]
    assert(getAgResp.agreements.size === 0)
  }

  // Note: testing of msgs is in DevicesSuite.scala

  /** Clean up, delete all the test agbots */
  test("Cleanup - DELETE all test agbots and agreements") {
    // deleteAllAgreements   <- these now get deleted when the user is deleted
    // deleteAllAgbots
    deleteAllUsers
  }

}