package com.horizon.exchangeapi

<<<<<<< HEAD
import java.time._

import com.horizon.exchangeapi._
import com.horizon.exchangeapi.tables._
import org.json4s._
import org.json4s.jackson.JsonMethods._
=======
import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Role, TestDBConnection}
import com.horizon.exchangeapi.tables.{ManagementPolicy, NodeRow, NodesTQ, OneProperty, OrgRow, OrgsTQ, RegService, ResourceChangesTQ, UserRow}
import org.json4s.jackson.JsonMethods.parse
import org.json4s.{DefaultFormats, Formats, JValue, JsonInput, jvalue2extractable, string2JsonInput}
>>>>>>> b2bb2e9 (Issue-556: Changed the DB schema for NMPs. Changed the syntax of table queries to be simpler.)
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
<<<<<<< HEAD
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val ACCEPTTEXT = ("Accept","text/plain")
  val CONTENT = ("Content-Type","application/json")
  val CONTENTTEXT = ("Content-Type","text/plain")
  val orgid = "MgmtPolSuiteTests"
  val authpref=orgid+"/"
  val URL = urlRoot+"/v1/orgs/"+orgid
=======
  val urlRoot: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally: Boolean = (urlRoot == localUrlRoot)
  val ACCEPT: (String, String) = ("Accept","application/json")
  val ACCEPTTEXT: (String, String) = ("Accept","text/plain")
  val CONTENT: (String, String) = ("Content-Type","application/json")
  val CONTENTTEXT: (String, String) = ("Content-Type","text/plain")
  val orgid = "MgmtPolSuite"
  val authpref: String = orgid + "/"
  val URL: String = urlRoot + "/v1/orgs/" + orgid
>>>>>>> b2bb2e9 (Issue-556: Changed the DB schema for NMPs. Changed the syntax of table queries to be simpler.)

  val user = "mpuser"
  val orguser: String = authpref + user
  val pw: String = user + "pw"
  val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(orguser + ":" + pw))
  val rootuser: String = Role.superUser
  val rootpw: String = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this root pw in config.json
  val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(rootuser + ":" + rootpw))
  val managementPolicy = "mymgmtpol"
  val orgManagementPolicy: String = authpref + managementPolicy
  val ALL_VERSIONS = "[0.0.0,INFINITY)"
  val NOORGURL: String = urlRoot + "/v1"
  val orgsList = new ListBuffer[String]()

