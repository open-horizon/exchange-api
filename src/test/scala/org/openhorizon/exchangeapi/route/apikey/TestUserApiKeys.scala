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
import scalaj.http.{Http, HttpResponse}
import scala.concurrent.Await
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._
import org.openhorizon.exchangeapi.auth.{Password, Role}
import scala.concurrent.ExecutionContext.Implicits.global
import _root_.org.openhorizon.exchangeapi.utility.HttpCode
class TestUserApiKeys extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val PASSWORD = "password"
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  private val TESTORGS = Seq(
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
  
  private val TESTUSERS = Seq(
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
      admin = false,
      email = "",
      hashedPw = Password.hash(PASSWORD),
      hubAdmin = false,
      lastUpdated = "",
      orgid = "testOrg1",
      updatedBy = "",
      username = "testOrg1/user1"
    ),

  )

  private val TESTAPIKEYS = Seq(
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
      orgid = "testOrg0",
      id = "key3",
      username = "testOrg0/user0",
      description = "Test API Key 2",
      hashedKey = "hash3"
    ),
    ApiKeyRow(
      orgid = "testOrg1",
      id = "key4",
      username = "testOrg1/user1",
      description = "Test API Key 3",
      hashedKey = "hash4"
    ),
  )

  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("testOrg0/admin0:password"))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode("testOrg0/user0:password"))


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

  // POST /v1/orgs/{orgid}/users/{username}/apikeys 
  test("POST /v1/orgs/{orgid}/users/{username}/apikeys -- user creates apikey") {
    validateAuthHeaders(Seq(ACCEPT, USERAUTH))
val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/user0/apikeys")
  .headers(ACCEPT)
  .headers(USERAUTH)
  .header("Content-Type", "application/json") 
  .postData("""{"description": "Test API Key"}""")
  .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000) 
  .asString

    
    assert(response.code === 201)
    val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]
    assert(responseBody.user === "user0")
    assert(responseBody.description === "Test API Key")
    assert(responseBody.id.nonEmpty)
    assert(responseBody.value.nonEmpty)
  }

  test("POST /v1/orgs/{orgid}/users/{username}/apikeys -- org admin creates apikey") {
    validateAuthHeaders(Seq(ACCEPT, ORGADMINAUTH))
val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/admin0/apikeys")
  .headers(ACCEPT)
  .headers(ORGADMINAUTH)
  .header("Content-Type", "application/json") 
  .postData("""{"description": "Admin Created Key"}""")
  .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000) 
  .asString

    
    assert(response.code === 201)
    val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]
    assert(responseBody.user === "admin0")
    assert(responseBody.description === "Admin Created Key")
    assert(responseBody.id.nonEmpty)
    assert(responseBody.value.nonEmpty)
  }
  test("POST /v1/orgs/{orgid}/users/{username}/apikeys -- org admin creates apikey for a user") {
    validateAuthHeaders(Seq(ACCEPT, ORGADMINAUTH))
val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/user0/apikeys")
  .headers(ACCEPT)
  .headers(ORGADMINAUTH)
  .header("Content-Type", "application/json") 
  .postData("""{"description": "Admin Created Key for a user"}""")
  .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000) 
  .asString

    
    assert(response.code === 201)
    val responseBody = JsonMethods.parse(response.body).extract[PostApiKeyResponse]
    assert(responseBody.user === "user0")
    assert(responseBody.description === "Admin Created Key for a user")
    assert(responseBody.id.nonEmpty)
    assert(responseBody.value.nonEmpty)
  }
  test("POST apikey without auth -- should return 401") {
  val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/user0/apikeys")
    .headers(ACCEPT)
    .header("Content-Type", "application/json")
    .postData("""{"description": "No Auth"}""")
    .timeout(connTimeoutMs = 1000, readTimeoutMs = 10000)
    .asString

  assert(response.code === HttpCode.BADCREDS.intValue)
}
test("POST apikey with invalid JSON -- should return 400") {
  val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/user0/apikeys")
    .headers(ACCEPT)
    .headers(USERAUTH)
    .header("Content-Type", "application/json")
    .postData("""{"desc": "missing required 'description'"}""") 
    .asString

  assert(response.code === HttpCode.BAD_INPUT.intValue)
}



  // GET /v1/orgs/{orgid}/users/{username}/apikeys 
  test("GET /v1/orgs/{orgid}/users/{username}/apikeys -- user gets own key") {
    validateAuthHeaders(Seq(ACCEPT, USERAUTH))
    val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/user0/apikeys")
      .headers(ACCEPT)
      .headers(USERAUTH)
      .asString
    
    assert(response.code === HttpCode.OK.intValue)
    val responseBody = JsonMethods.parse(response.body).extract[GetUserApiKeysResponse]
    assert(responseBody.apikeys.exists(_.id === "key2")) 
  }

  test("GET /v1/orgs/{orgid}/users/{username}/apikeys -- org admin get apikey from a user in this org") {
    validateAuthHeaders(Seq(ACCEPT, ORGADMINAUTH))
    val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/user0/apikeys")
      .headers(ACCEPT)
      .headers(ORGADMINAUTH)
      .asString
    
    assert(response.code === HttpCode.OK.intValue)
    val responseBody = JsonMethods.parse(response.body).extract[GetUserApiKeysResponse]
    assert(responseBody.apikeys.exists(_.id === "key2")) 
  }
  test("GET apikey for non-existent user -- should return 404 ") {
  val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/nonexistent/apikeys")
    .headers(ACCEPT)
    .headers(ORGADMINAUTH)
    .asString

  assert(response.code == HttpCode.NOT_FOUND.intValue)
}
test("GET apikeys from another org by org admin -- should be 403") {
  val response = Http("http://localhost:8080/v1/orgs/testOrg1/users/user1/apikeys")
    .headers(ACCEPT)
    .headers(ORGADMINAUTH) 
    .asString

  assert(response.code === HttpCode.ACCESS_DENIED.intValue)
}
test("GET apikeys of another user -- should return 403") {
  val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/admin0/apikeys")
    .headers(ACCEPT)
    .headers(USERAUTH) 
    .asString

  assert(response.code === HttpCode.ACCESS_DENIED.intValue)
}

