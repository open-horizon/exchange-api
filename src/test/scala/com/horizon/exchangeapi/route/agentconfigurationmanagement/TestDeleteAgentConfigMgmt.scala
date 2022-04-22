package com.horizon.exchangeapi.route.agentconfigurationmanagement

import com.horizon.exchangeapi.tables.{AgbotMsgRow, AgbotMsgsTQ, AgbotRow, AgbotsTQ, AgentCertificateVersionsTQ, AgentConfigurationVersionsTQ, AgentSoftwareVersionsTQ, AgentVersionsChangedTQ, AgentVersionsResponse, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ, SearchOffsetPolicyAttributes, SearchOffsetPolicyTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, ChangeEntry, GetAgbotMsgsResponse, HttpCode, PatchOrgRequest, PostPutUsersRequest, PutAgbotsRequest, ResourceChangesRequest, ResourceChangesRespObject, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.json4s.native.Serialization.write
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover, Suite}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import java.sql.Timestamp
import java.time.ZoneId
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

@DoNotDiscover
class TestDeleteAgentConfigMgmt extends AnyFunSuite with BeforeAndAfterAll with Suite {
  private val ACCEPT: (String, String) = ("Accept","application/json")
  private val CONTENT = ("Content-Type","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  // private val ORGID = "TestDeleteAgentConfigMgmt"
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id            = "TestDeleteAgentConfigMgmt/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint   = "",
             name          = "",
             orgid         = "TestDeleteAgentConfigMgmt",
             owner         = "TestDeleteAgentConfigMgmt/u1",
             publicKey     = "",
             token         = "$2a$10$dP1e0wIHWOK.VgzsE4cLEuqfiXik6Vz/VEjIMJvVP8BQEp8RFuZVW")  // TestDeleteAgentConfigMgmt/a1:a1tok
  private val TESTORGANIZATIONS: Seq[OrgRow] =
    Seq(OrgRow(heartbeatIntervals = "",
               description        = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               limits             = "",
               orgId              = "TestDeleteAgentConfigMgmt",
               orgType            = "",
               tags               = None))
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(admin       = true,
                email       = "",
                hashedPw    = "$2a$10$354oGJqNCxaAGCqPUVKWi.Zh1Yq2Fb3DalAmzdxMYnOzLL8oScNyO",  // TestDeleteAgentConfigMgmt/admin1:admin1pw
                hubAdmin    = false,
                lastUpdated = ApiTime.nowUTC,
                orgid       = "TestDeleteAgentConfigMgmt",
                updatedBy   = "",
                username    = "TestDeleteAgentConfigMgmt/admin1"),
        UserRow(admin       = false,
                email       = "",
                hashedPw    = "$2a$10$sF2iHLnB6vM9Ju/ricD5huT6EnuVjGUKVN/LtJpyAGOT7yaU/kcaW",  // TestDeleteAgentConfigMgmt/u1:a1pw
                hubAdmin    = false,
                lastUpdated = ApiTime.nowUTC,
                orgid       = "TestDeleteAgentConfigMgmt",
                updatedBy   = "",
                username    = "TestDeleteAgentConfigMgmt/u1"))
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ ++= TESTORGANIZATIONS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (AgbotsTQ += TESTAGBOT)
    ), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(change => {(change.orgId startsWith "TestPutAgentConfigMgmt") ||
                                                                           (change.orgId === "IBM" &&
                                                                            change.resource === "agentfileversion")}).delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestDeleteAgentConfigMgmt").delete), AWAITDURATION)
    
    DBCONNECTION.getDb.close()
  }
  
  
  // Agreement Bots that are dynamically needed, specific to the test case.
  def fixtureAgbots(testCode: Seq[AgbotRow] => Any, testData: Seq[AgbotRow]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(AgbotsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgbotsTQ.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }
  
  // Agent Certificate Versions that are dynamically needed, specific to the test case.
  def fixtureAgentCertVersions(testCode: Seq[(String, String, Option[Long])] => Any, testData: Seq[(String, String, Option[Long])]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Configuration Versions that are dynamically needed, specific to the test case.
  def fixtureAgentConfigVersions(testCode: Seq[(String, String, Option[Long])] => Any, testData: Seq[(String, String, Option[Long])]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Software Versions that are dynamically needed, specific to the test case.
  def fixtureAgentSoftVersions(testCode: Seq[(String, String, Option[Long])] => Any, testData: Seq[(String, String, Option[Long])]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Certificate Versions that are dynamically needed, specific to the test case.
  def fixtureUsers(testCode: Seq[UserRow] => Any, testData: Seq[UserRow]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(UsersTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username inSet testData.map(_.username)).delete), AWAITDURATION)
  }
  
  // Users that are dynamically needed, specific to the test case.
  def fixtureVersionsChanged(testCode: Seq[(java.sql.Timestamp, String)] => Any, testData: Seq[(java.sql.Timestamp, String)]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(AgentVersionsChangedTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentVersionsChangedTQ.delete), AWAITDURATION)
  }
  
  
  test("DELETE /v1/orgs/somerandomorg/AgentFileVersion -- 400 Bad Input - Bad Org") {
    val response: HttpResponse[String] = Http(URL + "somerandomorg/AgentFileVersion").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("DELETE /v1/orgs/IBM/AgentFileVersion -- 404 Not Found") {
    val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("DELETE /v1/orgs/IBM/AgentFileVersion -- 403 Unauthorized Access - IBM User") {
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))
    val TESTUSERS: Seq[UserRow] =
      Seq(UserRow(admin       = false,
        email       = "",
        hashedPw    = "$2a$10$0nyuleCFffuFumOrDHnvOOpP8scH2kFfG0YDp8TcyVBhYKK7dCzPG",  // IBM/TestDeleteAgentConfigMgmt-u1:TestDeleteAgentConfigMgmt-u1pw
        hubAdmin    = false,
        lastUpdated = ApiTime.nowUTC,
        orgid       = "IBM",
        updatedBy   = "",
        username    = "IBM/TestDeleteAgentConfigMgmt-u1"))
    
    fixtureUsers(
      _ =>{
        fixtureVersionsChanged(
          _ => {
            val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").method("delete").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("IBM/TestDeleteAgentConfigMgmt-u1:TestDeleteAgentConfigMgmt-u1pw"))).asString
            info("code: " + response.code)
            info("body: " + response.body)
            
            assert(response.code === HttpCode.ACCESS_DENIED.intValue)
          }, TESTCHG)
      }, TESTUSERS)
  }
  
  test("DELETE /v1/orgs/IBM/AgentFileVersion -- 403 Unauthorized Access - TestDeleteAgentConfigMgmt Organizational Admin") {
    val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").method("delete").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestDeleteAgentConfigMgmt/admin1:admin1pw"))).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("DELETE /v1/orgs/IBM/AgentFileVersion -- 403 Unauthorized Access - TestDeleteAgentConfigMgmt User") {
    val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").method("delete").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestDeleteAgentConfigMgmt/u1:u1pw"))).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("DELETE /v1/orgs/IBM/AgentFileVersion -- 204 Deleted - Root") {
    val TESTCERT: Seq[(String, String, Option[Long])] =
      Seq(("1.1.1", "IBM", None))
    val TESTCONFIG: Seq[(String, String, Option[Long])] =
      Seq(("2.2.2", "IBM", None))
    val TESTSOFT: Seq[(String, String, Option[Long])] =
      Seq(("IBM", "3.3.3", None))
    val TESTCHG: Seq[(Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))
    
    fixtureAgentCertVersions(
      _ => {
        fixtureAgentConfigVersions(
          _ => {
            fixtureAgentSoftVersions(
              _ => {
                fixtureVersionsChanged(
                  _ => {
                    val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
                    info("code: " + response.code)
                    info("body: " + response.body)
                    
                    assert(response.code === HttpCode.DELETED.intValue)
                    
                    val certificates: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ.result), AWAITDURATION)
                    val changed: Seq[(java.sql.Timestamp, String)] = Await.result(DBCONNECTION.getDb.run(AgentVersionsChangedTQ.result), AWAITDURATION)
                    val configurations: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ.result), AWAITDURATION)
                    val resource: Seq[ResourceChangeRow] =
                      Await.result(
                        DBCONNECTION.getDb.run(
                          ResourceChangesTQ.filter(_.category === ResChangeCategory.ORG.toString)
                                           .filter(_.id === "IBM")
                                           .filter(_.operation === ResChangeOperation.MODIFIED.toString)
                                           .filter(_.orgId === "IBM")
                                           .filter(_.resource === ResChangeResource.AGENTFILEVERSION.toString)
                                           .sortBy(_.changeId.desc)
                                           .result
                        ), AWAITDURATION)
                    val software: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ.result), AWAITDURATION)
                    
                    assert(certificates.isEmpty)
                    assert(changed.length === 1)
                    assert(configurations.isEmpty)
                    assert(resource.nonEmpty)
                    assert(software.isEmpty)
                    
                    assert(changed(0) !== TESTCHG(0)._1)
  
                    val request2: HttpResponse[String] = Http(URL + "TestDeleteAgentConfigMgmt/changes").postData(write(ResourceChangesRequest(changeId = 0, lastUpdated = None, maxRecords = 1000, orgList = None))).method("post").headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestDeleteAgentConfigMgmt/u1:u1pw"))).asString
                    info("code: " + request2.code)
                    info("body: " + request2.body)
  
                    assert(request2.code === HttpCode.POST_OK.intValue)
  
                    val versionChanges: List[ChangeEntry] = parse(request2.body).extract[ResourceChangesRespObject].changes.filter(change => {change.orgId === "IBM" && change.resource === "agentfileversion"})
  
                    assert(versionChanges.head.id === "IBM")
                    assert(versionChanges.head.operation === "modified")
                    assert(versionChanges.head.orgId === "IBM")
                    assert(versionChanges.head.resource === "agentfileversion")
                  }, TESTCHG)
              }, TESTSOFT)
          }, TESTCONFIG)
      }, TESTCERT)
  }
  
  test("DELETE /v1/orgs/IBM/AgentFileVersion -- 204 Deleted - IBM Agbot") {
    val TESTAGBOTS: Seq[AgbotRow] =
      Seq(AgbotRow(id            = "IBM/TestDeleteAgentConfigMgmt-a1",
        lastHeartbeat = ApiTime.nowUTC,
        msgEndPoint   = "",
        name          = "",
        orgid         = "IBM",
        owner         = "TestDeleteAgentConfigMgmt/u1",
        publicKey     = "",
        token         = "$2a$10$Dl2TjiIynGQEaomV0uZbcOdAAr9/X8JOuU/To4qlY7foaUbWYfeXG"))  // IBM/TestDeleteAgentConfigMgmt-a1:TestDeleteAgentConfigMgmt-a1tok
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))
    
    fixtureAgbots(
      _ =>{
        fixtureVersionsChanged(
          _ => {
            val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).method("delete").headers(("Authorization","Basic " + ApiUtils.encode("IBM/TestDeleteAgentConfigMgmt-a1:TestDeleteAgentConfigMgmt-a1tok"))).asString
            info("code: " + response.code)
            info("body: " + response.body)
            
            assert(response.code === HttpCode.DELETED.intValue)
  
            val certificates: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ.result), AWAITDURATION)
            val changed: Seq[(Timestamp, String)] = Await.result(DBCONNECTION.getDb.run(AgentVersionsChangedTQ.result), AWAITDURATION)
            val configurations: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ.result), AWAITDURATION)
            val resource: Seq[ResourceChangeRow] =
              Await.result(DBCONNECTION.getDb.run(
                ResourceChangesTQ.filter(_.category === ResChangeCategory.ORG.toString)
                                 .filter(_.id === "IBM")
                                 .filter(_.operation === ResChangeOperation.MODIFIED.toString)
                                 .filter(_.orgId === "IBM")
                                 .filter(_.resource === ResChangeResource.AGENTFILEVERSION.toString)
                                 .sortBy(_.changeId.desc)
                                 .result
              ), AWAITDURATION)
            val software: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ.result), AWAITDURATION)
  
            assert(certificates.isEmpty)
            assert(changed.length === 1)
            assert(configurations.isEmpty)
            assert(resource.nonEmpty)
            assert(software.isEmpty)
  
            assert(changed(0) !== TESTCHG(0)._1)
          }, TESTCHG)
      }, TESTAGBOTS)
  }
  
  test("DELETE /v1/orgs/IBM/AgentFileVersion -- 204 Deleted - IBM Organization Admin") {
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))
    val TESTUSER: Seq[UserRow] =
      Seq(UserRow(admin       = true,
                  email       = "",
                  hashedPw    = "$2a$10$wIq/n8oI5hJ8vq36hS4Gy.UkBb0ZSrd8W68IVlozD13lMZwpzRz4.",  // TestDeleteAgentConfigMgmt/admin1:admin1pw
                  hubAdmin    = false,
                  lastUpdated = ApiTime.nowUTC,
                  orgid       = "IBM",
                  updatedBy   = "",
                  username    = "IBM/TestDeleteAgentConfigMgmt-admin1"))
    fixtureUsers(
      _ => {
        fixtureVersionsChanged(
          _ => {
            val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").method("delete").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("IBM/TestDeleteAgentConfigMgmt-admin1:TestDeleteAgentConfigMgmt-admin1pw"))).asString
            info("code: " + response.code)
            info("body: " + response.body)
  
            assert(response.code === HttpCode.DELETED.intValue)
          }, TESTCHG)
      }, TESTUSER)
  }
  
  test("DELETE /v1/orgs/IBM/AgentFileVersion -- 204 Deleted - TestDeleteAgentConfigMgmt Agbot") {
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))
    
    fixtureVersionsChanged(
      _ => {
        val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").method("delete").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestDeleteAgentConfigMgmt/a1:a1tok"))).asString
        info("code: " + response.code)
        info("body: " + response.body)
        
        assert(response.code === HttpCode.DELETED.intValue)
      }, TESTCHG)
  }
}
