package exchangeapi

import org.scalatest.FunSuite

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scalaj.http._
import org.json4s._
//import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import com.horizon.exchangeapi._
//import scala.collection.immutable._
//import java.time._

/**
 * Tests for the /admin routes. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class AdminSuite extends FunSuite {

  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080")
  val URL = urlRoot+"/v1"
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val rootuser = Role.superUser
  val rootpw = sys.env.getOrElse("EXCHANGE_ROOTPW", "Horizon-Rul3s")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+rootuser+":"+rootpw)

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  /** Reload config file */
  test("POST /admin/reload") {
    val response = Http(URL+"/admin/reload").method("post").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(postResp.code === ApiResponseType.OK)
  }

  /** Hash a pw */
  test("POST /admin/hashpw") {
    val input = AdminHashpwRequest("foobar")
    val response = Http(URL+"/admin/hashpw").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postResp = parse(response.body).extract[AdminHashpwResponse]
    assert(Password.check(input.password, postResp.hashedPassword))
  }

  /** Set log level */
  test("POST /admin/loglevel") {
    val input = AdminLogLevelRequest("info")
    val response = Http(URL+"/admin/loglevel").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(postResp.code === ApiResponseType.OK)
  }

  /** Set invalid log level */
  test("POST /admin/loglevel - bad") {
    val input = AdminLogLevelRequest("foobar")
    val response = Http(URL+"/admin/loglevel").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postResp = parse(response.body).extract[ApiResponse]
    assert(postResp.code === ApiResponseType.BAD_INPUT)
  }
}