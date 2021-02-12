package com.horizon.exchangeapi

import com.horizon.exchangeapi.tables.{NodeHeartbeatIntervals, OrgLimits}
import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import scalaj.http._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import com.horizon.exchangeapi._
import com.horizon.exchangeapi.auth.IamAccountInfo

import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer


/**
  * Tests for the org resource. To run
  * the test suite, you can either:
  *  - run the "test" command in the SBT console
  *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
  *
  * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
  */
@RunWith(classOf[JUnitRunner])
class OrgsSuite extends AnyFunSuite {

  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080")
  val URL = urlRoot+"/v1/orgs"
  val MYORGSURL = urlRoot+"/v1/myorgs"
  val urlRootOrg = urlRoot + "/v1/orgs/root"
  val ACCEPT = ("Accept","application/json")
  val ACCEPTTEXT = ("Accept","text/plain")
  val CONTENT = ("Content-Type","application/json")
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(rootuser+":"+rootpw))
  val orgid = "OrgsSuiteTests"
  val maxRecords = 10000
  val secondsAgo = 120
  val hubadmin = "OrgSuiteTestsHubAdmin"
  val pw = "password"
  val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("root/"+hubadmin+":"+pw))
  val orgid2 = "OrgSuitesTests2"
  val orgid3 = "OrgSuitesTests3"
  val orgid4 = "OrgSuitesTests4"
  val orgid5 = "OrgSuitesTests5"
  val exchangeMaxNodes = ExchConfig.getInt("api.limits.maxNodes")
  val orgsList = ListBuffer(orgid, orgid2)
  val iamKey = sys.env.getOrElse("EXCHANGE_IAM_KEY", "")
  val iamUser = sys.env.getOrElse("EXCHANGE_IAM_EMAIL", "")
  val cloudorg = sys.env.getOrElse("ICP_CLUSTER_NAME", "")
  val CLOUDAUTH = ("Authorization", "Basic " + ApiUtils.encode(s"$cloudorg/iamapikey:$iamKey"))
  val CLOUDAUTHNOORG = ("Authorization","Basic " + ApiUtils.encode(s"/iamapikey:$iamKey"))


  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Create orgs to use for this test */
  test("POST /orgs/"+orgid+" - create org") {
    // Try deleting it 1st, in case it is left over from previous test
    var response = Http(URL+"/"+orgid).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)

    val input = PostPutOrgRequest(None, "My Org", "desc", None, None, None)
    response = Http(URL+"/"+orgid).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + orgid + " was created and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/"+orgid+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == orgid) && (y.operation == ResourceChangeConfig.CREATED)}))
  }

  test("PUT /orgs/"+orgid+" - update org") {
    val input = PostPutOrgRequest(None, "My Org", "desc", None, None, None)
    val response = Http(URL+"/"+orgid).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + orgid + " was updated via PUT and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/"+orgid+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == orgid) && (y.operation == ResourceChangeConfig.CREATEDMODIFIED)}))
  }

  test("PATCH /orgs/"+orgid+" - update org") {
    val nodeHeartbeatInterval = NodeHeartbeatIntervals(5, 5, 5)
    val input = PatchOrgRequest(None, None, Some("Patched My Org"), None, None, Some(nodeHeartbeatInterval))
    val response = Http(URL+"/"+orgid).postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + orgid + " was updated via PATCH and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/"+orgid+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == orgid) && (y.operation == ResourceChangeConfig.MODIFIED)}))
  }

  // Hub Admin Tests
  test("POST /orgs/root/users/" + hubadmin ) {
    val input = PostPutUsersRequest(pw, admin = false, Some(true), hubadmin + "@hotmail.com")
    val response = Http(urlRootOrg + "/users/" + hubadmin).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  }

  test("GET /orgs") {
    val response = Http(URL).method("get").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: "+response.code)
    assert(response.code == HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.size >= 1)
    assert(orgsList.orgs.contains(orgid))
  }

  test("GET /orgs/" + orgid) {
    val response = Http(URL + "/" + orgid).method("get").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: "+response.code)
    assert(response.code == HttpCode.OK.intValue)
    val orgsList = parse(response.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.size == 1)
    assert(orgsList.orgs.contains(orgid))
  }

  test("POST /orgs/" + orgid2) {
    val limits = OrgLimits(exchangeMaxNodes-5)
    // orgType, label, description, tags, limits, heartbeatIntervals
    val input = PostPutOrgRequest(None, "My Org", "desc", None, Some(limits), None)
    val response = Http(URL + "/" + orgid2).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code == HttpCode.POST_OK.intValue)
  }

  test("POST /orgs/badOrg -- should fail") {
    val limits = OrgLimits(exchangeMaxNodes+5)
    // orgType, label, description, tags, limits, heartbeatIntervals
    val input = PostPutOrgRequest(None, "My Org", "desc", None, Some(limits), None)
    val response = Http(URL + "/badOrg").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code == HttpCode.BAD_INPUT.intValue)
    assert(response.body.contains("Org specific limits cannot be over the global exchange limit"))
  }

  test("PUT /orgs/" + orgid2 + " update limits") {
    val limits = OrgLimits(exchangeMaxNodes-15)
    // orgType, label, description, tags, limits, heartbeatIntervals
    val input = PostPutOrgRequest(None, "My Org", "desc", None, Some(limits), None)
    val response = Http(URL + "/" + orgid2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code == HttpCode.PUT_OK.intValue)
  }

  test("PUT /orgs/" + orgid2 + " update limits -- should fail") {
    val limits = OrgLimits(exchangeMaxNodes+15)
    // orgType, label, description, tags, limits, heartbeatIntervals
    val input = PostPutOrgRequest(None, "My Org", "desc", None, Some(limits), None)
    val response = Http(URL + "/" + orgid2).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code == HttpCode.BAD_INPUT.intValue)
    assert(response.body.contains("Org specific limits cannot be over the global exchange limit"))
  }

  test("PATCH /orgs/" + orgid2 + " update limits") {
    val limits = OrgLimits(exchangeMaxNodes-5)
    // orgType, label, description, tags, limits, heartbeatIntervals
    val input = PatchOrgRequest(None, None, None, None, Some(limits), None)
    val response = Http(URL + "/" + orgid2).postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code == HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/" + orgid2 + " update tags") {
    val tags = Map("ibmcloud_id" -> Some("5678"))
    // orgType, label, description, tags, limits, heartbeatIntervals
    val input = PatchOrgRequest(None, None, None, Some(tags), None, None)
    val response = Http(URL + "/" + orgid2).postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code == HttpCode.PUT_OK.intValue)
  }

  test("PATCH /orgs/" + orgid2 + " update tags again check that its wiped") {
    val tags = Map("cloud_id" -> Some("55555"))
    // orgType, label, description, tags, limits, heartbeatIntervals
    val input = PatchOrgRequest(None, None, None, Some(tags), None, None)
    val response = Http(URL + "/" + orgid2).postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code == HttpCode.PUT_OK.intValue)

    val response2 = Http(URL + "/" + orgid2).method("get").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: "+response2.code)
    info("response.body: " + response.body)
    assert(response2.code == HttpCode.OK.intValue)
    val orgsList = parse(response2.body).extract[GetOrgsResponse]
    assert(orgsList.orgs.contains(orgid2))
    val org = orgsList.orgs(orgid2)
    val tagsMap = org.tags.getOrElse(Map("" -> ""))
    assert(tagsMap.contains("cloud_id"))
    assert(!tagsMap.contains("ibmcloud_id"))
  }

  test("PATCH /orgs/" + orgid2 + " update limits -- should fail") {
    val limits = OrgLimits(exchangeMaxNodes+100)
    // orgType, label, description, tags, limits, heartbeatIntervals
    val input = PatchOrgRequest(None, None, None, None, Some(limits), None)
    val response = Http(URL + "/" + orgid2).postData(write(input)).method("patch").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code == HttpCode.BAD_INPUT.intValue)
    assert(response.body.contains("Org specific limits cannot be over the global exchange limit"))
  }

  test("DELETE /orgs/" + orgid2 + " - as hub admin") {
    val response = Http(URL + "/" + orgid2).method("delete").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: "+response.code+", body: "+response.body)
    assert(response.code == HttpCode.DELETED.intValue)
  }

  test("DELETE /orgs/root - as hub admin - should fail") {
    val response = Http(URL + "/root").method("delete").headers(CONTENT).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("code: "+response.code+", body: "+response.body)
    assert(response.code == HttpCode.ACCESS_DENIED.intValue)
  }

  test("DELETE /orgs/root - as root - should fail") {
    val response = Http(URL + "/root").method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", body: "+response.body)
    assert(response.code == HttpCode.ACCESS_DENIED.intValue)
  }

