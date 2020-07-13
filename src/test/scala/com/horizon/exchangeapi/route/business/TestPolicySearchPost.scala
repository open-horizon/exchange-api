package com.horizon.exchangeapi.route.business

import com.horizon.exchangeapi.{ApiUtils, BusinessRoutes, HttpCode, PostBusinessPolicySearchRequest, PostBusinessPolicySearchResponse, PostPutBusinessPolicyRequest, PostPutOrgRequest, PostPutServiceRequest, PostPutUsersRequest, PutAgbotsRequest, Role}
import com.horizon.exchangeapi.tables.{BService, BServiceVersions, OneProperty, SearchOffsetPolicyTQ}
import org.json4s.jackson.JsonMethods.parse
import org.json4s.{DefaultFormats, Formats, JValue, JsonInput, jvalue2extractable, string2JsonInput}
import org.json4s.native.Serialization.write
import org.junit.runner.RunWith
import org.scalactic.Bad
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.{Await, Future}

@RunWith(classOf[JUnitRunner])
class TestPolicySearchPost extends AnyFunSuite with BeforeAndAfterAll {
  val ACCEPT: (String, String) = ("Content-Type", "application/json")
  val AGBOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestPolicySearchPost/a1" + ":" + "a1tok"))
  val CONTENT: (String, String) = ACCEPT
  val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/" + "TestPolicySearchPost"
  val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestPolicySearchPost/u1" + ":" + "u1pw"))
  
  
  implicit val formats: Formats = DefaultFormats.withLong
  
  override def beforeAll() {
    Http(URL).postData(write(PostPutOrgRequest(None, "TestPolicySearchPost", "desc", None, None))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    Http(URL + "/users/" + "u1").postData(write(PostPutUsersRequest("u1pw", admin = false, "u1" + "@hotmail.com"))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    Http(URL + "/agbots/" + "a1").postData(write(PutAgbotsRequest("a1tok", "a1" + "name", None, "AGBOTABC"))).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    Http(URL+"/services").postData(write(PostPutServiceRequest("svc1", None, public = false, None, "bluehorizon.network.sdr", "1.0.0", "amd64", "multiple", None, None, None, Some(""), Some(""), None, None, None))).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    Http(URL + "/business/policies/" + "pol1").postData(write(PostPutBusinessPolicyRequest("pol1", Some("desc"), BService("bluehorizon.network.sdr", "TestPolicySearchPost", "*", List(BServiceVersions("1.0.0", None, None)), None ), None, Some(List(OneProperty("purpose",None,"location"))), Some(List("a == b"))))).method("post").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
  }
  
  override def afterAll(): Unit = {
    Http(URL + "/orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    Http(URL + "/orgs/" + "TestPolicySearchPost" + "/services/" + "svc1").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    Http(URL + "/orgs/" + "TestPolicySearchPost" + "/agbots/" + "a1").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    Http(URL + "/orgs/" + "TestPolicySearchPost" + "/users/" + "u1").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    Http(URL + "/orgs/" + "TestPolicySearchPost").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
  }
  
  // Http Code 400 Failure States
  test("POST /org/" + "TestPolicySearchPost" + "/business/policy/" + "pol1" + "/search -- 400 Bad request - changeSince < 0L") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(-1L, None, None, None))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("POST /org/" + "TestPolicySearchPost" + "/business/policy/" + "pol1" + "/search -- 400 Bad request - ignoreOffset = false") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, Some(false), None, None))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("POST /org/" + "TestPolicySearchPost" + "/business/policy/" + "pol1" + "/search -- 400 Bad request - ignoreOffset = true && changedSince = 0L") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, Some(true), None, None))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("POST /org/" + "TestPolicySearchPost" + "/business/policy/" + "pol1" + "/search -- 400 Bad request - numEntries < 0") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some(-1)))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  ignore("POST /org/" + "TestPolicySearchPost" + "/business/policy/" + "pol1" + "/search -- Initial API Call From Agbot - No Nodes") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, None))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val responseBody = parse(response.body).extract[PostBusinessPolicySearchResponse]
    assert(responseBody.nodes.isEmpty)
    assert(responseBody.offsetUpdated === false)
    
    /*db.run(SearchOffsetPolicyTQ.getOffset("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result)
      .map(
        offset ⇒ {
          assert(offset.nonEmpty)
          assert(offset.size === 1)
          assert(offset.head.isEmpty)
        }
      )*/
  }
}
