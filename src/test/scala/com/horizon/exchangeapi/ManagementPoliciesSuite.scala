package com.horizon.exchangeapi

import java.time._

import com.horizon.exchangeapi._
import com.horizon.exchangeapi.tables._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import scalaj.http._

import scala.collection.immutable._
import scala.collection.mutable.ListBuffer

/**
 * Tests for the /managementpolicies routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class ManagementPoliciesSuite extends AnyFunSuite {

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val ACCEPTTEXT = ("Accept","text/plain")
  val CONTENT = ("Content-Type","application/json")
  val CONTENTTEXT = ("Content-Type","text/plain")
  val orgid = "MgmtPolSuiteTests"
  val authpref=orgid+"/"
  val URL = urlRoot+"/v1/orgs/"+orgid

  val user = "mpuser"
  val orguser = authpref+user
  val pw = user+"pw"
  val USERAUTH = ("Authorization","Basic "+ApiUtils.encode(orguser+":"+pw))
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+ApiUtils.encode(rootuser+":"+rootpw))
  val managementPolicy = "mymgmtpol"
  val orgManagementPolicy = authpref+managementPolicy
  val ALL_VERSIONS = "[0.0.0,INFINITY)"
  val NOORGURL = urlRoot+"/v1"
  val orgsList = new ListBuffer[String]()

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers() = {
    for (i <- List(user)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    }
  }

  //~~~~~ Clean up from previous run, and create orgs, users ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("POST /orgs/"+orgid+" - create org") {
    // Try deleting it 1st, in case it is left over from previous test
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)

    var input = PostPutOrgRequest(None, "My Org", "desc", None, None, None)
    response = Http(URL).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    orgsList+=orgid
  }

  /** Delete all the test users, in case they exist from a previous run. Do not need to delete the mgmt policies, because they are deleted when the user is deleted. */
  test("Begin - DELETE all test users") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllUsers()
  }

  test("Add users for future tests") {
    var userInput = PostPutUsersRequest(pw, admin = false, Some(false), user + "@hotmail.com")
    var userResponse = Http(URL + "/users/" + user).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + userResponse.code + ", userResponse.body: " + userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK.intValue)
  }

  //~~~~~ Create and update management policies ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  //PostPutManagementPolicyRequest(label: String, description: Option[String], properties: Option[List[OneProperty]], constraints: Option[List[String]], patterns: Option[List[String]], enabled: Boolean, agentUpgradePolicy: Option[AgentUpgradePolicy] )

  test("POST /orgs/"+orgid+"/managementpolicies/"+managementPolicy+" - add "+managementPolicy+" as user") {
    val input = PostPutManagementPolicyRequest(managementPolicy, Some("desc"),
      Some(List(OneProperty("purpose",None,"location"))), Some(List("a == b")),
      None, true, None
    )
    val response = Http(URL+"/managementpolicies/"+managementPolicy).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("management policy '"+orgManagementPolicy+"' created"))
  }

  test("PUT /orgs/"+orgid+"/managementpolicies/"+managementPolicy+" - add "+managementPolicy+" as user") {
    val input = PostPutManagementPolicyRequest(managementPolicy, Some("desc"),
      Some(List(OneProperty("purpose",None,"location2"))), Some(List("a == c")),
      None, true, None
    )
    val response = Http(URL+"/managementpolicies/"+managementPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("management policy updated"))
  }

  //~~~~~ Get (verify) management policies ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("GET /orgs/"+orgid+"/managementpolicies") {
    val response: HttpResponse[String] = Http(URL+"/managementpolicies").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetManagementPoliciesResponse]
    assert(respObj.managementPolicy.size === 1)

    assert(respObj.managementPolicy.contains(orgManagementPolicy))
    var mp = respObj.managementPolicy(orgManagementPolicy)
    assert(mp.label === managementPolicy)
    assert(mp.description === "desc")
    assert(mp.owner === orguser)
    assert(mp.properties.head.name === "purpose")
    assert(mp.properties.head.value === "location2")
    assert(mp.constraints.head === "a == c")
  }

  test("GET /orgs/"+orgid+"/managementpolicies"+managementPolicy) {
    val response: HttpResponse[String] = Http(URL+"/managementpolicies/"+managementPolicy).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetManagementPoliciesResponse]
    assert(respObj.managementPolicy.size === 1)

    assert(respObj.managementPolicy.contains(orgManagementPolicy))
    var mp = respObj.managementPolicy(orgManagementPolicy)
    assert(mp.label === managementPolicy)
    assert(mp.description === "desc")
    assert(mp.owner === orguser)
    assert(mp.properties.head.name === "purpose")
    assert(mp.properties.head.value === "location2")
    assert(mp.constraints.head === "a == c")
  }

  //~~~~~ Clean up ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("Cleanup - DELETE all test users, which will delete all management policies") {
    deleteAllUsers()
  }

  /** Delete the orgs we used for this test */
  test("DELETE orgs") {
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
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
