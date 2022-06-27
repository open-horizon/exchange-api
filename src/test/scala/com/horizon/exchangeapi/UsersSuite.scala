package com.horizon.exchangeapi

import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import scalaj.http._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write

import scala.collection.mutable.ListBuffer
import com.horizon.exchangeapi.tables._
import org.scalatest.BeforeAndAfterAll

import scala.collection.immutable._
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc.PostgresProfile.api._

/**
 * Tests for the /orgs and /orgs/"+orgid+"/users routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class UsersSuite extends AnyFunSuite with BeforeAndAfterAll {

  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080")
  val ACCEPT = ("Accept", "application/json")
  val CONTENT = ("Content-Type", "application/json")
  val orgid = "UsersSuiteTests"
  val orgid2 = "UsersSuiteTests2"
  val orgid3 = "UsersSuiteTests3"
  val orgid4 = "UsersSuiteTests4"
  val URL = urlRoot + "/v1/orgs/" + orgid
  val URL2 = urlRoot + "/v1/orgs/" + orgid2
  val URL3 = urlRoot + "/v1/orgs/" + orgid3
  val URL4 = urlRoot + "/v1/orgs/" + orgid4
  val urlRootOrg = urlRoot + "/v1/orgs/root"
  val cloudorg = "UsersSuiteTestsCloud"
  val CLOUDURL = urlRoot + "/v1/orgs/" + cloudorg
  val NOORGURL = urlRoot + "/v1"
  val user = "u1" // this is an admin user
  val orguser = orgid + "/" + user
  val org2user = orgid2 + "/" + user
  val pw = user + "pw"
  val creds = orguser + ":" + pw
  val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(creds))
  val ORG2USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(org2user + ":" + pw))
  //val encodedCreds = Base64.getEncoder.encodeToString(creds.getBytes("utf-8"))
  val BADUSERAUTH = ("Authorization", "Basic " + ApiUtils.encode(s"bad$orguser:$pw"))
  val USERAUTHBAD = ("Authorization", "Basic " + ApiUtils.encode(s"$orguser:${pw}bad"))
  val user2 = "u2" // this is NOT an admin user
  val orguser2 = orgid + "/" + user2
  val pw2 = user2 + " pw" // intentionally adding a space in the pw
  val creds2 = orguser2 + ":" + pw2
  val USERAUTH2 = ("Authorization", "Basic " + ApiUtils.encode(creds2))
  val pw2new = user2 + "pwnew"
  val creds2new = orguser2 + ":" + pw2new
  val USERAUTH2NEW = ("Authorization", "Basic " + ApiUtils.encode(creds2new))
  val user3 = "u3"
  val pw3 = user3 + "pw"
  val user4 = "u4" // this is NOT an admin user
  val orguser4 = orgid + "/" + user4
  val pw4 = user4 + " pw" // intentionally adding a space in the pw
  val creds4 = orguser4 + ":" + pw4
  val USERAUTH4 = ("Authorization", "Basic " + ApiUtils.encode(creds4))
  val pw4new = user4 + "pwnew"
  val creds4new = orguser4 + ":" + pw4new
  val USERAUTH4NEW = ("Authorization", "Basic " + ApiUtils.encode(creds4new))
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "") // need to put this same root pw in config.json
  val ROOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(rootuser + ":" + rootpw))
  val CONNTIMEOUT = HttpOptions.connTimeout(20000)
  val READTIMEOUT = HttpOptions.readTimeout(20000)
  val svcBase = "svc"
  val svcurl = "http://" + svcBase
  val svcarch = "arm"
  val svcversion = "1.0.0"
  val service = svcBase + "_" + svcversion + "_" + svcarch
  val ptBase = "pat"
  val ptUrl = "http://" + ptBase
  val pattern = ptBase + "_1.0.0_arm"
  val agbotId = "a1"
  val agbotToken = agbotId + "TokAbcDefGh1234"
  val iamKey = sys.env.getOrElse("EXCHANGE_IAM_KEY", "")
  val iamUIToken = sys.env.getOrElse("EXCHANGE_IAM_UI_TOKEN", "")
  val iamUser = sys.env.getOrElse("EXCHANGE_IAM_EMAIL", "")
  // specify only 1 of these 2 env vars (only 1 case can be tested at a time)
  val ocpAccountId = sys.env.getOrElse("EXCHANGE_MULT_ACCOUNT_ID", "") // indicates we should test OCP Multitenancy
  val iamAccountId = sys.env.getOrElse("EXCHANGE_IAM_ACCOUNT_ID", "") // indicates we should test ibm public cloud instead of OCP
  val iamOtherKey = sys.env.getOrElse("EXCHANGE_IAM_OTHER_KEY", "")
  val iamOtherAccountId = sys.env.getOrElse("EXCHANGE_IAM_OTHER_ACCOUNT_ID", "")
  val iamUserUIId = sys.env.getOrElse("EXCHANGE_IAM_UI_ID", "")
  val IAMAUTH = { org: String => ("Authorization", "Basic " + ApiUtils.encode(s"$org/iamapikey:$iamKey")) }
  val IAMUITOKENAUTH = { org: String => ("Authorization", "Basic " + ApiUtils.encode(s"$org/iamtoken:$iamUIToken")) }
  val IAMBADAUTH = { org: String => ("Authorization", "Basic " + ApiUtils.encode(s"$org/iamapikey:${iamKey}bad")) }
  val IAMOTHERAUTH = { org: String => ("Authorization", "Basic " + ApiUtils.encode(s"$org/iamapikey:$iamOtherKey")) }
  val hubadmin = "UsersSuiteHubAdmin"
  val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("root/"+hubadmin+":"+pw))
  val orgadmin = "orgadmin"
  val orgsList = ListBuffer(orgid, orgid2)

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  //todo: figure out how to run https client requests and add those to all the test suites

  /** Delete the orgs we used for this test */
  def deleteAllOrgs() = {
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    response = Http(CLOUDURL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
  }

  /** Delete all the test users in both orgs */
  def deleteAllUsers() = {
    for (i <- List(user, user2)) { // we do not delete the root user because it was created by the config file, not this test suite
      val response = Http(URL + "/users/" + i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE " + i + ", code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    }
    for (i <- List(user)) {
      val response = Http(URL2 + "/users/" + i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE " + i + ", code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)
    }
  }

  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val AWAITDURATION: Duration = 15.seconds

  private val TESTORGANIZATIONS: Seq[OrgRow] =
    Seq(OrgRow(heartbeatIntervals = "",
      description        = "",
      label              = "",
      lastUpdated        = ApiTime.nowUTC,
      orgId              = "UsersSuiteTests",
      orgType            = "",
      tags               = None,
      limits             = ""),
      OrgRow(heartbeatIntervals = "",
        description        = "",
        label              = "",
        lastUpdated        = ApiTime.nowUTC,
        orgId              = "UsersSuiteTests2",
        orgType            = "",
        tags               = None,
        limits             = ""),
      OrgRow(heartbeatIntervals = "",
        description        = "",
        label              = "",
        lastUpdated        = ApiTime.nowUTC,
        orgId              = "UsersSuiteTests3",
        orgType            = "",
        tags               = None,
        limits             = ""),
      OrgRow(heartbeatIntervals = "",
        description        = "",
        label              = "",
        lastUpdated        = ApiTime.nowUTC,
        orgId              = "UsersSuiteTests4",
        orgType            = "",
        tags               = None,
        limits             = ""),
      OrgRow(heartbeatIntervals = "",
        description        = "",
        label              = "",
        lastUpdated        = ApiTime.nowUTC,
        orgId              = "UsersSuiteTestsCloud",
        orgType            = "",
        tags               = None,
        limits             = ""))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ ++= TESTORGANIZATIONS)), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "UsersSuiteTest").delete andThen
      OrgsTQ.filter(_.orgid startsWith "UsersSuiteTest").delete), AWAITDURATION)

    DBCONNECTION.getDb.close()
  }


  /*
   * This test case will fail on successive runs due to the running Exchange application caching the IAM login. The
   * running Exchange instance must be restarted to clear the IAM Auth Cache in order for this test case to pass a 2+
   * time in a row.
   */
  test("IAM login") {
    if (rootpw == "") fail("The exchange root password must be set in EXCHANGE_ROOTPW and must also be put in config.json.")

    //val input2 = PostPutUsersRequest(pw, admin = false, Some(true), hubadmin + "@none.com")
    //var response = Http(urlRootOrg + "/users/" + hubadmin).postData(write(input2)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    //info("code: " + response.code + ", response.body: " + response.body)
    //assert(response.code === HttpCode.POST_OK.intValue)

    // these tests will perform authentication with IBM cloud and will only run
    // if the IAM info is provided in the env vars EXCHANGE_IAM_KEY (iamKey), EXCHANGE_IAM_EMAIL (iamUser), and EXCHANGE_MULT_ACCOUNT_ID (ocpAccountId) or EXCHANGE_IAM_ACCOUNT_ID (iamAccountId)
    if (iamKey.nonEmpty && iamUser.nonEmpty && (ocpAccountId.nonEmpty || iamAccountId.nonEmpty)) {
      assert(!(ocpAccountId.nonEmpty && iamAccountId.nonEmpty)) // can't test both at the same time

      val tagMap = if (ocpAccountId.nonEmpty) Map("cloud_id" -> ocpAccountId) else Map("ibmcloud_id" -> iamAccountId)
      info("Add cloud org with tag: " + tagMap)
      val input = PostPutOrgRequest(None, "Cloud Org", "desc", Some(tagMap), None, None)
      var response = Http(CLOUDURL).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.POST_OK.intValue)

      info("authenticating to cloud with iam api key and GETing " + CLOUDURL)
      response = Http(CLOUDURL).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("GET " + CLOUDURL + " code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.OK.intValue)

      info("authenticate as a cloud user and view org, ensuring cached user works")
      response = Http(CLOUDURL).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.OK.intValue)

      if (iamUIToken.nonEmpty) {
        info("authenticating to cloud with UI iam token and GETing " + CLOUDURL)
        response = Http(CLOUDURL).headers(ACCEPT).headers(IAMUITOKENAUTH(cloudorg)).asString
        info("GET " + CLOUDURL + " code: " + response.code)
        assert(response.code === HttpCode.OK.intValue)

        info("ensuring cached user works: authenticating to cloud with UI iam token and GETing " + CLOUDURL)
        response = Http(CLOUDURL).headers(ACCEPT).headers(IAMUITOKENAUTH(cloudorg)).asString
        info("GET " + CLOUDURL + " code: " + response.code)
        assert(response.code === HttpCode.OK.intValue)
      } else info("Skipping UI token login test")

      info("authenticate as a cloud user and view this user")
      response = Http(CLOUDURL + "/users/" + iamUser).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.OK.intValue)
      var getUserResp = parse(response.body).extract[GetUsersResponse]
      assert(getUserResp.users.size === 1)
      assert(getUserResp.users.contains(cloudorg + "/" + iamUser))
      var u = getUserResp.users(cloudorg + "/" + iamUser)
      assert(u.email === iamUser)

      info("run special case of authenticate as a cloud user and view your own user")
      response = Http(CLOUDURL + "/users/iamapikey").headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.OK.intValue)
      getUserResp = parse(response.body).extract[GetUsersResponse]
      assert(getUserResp.users.size === 1)
      assert(getUserResp.users.contains(cloudorg + "/" + iamUser))
      u = getUserResp.users(cloudorg + "/" + iamUser)
      assert(u.email === iamUser)

      info("ensure user does not have admin auth by trying to get other users")
      response = Http(CLOUDURL + "/users/" + user).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)

      info("ensure user can't view other org (action they are not authorized for)")
      response = Http(URL2).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.ACCESS_DENIED.intValue)

      info("ensure user has auth to view patterns")
      response = Http(CLOUDURL + "/patterns").headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.OK.intValue || response.code === HttpCode.NOT_FOUND.intValue)

      info("ensure bad iam api key returns 401")
      response = Http(CLOUDURL + "/patterns").headers(ACCEPT).headers(IAMBADAUTH(cloudorg)).asString
      info("code: " + response.code)
      assert(response.code === HttpCode.BADCREDS.intValue)

      // Can only add resources to an ibm public cloud org that we created (the icp org is the one in the cluster)
      if (iamAccountId.nonEmpty) {
        // ensure we can add a service to check acls to other objects
        val inputSvc = PostPutServiceRequest(label = "testSvc",
          description = Some("desc"),
          public = false,
          documentation = None,
          url = "s1",
          version = "1.2.3",
          arch = "amd64",
          sharable = "single",
          matchHardware = None,
          requiredServices = None,
          userInput = None,
          deployment = Some("a"),
          deploymentSignature = Some("b"),
          clusterDeployment = None,
          clusterDeploymentSignature = None,
          imageStore = None)
        response = Http(CLOUDURL + "/services").postData(write(inputSvc)).method("post").headers(CONTENT).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.POST_OK.intValue)

        // Only for ibm public cloud: ensure we can add a node to check acls to other objects
        val inputNode = PutNodesRequest("abc", "my node", None, "", None, None, None, None, "ABC", None, None)
        response = Http(CLOUDURL + "/nodes/n1").postData(write(inputNode)).method("put").headers(CONTENT).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.PUT_OK.intValue)
      } else {
        // ICP case - ensure using the cloud creds with a different org prepended fails
        //response = Http(CLOUDURL + "/patterns").headers(ACCEPT).headers(IAMAUTH(orgid)).asString
        response = Http(CLOUDURL + "/patterns").headers(ACCEPT).headers(IAMAUTH(orgid2)).asString
        info("code: " + response.code)
        assert(response.code === HttpCode.BADCREDS.intValue)
      }

      // Test a 2nd org associated with the ibm cloud account
      if (iamAccountId.nonEmpty && iamOtherKey.nonEmpty && iamOtherAccountId.nonEmpty) {
        // remove created user
        response = Http(CLOUDURL + "/users/" + iamUser).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
        info("DELETE " + iamUser + ", code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.DELETED.intValue || response.code === HttpCode.NOT_FOUND.intValue)

        /* this is no longer needed because https://github.com/open-horizon/exchange-api/issues/176 is fixed
        response = Http(NOORGURL+"/admin/clearauthcaches").method("post").headers(ACCEPT).headers(ROOTAUTH).asString
        info("CLEAR CACHE code: "+response.code+", response.body: "+response.body)
        assert(response.code === HttpCode.POST_OK.intValue) */

        // add ibmcloud_id to different org
        var tagInput = s"""{ "tags": {"ibmcloud_id": "$iamAccountId"} }"""
        response = Http(URL2).postData(tagInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.PUT_OK.intValue)

        // authenticating with wrong org should notify user
        response = Http(CLOUDURL).headers(ACCEPT).headers(IAMOTHERAUTH(cloudorg)).asString
        info("test for api key not part of this org: code: " + response.code + ", response.body: " + response.body)
        //info("code: "+response.code)
        assert(response.code === HttpCode.BADCREDS.intValue)
        var errorMsg = s"the iamapikey or iamtoken specified can not be used with org '$cloudorg' prepended to it, because the iamapikey or iamtoken is not associated with that org."
        assert(parse(response.body).extract[Map[String, String]].apply("msg").startsWith(errorMsg))

        // remove ibmcloud_id from org
        tagInput = """{ "tags": {"ibmcloud_id": null} }"""
        response = Http(CLOUDURL).postData(tagInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.PUT_OK.intValue)

        response = Http(CLOUDURL).headers(ACCEPT).headers(IAMAUTH(cloudorg)).asString
        info("code: "+response.code)
        assert(response.code === HttpCode.BADCREDS.intValue)
        errorMsg = s"the iamapikey or iamtoken specified can not be used with org '$cloudorg' prepended to it, because the iamapikey or iamtoken is not associated with that org"
        assert(parse(response.body).extract[Map[String, String]].apply("msg").startsWith(errorMsg))
      }
      else {
        info("Skipping IAM public cloud tests tests")
      }

      /* remove ibmcloud_id from org - do not need to do this, because we delete both orgs at the end
      tagInput = """{ "tags": {"ibmcloud_id": null} }"""
      response = Http(URL2).postData(tagInput).method("patch").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)
      */
    }
    else {
      info("Skipping all IAM login tests")
    }
  }

  test("Multitenancy Pathway") {
    //todo: ICP_EXTERNAL_MGMT_INGRESS is not used in these tests, so we should not require it be set
    if((sys.env.getOrElse("ICP_EXTERNAL_MGMT_INGRESS", "") != "") && ocpAccountId.nonEmpty && iamKey.nonEmpty && iamUser.nonEmpty){
      info("Try deleting the test org first in case it stuck around")
      val responseOrg = Http(URL3).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+responseOrg.code+", response.body: "+responseOrg.body)
      assert(responseOrg.code === HttpCode.DELETED.intValue || responseOrg.code === HttpCode.NOT_FOUND.intValue)

      info("Creating new org with cloud_id")
      val input = PostPutOrgRequest(None, "", "Desc", Some(Map("cloud_id" -> ocpAccountId)), None, None)
      var response = Http(URL3).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)
      orgsList+=orgid3

      info("Cloud user with apikey should be able to access the org")
      response = Http(URL3).headers(ACCEPT).headers(IAMAUTH(orgid3)).asString
      info("GET " + URL3 + " code: " + response.code)
      assert(response.code === HttpCode.OK.intValue)

      if (iamUIToken.nonEmpty){
        // we can authenticate as a UI user
        info("Cloud (UI) user with token should be able to access the org")
        response = Http(URL3).headers(ACCEPT).headers(IAMUITOKENAUTH(orgid3)).asString
        info("GET " + URL3 + " code: " + response.code)
        assert(response.code === HttpCode.OK.intValue)
      } else info ("Skipping UI login tests 1")

      info("Cloud user with apikey should not be able to access org without accountID")
      //response = Http(URL).headers(ACCEPT).headers(IAMAUTH(orgid)).asString
      response = Http(URL).headers(ACCEPT).headers(IAMAUTH(orgid2)).asString
      info("GET " + URL + " code: " + response.code)
      info("GET " + URL + " body: " + response.body)
      assert(response.code === HttpCode.BADCREDS.intValue)

      if (iamUIToken.nonEmpty){
        // we can authenticate as a UI user
        info("Cloud user with token should not be able to access org without accountID")
        response = Http(URL).headers(ACCEPT).headers(IAMUITOKENAUTH(orgid)).asString
        info("GET " + URL + " code: " + response.code)
        info("GET " + URL + " body: " + response.body)
        assert(response.code === HttpCode.BADCREDS.intValue)
      } else info ("Skipping UI login tests 2")

      info("Try deleting the second test org first in case it stuck around")
      val responseOrg2 = Http(URL4).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+responseOrg2.code+", response.body: "+responseOrg2.body)
      assert(responseOrg2.code === HttpCode.DELETED.intValue || responseOrg2.code === HttpCode.NOT_FOUND.intValue)

      info("Creating new org with the same cloud_id")
      response = Http(URL4).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)
      orgsList+=orgid4

      info("Creating basic user in root org with hubAdmin=true and same userid as cloud user")
      val userInput = PostPutUsersRequest("", admin=false, hubAdmin=Some(true), "")
      response = Http(urlRootOrg+ "/users/" + iamUser).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.PUT_OK.intValue)

      info("Cloud user who is now hubadmin should be able to access all orgs with apikey")
      response = Http(urlRoot + "/v1/orgs").headers(ACCEPT).headers(IAMAUTH("root")).asString
      info("GET " + urlRoot + "/v1/orgs" + " code: " + response.code)
      assert(response.code === HttpCode.OK.intValue)

      if (iamUIToken.nonEmpty && iamUserUIId.nonEmpty){
        // we can authenticate as a UI user
        info("Creating basic user in root org with hubAdmin=true and same userid as cloud user")
        response = Http(urlRootOrg+ "/users/" + iamUserUIId).postData(write(userInput)).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.PUT_OK.intValue)

        info("Cloud user who is now hubadmin should be able to access all orgs with token")
        response = Http(urlRoot + "/v1/orgs").headers(ACCEPT).headers(IAMUITOKENAUTH("root")).asString
        info("GET " + urlRoot + "/v1/orgs" + " code: " + response.code)
        assert(response.code === HttpCode.OK.intValue)
      } else info ("Skipping UI login tests 2")

      info("CLEANUP -- delete the test org")
      response = Http(URL3).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.DELETED.intValue)

      info("CLEANUP -- delete the second test org")
      response = Http(URL4).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.DELETED.intValue)

      info("CLEANUP -- delete the first hub admin")
      response = Http(urlRootOrg+ "/users/" + iamUser).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.DELETED.intValue)

      if (iamUIToken.nonEmpty && iamUserUIId.nonEmpty){
        info("CLEANUP -- delete the second hub admin if we made it")
        response = Http(urlRootOrg+ "/users/" + iamUserUIId).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("code: " + response.code + ", response.body: " + response.body)
        assert(response.code === HttpCode.DELETED.intValue)
      }

    } else info("Skipping multitenancy pathway tests")
  }

  /** Clean up, delete all the test users */
  /*test("Cleanup 1 - DELETE all test users") {
    deleteAllUsers()
  }

  test("DELETE /orgs/root/users/" + hubadmin ) {
    val response = Http(urlRootOrg + "/users/" + hubadmin).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
  }*/

  /** Delete the orgs we used for this test */
  /*test("DELETE orgs") {
    var response = Http(URL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)
    response = Http(URL2).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code + ", response.body: " + response.body)
    assert(response.code === HttpCode.DELETED.intValue)

    if (iamKey.nonEmpty && iamUser.nonEmpty && (ocpAccountId.nonEmpty || iamAccountId.nonEmpty)) {
      response = Http(CLOUDURL).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: " + response.code + ", response.body: " + response.body)
      assert(response.code === HttpCode.DELETED.intValue)
    }
  }

  test("Cleanup -- DELETE org changes") {
    for (org <- orgsList){
      val input = DeleteOrgChangesRequest(List())
      val response = Http(urlRoot+"/v1/orgs/"+org+"/changes/cleanup").postData(write(input)).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
      info("code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED.intValue)
    }
  }*/

}
