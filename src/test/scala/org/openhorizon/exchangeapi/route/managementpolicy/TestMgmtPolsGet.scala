package org.openhorizon.exchangeapi.route.managementpolicy

import org.json4s.DefaultFormats
import org.json4s.native.JsonMethods
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.managementpolicy.{ManagementPoliciesTQ, ManagementPolicy, ManagementPolicyRow}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, HttpCode}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestMgmtPolsGet extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val AWAITDURATION: Duration = 15.seconds
  implicit val formats: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.
  
  private val TESTMANAGEMENTPOLICES: Seq[ManagementPolicyRow] =
    Seq(ManagementPolicyRow(allowDowngrade   = true,
                            constraints      = """["a","b"]""",
                            created          = ApiTime.nowUTC,
                            description      = "description",
                            enabled          = true,
                            label            = "label",
                            lastUpdated      = ApiTime.nowUTC,
                            manifest         = "manifest",
                            managementPolicy = "TestMgmtPolsGet/pol1",
                            orgid            = "TestMgmtPolsGet",
                            owner            = "TestMgmtPolsGet/u1",
                            patterns         = """["p1","p2"]""",
                            properties       = """[{"name":"name1","type":"type1","value":"value1"},{"name":"name2","value":"value2"}]""",
                            start            = "start",
                            startWindow      = 1L),
        ManagementPolicyRow(allowDowngrade   = false,
                            constraints      = "",
                            created          = ApiTime.nowUTC,
                            description      = "description2",
                            enabled          = false,
                            label            = "label2",
                            lastUpdated      = ApiTime.nowUTC,
                            manifest         = "manifest2",
                            managementPolicy = "TestMgmtPolsGet/pol2",
                            orgid            = "TestMgmtPolsGet",
                            owner            = "TestMgmtPolsGet/u2",
                            patterns         = "",
                            properties       = "",
                            start            = "",
                            startWindow      = 0L),
        ManagementPolicyRow(allowDowngrade   = false,
                            constraints      = "",
                            created          = ApiTime.nowUTC,
                            description      = "",
                            enabled          = false,
                            label            = "",
                            lastUpdated      = ApiTime.nowUTC,
                            manifest         = "",
                            managementPolicy = "TestMgmtPolsGet2/pol1",
                            orgid            = "TestMgmtPolsGet2",
                            owner            = "TestMgmtPolsGet2/u1",
                            patterns         = "",
                            properties       = "",
                            start            = "",
                            startWindow      = 0L))
  private val TESTORGANIZATIONS: Seq[OrgRow] =
    Seq(OrgRow(heartbeatIntervals = "",
               description        = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               orgId              = "TestMgmtPolsGet",
               orgType            = "",
               tags               = None,
               limits             = ""),
        OrgRow(heartbeatIntervals = "",
               description        = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               orgId              = "TestMgmtPolsGet2",
               orgType            = "",
               tags               = None,
               limits             = ""))
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(admin       = false,
                hubAdmin    = false,
                email       = "",
                hashedPw    = Password.fastHash("u1pw"),
                lastUpdated = ApiTime.nowUTC,
                orgid       = "TestMgmtPolsGet",
                updatedBy   = "",
                username    = "TestMgmtPolsGet/u1"),
        UserRow(admin       = false,
                hubAdmin    = false,
                email       = "",
                hashedPw    = "",
                lastUpdated = ApiTime.nowUTC,
                orgid       = "TestMgmtPolsGet",
                updatedBy   = "",
                username    = "TestMgmtPolsGet/u2"),
        UserRow(admin       = false,
                hubAdmin    = false,
                email       = "",
                hashedPw    = Password.fastHash("u1pw"),
                lastUpdated = ApiTime.nowUTC,
                orgid       = "TestMgmtPolsGet2",
                updatedBy   = "",
                username    = "TestMgmtPolsGet2/u1"))
  
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGANIZATIONS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (ManagementPoliciesTQ ++= TESTMANAGEMENTPOLICES)), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestMgmtPolsGet").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestMgmtPolsGet").delete), AWAITDURATION)
  }
  
  
  def fixtureNodeMgmtPol(testCode: Seq[ManagementPolicyRow] => Any, testData: Seq[ManagementPolicyRow]): Any = {
    try {
      Await.result(DBCONNECTION.run(ManagementPoliciesTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(ManagementPoliciesTQ.filter(_.managementPolicy inSet testData.map(_.managementPolicy)).delete), AWAITDURATION)
  }
  
  test("GET /v1/orgs/" + TESTORGANIZATIONS(0).orgId + "/managementpolicies -- 200 ok - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATIONS(0).orgId + "/managementpolicies").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val managementPolicies: Map[String, ManagementPolicy] = JsonMethods.parse(response.body).extract[GetManagementPoliciesResponse].managementPolicy
    assert(managementPolicies.size === 2)
    
    assert(managementPolicies.contains("TestMgmtPolsGet/pol1"))
    assert(managementPolicies("TestMgmtPolsGet/pol1").agentUpgradePolicy.allowDowngrade === TESTMANAGEMENTPOLICES(0).allowDowngrade)
    assert(managementPolicies("TestMgmtPolsGet/pol1").agentUpgradePolicy.manifest       === TESTMANAGEMENTPOLICES(0).manifest)
    assert(managementPolicies("TestMgmtPolsGet/pol1").constraints.length                === 2)
    assert(managementPolicies("TestMgmtPolsGet/pol1").constraints(0)                    === "a")
    assert(managementPolicies("TestMgmtPolsGet/pol1").constraints(1)                    === "b")
    assert(managementPolicies("TestMgmtPolsGet/pol1").created                           === TESTMANAGEMENTPOLICES(0).created)
    assert(managementPolicies("TestMgmtPolsGet/pol1").description                       === TESTMANAGEMENTPOLICES(0).description)
    assert(managementPolicies("TestMgmtPolsGet/pol1").enabled                           === TESTMANAGEMENTPOLICES(0).enabled)
    assert(managementPolicies("TestMgmtPolsGet/pol1").label                             === TESTMANAGEMENTPOLICES(0).label)
    assert(managementPolicies("TestMgmtPolsGet/pol1").lastUpdated                       === TESTMANAGEMENTPOLICES(0).lastUpdated)
    assert(managementPolicies("TestMgmtPolsGet/pol1").owner                             === TESTMANAGEMENTPOLICES(0).owner)
    assert(managementPolicies("TestMgmtPolsGet/pol1").patterns.length                   === 2)
    assert(managementPolicies("TestMgmtPolsGet/pol1").patterns(0)                       === "p1")
    assert(managementPolicies("TestMgmtPolsGet/pol1").patterns(1)                       === "p2")
    assert(managementPolicies("TestMgmtPolsGet/pol1").properties.length                 === 2)
    assert(managementPolicies("TestMgmtPolsGet/pol1").properties(0).name                === "name1")
    assert(managementPolicies("TestMgmtPolsGet/pol1").properties(0).`type`              === Option("type1"))
    assert(managementPolicies("TestMgmtPolsGet/pol1").properties(0).value               === "value1")
    assert(managementPolicies("TestMgmtPolsGet/pol1").properties(1).name                === "name2")
    assert(managementPolicies("TestMgmtPolsGet/pol1").properties(1).`type`              === None)
    assert(managementPolicies("TestMgmtPolsGet/pol1").properties(1).value               === "value2")
    assert(managementPolicies("TestMgmtPolsGet/pol1").start                             === TESTMANAGEMENTPOLICES(0).start)
    assert(managementPolicies("TestMgmtPolsGet/pol1").startWindow                       === TESTMANAGEMENTPOLICES(0).startWindow)
    
    assert(managementPolicies.contains("TestMgmtPolsGet/pol2"))
  }
  
  test("GET /v1/orgs/" + TESTORGANIZATIONS(0).orgId + "/managementpolicies -- 403 access denied - different organization - TestMgmtPolsGet2/user1") {
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATIONS(0).orgId + "/managementpolicies").headers(CONTENT).headers(ACCEPT).headers(("Authorization", "Basic " + ApiUtils.encode("TestMgmtPolsGet2/u1" + ":" +  "u1pw"))).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  // ---------- description
  test("GET /v1/orgs/" + TESTORGANIZATIONS(0).orgId + "/managementpolicies/?description=description -- 200 ok - description - equals - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATIONS(0).orgId + "/managementpolicies").param(key = "description", value = "description").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val managementPolicies: Map[String, ManagementPolicy] = JsonMethods.parse(response.body).extract[GetManagementPoliciesResponse].managementPolicy
    assert(managementPolicies.size === 1)
    
    assert(managementPolicies.contains("TestMgmtPolsGet/pol1"))
  }
  
  test("GET /v1/orgs/" + TESTORGANIZATIONS(0).orgId + "/managementpolicies/?description=d%s%r%p%i%n2 -- 200 ok - description - like - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATIONS(0).orgId + "/managementpolicies").param(key = "description", value = "d%s%r%p%i%n2").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val managementPolicies: Map[String, ManagementPolicy] = JsonMethods.parse(response.body).extract[GetManagementPoliciesResponse].managementPolicy
    assert(managementPolicies.size === 1)
    
    assert(managementPolicies.contains("TestMgmtPolsGet/pol2"))
  }
  
  // ---------- idfilter
  test("GET /v1/orgs/" + TESTORGANIZATIONS(0).orgId + "/managementpolicies/?idfilter=TestMgmtPolsGet/pol1 -- 200 ok - idfilter - equals - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATIONS(0).orgId + "/managementpolicies").param(key = "idfilter", value = "TestMgmtPolsGet/pol1").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val managementPolicies: Map[String, ManagementPolicy] = JsonMethods.parse(response.body).extract[GetManagementPoliciesResponse].managementPolicy
    assert(managementPolicies.size === 1)
    
    assert(managementPolicies.contains("TestMgmtPolsGet/pol1"))
  }
  
  test("GET /v1/orgs/" + TESTORGANIZATIONS(0).orgId + "/managementpolicies/?idfilter=p%l1 -- 200 ok - idfilter - like - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATIONS(0).orgId + "/managementpolicies").param(key = "idfilter", value = "%p%l1").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val managementPolicies: Map[String, ManagementPolicy] = JsonMethods.parse(response.body).extract[GetManagementPoliciesResponse].managementPolicy
    assert(managementPolicies.size === 1)
    
    assert(managementPolicies.contains("TestMgmtPolsGet/pol1"))
  }
  
  // ---------- label
  test("GET /v1/orgs/" + TESTORGANIZATIONS(0).orgId + "/managementpolicies/?label=label -- 200 ok - label - equals - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATIONS(0).orgId + "/managementpolicies").param(key = "label", value = "label").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val managementPolicies: Map[String, ManagementPolicy] = JsonMethods.parse(response.body).extract[GetManagementPoliciesResponse].managementPolicy
    assert(managementPolicies.size === 1)
    
    assert(managementPolicies.contains("TestMgmtPolsGet/pol1"))
  }
  
  test("GET /v1/orgs/" + TESTORGANIZATIONS(0).orgId + "/managementpolicies/?label=%2 -- 200 ok - label - like - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATIONS(0).orgId + "/managementpolicies").param(key = "label", value = "%2").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val managementPolicies: Map[String, ManagementPolicy] = JsonMethods.parse(response.body).extract[GetManagementPoliciesResponse].managementPolicy
    assert(managementPolicies.size === 1)
    
    assert(managementPolicies.contains("TestMgmtPolsGet/pol2"))
  }
  
  // ---------- manifest
  test("GET /v1/orgs/" + TESTORGANIZATIONS(0).orgId + "/managementpolicies/?manifest=manifest2 -- 200 ok - manifest - equals - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATIONS(0).orgId + "/managementpolicies").param(key = "manifest", value = "manifest2").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val managementPolicies: Map[String, ManagementPolicy] = JsonMethods.parse(response.body).extract[GetManagementPoliciesResponse].managementPolicy
    assert(managementPolicies.size === 1)
    
    assert(managementPolicies.contains("TestMgmtPolsGet/pol2"))
  }
  
  test("GET /v1/orgs/" + TESTORGANIZATIONS(0).orgId + "/managementpolicies/?manifest=% -- 200 ok - manifest - like - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATIONS(0).orgId + "/managementpolicies").param(key = "manifest", value = "%").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val managementPolicies: Map[String, ManagementPolicy] = JsonMethods.parse(response.body).extract[GetManagementPoliciesResponse].managementPolicy
    assert(managementPolicies.size === 2)
    
    assert(managementPolicies.contains("TestMgmtPolsGet/pol1"))
    assert(managementPolicies.contains("TestMgmtPolsGet/pol2"))
  }
  
  // ---------- owner
  test("GET /v1/orgs/" + TESTORGANIZATIONS(0).orgId + "/managementpolicies/?owner=TestMgmtPolsGet/u2 -- 200 ok - owner - equals - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATIONS(0).orgId + "/managementpolicies").param(key = "owner", value = "TestMgmtPolsGet/u2").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val managementPolicies: Map[String, ManagementPolicy] = JsonMethods.parse(response.body).extract[GetManagementPoliciesResponse].managementPolicy
    assert(managementPolicies.size === 1)
    
    assert(managementPolicies.contains("TestMgmtPolsGet/pol2"))
  }
  
  test("GET /v1/orgs/" + TESTORGANIZATIONS(1).orgId + "/managementpolicies/?owner=% -- 200 ok - owner - like - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATIONS(1).orgId + "/managementpolicies").param(key = "owner", value = "%").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val managementPolicies: Map[String, ManagementPolicy] = JsonMethods.parse(response.body).extract[GetManagementPoliciesResponse].managementPolicy
    assert(managementPolicies.size === 1)
    
    assert(managementPolicies.contains("TestMgmtPolsGet2/pol1"))
  }
}
