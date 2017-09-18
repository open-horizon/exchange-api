package exchangeapi

import org.scalatest.FunSuite
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scalaj.http._
import org.json4s._
//import org.json4s.JsonDSL._
//import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import com.horizon.exchangeapi._

import scala.collection.immutable._
//import java.time._

import com.horizon.exchangeapi.tables._

//TODO: have not decided if i should use this or stick with the scr/test/bash/scale scripts (which can easily have multiple instances run concurrently, and from different clients)

/** Run some performance tests on the exchange. */
@RunWith(classOf[JUnitRunner])
class PerfSuite extends FunSuite {

  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080")
  val URL = urlRoot+"/v1"
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val user = "9975"
  val pw = user+"pw"
  val AUTH = ("Authorization","Basic "+user+":"+pw)
  val agbotId = "9930"      // need to use a different id than AgbotsSuite.scala, because all of the suites run concurrently
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+agbotId+":"+agbotToken)

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  def createAgbot = {
    val input = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-norm", List[APattern](), "whisper-id", "ABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  def doGetAgbots() = {
    val response: HttpResponse[String] = Http(URL+"/agbots").headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    assert(response.body.startsWith("{"))
    // val getAgbotResp = parse(response.body).extract[GetAgbotsResponse]
    // assert(getAgbotResp.agbots.size === 1)   // since the other test suites are creating some of these too, we can not know how many there are right now
    info("GET /agbots output verified")
  }

  ignore("Time GET /agbots") {
    createAgbot
    for (_ <- 1 to 5) {
      doGetAgbots()
    }
  }
}