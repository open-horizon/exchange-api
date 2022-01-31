package com.horizon.exchangeapi

import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Role, TestDBConnection}
import com.horizon.exchangeapi.tables.{NodeRow, NodesTQ, OneProperty, OrgRow, OrgsTQ, RegService, ResourceChangesTQ, UserRow}
import org.json4s.jackson.JsonMethods.parse
import org.json4s.{DefaultFormats, Formats, JValue, JsonInput, jvalue2extractable, string2JsonInput}
import org.json4s.native.Serialization.write
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable
import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Tests for the /managementpolicies routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class ManagementPoliciesSuite extends AnyFunSuite with BeforeAndAfterAll{

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally = (urlRoot == localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val ACCEPTTEXT = ("Accept","text/plain")
  val CONTENT = ("Content-Type","application/json")
  val CONTENTTEXT = ("Content-Type","text/plain")
  val orgid = "MgmtPolSuite"
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
  private val DBCONNECTION: TestDBConnection = new TestDBConnection

  implicit val formats = DefaultFormats // Brings in default date formats etc.
  
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
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ.rows += TESTORGANIZATION)), AWAITDURATION)
  }
  
  // Teardown testing harness and cleanup.
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.rows.filter(_.orgId startsWith "MgmtPolSuite").delete andThen
                                       OrgsTQ.rows.filter(_.orgid startsWith "MgmtPolSuite").delete), AWAITDURATION)
    
    DBCONNECTION.getDb.close()
  }
  
  // Nodes that are dynamically needed, specific to the test case.
  def fixtureNodes(testCode: Seq[NodeRow] => Any, testData: Seq[NodeRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(NodesTQ.rows ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(NodesTQ.rows.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }
  
  //~~~~~ Clean up from previous run, and create orgs, users ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
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
    var response = Http(URL+"/managementpolicies/"+managementPolicy).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    // Only Organization Admins and Hub Admins are able to write Node Management Policies
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  
    response = Http(URL+"/managementpolicies/"+managementPolicy).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
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
    var response = Http(URL+"/managementpolicies/"+managementPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    // Only Organization Admins and Hub Admins are able to write Node Management Policies
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  
    response = Http(URL+"/managementpolicies/"+managementPolicy).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
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
    val respObj = parse(response.body).extract[GetManagementPoliciesResponse]
    assert(respObj.managementPolicy.size === 1)

    assert(respObj.managementPolicy.contains(orgManagementPolicy))
    var mp = respObj.managementPolicy(orgManagementPolicy)
    assert(mp.label === managementPolicy)
    assert(mp.description === "desc")
    assert(mp.owner === rootuser)
    assert(mp.properties.head.name === "purpose")
    assert(mp.properties.head.value === "location2")
    assert(mp.constraints.head === "a == c")
  }
  
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
                  lastHeartbeat = Some(ApiTime.nowUTC),
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
        
        assert(change.resourceChanges.length > 0)
      }, TESTNODE)
  }
}
