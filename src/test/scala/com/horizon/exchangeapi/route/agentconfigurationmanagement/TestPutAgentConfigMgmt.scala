package com.horizon.exchangeapi.route.agentconfigurationmanagement

import com.horizon.exchangeapi.tables.{AgbotMsgRow, AgbotMsgsTQ, AgbotRow, AgbotsTQ, AgentCertificateVersionsTQ, AgentConfigurationVersionsTQ, AgentSoftwareVersionsTQ, AgentVersionsChangedTQ, AgentVersionsRequest, AgentVersionsResponse, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResourceChangesTQ, SearchOffsetPolicyAttributes, SearchOffsetPolicyTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, GetAgbotMsgsResponse, HttpCode, PostPutUsersRequest, PutAgbotsRequest, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.json4s.native.Serialization.write
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import java.time.ZoneId
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPutAgentConfigMgmt extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept", "application/json")
  private val CONTENT = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  // private val ORGID = "TestPutAgentConfigMgmt"
  private val ROOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"

  private implicit val formats = DefaultFormats

  private val TESTAGBOT: AgbotRow =
    AgbotRow(id = "TestPutAgentConfigMgmt/a1",
      lastHeartbeat = ApiTime.nowUTC,
      msgEndPoint = "",
      name = "",
      orgid = "TestPutAgentConfigMgmt",
      owner = "TestPutAgentConfigMgmt/u1",
      publicKey = "",
      token = "$2a$10$RdMlsjB6jwIaoqJNIoCUieM710YLHDYGuRD.y8q0IpqFufkor1by6") // TestPutAgentConfigMgmt/a1:a1tok
  private val TESTORGANIZATIONS: Seq[OrgRow] =
    Seq(OrgRow(heartbeatIntervals = "",
      description = "",
      label = "",
      lastUpdated = ApiTime.nowUTC,
      limits = "",
      orgId = "TestPutAgentConfigMgmt",
      orgType = "",
      tags = None))
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(admin = false,
      email = "",
      hashedPw = "$2a$10$277Ds6AvpLchRM7UBslfpuoS1cU8rFvdv9vG3lXnmVCigws90WBl.", // TestPutAgentConfigMgmt/u1:a1pw
      hubAdmin = false,
      lastUpdated = ApiTime.nowUTC,
      orgid = "TestPutAgentConfigMgmt",
      updatedBy = "",
      username = "TestPutAgentConfigMgmt/u1"))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ ++= TESTORGANIZATIONS) andThen
      (UsersTQ ++= TESTUSERS) andThen
      (AgbotsTQ += TESTAGBOT)), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPutAgentConfigMgmt").delete andThen
      OrgsTQ.filter(_.orgid startsWith "TestPutAgentConfigMgmt").delete), AWAITDURATION)

    DBCONNECTION.getDb.close()
  }

  // Agreement Bots that are dynamically needed, specific to the test case.
  def fixtureAgbots(testCode: Seq[AgbotRow] => Any, testData: Seq[AgbotRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(AgbotsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgbotsTQ.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }

  // Agent Certificate Versions that are dynamically needed, specific to the test case.
  def fixtureAgentCertVersions(testCode: Seq[(String, String)] => Any, testData: Seq[(String, String)]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ.delete), AWAITDURATION)
  }

  // Agent Configuration Versions that are dynamically needed, specific to the test case.
  def fixtureAgentConfigVersions(testCode: Seq[(String, String)] => Any, testData: Seq[(String, String)]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ.delete), AWAITDURATION)
  }

  // Agent Software Versions that are dynamically needed, specific to the test case.
  def fixtureAgentSoftVersions(testCode: Seq[(String, String)] => Any, testData: Seq[(String, String)]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ.delete), AWAITDURATION)
  }

  // Agent Certificate Versions that are dynamically needed, specific to the test case.
  def fixtureUsers(testCode: Seq[UserRow] => Any, testData: Seq[UserRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(UsersTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username inSet testData.map(_.username)).delete), AWAITDURATION)
  }

  // Users that are dynamically needed, specific to the test case.
  def fixtureVersionsChanged(testCode: Seq[(java.sql.Timestamp, String)] => Any, testData: Seq[(java.sql.Timestamp, String)]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(AgentVersionsChangedTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentVersionsChangedTQ.delete), AWAITDURATION)
  }

  test("POST /orgs/randomOrg/AgentFileVersion -- 400 Bad Request - Random OrgId") {

    val TESTCERT: Seq[String] =
      Seq("1.1.1", "IBM")
    val TESTCONFIG: Seq[String] =
      Seq("2.2.2", "IBM")
    val TESTSOFT: Seq[String] =
      Seq("IBM", "3.3.3")
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))

    val requestBody: AgentVersionsRequest =
      AgentVersionsRequest(
        agentCertVersions = TESTCERT,
        agentConfigVersions = TESTCONFIG,
        agentSoftwareVersions = TESTSOFT)

    val response: HttpResponse[String] = Http(URL + "randomOrg/AgentFileVersion").postData(write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }


  test("GET /v1/orgs/IBM/AgentFileVersion -- Default") {
    val TESTCERT: Seq[(String, String)] =
      Seq(("1.1.1", "IBM"))
    val TESTCONFIG: Seq[(String, String)] =
      Seq(("2.2.2", "IBM"))
    val TESTSOFT: Seq[(String, String)] =
      Seq(("IBM", "3.3.3"))
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))

    fixtureAgentCertVersions(
      _ => {
        fixtureAgentConfigVersions(
          _ => {
            fixtureAgentSoftVersions(
              _ => {
                fixtureVersionsChanged(
                  _ => {
                    val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).headers(ROOTAUTH).asString
                    info("code: " + response.code)
                    info("body: " + response.body)

                    assert(response.code === HttpCode.OK.intValue)

                    val versions: AgentVersionsResponse = parse(response.body).extract[AgentVersionsResponse]

                    assert(versions.agentCertVersions.length     === 1)
                    assert(versions.agentConfigVersions.length   === 1)
                    assert(versions.agentSoftwareVersions.length === 1)

                    assert(versions.agentCertVersions(0)     === TESTCERT(0)._1)
                    assert(versions.agentConfigVersions(0)   === TESTCONFIG(0)._1)
                    assert(versions.agentSoftwareVersions(0) === TESTSOFT(0)._2)
                    assert(versions.lastUpdated              === TESTCHG(0)._1.toLocalDateTime.atZone(ZoneId.of("UTC")).toString)
                  }, TESTCHG)
              }, TESTSOFT)
          }, TESTCONFIG)
      }, TESTCERT)
  }
}