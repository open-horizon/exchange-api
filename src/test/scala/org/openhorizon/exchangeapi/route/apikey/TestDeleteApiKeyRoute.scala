package org.openhorizon.exchangeapi.route.apikey
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.openhorizon.exchangeapi.auth.{Identity, Role}
import org.openhorizon.exchangeapi.table.apikey.{ApiKeyMetadata, ApiKeyRow, ApiKeysTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiUtils, Configuration, DatabaseConnection, ExchMsg}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.route.user.GetUsersResponse
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
import _root_.org.openhorizon.exchangeapi.utility.{HttpCode,ApiTime}
import java.sql.Timestamp
import java.util.UUID

class TestDeleteApiKeyRoute extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/apikeys/"
  private val PASSWORD = "password"
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  private val TESTORGS = Seq(
    OrgRow(
      description = "",
      heartbeatIntervals = "",
      label = "",
      lastUpdated = "",
      limits = "",
      orgId = "testDeleteApiKeyRouteOrg0",
      orgType = "",
      tags = None
    ),
    OrgRow(
      description = "",
      heartbeatIntervals = "",
      label = "",
      lastUpdated = "",
      limits = "",
      orgId = "testDeleteApiKeyRouteOrg1",
      orgType = "",
      tags = None
    )
  )

  
  private val TESTUSERS = Seq(
    UserRow(
      createdAt = ApiTime.nowUTCTimestamp,
      email = Some("admin0@example.com"),
      identityProvider = "Open Horizon",
      isHubAdmin = false,
      isOrgAdmin = true,
      modifiedAt = ApiTime.nowUTCTimestamp,
      modified_by = None,
      organization = "testDeleteApiKeyRouteOrg0",
      password = Some(Password.hash(PASSWORD)),
      user = UUID.randomUUID(),
      username = "testDeleteApiKeyRouteAdmin0"
    ),
    UserRow(
      createdAt = ApiTime.nowUTCTimestamp,
      email = Some("user0@example.com"),
      identityProvider = "Open Horizon",
      isHubAdmin = false,
      isOrgAdmin = false,
      modifiedAt = ApiTime.nowUTCTimestamp,
      modified_by = None,
      organization = "testDeleteApiKeyRouteOrg0",
      password = Some(Password.hash(PASSWORD)),
      user = UUID.randomUUID(),
      username = "testDeleteApiKeyRouteUser0"
    ),
    UserRow(
      createdAt = ApiTime.nowUTCTimestamp,
      email = Some("user1@example.com"),
      identityProvider = "Open Horizon",
      isHubAdmin = false,
      isOrgAdmin = false,
      modifiedAt = ApiTime.nowUTCTimestamp,
      modified_by = None,
      organization = "testDeleteApiKeyRouteOrg1",
      password = Some(Password.hash(PASSWORD)),
      user = UUID.randomUUID(),
      username = "testDeleteApiKeyRouteUser1"
    ),
    UserRow(
      createdAt = ApiTime.nowUTCTimestamp,
      email = Some("hubadmin0@example.com"),
      identityProvider = "Open Horizon",
      isHubAdmin = true,
      isOrgAdmin = false,
      modifiedAt = ApiTime.nowUTCTimestamp,
      modified_by = None,
      organization = "root",
      password = Some(Password.hash(PASSWORD)),
      user = UUID.randomUUID(),
      username = "testDeleteApiKeyRouteHubAdmin0"
    )
  )

  private val ROOTUSERID: UUID = {
    val rootUserQuery = UsersTQ.filter(u => u.username === "root" && u.organization === "root").result.headOption
    Await.result(DBCONNECTION.run(rootUserQuery), AWAITDURATION).get.user
  }

  private val TESTAPIKEYS = Seq(
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(0).user,
      description = "TestDeleteApiKeyRoute Test API Key 1",
      hashedKey = "hash1",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(0).user,
      orgid = "testDeleteApiKeyRouteOrg0",
      user = TESTUSERS(0).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(1).user,
      description = "TestDeleteApiKeyRoute Test API Key 2",
      hashedKey = "hash2",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(1).user,
      orgid = "testDeleteApiKeyRouteOrg0",
      user = TESTUSERS(1).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(1).user,
      description = "TestDeleteApiKeyRoute Test API Key 3",
      hashedKey = "hash3",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(1).user,
      orgid = "testDeleteApiKeyRouteOrg0",
      user = TESTUSERS(1).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(2).user,
      description = "TestDeleteApiKeyRoute Test API Key 4",
      hashedKey = "hash4",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(2).user,
      orgid = "testDeleteApiKeyRouteOrg1",
      user = TESTUSERS(2).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(3).user,
      description = "TestDeleteApiKeyRoute Test API Key HubAdmin",
      hashedKey = "hash5",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(3).user,
      orgid = "root",
      user = TESTUSERS(3).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = ROOTUSERID,
      description = "TestDeleteApiKeyRoute Test API Key Root Own",
      hashedKey = "hash6",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = ROOTUSERID,
      orgid = "root",
      user = ROOTUSERID
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(0).user,
      description = "TestDeleteApiKeyRoute Test API Key For Root Delete Admin",
      hashedKey = "hash7",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(0).user,
      orgid = "testDeleteApiKeyRouteOrg0",
      user = TESTUSERS(0).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(1).user,
      description = "TestDeleteApiKeyRoute Test API Key For Root Delete User",
      hashedKey = "hash8",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(1).user,
      orgid = "testDeleteApiKeyRouteOrg0",
      user = TESTUSERS(1).user
    ),
    ApiKeyRow(
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(3).user,
      description = "TestDeleteApiKeyRoute Test API Key For Root Delete HubAdmin",
      hashedKey = "hash9",
      id = UUID.randomUUID(),
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(3).user,
      orgid = "root",
      user = TESTUSERS(3).user
    )
  )

  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("testDeleteApiKeyRouteOrg0/testDeleteApiKeyRouteAdmin0:password"))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode("testDeleteApiKeyRouteOrg0/testDeleteApiKeyRouteUser0:password"))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("root/testDeleteApiKeyRouteHubAdmin0:password"))
  private val ROOTUSERAUTH = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))


  override def beforeAll(): Unit = {
    val insertAction = DBIO.seq(
      OrgsTQ ++= TESTORGS,
      UsersTQ ++= TESTUSERS,
      ApiKeysTQ ++= TESTAPIKEYS)
      Await.result(DBCONNECTION.run(insertAction.transactionally), AWAITDURATION)
  }

