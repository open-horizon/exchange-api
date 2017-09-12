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
 * Tests for the /workloads routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class WorkloadsSuite extends FunSuite {

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val URL = urlRoot+"/v1"
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val user = "9999"
  val pw = user+"pw"
  val USERAUTH = ("Authorization","Basic "+user+":"+pw)
  val user2 = "10000"
  val pw2 = user2+"pw"
  val USER2AUTH = ("Authorization","Basic "+user2+":"+pw2)
  val rootuser = "root/root"
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "Horizon-Rul3s")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+rootuser+":"+rootpw)
  val deviceId = "9912"     // the 1st device created, that i will use to run some rest methods
  val deviceToken = deviceId+"tok"
  val DEVICEAUTH = ("Authorization","Basic "+deviceId+":"+deviceToken)
  val agbotId = "9947"
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+agbotId+":"+agbotToken)
  val wkBase = "wk9920"
  val wkUrl = "http://" + wkBase
  val workload = wkBase + "_1.0.0_arm"
  val wkBase2 = "wk9921"
  val wkUrl2 = "http://" + wkBase2
  val workload2 = wkBase2 + "_1.0.0_arm"
  val wkBase3 = "wk9922"
  val wkUrl3 = "http://" + wkBase3
  val workload3 = wkBase3 + "_1.0.0_arm"
  var numExistingWorkloads = 0    // this will be set later

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers() = {
    for (i <- List(user,user2)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the workloads, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    deleteAllUsers()
  }

  /** Add users, device, workload for future tests */
  test("Add users, device, workload for future tests") {
    var userInput = PutUsersRequest(pw, user+"@hotmail.com")
    var userResponse = Http(URL+"/users/"+user).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).asString    // Note: no AUTH
    info("code: "+userResponse.code+", userResponse.body: "+userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)

    userInput = PutUsersRequest(pw2, user2+"@hotmail.com")
    userResponse = Http(URL+"/users/"+user2).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).asString    // Note: no AUTH
    info("code: "+userResponse.code+", userResponse.body: "+userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK)

    val devInput = PutDevicesRequest(deviceToken, "bc dev test", List(RegMicroservice("foo",1,"{}",List(
      Prop("arch","arm","string","in"),
      Prop("version","2.0.0","version","in"),
      Prop("blockchainProtocols","agProto","list","in")))), "whisper-id", Map(), "DEVICEABC")
    val devResponse = Http(URL+"/devices/"+deviceId).postData(write(devInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+devResponse.code)
    assert(devResponse.code === HttpCode.PUT_OK)

    val agbotInput = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-norm", "whisper-id", "ABC")
    val agbotResponse = Http(URL+"/agbots/"+agbotId).postData(write(agbotInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+agbotResponse.code+", agbotResponse.body: "+agbotResponse.body)
    assert(agbotResponse.code === HttpCode.PUT_OK)
  }

  test("GET /workloads - get initial number of workloads in the db") {
    val response: HttpResponse[String] = Http(URL+"/workloads").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK || response.code === HttpCode.NOT_FOUND)
    val getWorkloadResp = parse(response.body).extract[GetWorkloadsResponse]
    numExistingWorkloads = getWorkloadResp.workloads.size
    info("initially "+numExistingWorkloads+" workloads")
  }

  test("PUT /workloads/"+workload+" - update WK that is not there yet - should fail") {
    // PostPutWorkloadRequest(label: String, description: String, workloadUrl: String, version: String, arch: String, downloadUrl: String, apiSpec: List[Map[String,String]], userInput: List[Map[String,String]], workloads: List[Map[String,String]]) {
    val input = PostPutWorkloadRequest(wkBase+" arm", "desc", wkUrl, "1.0.0", "arm", "updated", List(Map("specRef" -> "https://msurl")), List(Map("name" -> "foo")), List(Map("deployment" -> "{\"services\":{}}")))
    val response = Http(URL+"/workloads/"+workload).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  test("POST /workloads - add "+workload+" as user") {
    val input = PostPutWorkloadRequest(wkBase+" arm", "desc", wkUrl, "1.0.0", "arm", "", List(Map("specRef" -> "https://msurl")), List(Map("name" -> "foo")), List(Map("deployment" -> "{\"services\":{}}")))
    val response = Http(URL+"/workloads").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("workload '"+workload+"' created"))
  }

  test("POST /workloads - add "+workload+" again - should fail") {
    val input = PostPutWorkloadRequest(wkBase+" arm", "desc", wkUrl, "1.0.0", "arm", "", List(Map("specRef" -> "https://msurl")), List(Map("name" -> "foo")), List(Map("deployment" -> "{\"services\":{}}")))
    val response = Http(URL+"/workloads").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ALREADY_EXISTS)
  }

  test("PUT /workloads/"+workload+" - update as same user") {
    val input = PostPutWorkloadRequest(wkBase+" arm", "desc", wkUrl, "1.0.0", "arm", "updated", List(Map("specRef" -> "https://msurl")), List(Map("name" -> "foo")), List(Map("deployment" -> "{\"services\":{}}")))
    val response = Http(URL+"/workloads/"+workload).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PUT /workloads/"+workload+" - update as 2nd user - should fail") {
    val input = PostPutWorkloadRequest(wkBase+" arm", "desc", wkUrl, "1.0.0", "arm", "should not work", List(Map("specRef" -> "https://msurl")), List(Map("name" -> "foo")), List(Map("deployment" -> "{\"services\":{}}")))
    val response = Http(URL+"/workloads/"+workload).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /workloads/"+workload+" - update as agbot - should fail") {
    val input = PostPutWorkloadRequest(wkBase+" arm", "desc", wkUrl, "1.0.0", "arm", "", List(Map("specRef" -> "https://msurl")), List(Map("name" -> "foo")), List(Map("deployment" -> "{\"services\":{}}")))
    val response = Http(URL+"/workloads/"+workload).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("PUT /workloads/"+workload2+" - invalid workload body") {
    val badJsonInput = """{
      "labelxx": "GPS x86_64"
    }"""
    val response = Http(URL+"/workloads/"+workload2).postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)
  }

  test("POST /workloads - add "+workload2+" as device - should fail") {
    val input = PostPutWorkloadRequest(wkBase2+" arm", "desc", wkUrl2, "1.0.0", "arm", "", List(Map("specRef" -> "https://msurl")), List(Map("name" -> "foo")), List(Map("deployment" -> "{\"services\":{}}")))
    val response = Http(URL+"/workloads").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(DEVICEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("POST /workloads - add "+workload2+" as 2nd user") {
    val input = PostPutWorkloadRequest(wkBase2+" arm", "desc", wkUrl2, "1.0.0", "arm", "", List(Map("specRef" -> "https://msurl2")), List(Map("name" -> "foo")), List(Map("deployment" -> "{\"services\":{}}")))
    val response = Http(URL+"/workloads").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /*todo: when all test suites are run at the same time, there are sometimes timing problems them all setting config values...
  test("POST /workloads - with low maxWorkloads - should fail") {
    if (runningLocally) {     // changing limits via POST /admin/config does not work in multi-node mode
      // Get the current config value so we can restore it afterward
      ExchConfig.load()
      val origMaxWorkloads = ExchConfig.getInt("api.limits.maxWorkloads")

      // Change the maxWorkloads config value in the svr
      var configInput = AdminConfigRequest("api.limits.maxWorkloads", "0")    // user only owns 1 currently
      var response = Http(URL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)

      // Now try adding another workload - expect it to be rejected
      val input = PostPutWorkloadRequest(wkBase3+" arm", "desc", wkUrl3, "1.0.0", "arm", "", List(Map("specRef" -> "https://msurl")), List(Map("name" -> "foo")), List(Map("deployment" -> "{\"services\":{}}")))
      response = Http(URL+"/workloads").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.ACCESS_DENIED)
      val respObj = parse(response.body).extract[ApiResponse]
      assert(respObj.msg.contains("Access Denied"))

      // Restore the maxWorkloads config value in the svr
      configInput = AdminConfigRequest("api.limits.maxWorkloads", origMaxWorkloads.toString)
      response = Http(URL+"/admin/config").postData(write(configInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK)
    }
  }
  */

  test("GET /workloads") {
    val response: HttpResponse[String] = Http(URL+"/workloads").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetWorkloadsResponse]
    assert(respObj.workloads.size === 2 + numExistingWorkloads)

    assert(respObj.workloads.contains(workload))
    var wk = respObj.workloads.get(workload).get     // the 2nd get turns the Some(val) into val
    assert(wk.label === wkBase+" arm")
    assert(wk.owner === user)

    assert(respObj.workloads.contains(workload2))
    wk = respObj.workloads.get(workload2).get     // the 2nd get turns the Some(val) into val
    assert(wk.label === wkBase2+" arm")
    assert(wk.owner === user2)
  }

  test("GET /workloads - filter owner and workloadUrl") {
    val response: HttpResponse[String] = Http(URL+"/workloads").headers(ACCEPT).headers(USERAUTH).param("owner",user2).param("specRef","%msurl2").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetWorkloadsResponse]
    assert(respObj.workloads.size === 1)
    assert(respObj.workloads.contains(workload2))
  }

  test("GET /workloads - as device") {
    val response: HttpResponse[String] = Http(URL+"/workloads").headers(ACCEPT).headers(DEVICEAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetWorkloadsResponse]
    assert(respObj.workloads.size === 2 + numExistingWorkloads)
  }

  test("GET /workloads - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/workloads").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetWorkloadsResponse]
    assert(respObj.workloads.size === 2 + numExistingWorkloads)
  }

  test("GET /workloads/"+workload+" - as user") {
    val response: HttpResponse[String] = Http(URL+"/workloads/"+workload).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetWorkloadsResponse]
    assert(respObj.workloads.size === 1)

    assert(respObj.workloads.contains(workload))
    val wk = respObj.workloads.get(workload).get     // the 2nd get turns the Some(val) into val
    assert(wk.label === wkBase+" arm")

    // Verify the lastUpdated from the PUT above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastUp = ZonedDateTime.parse(wk.lastUpdated).toEpochSecond
    assert(now - lastUp <= 3)    // should not be more than 3 seconds from the time the put was done above
  }

  test("PATCH /workloads/"+workload+" - as user") {
    val jsonInput = """{
      "downloadUrl": "this is now patched"
    }"""
    val response = Http(URL+"/workloads/"+workload).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("PATCH /workloads/"+workload+" - as user2 - should fail") {
    val jsonInput = """{
      "downloadUrl": "this is now patched"
    }"""
    val response = Http(URL+"/workloads/"+workload).postData(jsonInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(USER2AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED)
  }

  test("GET /workloads/"+workload+" - as agbot, check patch by getting that 1 attr") {
    val response: HttpResponse[String] = Http(URL+"/workloads/"+workload).headers(ACCEPT).headers(AGBOTAUTH).param("attribute","downloadUrl").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val respObj = parse(response.body).extract[GetWorkloadAttributeResponse]
    assert(respObj.attribute === "downloadUrl")
    assert(respObj.value === "this is now patched")
  }

  test("GET /workloads/"+workload+"notthere - as user - should fail") {
    val response: HttpResponse[String] = Http(URL+"/workloads/"+workload+"notthere").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getWorkloadResp = parse(response.body).extract[GetWorkloadsResponse]
    assert(getWorkloadResp.workloads.size === 0)
  }

  test("DELETE /workloads/"+workload) {
    val response = Http(URL+"/workloads/"+workload).method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /workloads/"+workload+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/workloads/"+workload).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getWorkloadResp = parse(response.body).extract[GetWorkloadsResponse]
    assert(getWorkloadResp.workloads.size === 0)
  }

  test("DELETE /users/"+user2+" - which should also delete workload2") {
    val response = Http(URL+"/users/"+user2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  test("GET /workloads/"+workload2+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(URL+"/workloads/"+workload2).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
    val getWorkloadResp = parse(response.body).extract[GetWorkloadsResponse]
    assert(getWorkloadResp.workloads.size === 0)
  }

  /** Clean up, delete all the test workloads */
  test("Cleanup - DELETE all test workloads") {
    deleteAllUsers()
  }

}