// GET /users/{username}/apikeys/{keyid}
test("GET existing apikey by id -- should return 200") {
  val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/user0/apikeys/key2")
    .headers(ACCEPT)
    .headers(USERAUTH)
    .asString

  assert(response.code === HttpCode.OK.intValue)
}

test("GET non-existent apikey by id -- should return 404") {
  val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/user0/apikeys/nonexistentkey")
    .headers(ACCEPT)
    .headers(USERAUTH)
    .asString

  assert(response.code === HttpCode.NOT_FOUND.intValue)
}


  // DELETE /v1/orgs/{orgid}/users/{username}/apikeys/{keyid} 
  test("DELETE /v1/orgs/{orgid}/users/{username}/apikeys/{keyid} -- user deletes own apikey") {
    validateAuthHeaders(Seq(ACCEPT, USERAUTH))
    val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/user0/apikeys/key2")
      .headers(ACCEPT)
      .headers(USERAUTH)
      .method("DELETE")
      .asString
    
    assert(response.code ===HttpCode.DELETED.intValue )
    

    val checkResponse = Http("http://localhost:8080/v1/orgs/testOrg0/users/user0/apikeys")
      .headers(ACCEPT)
      .headers(USERAUTH)
      .asString
    
    assert(checkResponse.code === HttpCode.OK.intValue)
    val responseBody = JsonMethods.parse(checkResponse.body).extract[GetUserApiKeysResponse]
    assert(!responseBody.apikeys.exists(_.id === "key2"))
  }

  test("DELETE /v1/orgs/{orgid}/users/{username}/apikeys/{keyid} -- org admin deletes apikey of the user in this org") {
    validateAuthHeaders(Seq(ACCEPT, ORGADMINAUTH))
    val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/user0/apikeys/key3")
      .headers(ACCEPT)
      .headers(ORGADMINAUTH)
      .method("DELETE")
      .asString
    
    assert(response.code === HttpCode.DELETED.intValue)
    val checkResponse = Http("http://localhost:8080/v1/orgs/testOrg0/users/user0/apikeys")
      .headers(ACCEPT)
      .headers(USERAUTH)
      .asString
    assert(checkResponse.code === HttpCode.OK.intValue)
    val responseBody = JsonMethods.parse(checkResponse.body).extract[GetUserApiKeysResponse]
    assert(!responseBody.apikeys.exists(_.id === "key2"))
  }
  test("DELETE non-existent apikey -- should return 404") {
  val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/user0/apikeys/nonexistentkey")
    .headers(ACCEPT)
    .headers(USERAUTH)
    .method("DELETE")
    .asString

  assert(response.code === HttpCode.NOT_FOUND.intValue )
}
test("DELETE apikey of another user -- should return 403") {
  val response = Http("http://localhost:8080/v1/orgs/testOrg0/users/admin0/apikeys/key1")
    .headers(ACCEPT)
    .headers(USERAUTH) 
    .method("DELETE")
    .asString

  assert(response.code === HttpCode.ACCESS_DENIED.intValue)
}
test("DELETE key from another org -- should return 403") {
  val response = Http("http://localhost:8080/v1/orgs/testOrg1/users/user1/apikeys/key3")
    .headers(ACCEPT)
    .headers(ORGADMINAUTH)
    .method("DELETE")
    .asString

  assert(response.code === HttpCode.ACCESS_DENIED.intValue)
}




}