package exchangeapi

import org.json4s._
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

import scalaj.http._
import com.horizon.exchangeapi._
import org.json4s.native.Serialization.write

//someday: do some short perf tests here (in addition to scr/test/go) so we get some automatic perf info

/** Run some performance tests on the exchange. */
@RunWith(classOf[JUnitRunner])
class PerfSuite extends AnyFunSuite {

  val urlRoot = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080")
  val URL = urlRoot+"/v1"
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val user = "9975"
  val pw = user+"pw"
  val AUTH = ("Authorization","Basic "+ApiUtils.encode(user+":"+pw))
  val agbotId = "9930"      // need to use a different id than AgbotsSuite.scala, because all of the suites run concurrently
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+ApiUtils.encode(agbotId+":"+agbotToken))

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  def createAgbot = {
    val input = PutAgbotsRequest(agbotToken, "agbot"+agbotId+"-norm", /*List[APattern](),*/ None, "ABC")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK.intValue)
  }

  def doGetAgbots() = {
    val response: HttpResponse[String] = Http(URL+"/agbots").headers(ACCEPT).headers(AUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK.intValue)
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