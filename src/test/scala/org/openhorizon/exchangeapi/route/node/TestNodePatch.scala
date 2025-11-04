package org.openhorizon.exchangeapi.route.node

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.json4s.{DefaultFormats, JObject, JValue}
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization.write
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.resourcechange.ResChangeCategory.ResChangeCategory
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.deploymentpattern.{OneUserInputService, OneUserInputValue, PatternRow, PatternsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeHeartbeatIntervals, NodeRow, NodesTQ, Prop, RegService}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.service.{ServiceRow, ServicesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.time.{Instant, ZoneId}
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestNodePatch extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Accept","application/json")
  private val ADMINAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestNodePatch" + "/" + "u2" + ":" + "u2pw"))
  private val AWAITDURATION: Duration = 15.seconds
  private val CONTENT: (String, String) = ("Content-Type","application/json")
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val NODEAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestNodePatch" + "/" + "n2" + ":" + "n2tok"))
  // private val ORGID = "TestNodePatch"
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestNodePatch" + "/" + "u1" + ":" + "u1pw"))
  
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp
  
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "TestNodePatch",
                password     = Option(Password.hash("u1pw")),
                username     = "u1"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = TIMESTAMP,
                organization = "TestNodePatch",
                password     = Option(Password.hash("u2pw")),
                username     = "u2"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "TestNodePatch2",
                password     = None,
                username     = "u1"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = TIMESTAMP,
                organization = "TestNodePatch3@somecomp.com",
                password     = Option(Password.hash("adminpw")),
                username     = "e2edevadmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "TestNodePatch3@somecomp.com",
                password     = Option(Password.hash("userpw")),
                username     = "user"))
  private val TESTNODES: Seq[NodeRow] =
    Seq(NodeRow(arch               = "",
                id                 = "TestNodePatch/n1",
                heartbeatIntervals = "",
                lastHeartbeat      = None,
                lastUpdated        = ApiTime.nowUTC,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = "TestNodePatch",
                owner              = TESTUSERS(0).user,
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""))
  private val TESTORGANIZATIONS: Seq[OrgRow] =
    Seq(OrgRow(description        = "",
               heartbeatIntervals = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               limits             = "",
               orgId              = "TestNodePatch",
               orgType            = "",
               tags               = None),
        OrgRow(description        = "",
               heartbeatIntervals = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               limits             = "",
               orgId              = "TestNodePatch2",
               orgType            = "",
               tags               = None),
        OrgRow(description        = "",
               heartbeatIntervals = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               limits             = "",
               orgId              = "TestNodePatch3@somecomp.com",
               orgType            = "",
               tags               = None))
  private val TESTPATTERNS: Seq[PatternRow] =
    Seq(PatternRow(agreementProtocols = "",
                   clusterNamespace   = None,
                   description        = "",
                   label              = "",
                   lastUpdated        = ApiTime.nowUTC,
                   orgid              = "TestNodePatch",
                   owner              = TESTUSERS(0).user,
                   pattern            = "TestNodePatch/p1",
                   public             = false,
                   services           = "",
                   secretBinding      = "",
                   userInput          = ""),
        PatternRow(agreementProtocols = "",
                   clusterNamespace   = None,
                   description        = "",
                   label              = "",
                   lastUpdated        = ApiTime.nowUTC,
                   orgid              = "TestNodePatch",
                   owner              = TESTUSERS(1).user,
                   pattern            = "TestNodePatch/p2",
                   public             = false,
                   services           = "",
                   secretBinding      = "",
                   userInput          = ""),
        PatternRow(agreementProtocols = "",
                   clusterNamespace   = None,
                   description        = "",
                   label              = "",
                   lastUpdated        = ApiTime.nowUTC,
                   orgid              = "TestNodePatch2",
                   owner              = TESTUSERS(2).user,
                   pattern            = "TestNodePatch2/p1",
                   public             = false,
                   services           = "",
                   secretBinding      = "",
                   userInput          = ""),
        PatternRow(agreementProtocols = "",
                   clusterNamespace   = None,
                   description        = "",
                   label              = "",
                   lastUpdated        = ApiTime.nowUTC,
                   orgid              = "TestNodePatch2",
                   owner              = TESTUSERS(2).user,
                   pattern            = "TestNodePatch2/p2",
                   public             = true,
                   services           = "",
                   secretBinding      = "",
                   userInput          = ""))
  private val TESTSERVICES: Seq[ServiceRow] =
    Seq(ServiceRow(arch                       = "amd64",
                   clusterDeployment          = "",
                   clusterDeploymentSignature = "",
                   deployment                 = "",
                   deploymentSignature        = "",
                   description                = "",
                   documentation              = "",
                   imageStore                 = "",
                   label                      = "",
                   lastUpdated                = ApiTime.nowUTC,
                   matchHardware              = "",
                   orgid                      = "TestNodePatch",
                   owner                      = TESTUSERS(0).user,
                   public                     = false,
                   requiredServices           = "",
                   service                    = "TestNodePatch/TestNodePatch.service1_1.2.3_amd64",
                   sharable                   = "",
                   url                        = "TestNodePatch.service1",
                   userInput                  = "",
                   version                    = "1.2.3"),
        ServiceRow(arch                       = "i386",
                   clusterDeployment          = "",
                   clusterDeploymentSignature = "",
                   deployment                 = "",
                   deploymentSignature        = "",
                   description                = "",
                   documentation              = "",
                   imageStore                 = "",
                   label                      = "",
                   lastUpdated                = ApiTime.nowUTC,
                   matchHardware              = "",
                   orgid                      = "TestNodePatch",
                   owner                      = TESTUSERS(0).user,
                   public                     = false,
                   requiredServices           = "",
                   service                    = "TestNodePatch/TestNodePatch.service2_4.5.6_i386",
                   sharable                   = "",
                   url                        = "TestNodePatch.service2",
                   userInput                  = "",
                   version                    = "4.5.6"),
        ServiceRow(arch                       = "arm64",
                   clusterDeployment          = "",
                   clusterDeploymentSignature = "",
                   deployment                 = "",
                   deploymentSignature        = "",
                   description                = "",
                   documentation              = "",
                   imageStore                 = "",
                   label                      = "",
                   lastUpdated                = ApiTime.nowUTC,
                   matchHardware              = "",
                   orgid                      = "TestNodePatch",
                   owner                      = TESTUSERS(1).user,
                   public                     = false,
                   requiredServices           = "",
                   service                    = "TestNodePatch/TestNodePatch.service3_7.8.9_arm64",
                   sharable                   = "",
                   url                        = "TestNodePatch.service3",
                   userInput                  = "",
                   version                    = "7.8.9"),
        ServiceRow(arch                       = "amd64",
                   clusterDeployment          = "",
                   clusterDeploymentSignature = "",
                   deployment                 = "",
                   deploymentSignature        = "",
                   description                = "",
                   documentation              = "",
                   imageStore                 = "",
                   label                      = "",
                   lastUpdated                = ApiTime.nowUTC,
                   matchHardware              = "",
                   orgid                      = "TestNodePatch2",
                   owner                      = TESTUSERS(2).user,
                   public                     = false,
                   requiredServices           = "",
                   service                    = "TestNodePatch2/TestNodePatch2.service1_1.2.3_amd64",
                   sharable                   = "",
                   url                        = "TestNodePatch2.service1",
                   userInput                  = "",
                   version                    = "1.2.3"),
        ServiceRow(arch                       = "i386",
                   clusterDeployment          = "",
                   clusterDeploymentSignature = "",
                   deployment                 = "",
                   deploymentSignature        = "",
                   description                = "",
                   documentation              = "",
                   imageStore                 = "",
                   label                      = "",
                   lastUpdated                = ApiTime.nowUTC,
                   matchHardware              = "",
                   orgid                      = "TestNodePatch2",
                   owner                      = TESTUSERS(2).user,
                   public                     = true,
                   requiredServices           = "",
                   service                    = "TestNodePatch2/TestNodePatch2.service2_4.5.6_i386",
                   sharable                   = "",
                   url                        = "TestNodePatch2.service2",
                   userInput                  = "",
                   version                    = "4.5.6"),
        ServiceRow(arch                       = "amd64",
                   clusterDeployment          = "",
                   clusterDeploymentSignature = "",
                   deployment                 = "",
                   deploymentSignature        = "",
                   description                = "test service",
                   documentation              = "",
                   imageStore                 = "",
                   label                      = "test",
                   lastUpdated                = ApiTime.nowUTC,
                   matchHardware              = "",
                   orgid                      = "TestNodePatch3@somecomp.com",
                   owner                      = TESTUSERS(3).user,
                   public                     = false,
                   requiredServices           = "",
                   service                    = "TestNodePatch3@somecomp.com/bluehorizon.network-services-testservice_1.0.0_amd64",
                   sharable                   = "multiple",
                   url                        = "https://bluehorizon.network/services/testservice",
                   userInput                  = """[{"name":"var1","label":"","type":"string"},{"name":"var2","label":"","type":"int"},{"name":"var3","label":"","type":"float"},{"name":"var4","label":"","type":"list of strings"},{"name":"var5","label":"","type":"string","defaultValue":"foo"}]""",
                   version                    = "1.0.0"))
  
  
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGANIZATIONS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (PatternsTQ ++= TESTPATTERNS) andThen
                                       (ServicesTQ ++= TESTSERVICES)), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestNodePatch").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestNodePatch").delete), AWAITDURATION)
    
    val response: HttpResponse[String] = Http(sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/admin/clearauthcaches").method("POST").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
  }
  
  
  def fixtureNodes(testCode: Seq[NodeRow] => Any, testData: Seq[NodeRow]): Any = {
    try {
      Await.result(DBCONNECTION.run(NodesTQ ++= testData), AWAITDURATION)
      testCode(testData)
    } finally {
      Http(sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/admin/clearauthcaches").method("POST").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      Await.result(DBCONNECTION.run(NodesTQ.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
    }
  }
  
  
  test("PATCH /v1/orgs/" + "somerog" + "/nodes/" + "n2" + " -- 404 not found - bad organization - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = Option(""),
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  clusterNamespace   = None,
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "someorg" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.NotFound.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "somenode" + " -- 404 not found - bad node - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = Option(""),
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    
    val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "somenode").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.NotFound.intValue)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - no attribute - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  clusterNamespace   = None,
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - multiple attributes - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = Option("a"),
                        clusterNamespace = Option("a"),
                        heartbeatIntervals = None,
                        msgEndPoint = Option("a"),
                        token = None,
                        name = Option("a"),
                        nodeType = Option("a"),
                        pattern = None,
                        publicKey = Option("a"),
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  clusterNamespace   = None,
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - no attributes - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  clusterNamespace   = None,
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  // ---------- clusterNamespace
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - clusterNamespace - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = Option("clusterNamespace"),
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  clusterNamespace   = None,
                  heartbeatIntervals = "",
                  id                 = "TestNodePatch/n2",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.arch === testnodes(0).arch)
        assert(node.clusterNamespace.isDefined)
        assert(node.clusterNamespace.get === input.clusterNamespace.get)
        assert(node.heartbeatIntervals === testnodes(0).heartbeatIntervals)
        assert(node.id === testnodes(0).id)
        assert(node.isNamespaceScoped === testnodes(0).isNamespaceScoped)
        assert(node.lastHeartbeat === testnodes(0).lastHeartbeat)
        assert(node.lastUpdated !== testnodes(0).lastUpdated)
        assert(node.msgEndPoint === testnodes(0).msgEndPoint)
        assert(node.name === testnodes(0).name)
        assert(node.nodeType === testnodes(0).nodeType)
        assert(node.orgid === testnodes(0).orgid)
        assert(node.owner === testnodes(0).owner)
        assert(node.pattern === testnodes(0).pattern)
        assert(node.publicKey === testnodes(0).publicKey)
        assert(node.regServices === testnodes(0).regServices)
        assert(node.softwareVersions === testnodes(0).softwareVersions)
        assert(node.token === testnodes(0).token)
        assert(node.userInput === testnodes(0).userInput)
        
        val change: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId like "TestNodePatch").result), AWAITDURATION)
        assert(change.length === 1)
        
        assert(change.head.category === ResChangeCategory.NODE.toString)
        assert(change.head.id === "n2")
        assert(change.head.lastUpdated.toString !== testnodes.head.lastUpdated)
        assert(change.head.operation === ResChangeOperation.MODIFIED.toString)
        assert(change.head.public === "false")
        assert(change.head.resource === ResChangeResource.NODE.toString)
      }, testnodes)
  }
  
  // ---------- heartbeatIntervals
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - heartbeatIntervals - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = Option(NodeHeartbeatIntervals(minInterval = 0, maxInterval = 2, intervalAdjustment = 1)),
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  clusterNamespace   = None,
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.arch === testnodes(0).arch)
        assert(node.clusterNamespace === testnodes(0).clusterNamespace)
        assert(node.id === testnodes(0).id)
        
        val heartbeatIntervals: NodeHeartbeatIntervals = parse(node.heartbeatIntervals).extract[NodeHeartbeatIntervals]
        assert(heartbeatIntervals.intervalAdjustment === input.heartbeatIntervals.get.intervalAdjustment)
        assert(heartbeatIntervals.minInterval === input.heartbeatIntervals.get.minInterval)
        assert(heartbeatIntervals.maxInterval === input.heartbeatIntervals.get.maxInterval)
        
        assert(node.lastHeartbeat === testnodes(0).lastHeartbeat)
        assert(node.lastUpdated !== testnodes(0).lastUpdated)
        assert(node.msgEndPoint === testnodes(0).msgEndPoint)
        assert(node.name === testnodes(0).name)
        assert(node.nodeType === testnodes(0).nodeType)
        assert(node.orgid === testnodes(0).orgid)
        assert(node.owner === testnodes(0).owner)
        assert(node.pattern === testnodes(0).pattern)
        assert(node.publicKey === testnodes(0).publicKey)
        assert(node.regServices === testnodes(0).regServices)
        assert(node.softwareVersions === testnodes(0).softwareVersions)
        assert(node.token === testnodes(0).token)
        assert(node.userInput === testnodes(0).userInput)
      }, testnodes)
  }
  
  // ---------- isNamespaceScoped
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - isNamespaceScoped - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        isNamespaceScoped = Option(false),
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  clusterNamespace   = None,
                  heartbeatIntervals = "",
                  id                 = "TestNodePatch/n2",
                  isNamespaceScoped  = true,
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.arch === testnodes(0).arch)
        assert(node.clusterNamespace === testnodes(0).clusterNamespace)
        assert(node.heartbeatIntervals === testnodes(0).heartbeatIntervals)
        assert(node.id === testnodes(0).id)
        assert(node.isNamespaceScoped === input.isNamespaceScoped.get)
        assert(node.lastHeartbeat === testnodes(0).lastHeartbeat)
        assert(node.lastUpdated !== testnodes(0).lastUpdated)
        assert(node.msgEndPoint === testnodes(0).msgEndPoint)
        assert(node.name === testnodes(0).name)
        assert(node.nodeType === testnodes(0).nodeType)
        assert(node.orgid === testnodes(0).orgid)
        assert(node.owner === testnodes(0).owner)
        assert(node.pattern === testnodes(0).pattern)
        assert(node.publicKey === testnodes(0).publicKey)
        assert(node.regServices === testnodes(0).regServices)
        assert(node.softwareVersions === testnodes(0).softwareVersions)
        assert(node.token === testnodes(0).token)
        assert(node.userInput === testnodes(0).userInput)
      }, testnodes)
  }
  
  // ---------- msgEndPoint
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - msgEndPoint - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = Option("msgEndPoint"),
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.msgEndPoint === input.msgEndPoint.get)
      }, testnodes)
  }
  
  // ---------- token
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - token - empty token - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = Option(""),
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "token",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - token - public key set - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = Option("n2tok"),
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "publicKey",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - token - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = Option("n2tok"),
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(Password.check(plainPw = input.token.get, hashedPw = node.token) === true)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - token - public key set- node") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = Option("n2tok2"),
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "publicKey",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = Password.hash("n2tok"),
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - token - node") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = Option("n2tok2"),
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = Password.hash("n2tok"),
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(Password.check(plainPw = input.token.get, hashedPw = node.token) === true)
      }, testnodes)
  }
  
  // ---------- name
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - name - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = Option("name"),
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.name === input.name.get)
      }, testnodes)
  }
  
  // ---------- nodeType
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - nodeType - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = Option("nodeType"),
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.nodeType === input.nodeType.get)
      }, testnodes)
  }
  
  // ---------- pattern - root
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - publicKey - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "publicKey",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - normal - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - update - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "pattern",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - update blank - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option(""),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "pattern",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - different user - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p2"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - different organization - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch2/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - public - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch2/p2"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  // --------- pattern - organization admin
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - pattern - publicKey - organization admin") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "publicKey",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ADMINAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - normal - organization admin") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ADMINAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - update - organization admin") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "pattern",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ADMINAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - different user - organization admin") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p2"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ADMINAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - pattern - different organization - organization admin") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch2/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ADMINAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - public - organization admin") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch2/p2"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ADMINAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  // --------- pattern - user
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - pattern - publicKey - user") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "publicKey",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - normal - usern") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - update - user") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "pattern",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - pattern - different user - user") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p2"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - different organization - user") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch2/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ADMINAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - public - user") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch2/p2"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  // --------- pattern - node
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - pattern - publicKey - node") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "publicKey",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = Password.hash("n2tok"),
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - normal - node") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = Password.hash("n2tok"),
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - update - node") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "pattern",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = Password.hash("n2tok"),
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - different user - node") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch/p2"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = Password.hash("n2tok"),
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - pattern - different organization - node") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch2/p1"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = Password.hash("n2tok"),
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - pattern - public - node") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = Option("TestNodePatch2/p2"),
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = Password.hash("n2tok"),
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.pattern === input.pattern.get)
      }, testnodes)
  }
  
  // ---------- publicKey
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - publicKey - update - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = Option("new publicKey"),
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "publicKey",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - publicKey - set - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = Option("publicKey"),
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.publicKey === input.publicKey.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - publicKey - unset - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = Option(""),
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "publicKey",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.publicKey === input.publicKey.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - publicKey - unset empty - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = Option(""),
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.publicKey === input.publicKey.get)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 403 access denied - publicKey - set - admin") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = Option("publicKey"),
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ADMINAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Forbidden.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 403 access denied - publicKey - set - user") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = Option("publicKey"),
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Forbidden.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - publicKey - set - node") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = Option("publicKey"),
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = Password.hash("n2tok"),
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        
        assert(node.publicKey === input.publicKey.get)
      }, testnodes)
  }
  
  // ---------- registeredServices
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - registeredServices - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices =
                          Option(List(RegService(configState = None,
                                                 numAgreements = 0,
                                                 policy = "policy",
                                                 properties = List(),
                                                 url = "url",
                                                 version = None),
                                      RegService(configState = Option("configState"),
                                                 numAgreements = 1,
                                                 policy = "policy1",
                                                 properties =
                                                   List(Prop(name = "name",
                                                             op = "op",
                                                             propType = "propType",
                                                             value = "value"),
                                                        Prop(name = "name1",
                                                             op = "op2",
                                                             propType = "propType3",
                                                             value = "value4")),
                                                 url = "url1",
                                                 version = Option("version")))),
                        softwareVersions = None,
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        val services: Seq[RegService] = parse(node.regServices).extract[Seq[RegService]]
        assert(services.nonEmpty)
        assert(services.length === 2)
        
        assert(services(0).configState       === input.registeredServices.get(0).configState)
        assert(services(0).numAgreements     === input.registeredServices.get(0).numAgreements)
        assert(services(0).policy            === input.registeredServices.get(0).policy)
        assert(services(0).configState       === input.registeredServices.get(0).configState)
        assert(services(0).properties.length === input.registeredServices.get(0).properties.length)
        assert(services(0).url               === input.registeredServices.get(0).url)
        assert(services(0).version           === input.registeredServices.get(0).version)
        
        assert(services(1).configState            === input.registeredServices.get(1).configState)
        assert(services(1).configState.get        === input.registeredServices.get(1).configState.get)
        assert(services(1).numAgreements          === input.registeredServices.get(1).numAgreements)
        assert(services(1).policy                 === input.registeredServices.get(1).policy)
        assert(services(1).configState            === input.registeredServices.get(1).configState)
        assert(services(1).properties.length      === input.registeredServices.get(1).properties.length)
        assert(services(1).properties(0).name     === input.registeredServices.get(1).properties(0).name)
        assert(services(1).properties(0).op       === input.registeredServices.get(1).properties(0).op)
        assert(services(1).properties(0).propType === input.registeredServices.get(1).properties(0).propType)
        assert(services(1).properties(0).value    === input.registeredServices.get(1).properties(0).value)
        assert(services(1).properties(1).name     === input.registeredServices.get(1).properties(1).name)
        assert(services(1).properties(1).op       === input.registeredServices.get(1).properties(1).op)
        assert(services(1).properties(1).propType === input.registeredServices.get(1).properties(1).propType)
        assert(services(1).properties(1).value    === input.registeredServices.get(1).properties(1).value)
        assert(services(1).url                    === input.registeredServices.get(1).url)
        assert(services(1).version                === input.registeredServices.get(1).version)
        assert(services(1).version.get            === input.registeredServices.get(1).version.get)
      }, testnodes)
  }
  
  // ---------- softwareVersions
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - softwareVersions - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = Option(Map(("some", "thing"),("some1", "thing1"))),
                        userInput = None)
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        val versions: Map[String, String] = parse(node.softwareVersions).extract[Map[String, String]]
        assert(versions.nonEmpty)
        assert(versions.size === 2)
        
        assert(versions.contains("some"))
        assert(versions("some") === input.softwareVersions.get("some"))
        assert(versions.contains("some1"))
        assert(versions("some1") === input.softwareVersions.get("some1"))
      }, testnodes)
  }
  
  // ---------- userInput
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - userInput - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput =
                          Option(List(
                            OneUserInputService(inputs =
                                                  List(OneUserInputValue(name = "input1", value = "value1"),
                                                       OneUserInputValue(name = "input2", value = "value2")),
                                                serviceArch = Option("amd64"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service1",
                                                serviceVersionRange = Option("[1.2.3,1.2.3]")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("i%6"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service2",
                                                serviceVersionRange = Option("[1.2.3,4.5.6)")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service3",
                                                serviceVersionRange = Option("%")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch2",
                                                serviceUrl = "TestNodePatch2.service1",
                                                serviceVersionRange = Option("%")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch2",
                                                serviceUrl = "TestNodePatch2.service2",
                                                serviceVersionRange = Option("%")))))
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        assert(node.userInput.nonEmpty)
        
        val userInputs: Seq[OneUserInputService] = parse(node.userInput).extract[Seq[OneUserInputService]]
        
        assert(userInputs(0).inputs(0).name === input.userInput.get(0).inputs(0).name)
        assert(userInputs(0).inputs(0).value === input.userInput.get(0).inputs(0).value)
        assert(userInputs(0).inputs(1).name === input.userInput.get(0).inputs(1).name)
        assert(userInputs(0).inputs(1).value === input.userInput.get(0).inputs(1).value)
        assert(userInputs(0).serviceArch === input.userInput.get(0).serviceArch)
        assert(userInputs(0).serviceOrgid === input.userInput.get(0).serviceOrgid)
        assert(userInputs(0).serviceUrl === input.userInput.get(0).serviceUrl)
        assert(userInputs(0).serviceVersionRange === input.userInput.get(0).serviceVersionRange)
        
        assert(userInputs(1).inputs.length === input.userInput.get(1).inputs.length)
        assert(userInputs(1).serviceArch === input.userInput.get(1).serviceArch)
        assert(userInputs(1).serviceOrgid === input.userInput.get(1).serviceOrgid)
        assert(userInputs(1).serviceUrl === input.userInput.get(1).serviceUrl)
        assert(userInputs(1).serviceVersionRange === input.userInput.get(1).serviceVersionRange)
        
        assert(userInputs(2).inputs.length === input.userInput.get(2).inputs.length)
        assert(userInputs(2).serviceArch === input.userInput.get(2).serviceArch)
        assert(userInputs(2).serviceOrgid === input.userInput.get(2).serviceOrgid)
        assert(userInputs(2).serviceUrl === input.userInput.get(2).serviceUrl)
        assert(userInputs(2).serviceVersionRange === input.userInput.get(2).serviceVersionRange)
        
        assert(userInputs(3).inputs.length === input.userInput.get(3).inputs.length)
        assert(userInputs(3).serviceArch === input.userInput.get(3).serviceArch)
        assert(userInputs(3).serviceOrgid === input.userInput.get(3).serviceOrgid)
        assert(userInputs(3).serviceUrl === input.userInput.get(3).serviceUrl)
        assert(userInputs(3).serviceVersionRange === input.userInput.get(3).serviceVersionRange)
        
        assert(userInputs(4).inputs.length === input.userInput.get(4).inputs.length)
        assert(userInputs(4).serviceArch === input.userInput.get(4).serviceArch)
        assert(userInputs(4).serviceOrgid === input.userInput.get(4).serviceOrgid)
        assert(userInputs(4).serviceUrl === input.userInput.get(4).serviceUrl)
        assert(userInputs(4).serviceVersionRange === input.userInput.get(4).serviceVersionRange)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - userInput - update empty - root") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput = Option(List()))
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = "userInput"))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        assert(node.userInput.nonEmpty)
        
        val userInputs: Seq[OneUserInputService] = parse(node.userInput).extract[Seq[OneUserInputService]]
        assert(userInputs.isEmpty)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - userInput - organization admin") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput =
                          Option(List(
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("amd64"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service1",
                                                serviceVersionRange = Option("[1.2.3,1.2.3]")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("i%6"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service2",
                                                serviceVersionRange = Option("[1.2.3,4.5.6)")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service3",
                                                serviceVersionRange = Option("%")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch2",
                                                serviceUrl = "TestNodePatch2.service1",
                                                serviceVersionRange = Option("%")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch2",
                                                serviceUrl = "TestNodePatch2.service2",
                                                serviceVersionRange = Option("%")))))
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ADMINAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - userInput - organization admin") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput =
                          Option(List(
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("amd64"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service1",
                                                serviceVersionRange = Option("[1.2.3,1.2.3]")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("i%6"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service2",
                                                serviceVersionRange = Option("[1.2.3,4.5.6)")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service3",
                                                serviceVersionRange = Option("%")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch2",
                                                serviceUrl = "TestNodePatch2.service2",
                                                serviceVersionRange = Option("%")))))
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ADMINAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - userInput - user") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput =
                          Option(List(
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("amd64"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service1",
                                                serviceVersionRange = Option("[1.2.3,1.2.3]")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("i%6"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service2",
                                                serviceVersionRange = Option("[1.2.3,4.5.6)")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service3",
                                                serviceVersionRange = Option("%")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch2",
                                                serviceUrl = "TestNodePatch2.service1",
                                                serviceVersionRange = Option("%")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch2",
                                                serviceUrl = "TestNodePatch2.service2",
                                                serviceVersionRange = Option("%")))))
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - userInput - user") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput =
                          Option(List(
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("amd64"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service1",
                                                serviceVersionRange = Option("[1.2.3,1.2.3]")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("i%6"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service2",
                                                serviceVersionRange = Option("[1.2.3,4.5.6)")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service3",
                                                serviceVersionRange = Option("%")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch2",
                                                serviceUrl = "TestNodePatch2.service2",
                                                serviceVersionRange = Option("%")))))
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch3@somecomp.com" + "/nodes/" + "an12345" + " -- 201 ok - userInput - user") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput =
                          Option(List(
                            OneUserInputService(inputs =
                                                  List(OneUserInputValue(name = "var1", value = "aString"),
                                                       OneUserInputValue(name = "var2", value = 5),
                                                       OneUserInputValue(name = "var3", value = 10.2),
                                                       OneUserInputValue(name = "var4", value = Seq("abc","123")),
                                                       OneUserInputValue(name = "var5", value = "override")),
                                                serviceArch = Option("amd64"),
                                                serviceOrgid = "TestNodePatch3@somecomp.com",
                                                serviceUrl = "https://bluehorizon.network/services/testservice",
                                                serviceVersionRange = Option("[0.0.0,INFINITY)")))))
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch3@somecomp.com/an12345",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch3@somecomp.com",
                  owner              = TESTUSERS(4).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = "",
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch3@somecomp.com" + "/nodes/" + "an12345").postData(write(input)).method("PATCH").headers(CONTENT).headers(ACCEPT).headers(("Authorization", "Basic " + ApiUtils.encode("TestNodePatch3@somecomp.com" + "/" + "user" + ":" + "userpw"))).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
        
        val node: NodeRow = Await.result(DBCONNECTION.run(NodesTQ.filter(_.id === testnodes.head.id).result), AWAITDURATION).head
        assert(node.userInput.nonEmpty)
        
        val userInputs: Seq[OneUserInputService] = parse(node.userInput).extract[Seq[OneUserInputService]]
        assert(userInputs.length === 1)
        
        assert(userInputs.head.inputs.length       === 5)
        assert(userInputs.head.inputs(0).name      === input.userInput.get.head.inputs(0).name)
        assert(userInputs.head.inputs(0).value     === input.userInput.get.head.inputs(0).value)
        assert(userInputs.head.inputs(1).name      === input.userInput.get.head.inputs(1).name)
        assert(userInputs.head.inputs(1).value     === input.userInput.get.head.inputs(1).value)
        assert(userInputs.head.inputs(2).name      === input.userInput.get.head.inputs(2).name)
        assert(userInputs.head.inputs(2).value     === input.userInput.get.head.inputs(2).value)
        assert(userInputs.head.inputs(3).name      === input.userInput.get.head.inputs(3).name)
        assert(userInputs.head.inputs(3).value     === input.userInput.get.head.inputs(3).value)
        assert(userInputs.head.inputs(4).name      === input.userInput.get.head.inputs(4).name)
        assert(userInputs.head.inputs(4).value     === input.userInput.get.head.inputs(4).value)
        assert(userInputs.head.serviceArch         === input.userInput.get.head.serviceArch)
        assert(userInputs.head.serviceOrgid        === input.userInput.get.head.serviceOrgid)
        assert(userInputs.head.serviceUrl          === input.userInput.get.head.serviceUrl)
        assert(userInputs.head.serviceVersionRange === input.userInput.get.head.serviceVersionRange)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 400 bad input - userInput - node") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput =
                          Option(List(
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("amd64"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service1",
                                                serviceVersionRange = Option("[1.2.3,1.2.3]")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("i%6"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service2",
                                                serviceVersionRange = Option("[1.2.3,4.5.6)")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service3",
                                                serviceVersionRange = Option("%")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch2",
                                                serviceUrl = "TestNodePatch2.service1",
                                                serviceVersionRange = Option("%")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch2",
                                                serviceUrl = "TestNodePatch2.service2",
                                                serviceVersionRange = Option("%")))))
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = Password.hash("n2tok"),
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.BadRequest.intValue)
      }, testnodes)
  }
  
  test("PATCH /v1/orgs/" + "TestNodePatch" + "/nodes/" + "n2" + " -- 201 ok - userInput - node") {
    val input: PatchNodesRequest =
      PatchNodesRequest(arch = None,
                        clusterNamespace = None,
                        heartbeatIntervals = None,
                        msgEndPoint = None,
                        token = None,
                        name = None,
                        nodeType = None,
                        pattern = None,
                        publicKey = None,
                        registeredServices = None,
                        softwareVersions = None,
                        userInput =
                          Option(List(
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("amd64"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service1",
                                                serviceVersionRange = Option("[1.2.3,1.2.3]")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("i%6"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service2",
                                                serviceVersionRange = Option("[1.2.3,4.5.6)")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch",
                                                serviceUrl = "TestNodePatch.service3",
                                                serviceVersionRange = Option("%")),
                            OneUserInputService(inputs = List(),
                                                serviceArch = Option("%"),
                                                serviceOrgid = "TestNodePatch2",
                                                serviceUrl = "TestNodePatch2.service2",
                                                serviceVersionRange = Option("%")))))
    val testnodes: Seq[NodeRow] =
      Seq(NodeRow(arch               = "",
                  id                 = "TestNodePatch/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat      = None,
                  lastUpdated        = ApiTime.nowUTC,
                  msgEndPoint        = "",
                  name               = "",
                  nodeType           = "",
                  orgid              = "TestNodePatch",
                  owner              = TESTUSERS(0).user,
                  pattern            = "",
                  publicKey          = "",
                  regServices        = "",
                  softwareVersions   = "",
                  token              = Password.hash("n2tok"),
                  userInput          = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + "TestNodePatch" + "/nodes/" + "n2").postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(NODEAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Created.intValue)
      }, testnodes)
  }
}
