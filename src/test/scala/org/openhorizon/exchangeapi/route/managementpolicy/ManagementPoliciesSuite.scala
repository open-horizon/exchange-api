package org.openhorizon.exchangeapi.route.managementpolicy

import org.json4s.jackson.JsonMethods.parse
import org.json4s.native.Serialization.write
import org.json4s.{DefaultFormats, jvalue2extractable}
import org.junit.runner.RunWith
import org.openhorizon.exchangeapi.route.organization.{ChangeEntry, ResourceChangesRequest, ResourceChangesRespObject}
import org.openhorizon.exchangeapi.route.user.PostPutUsersRequest
import org.openhorizon.exchangeapi.table.managementpolicy.ManagementPolicy
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.service.OneProperty
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.utility.{ApiResponse, ApiTime, ApiUtils, Configuration, DatabaseConnection, HttpCode}
import org.openhorizon.exchangeapi.auth.Role
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.util.UUID
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
  val urlRoot: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val runningLocally: Boolean = (urlRoot == localUrlRoot)
  val ACCEPT: (String, String) = ("Accept","application/json")
  val ACCEPTTEXT: (String, String) = ("Accept","text/plain")
  val CONTENT: (String, String) = ("Content-Type","application/json")
  val CONTENTTEXT: (String, String) = ("Content-Type","text/plain")
  val orgid = "MgmtPolSuite"
  val authpref: String = orgid + "/"
  val URL: String = urlRoot + "/v1/orgs/" + orgid

  val user = "mpuser"
  val orguser: String = authpref + user
  val pw: String = user + "pw"
  val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(orguser + ":" + pw))
  val rootuser: String = Role.superUser
  val rootpw: String = (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })      // need to put this root pw in config.json
  val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(rootuser + ":" + rootpw))
  val managementPolicy = "mymgmtpol"
  val orgManagementPolicy: String = authpref + managementPolicy
  val ALL_VERSIONS = "[0.0.0,INFINITY)"
  val NOORGURL: String = urlRoot + "/v1"
  val orgsList = new ListBuffer[String]()
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase

  implicit val formats: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.
  
  private val AWAITDURATION: Duration = 15.seconds
  
  val TIMESTAMP: java.sql.Timestamp = ApiTime.nowUTCTimestamp
  
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
    Await.ready(DBCONNECTION.run((OrgsTQ += TESTORGANIZATION)), AWAITDURATION)
  }
  
  // Teardown testing harness and cleanup.
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "MgmtPolSuite").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "MgmtPolSuite").delete), AWAITDURATION)
  }
  
  // Nodes that are dynamically needed, specific to the test case.
  def fixtureNodes(testCode: Seq[NodeRow] => Any, testData: Seq[NodeRow]): Any = {
    try {
      Await.result(DBCONNECTION.run(NodesTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(NodesTQ.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }
  
  //~~~~~ Clean up from previous run, and create orgs, users ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
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
    // Only Organization Admins and Hub Admins are able to write Node Management Policies
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
    // Only Organization Admins and Hub Admins are able to write Node Management Policies
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
  
  // Nodes should be able to see changes to Node Management Policies
  test("GET /orgs/" + orgid + "/changes - As a node") {
    val rootUser: UUID = Await.result(DBCONNECTION.run(UsersTQ.filter(users => users.organization === "root" && users.username === "root").map(_.user).result.head), AWAITDURATION)
    
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "MgmtPolSuite/n1",
                  orgid = "MgmtPolSuite",
                  token = "$2a$04$BFX1t20Vd08CQvOisW8B0.JhXw63q0/NydkAwqa2OawPjQmUfJaQG", // MgmtPolSuite/n1:n1pw
                  name = "",
                  owner = rootUser,
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
  }
}