override def afterAll(): Unit = {
  Await.ready(DBCONNECTION.run(
    ResourceChangesTQ.filter(_.orgId startsWith "testDeleteApiKeyRouteOrg").delete
  ), AWAITDURATION)
  Await.ready(DBCONNECTION.run(
    ApiKeysTQ.filter(_.description.startsWith("TestDeleteApiKeyRoute")).delete
  ), AWAITDURATION)
  Await.ready(DBCONNECTION.run(
    UsersTQ.filter(_.username startsWith "testDeleteApiKeyRoute").delete
  ), AWAITDURATION)
  Await.ready(DBCONNECTION.run(
    OrgsTQ.filter(_.orgid startsWith "testDeleteApiKeyRouteOrg").delete
  ), AWAITDURATION)

  val response: HttpResponse[String] = Http(sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/admin/clearauthcaches").method("POST").headers(ACCEPT).headers(CONTENT).headers(ROOTUSERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
}

  // DELETE /v1/orgs/{orgid}/users/{username}/apikeys/{keyid} 
// User deletes their own API key - Expected: 204
  test("DELETE /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id + " -- user deletes own apikey - 204") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id
    ).headers(ACCEPT).headers(USERAUTH).method("DELETE").asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)

    val checkResponse = Http(
      URL + TESTORGS(0).orgId + "/users/" +
        TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id
    ).headers(ACCEPT).headers(USERAUTH).asString

    info("Check Code: " + checkResponse.code)
    info("Check Body: " + checkResponse.body)
    assert(checkResponse.code === HttpCode.NOT_FOUND.intValue)
  }

  // Org admin deletes an API key belonging to a user in their org - Expected: 204
  test("DELETE /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(2).id + " -- org admin deletes apikey of the user in this org - 204") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(2).id
    ).headers(ACCEPT).headers(ORGADMINAUTH).method("DELETE").asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)

    val checkResponse = Http(
      URL + TESTORGS(0).orgId + "/users/" +
        TESTUSERS(1).username + ROUTE + TESTAPIKEYS(2).id
    ).headers(ACCEPT).headers(ORGADMINAUTH).asString

    info("Check Code: " + checkResponse.code)
    info("Check Body: " + checkResponse.body)
    assert(checkResponse.code === HttpCode.NOT_FOUND.intValue)
  }

  // Deleting a non-existent API key should return 404 - Expected: 404
  test("DELETE /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + "nonexistentkey -- 404") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + UUID.randomUUID()
    ).headers(ACCEPT).headers(USERAUTH).method("DELETE").asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  // User tries to delete another user's API key in the same org - Expected: 403
  test("DELETE /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(0).id + " -- user tries to delete apikey of another user - 403") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(0).id
    ).headers(ACCEPT).headers(USERAUTH).method("DELETE").asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  // Org admin tries to delete an API key from a different org (should fail) - Expected: 403
  test("DELETE /orgs/" + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username + ROUTE + TESTAPIKEYS(3).id + " -- org admin tries to delete key from another org - 403") {
    val response = Http(
      URL + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username + ROUTE + TESTAPIKEYS(3).id
    ).headers(ACCEPT).headers(ORGADMINAUTH).method("DELETE").asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  // Hub admin deletes their own API key - Expected: 204
  test("DELETE /orgs/" + "root" + "/users/" + TESTUSERS(3).username + ROUTE + TESTAPIKEYS(4).id + " -- hub admin deletes own apikey - 204") {
    val response = Http(
      URL + "root" + "/users/" + TESTUSERS(3).username + ROUTE + TESTAPIKEYS(4).id
    ).headers(ACCEPT).headers(HUBADMINAUTH).method("DELETE").asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)

    val checkResponse = Http(
      URL + "root" + "/users/" +
        TESTUSERS(3).username + ROUTE + TESTAPIKEYS(4).id
    ).headers(ACCEPT).headers(HUBADMINAUTH).asString

    info("Check Code: " + checkResponse.code)
    info("Check Body: " + checkResponse.body)
    assert(checkResponse.code === HttpCode.NOT_FOUND.intValue)
  }

  // Hub admin deletes org admin's API key - Expected: 204
  test("DELETE /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(0).id + " -- hub admin deletes org admin apikey - 204") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(0).id
    ).headers(ACCEPT).headers(HUBADMINAUTH).method("DELETE").asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)

    val checkResponse = Http(
      URL + TESTORGS(0).orgId + "/users/" +
        TESTUSERS(0).username + ROUTE + TESTAPIKEYS(0).id
    ).headers(ACCEPT).headers(HUBADMINAUTH).asString

    info("Check Code: " + checkResponse.code)
    info("Check Body: " + checkResponse.body)
    assert(checkResponse.code === HttpCode.NOT_FOUND.intValue)
  }

  // Hub admin tries to delete regular user's API key (should fail) - Expected: 404
  test("DELETE /orgs/" + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username + ROUTE + TESTAPIKEYS(3).id + " -- hub admin tries to delete user apikey - 404") {
    val response = Http(
      URL + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username + ROUTE + TESTAPIKEYS(3).id
    ).headers(ACCEPT).headers(HUBADMINAUTH).method("DELETE").asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  // Root user deletes their own API key - Expected: 204
  test("DELETE /orgs/" + "root" + "/users/" + "root" + ROUTE + TESTAPIKEYS(5).id + " -- root user deletes own apikey - 204") {
    val response = Http(
      URL + "root" + "/users/" + "root" + ROUTE + TESTAPIKEYS(5).id
    ).headers(ACCEPT).headers(ROOTUSERAUTH).method("DELETE").asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  // Root user deletes org admin's API key - Expected: 204
  test("DELETE /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(6).id + " -- root user deletes org admin apikey - 204") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(6).id
    ).headers(ACCEPT).headers(ROOTUSERAUTH).method("DELETE").asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  // Root user deletes regular user's API key - Expected: 204
  test("DELETE /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(7).id + " -- root user deletes regular user apikey - 204") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(7).id
    ).headers(ACCEPT).headers(ROOTUSERAUTH).method("DELETE").asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  // Root user deletes hub admin's API key - Expected: 204
  test("DELETE /orgs/" + "root" + "/users/" + TESTUSERS(3).username + ROUTE + TESTAPIKEYS(8).id + " -- root user deletes hub admin apikey - 204") {
    val response = Http(
      URL + "root" + "/users/" + TESTUSERS(3).username + ROUTE + TESTAPIKEYS(8).id
    ).headers(ACCEPT).headers(ROOTUSERAUTH).method("DELETE").asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }
  
}