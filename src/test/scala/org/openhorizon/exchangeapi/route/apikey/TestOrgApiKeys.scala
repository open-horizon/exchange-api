package org.openhorizon.exchangeapi.route.apikey

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.openhorizon.exchangeapi.auth.{Identity, Role}
import org.openhorizon.exchangeapi.table.apikey.{ApiKeyMetadata, ApiKeyRow, ApiKeysTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiUtils, Configuration, DatabaseConnection, ExchMsg}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, ExchMsg, HttpCode}
import scalaj.http.{Http, HttpResponse}
import scala.concurrent.Await
import org.openhorizon.exchangeapi.auth.{Password, Role}
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.ExecutionContext.Implicits.global
import org.openhorizon.exchangeapi.utility.HttpCode

class TestOrgApiKeys extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val PASSWORD = "password"
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  private val TESTORGS: Seq[OrgRow] = Seq(
    OrgRow(
      description = "",
      heartbeatIntervals = "",
      label = "",
      lastUpdated = "",
      limits = "",
      orgId = "testOrg0",
      orgType = "",
      tags = None
    ),
    OrgRow(
      description = "",
      heartbeatIntervals = "",
      label = "",
      lastUpdated = "",
      limits = "",
      orgId = "testOrg1",
      orgType = "",
      tags = None
    )
  )
  
  private val TESTUSERS: Seq[UserRow] = Seq(
    UserRow(
      admin = true,
      email = "",
      hashedPw = Password.hash(PASSWORD),
      hubAdmin = false,
      lastUpdated = "",
      orgid = "testOrg0",
      updatedBy = "",
      username = "testOrg0/admin0"
    ),
    UserRow(
      admin = false,
      email = "",
      hashedPw = Password.hash(PASSWORD),
      hubAdmin = false,
      lastUpdated = "",
      orgid = "testOrg0",
      updatedBy = "",
      username = "testOrg0/user0"
    ),
    UserRow(
      admin = true,
      email = "",
      hashedPw = Password.hash(PASSWORD),
      hubAdmin = true,
      lastUpdated = "",
      orgid = "root",
      updatedBy = "",
      username = "root/hubadmin0"
    )
  )
  
  private val TESTAPIKEYS: Seq[ApiKeyRow] = Seq(
    ApiKeyRow(
      orgid = "testOrg0",
      id = "key1",
      username = "testOrg0/admin0",
      description = "Test API Key 1",
      hashedKey = "hash1"
    ),
    ApiKeyRow(
      orgid = "testOrg0",
      id = "key2",
      username = "testOrg0/user0",
      description = "Test API Key 2",
      hashedKey = "hash2"
    ),
    ApiKeyRow(
      orgid = "testOrg1",
      id = "key3",
      username = "root/hubadmin0",
      description = "Test API Key 3",
      hashedKey = "hash3"
    )
  )
  
  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("testOrg0/admin0:password"))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode("testOrg0/user0:password"))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("root/hubadmin0:password"))
 override def beforeAll(): Unit = {
    val setupAction = DBIO.seq(
      OrgsTQ ++= TESTORGS,
      UsersTQ ++= TESTUSERS,
      ApiKeysTQ ++= TESTAPIKEYS
    ).map(_ => TESTAPIKEYS.length)
    
    val result = Await.result(DBCONNECTION.run(setupAction.transactionally), AWAITDURATION)
    assert(result == TESTAPIKEYS.length,
           s"Failed to insert all API keys: expected=${TESTAPIKEYS.length}, actual=$result")
  }
  
override def afterAll(): Unit = {
  Await.ready(DBCONNECTION.run(
    ApiKeysTQ.filter(_.orgid startsWith "testOrg").delete
  ), AWAITDURATION)
  Await.ready(DBCONNECTION.run(
    UsersTQ.filter(_.username startsWith "testOrg").delete andThen
    UsersTQ.filter(_.username === "root/hubadmin0").delete
  ), AWAITDURATION)
  Await.ready(DBCONNECTION.run(
    OrgsTQ.filter(_.orgid startsWith "testOrg").delete
  ), AWAITDURATION)
}
private def validateAuthHeaders(headers: Seq[(String, String)]): Unit = {
  assert(headers.exists { case (k, v) => 
    k == "Authorization" && v.startsWith("Basic ")
  }, "Authorization header must be present and start with 'Basic '")
}

  test("GET /v1/orgs/{orgid}/apikeys -- org admin - 200 ok - normal success") {
     validateAuthHeaders(Seq(ACCEPT, ORGADMINAUTH))
    val response = Http("http://localhost:8080/v1/orgs/testOrg0/apikeys")
      .headers(ACCEPT)
      .headers(ORGADMINAUTH)
      .asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === 200)
    val apikeys: GetOrgApiKeysResponse = JsonMethods.parse(response.body).extract[GetOrgApiKeysResponse]
    assert(apikeys.apikeys.length === 2)
    assert(apikeys.apikeys.exists(_.id === "key1"))
    assert(apikeys.apikeys.exists(_.id === "key2"))
  }
  
  test("GET /v1/orgs/{orgid}/apikeys -- normal user - 403 forbidden") {
    
     validateAuthHeaders(Seq(ACCEPT, USERAUTH))
    val response = Http("http://localhost:8080/v1/orgs/testOrg0/apikeys")
      .headers(ACCEPT)
      .headers(USERAUTH)
      .asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("GET /v1/orgs/{orgid}/apikeys -- hub admin - 403 forbidden for other org") {
    validateAuthHeaders(Seq(ACCEPT, HUBADMINAUTH))
    val response = Http("http://localhost:8080/v1/orgs/testOrg0/apikeys")
      .headers(ACCEPT)
      .headers(HUBADMINAUTH)
      .asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("GET /v1/orgs/{orgid}/apikeys -- hub admin - 200 ok for root org") {
   validateAuthHeaders(Seq(ACCEPT, HUBADMINAUTH))
    val response = Http("http://localhost:8080/v1/orgs/root/apikeys")
      .headers(ACCEPT)
      .headers(HUBADMINAUTH)
      .asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
  }
  
  test("GET /v1/orgs/{orgid}/apikeys -- non-existent org - 404 not found") {
    validateAuthHeaders(Seq(ACCEPT, ORGADMINAUTH))

    val response = Http("http://localhost:8080/v1/orgs/nonExistentOrg/apikeys")
      .headers(ACCEPT)
      .headers(ORGADMINAUTH)
      .asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("GET /v1/orgs/{orgid}/apikeys -- no auth header - should return 401") {
  val response = Http("http://localhost:8080/v1/orgs/testOrg0/apikeys")
    .headers(ACCEPT)
    .asString

  assert(response.code === HttpCode.BADCREDS.intValue)
}

}