//  test("POST /orgs/"+orgid2+"/changes - verify " + orgid2 + " was deleted and stored") {
//    val time = ApiTime.pastUTC(secondsAgo)
//    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
//    val response = Http(URL+"/"+orgid2+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
//    info("code: "+response.code)
//    assert(response.code === HttpCode.POST_OK.intValue)
//    assert(!response.body.isEmpty)
//    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
//    assert(parsedBody.changes.exists(y => {(y.id == orgid2) && (y.operation == ResourceChangeConfig.DELETED)}))
//  }

  // Delete Hub Admin User
  test("DELETE /orgs/root/users/" + hubadmin ) {
    val response = Http(urlRootOrg + "/users/" + hubadmin).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  // Try deleting it 1st, in case it is left over from previous test
  test("POST /orgs/"+orgid+" - delete org") {
    val response = Http(URL+"/"+orgid).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }

  test("POST /orgs/"+orgid+"/changes - verify " + orgid + " was DELETED and stored") {
    val time = ApiTime.pastUTC(secondsAgo)
    val input = ResourceChangesRequest(0, Some(time), maxRecords, None)
    val response = Http(URL+"/"+orgid+"/changes").postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK.intValue)
    assert(!response.body.isEmpty)
    val parsedBody = parse(response.body).extract[ResourceChangesRespObject]
    assert(parsedBody.changes.exists(y => {(y.id == orgid) && (y.operation == ResourceChangeConfig.DELETED)}))
  }

  test("IAM Creds - POST /myorgs route") {
    if (iamKey.nonEmpty && iamUser.nonEmpty && cloudorg.nonEmpty) {
      info("Try deleting test orgs first in case they stuck around")
      var responseOrg = Http(URL+"/"+orgid3).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+responseOrg.code+", response.body: "+responseOrg.body)
      assert(responseOrg.code === HttpCode.DELETED.intValue || responseOrg.code === HttpCode.NOT_FOUND.intValue)

      responseOrg = Http(URL+"/"+orgid4).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+responseOrg.code+", response.body: "+responseOrg.body)
      assert(responseOrg.code === HttpCode.DELETED.intValue || responseOrg.code === HttpCode.NOT_FOUND.intValue)

      info("Hitting POST /myOrgs before any orgs with the right account ids have been added with cloud auth")
      val input = List(IamAccountInfo("UserSuiteTestsAccID1", "mycluster Account", "Description for mycluster Account", "2020-09-15T00:20:43.853Z"),
        IamAccountInfo("UserSuiteTestsAccID2", "mycluster Account", "Description for mycluster Account", "2020-09-15T00:20:43.853Z"))
      val response = Http(MYORGSURL)
        .method("post")
        .postData(write(input))
        .headers(ACCEPT)
        .headers(CONTENT)
        .headers(CLOUDAUTH)
        .asString

      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.NOT_FOUND.intValue)

      info("Hitting POST /myOrgs before any orgs with the right account ids have been added with cloud auth no org")
      val responseNoOrg = Http(MYORGSURL)
        .method("post")
        .postData(write(input))
        .headers(ACCEPT)
        .headers(CONTENT)
        .headers(CLOUDAUTHNOORG)
        .asString

      info("code: "+responseNoOrg.code+", response.body: "+responseNoOrg.body)
      assert(responseNoOrg.code === HttpCode.NOT_FOUND.intValue)

      info("Adding first org with the correct account IDs")
      val input2 = PostPutOrgRequest(None, "My Org", "desc", Some(Map("cloud_id" -> "UserSuiteTestsAccID1")), None, None)
      val response2 = Http(URL+"/"+orgid3).postData(write(input2)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response2.code+", response.body: "+response2.body)
      assert(response2.code === HttpCode.POST_OK.intValue)
      orgsList+=orgid3

      info("Adding second org with the correct account IDs")
      val input3 = PostPutOrgRequest(None, "My Org", "desc", Some(Map("cloud_id" -> "UserSuiteTestsAccID2")), None, None)
      val response3 = Http(URL+"/"+orgid4).postData(write(input3)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response3.code+", response.body: "+response3.body)
      assert(response3.code === HttpCode.POST_OK.intValue)
      orgsList+=orgid4

      info("Hitting POST /myOrgs after orgs with the right account ids have been added")
      val response4 = Http(MYORGSURL)
        .method("post")
        .postData(write(input))
        .headers(ACCEPT)
        .headers(CONTENT)
        .headers(CLOUDAUTHNOORG)
        .asString
      info("code: "+response4.code+", response.body: "+response4.body)
      assert(response4.code === HttpCode.OK.intValue)
      val orgList = parse(response4.body).extract[GetOrgsResponse]
      assert(orgList.orgs.size >= 2)
      assert(orgList.orgs.contains(orgid3))
      assert(orgList.orgs.contains(orgid4))

      info("CLEANUP -- delete " +orgid3)
      var response5 = Http(URL+"/"+orgid3).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response5.code+", response.body: "+response5.body)
      assert(response5.code === HttpCode.DELETED.intValue || response5.code === HttpCode.NOT_FOUND.intValue)

      info("CLEANUP -- delete " +orgid4)
      response5 = Http(URL+"/"+orgid4).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response5.code+", response.body: "+response5.body)
      assert(response5.code === HttpCode.DELETED.intValue || response5.code === HttpCode.NOT_FOUND.intValue)

    } else info("Skipping IAM login - POST /myorgs route tests")
  }

  test("Exchange Creds - POST /myorgs route") {
    info("Try deleting test orgs first in case they stuck around")
    val responseOrg = Http(URL+"/"+orgid5).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+responseOrg.code+", response.body: "+responseOrg.body)
    assert(responseOrg.code === HttpCode.DELETED.intValue || responseOrg.code === HttpCode.NOT_FOUND.intValue)

    info("Adding first org without an account ID")
    var input2 = PostPutOrgRequest(None, "My Org", "desc", None, None, None)
    var response2 = Http(URL+"/"+orgid5).postData(write(input2)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response2.code+", response.body: "+response2.body)
    assert(response2.code === HttpCode.POST_OK.intValue)
    orgsList+=orgid5

    info("Add a normal exchange user to hit these routes")
    val user = "user1"
    val inputUser = PostPutUsersRequest(pw, admin = false, Some(false), user + "@hotmail.com")
    val responseUser = Http(URL+"/"+orgid5 + "/users/" + user).postData(write(inputUser)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + responseUser.code + ", response.body: " + responseUser.body)
    assert(responseUser.code === HttpCode.POST_OK.intValue)

    val userAuth = ("Authorization","Basic " + ApiUtils.encode(orgid5+"/"+user+":"+pw))

    info("Hitting POST /myOrgs before any orgs with the right account ids have been added")
    val input = List(IamAccountInfo("UserSuiteTestsAccID1", "mycluster Account", "Description for mycluster Account", "2020-09-15T00:20:43.853Z"),
      IamAccountInfo("UserSuiteTestsAccID2", "mycluster Account", "Description for mycluster Account", "2020-09-15T00:20:43.853Z"))
    val response = Http(MYORGSURL)
      .method("post")
      .postData(write(input))
      .headers(ACCEPT)
      .headers(CONTENT)
      .headers(userAuth)
      .asString

    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)

    info("Updating the org with the correct account ID")
    input2 = PostPutOrgRequest(None, "My Org", "desc", Some(Map("cloud_id" -> "UserSuiteTestsAccID1")), None, None)
    response2 = Http(URL+"/"+orgid5).postData(write(input2)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+ response2.code +", response.body: "+response2.body)
    assert(response2.code === HttpCode.POST_OK.intValue)

    info("Hitting POST /myOrgs after orgs with the right account ids have been added")
    val response4 = Http(MYORGSURL)
      .method("post")
      .postData(write(input))
      .headers(ACCEPT)
      .headers(CONTENT)
      .headers(ROOTAUTH)
      .asString
    info("code: "+response4.code+", response.body: "+response4.body)
    assert(response4.code === HttpCode.OK.intValue)
    val orgList = parse(response4.body).extract[GetOrgsResponse]
    assert(orgList.orgs.size >= 1)
    assert(orgList.orgs.contains(orgid5))

    info("CLEANUP -- delete " +orgid5)
    val response5 = Http(URL+"/"+orgid5).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response5.code === HttpCode.DELETED.intValue || response5.code === HttpCode.NOT_FOUND.intValue)
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