<<<<<<< HEAD
  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Delete all the test users */
  def deleteAllUsers() = {
    for (i <- List(user)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    }
=======
  implicit val formats: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.
  
  private val AWAITDURATION: Duration = 15.seconds
  
  private val TESTORGANIZATION: OrgRow =
    OrgRow(heartbeatIntervals = "",
           description        = "",
           label              = "",
           lastUpdated        = ApiTime.nowUTC,
           orgId              = "MgmtPolSuite",
           orgType            = "",
           tags               = None,
           limits             = "")
  
  // Begin building testing harness.
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ += TESTORGANIZATION)), AWAITDURATION)
  }
  
  // Teardown testing harness and cleanup.
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "MgmtPolSuite").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "MgmtPolSuite").delete), AWAITDURATION)
    
    DBCONNECTION.getDb.close()
  }
  
  // Nodes that are dynamically needed, specific to the test case.
  def fixtureNodes(testCode: Seq[NodeRow] => Any, testData: Seq[NodeRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(NodesTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(NodesTQ.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
>>>>>>> b2bb2e9 (Issue-556: Changed the DB schema for NMPs. Changed the syntax of table queries to be simpler.)
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
    var userInput: PostPutUsersRequest = PostPutUsersRequest(pw, admin = false, Option(false), user + "@hotmail.com")
    var userResponse: HttpResponse[String] = Http(URL + "/users/" + user).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + userResponse.code + ", userResponse.body: " + userResponse.body)
    assert(userResponse.code === HttpCode.POST_OK.intValue)
  }

  //~~~~~ Create and update management policies ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  //PostPutManagementPolicyRequest(label: String, description: Option[String], properties: Option[List[OneProperty]], constraints: Option[List[String]], patterns: Option[List[String]], enabled: Boolean, agentUpgradePolicy: Option[AgentUpgradePolicy] )

  test("POST /orgs/"+orgid+"/managementpolicies/"+managementPolicy+" - add "+managementPolicy+" as user") {
    val input: PostPutManagementPolicyRequest =
      PostPutManagementPolicyRequest(agentUpgradePolicy = None,
                                     constraints = Option(List("a == b")),
                                     description = Option("desc"),
                                     enabled = true,
                                     label = managementPolicy,
                                     patterns = None,
                                     properties = Option(List(OneProperty("purpose", None, "location"))),
                                     start = "now",
                                     startWindow = 0)
    
    var response: HttpResponse[String] = Http(URL + "/managementpolicies/" + managementPolicy).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  
    response = Http(URL+"/managementpolicies/"+managementPolicy).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    val respObj: ApiResponse = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("management policy '"+orgManagementPolicy+"' created"))
  }

  test("PUT /orgs/"+orgid+"/managementpolicies/"+managementPolicy+" - add "+managementPolicy+" as user") {
    val input: PostPutManagementPolicyRequest =
      PostPutManagementPolicyRequest(agentUpgradePolicy = None,
      constraints = Option(List("a == c")),
      description = Option("desc"),
      enabled = true,
      label = managementPolicy,
      patterns = None,
      properties = Option(List(OneProperty("purpose", None, "location2"))),
      start = "now",
      startWindow = 0)
    
    var response: HttpResponse[String] = Http(URL + "/managementpolicies/" + managementPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  
    response = Http(URL+"/managementpolicies/"+managementPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    val respObj: ApiResponse = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("management policy updated"))
  }

  //~~~~~ Get (verify) management policies ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("GET /orgs/"+orgid+"/managementpolicies") {
    val response: HttpResponse[String] = Http(URL+"/managementpolicies").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj: GetManagementPoliciesResponse = parse(response.body).extract[GetManagementPoliciesResponse]
    assert(respObj.managementPolicy.size === 1)

    assert(respObj.managementPolicy.contains(orgManagementPolicy))
    var mp: ManagementPolicy = respObj.managementPolicy(orgManagementPolicy)
    assert(mp.label === managementPolicy)
    assert(mp.description === "desc")
    assert(mp.owner === rootuser)
    assert(mp.properties.head.name === "purpose")
    assert(mp.properties.head.value === "location2")
    assert(mp.constraints.head === "a == c")
  }

  test("GET /orgs/"+orgid+"/managementpolicies"+managementPolicy) {
    val response: HttpResponse[String] = Http(URL+"/managementpolicies/"+managementPolicy).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj: GetManagementPoliciesResponse = parse(response.body).extract[GetManagementPoliciesResponse]
    assert(respObj.managementPolicy.size === 1)

    assert(respObj.managementPolicy.contains(orgManagementPolicy))
    var mp: ManagementPolicy = respObj.managementPolicy(orgManagementPolicy)
    assert(mp.label === managementPolicy)
    assert(mp.description === "desc")
    assert(mp.owner === rootuser)
    assert(mp.properties.head.name === "purpose")
    assert(mp.properties.head.value === "location2")
    assert(mp.constraints.head === "a == c")
  }
<<<<<<< HEAD

  //~~~~~ Clean up ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  test("Cleanup - DELETE all test users, which will delete all management policies") {
    deleteAllUsers()
  }

  /** Delete the orgs we used for this test */
  test("DELETE orgs") {
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
=======
  
  // Nodes should be able to see changes to Node Management Policies
  test("GET /orgs/" + orgid + "/changes - As a node") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "MgmtPolSuite/n1",
                  orgid = "MgmtPolSuite",
                  token = "$2a$04$BFX1t20Vd08CQvOisW8B0.JhXw63q0/NydkAwqa2OawPjQmUfJaQG", // MgmtPolSuite/n1:n1pw
                  name = "",
                  owner = "root/root",
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Option(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    
    fixtureNodes(
      _ => {
        val input: ResourceChangesRequest = ResourceChangesRequest(0, None, 100, Option(List("MgmtPolSuite")))
        val response: HttpResponse[String] = Http(URL + "/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic "+ApiUtils.encode(TESTNODE.head.id + ":n1pw"))).asString
        
        info("code: " + response.code)
        info("body: " + response.body)
        
        val change: ChangeEntry = parse(response.body).extract[ResourceChangesRespObject].changes.filter(c => c.resource === "mgmtpolicy" && c.orgId === "MgmtPolSuite").head
        
        assert(change.resourceChanges.nonEmpty)
      }, TESTNODE)
>>>>>>> b2bb2e9 (Issue-556: Changed the DB schema for NMPs. Changed the syntax of table queries to be simpler.)
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
