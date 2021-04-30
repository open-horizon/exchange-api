package com.horizon.exchangeapi

import com.horizon.exchangeapi._
import com.horizon.exchangeapi.tables.{PServiceVersions, PServices}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import scala.collection.immutable._
import scalaj.http._

import scala.collection.mutable.ListBuffer

// Tests the catalog APIs

@RunWith(classOf[JUnitRunner])
class CatalogSuite extends AnyFunSuite {

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  val localUrlRoot = "http://localhost:8080"
  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", localUrlRoot)
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val orgid = "CatalogSuiteTests1"    // this will be orgType: IBM
  val orgid2 = "CatalogSuiteTests2"    // this will be orgType: IBM
  val orgid3 = "CatalogSuiteTests3"    // this will be orgType: ""
  val URL = urlRoot+"/v1/orgs/"+orgid
  val URL2 = urlRoot+"/v1/orgs/"+orgid2
  val URL3 = urlRoot+"/v1/orgs/"+orgid3
  // A user in each org
  val user = "u1"
  val pw = user+"pw"
  val orguser = orgid+"/"+user
  val USERAUTH = ("Authorization","Basic "+ApiUtils.encode(orguser+":"+pw))
  val orguser2 = orgid2+"/"+user
  val USERAUTH2 = ("Authorization","Basic "+ApiUtils.encode(orguser2+":"+pw))
  val orguser3 = orgid3+"/"+user
  val USERAUTH3 = ("Authorization","Basic "+ApiUtils.encode(orguser3+":"+pw))
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+ApiUtils.encode(rootuser+":"+rootpw))
  // node and agbot in the 1st org
  val nodeId = "n1"     // the 1st node created, that i will use to run some rest methods
  val nodeToken = nodeId+"tok"
  val NODEAUTH = ("Authorization","Basic "+ApiUtils.encode(orgid+"/"+nodeId+":"+nodeToken))
  val agbotId = "a1"
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+ApiUtils.encode(orgid+"/"+agbotId+":"+agbotToken))
  // A public service in each org and a private one in the 1st org
  val svcUrl = "s1"
  val svcVersion = "1.0.0"
  val svcArch = "amd64"
  val service = svcUrl + "_" + svcVersion + "_" + svcArch
  val orgservice = orgid+"/"+service
  val svcUrlPriv = "spriv"
  val svcVersionPriv = "8.0.0"
  val svcArchPriv = "amd64"
  val servicePriv = svcUrlPriv + "_" + svcVersionPriv + "_" + svcArchPriv
  val orgservicePriv = orgid+"/"+servicePriv
  val svcUrl2 = "s2"
  val svcVersion2 = "2.0.0"
  val svcArch2 = "arm"
  val service2 = svcUrl2 + "_" + svcVersion2 + "_" + svcArch2
  val orgservice2 = orgid2+"/"+service2
  val svcUrl3 = "s3"
  val svcVersion3 = "3.0.0"
  val svcArch3 = "arm64"
  val service3 = svcUrl3 + "_" + svcVersion3 + "_" + svcArch3
  val orgservice3 = orgid3+"/"+service3
  val pattern = "p1"
  val orgpattern = orgid+"/"+pattern
  val patternPriv = "ppriv"
  val orgpatternPriv = orgid+"/"+patternPriv
  val pattern2 = "p2"
  val orgpattern2 = orgid2+"/"+pattern2
  val pattern3 = "p3"
  val orgpattern3 = orgid3+"/"+pattern3
  val orgsList = List(orgid, orgid2, orgid3)

  def deleteAllOrgs() = {
    for (url <- List(URL,URL2,URL3)) {
      val response = Http(url).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+url+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    }
  }

  // Delete all the test orgs, in case they are left over from previous test
  test("Begin - DELETE all test users") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")
    deleteAllOrgs()
  }

  // Create all the orgs
  test("POST /orgs/"+orgid+" - create org") {
    for (url <- List(URL, URL2, URL3)) {
      val orgType = if (url == URL3) "" else "IBM"
      val input = PostPutOrgRequest(Some(orgType), "", "desc", None, None, None)
      val response = Http(url).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)
    }
  }

  /* test("GET /orgs") {
    val response: HttpResponse[String] = Http(urlRoot + "/v1/orgs").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
  } */

  // Add users, node, agbot, for future tests
  test("Add users, node, agbot for future tests") {
    for (url <- List(URL,URL2,URL3)) {
      val userInput = PostPutUsersRequest(pw, admin = false, Some(false), user + "@hotmail.com")
      val userResponse = Http(url + "/users/" + user).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + userResponse.code + ", userResponse.body: " + userResponse.body)
      assert(userResponse.code === HttpCode.POST_OK.intValue)
    }

    val devInput = PutNodesRequest(nodeToken, "", None, "", None, None, None, None, "", None, None)
    val devResponse = Http(URL+"/nodes/"+nodeId).postData(write(devInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+devResponse.code)
    assert(devResponse.code === HttpCode.PUT_OK.intValue)

    val agbotInput = PutAgbotsRequest(agbotToken, "", None, "ABC")
    val agbotResponse = Http(URL+"/agbots/"+agbotId).postData(write(agbotInput)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+agbotResponse.code+", agbotResponse.body: "+agbotResponse.body)
    assert(agbotResponse.code === HttpCode.PUT_OK.intValue)
  }

  // Create a service in each org and an extra private service
  test("POST /orgs/"+orgid+"/services - add services in each org") {
    var input = PostPutServiceRequest("", None, public = true, None, svcUrl, svcVersion, svcArch, "multiple", None, None, None, None, Some("{\"services\":{}}"),Some("a"),None, None, None)
    var response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    input = PostPutServiceRequest("", None, public = false, None, svcUrlPriv, svcVersionPriv, svcArchPriv, "multiple", None, None, None, None, Some("{\"services\":{}}"),Some("a"),None, None, None)
    response = Http(URL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    input = PostPutServiceRequest("", None, public = true, None, svcUrl2, svcVersion2, svcArch2, "multiple", None, None, None, None, Some("{\"services\":{}}"),Some("a"),None, None, None)
    response = Http(URL2+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH2).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    input = PostPutServiceRequest("", None, public = true, None, svcUrl3, svcVersion3, svcArch3, "multiple", None, None, None,None, Some("{\"services\":{}}"),Some("a"),None, None, None)
    response = Http(URL3+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH3).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  // Query to catalog services and check them
  test("GET /catalog/services") {
    val response: HttpResponse[String] = Http(urlRoot+"/v1/catalog/services").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    //info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetServicesResponse]
    //assert(respObj.services.size === 2)  // can't check the size, because my IBM org might contain some

    // Verify the services that should be there are
    assert(respObj.services.contains(orgservice))
    var svc = respObj.services(orgservice)
    assert(svc.url === svcUrl)
    assert(svc.version === svcVersion)
    assert(svc.arch === svcArch)

    assert(respObj.services.contains(orgservice2))
    svc = respObj.services(orgservice2)
    assert(svc.url === svcUrl2)
    assert(svc.version === svcVersion2)
    assert(svc.arch === svcArch2)

    // Verify the services that shouldn't be there aren't
    assert(!respObj.services.contains(orgservicePriv))
    assert(!respObj.services.contains(orgservice3))
  }

  // Create a pattern in each org and an extra private pattern
  test("POST /orgs/"+orgid+"/patterns - add patterns in each org") {
    var input = PostPutPatternRequest(pattern, Some(pattern), Some(true), List( PServices(svcUrl, orgid, svcArch, None, List(PServiceVersions(svcVersion, None, None, None, None)), None, None )), None, None, None)
    var response = Http(URL+"/patterns/"+pattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    input = PostPutPatternRequest(patternPriv, Some(patternPriv), Some(false), List( PServices(svcUrlPriv, orgid, svcArchPriv, None, List(PServiceVersions(svcVersionPriv, None, None, None, None)), None, None )), None, None, None)
    response = Http(URL+"/patterns/"+patternPriv).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    input = PostPutPatternRequest(pattern2, Some(pattern2), Some(true), List( PServices(svcUrl2, orgid2, svcArch2, None, List(PServiceVersions(svcVersion2, None, None, None, None)), None, None )), None, None, None)
    response = Http(URL2+"/patterns/"+pattern2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH2).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)

    input = PostPutPatternRequest(pattern3, Some(pattern3), Some(false), List( PServices(svcUrl3, orgid3, svcArch3, None, List(PServiceVersions(svcVersion3, None, None, None, None)), None, None )), None, None, None)
    response = Http(URL3+"/patterns/"+pattern3).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH3).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  // Query to catalog patterns and check them
  test("GET /catalog/patterns") {
    val response: HttpResponse[String] = Http(urlRoot+"/v1/catalog/patterns").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    //info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetPatternsResponse]
    //assert(respObj.patterns.size === 2)  // can't check the size, because my IBM org might contain some

    // Verify the patterns that should be there are
    assert(respObj.patterns.contains(orgpattern))
    var pat = respObj.patterns(orgpattern)
    assert(pat.label === pattern)

    assert(respObj.patterns.contains(orgpattern2))
    pat = respObj.patterns(orgpattern2)
    assert(pat.label === pattern2)

    // Verify the patterns that shouldn't be there aren't
    assert(!respObj.patterns.contains(orgpatternPriv))
    assert(!respObj.patterns.contains(orgpattern3))
  }

  val IBMURL = urlRoot+"/v1/orgs/IBM"
  val ibmSvcBase = "service-only-for-catalog-automated-tests"   // needs to be different from the IBM svc created in ServicesSuite
  val ibmSvcUrl = "http://" + ibmSvcBase
  val ibmSvcVersion = "9.7.5"
  val ibmSvcArch = "arm"
  val ibmService = ibmSvcBase + "_" + ibmSvcVersion + "_" + ibmSvcArch
  val ibmOrgService = "IBM/"+ibmService
  val ibmPattern = "pattern-only-for-automated-tests"
  val ibmOrgPattern = "IBM/"+ibmPattern

  test("POST /orgs/IBM/services - add IBM service so patterns can reference it") {
    val input = PostPutServiceRequest("IBMTestSvc", Some("desc"), public = true, None, ibmSvcUrl, ibmSvcVersion, ibmSvcArch, "single", None, None, None, None, Some("{\"services\":{}}"),Some("a"), None, None, None)
    val response = Http(IBMURL+"/services").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/IBM/patterns/"+ibmPattern+" - add "+ibmPattern+" as root") {
    val input = PostPutPatternRequest(ibmPattern, None, Some(true),
      List( PServices(ibmSvcUrl, "IBM", ibmSvcArch, None, List(PServiceVersions(ibmSvcVersion, None, None, None, None)), None, None )),
      None, None, None
    )
    val response = Http(IBMURL+"/patterns/"+ibmPattern).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    val respObj = parse(response.body).extract[ApiResponse]
    assert(respObj.msg.contains("pattern '"+ibmOrgPattern+"' created"))
  }

  test("GET /catalog/"+orgid3+"/patterns") {
    val response: HttpResponse[String] = Http(urlRoot+"/v1/catalog/"+orgid3+"/patterns").headers(ACCEPT).headers(USERAUTH3).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetPatternsResponse]

    // Verify the patterns that should be there are
    assert(respObj.patterns.contains(orgpattern))
    var pat = respObj.patterns(orgpattern)
    assert(pat.label === pattern)

    assert(respObj.patterns.contains(orgpattern2))
    pat = respObj.patterns(orgpattern2)
    assert(pat.label === pattern2)

    assert(respObj.patterns.contains(orgpattern3))
    pat = respObj.patterns(orgpattern3)
    assert(pat.label === pattern3)

    assert(respObj.patterns.contains(ibmOrgPattern))

    // shouldn't contain private pattern from non-caller org
    assert(!respObj.patterns.contains(orgpatternPriv))
  }

  test("GET /catalog/"+orgid3+"/patterns by user who can't see " + orgid3) {
    val response: HttpResponse[String] = Http(urlRoot+"/v1/catalog/"+orgid3+"/patterns").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetPatternsResponse]

    // Verify the patterns that should be there are
    assert(respObj.patterns.contains(orgpattern))
    var pat = respObj.patterns(orgpattern)
    assert(pat.label === pattern)

    assert(respObj.patterns.contains(orgpattern2))
    pat = respObj.patterns(orgpattern2)
    assert(pat.label === pattern2)

    assert(respObj.patterns.contains(ibmOrgPattern))

    assert(!respObj.patterns.contains(orgpattern3))

    //doesn't include their own private pattern because made the call to the wrong org
    assert(!respObj.patterns.contains(orgpatternPriv))
  }

  test("GET /catalog/"+orgid+"/patterns") {
    val response: HttpResponse[String] = Http(urlRoot+"/v1/catalog/"+orgid+"/patterns").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetPatternsResponse]

    // Verify the patterns that should be there are
    assert(respObj.patterns.contains(orgpattern))
    var pat = respObj.patterns(orgpattern)
    assert(pat.label === pattern)

    assert(respObj.patterns.contains(orgpattern2))
    pat = respObj.patterns(orgpattern2)
    assert(pat.label === pattern2)

    assert(respObj.patterns.contains(ibmOrgPattern))

    // shouldn't include non-IBM and non-caller-org patterns, even if public
    assert(!respObj.patterns.contains(orgpattern3))
    // should include their own private pattern
    assert(respObj.patterns.contains(orgpatternPriv))
  }

  test("GET /catalog/"+orgid3+"/services") {
    val response: HttpResponse[String] = Http(urlRoot+"/v1/catalog/"+orgid3+"/services").headers(ACCEPT).headers(USERAUTH3).asString
    info("code: "+response.code)
    //info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
    val respObj = parse(response.body).extract[GetServicesResponse]
    //assert(respObj.services.size === 2)  // can't check the size, because my IBM org might contain some

    // Verify the services that should be there are
    assert(respObj.services.contains(orgservice))
    var svc = respObj.services(orgservice)
    assert(svc.url === svcUrl)
    assert(svc.version === svcVersion)
    assert(svc.arch === svcArch)

    assert(respObj.services.contains(orgservice2))
    svc = respObj.services(orgservice2)
    assert(svc.url === svcUrl2)
    assert(svc.version === svcVersion2)
    assert(svc.arch === svcArch2)

    assert(respObj.services.contains(orgservice3))
    svc = respObj.services(orgservice3)
    assert(svc.url === svcUrl3)
    assert(svc.version === svcVersion3)
    assert(svc.arch === svcArch3)

    assert(respObj.services.contains(ibmOrgService))

    // shouldn't contain private service from non-caller org
    assert(!respObj.services.contains(orgservicePriv))
  }

  test("DELETE /orgs/IBM/patterns/"+ibmPattern) {
    val response = Http(IBMURL+"/patterns/"+ibmPattern).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("GET /orgs/IBM/patterns/"+ibmPattern+" - as user - verify gone") {
    val response: HttpResponse[String] = Http(IBMURL+"/patterns/"+ibmPattern).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    //assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("DELETE /orgs/IBM/services/"+ibmService) {
    val response = Http(IBMURL+"/services/"+ibmService).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("DELETE all orgs to clean up") {
    deleteAllOrgs()
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
