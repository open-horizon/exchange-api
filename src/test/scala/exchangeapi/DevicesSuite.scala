package exchangeapi

import org.scalatest.FunSuite

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scalaj.http._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.write
import com.horizon.exchangeapi._
import com.horizon.exchangeapi.tables._
import scala.collection.immutable._
import java.time._
import java.util.Base64
import java.nio.charset.StandardCharsets

/**
 * This class is a test suite for the methods in object FunSets. To run
 * the test suite, you can either:
 *  - run the "test" command in the SBT console
 *  - right-click the file in eclipse and chose "Run As" - "JUnit Test"
 *
 * clear and detailed tutorial of FunSuite: http://doc.scalatest.org/1.9.1/index.html#org.scalatest.FunSuite
 */
@RunWith(classOf[JUnitRunner])
class DevicesSuite extends FunSuite {

  val urlRoot = sys.env.get("EXCHANGE_URL_ROOT").getOrElse("http://localhost:8080")
  val URL = urlRoot+"/v1"
  val ACCEPT = ("Accept","application/json")
  val CONTENT = ("Content-Type","application/json")
  val SDRSPEC = "https://bluehorizon.network/documentation/sdr-device-api"
  val NETSPEEDSPEC = "https://bluehorizon.network/documentation/netspeed-device-api/"     // test the trailing / for this one
  val PWSSPEC = "https://bluehorizon.network/documentation/pws-device-api"
  val NOTTHERESPEC = "https://bluehorizon.network/documentation/notthere-device-api"
  val user = "9980"
  val pw = user+"pw"
  val USERAUTH = ("Authorization","Basic "+user+":"+pw)
  val BADAUTH = ("Authorization","Basic "+user+":"+pw+"x")
  val rootuser = "root"
  val rootpw = sys.env.get("EXCHANGE_ROOTPW").getOrElse("Horizon-Rul3s")      // need to put this root pw in config.json
  val ROOTAUTH = ("Authorization","Basic "+rootuser+":"+rootpw)
  val deviceId = "9900"     // the 1st device created, that i will use to run some rest methods
  val deviceToken = "mytok"
  val DEVICEAUTH = ("Authorization","Basic "+deviceId+":"+deviceToken)
  val creds = deviceId+":"+deviceToken
  val encodedCreds = Base64.getEncoder().encodeToString(creds.getBytes("utf-8"))
  val ENCODEDAUTH = ("Authorization","Basic "+encodedCreds)
  var numExistingDevices = 0    // this will be set later
  val agbotId = "9940"      // need to use a different id than AgbotsSuite.scala, because all of the suites run concurrently
  val agbotToken = agbotId+"tok"
  val AGBOTAUTH = ("Authorization","Basic "+agbotId+":"+agbotToken)
  // val numPredefinedDevices = if (usingPersistence) 0 else 1     // predefined in TempDb
  val agProto = "ExchangeAutomatedTest"    // using this to avoid db entries from real users and predefined ones

  implicit val formats = DefaultFormats // Brings in default date formats etc.

  // Operators: test, ignore, pending

  /** Delete all the test users */
  def deleteAllUsers = {
    for (i <- List(user)) {
      val response = Http(URL+"/users/"+i).method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED)
    }
  }

  /** Delete all the test devices - this is not longer used because deleting the user deletes these too */
  def deleteAllDevices = {
    for (i <- List(deviceId,9901,9902,9903)) {
      val response = Http(URL+"/devices/"+i).method("delete").headers(ACCEPT).headers(USERAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test agreements - this is not longer used because deleting the user deletes these too */
  def deleteAllAgreements = {
    for (i <- List(9950)) {
      val response = Http(URL+"/devices/"+deviceId+"/agreements/"+i).method("delete").headers(ACCEPT).headers(USERAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test agbots - this is not longer used because deleting the user deletes these too */
  def deleteAllAgbots = {
    for (i <- List(agbotId)) {
      val response = Http(URL+"/agbots/"+i).method("delete").headers(ACCEPT).headers(USERAUTH).asString
      info("DELETE "+i+", code: "+response.code+", response.body: "+response.body)
      assert(response.code === HttpCode.DELETED || response.code === HttpCode.NOT_FOUND)
    }
  }

  /** Delete all the test users, in case they exist from a previous run */
  test("Begin - DELETE all test users") {
    deleteAllUsers
  }

  /** Add a normal user */
  test("POST /users/"+user+" - normal") {
    val input = PutUsersRequest(pw, user+"@hotmail.com")
    val response = Http(URL+"/users/"+user).postData(write(input)).method("post").headers(CONTENT).headers(ACCEPT).asString    // Note: no AUTH
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
  }

  /** Delete all the test devices and agreements, in case they exist from a previous run
  test("Begin - DELETE all test devices and agreements") {
    deleteAllAgreements
    deleteAllDevices
    deleteAllAgbots
  }
  */

  ExchConfig.load
  val usingPersistence = !ExchConfig.getBoolean("api.db.memoryDb")
  // println("DevicesSuite: usingPersistence: "+usingPersistence)
    // Get the number of existing devices so we can later check the number we added
  test("GET number of existing devices") {
    val response: HttpResponse[String] = Http(URL+"/devices").headers(ACCEPT).headers(USERAUTH).asString
    // info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getDeviceResp = parse(response.body).extract[GetDevicesResponse]
    numExistingDevices = getDeviceResp.devices.size
    info("Set number of existing devices")
  }

  val putDevRespDisabled = ExchConfig.getBoolean("api.microservices.disable")
  /** Add a normal device */
  test("PUT /devices/"+deviceId+" - normal") {
    val input = PutDevicesRequest(deviceToken, "rpi"+deviceId+"-norm",
      List(
        Microservice(PWSSPEC,1,"{json policy for "+deviceId+" pws}",List(
          Prop("arch","arm","string","in"),
          Prop("version","1.0.0","version","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("dataVerification","true","boolean","="))),
        Microservice(NETSPEEDSPEC,1,"{json policy for "+deviceId+" netspeed}",List(
          Prop("arch","arm","string","in"),
          Prop("cpus","2","int",">="),
          Prop("version","1.0.0","version","in")))
      ),
      "whisper-id", Map("horizon"->"3.2.3"))
    val response = Http(URL+"/devices/"+deviceId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
    val putDevResp = parse(response.body).extract[Map[String,String]]
    if (putDevRespDisabled) assert(putDevResp.size === 0)
    else {
      assert(putDevResp.size === 1)
      assert(putDevResp.contains(PWSSPEC))
      var microTmpl = putDevResp.get(PWSSPEC).get     // the 2nd get turns the Some(val) into val
      assert(microTmpl.contains("""deployment":"""))
    }
  }

  /** Update a normal device as user */
  test("PUT /devices/"+deviceId+" - normal - update") {
    val input = PutDevicesRequest(deviceToken, "rpi"+deviceId+"-normal-user",
      List(
        Microservice(PWSSPEC,1,"{json policy for "+deviceId+" pws}",List(
          Prop("arch","arm","string","in"),
          Prop("version","1.0.0","version","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("dataVerification","true","boolean","="))),
        Microservice(NETSPEEDSPEC,1,"{json policy for "+deviceId+" netspeed}",List(
          Prop("arch","arm","string","in"),
          Prop("cpus","2","int",">="),
          Prop("version","1.0.0","version","in")))
      ),
      "whisper-id", Map("horizon"->"3.2.3"))
    val response = Http(URL+"/devices/"+deviceId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
    val putDevResp = parse(response.body).extract[Map[String,String]]
    if (putDevRespDisabled) assert(putDevResp.size === 0)
    else {
      assert(putDevResp.size === 1)
      assert(putDevResp.contains(PWSSPEC))
      var microTmpl = putDevResp.get(PWSSPEC).get     // the 2nd get turns the Some(val) into val
      assert(microTmpl.contains("""deployment":"""))
    }
  }

  /** Update the normal device as the device */
  test("PUT /devices/"+deviceId+" - normal - as device") {
    val input = PutDevicesRequest(deviceToken, "rpi"+deviceId+"-normal",
      List(
        Microservice(SDRSPEC,1,"{json policy for "+deviceId+" sdr}",List(
          Prop("arch","arm","string","in"),
          Prop("memory","300","int",">="),
          Prop("version","1.0.0","version","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("dataVerification","true","boolean","="))),
        Microservice(NETSPEEDSPEC,1,"{json policy for "+deviceId+" netspeed}",List(
          Prop("arch","arm","string","in"),
          Prop("agreementProtocols",agProto,"list","in"),
          Prop("version","1.0.0","version","in")))
      ),
      "whisper-id", Map("horizon"->"3.2.1"))
    val response = Http(URL+"/devices/"+deviceId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(DEVICEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
    val putDevResp = parse(response.body).extract[Map[String,String]]
    if (putDevRespDisabled) assert(putDevResp.size === 0)
    else {
      assert(putDevResp.size === 2)
      assert(putDevResp.contains(SDRSPEC))
      var microTmpl = putDevResp.get(SDRSPEC).get     // the 2nd get turns the Some(val) into val
      assert(microTmpl.contains("""deployment":"""))
      assert(putDevResp.contains(NETSPEEDSPEC))
    }
  }

  /** Add a device with higher memory and version */
  test("PUT /devices/9901 - memory 400, version 2.0.0") {
    val input = PutDevicesRequest("mytok", "rpi9901-mem-400-vers-2", List(Microservice(SDRSPEC,1,"{json policy for 9901 sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","400","int",">="),
      Prop("version","2.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","=")))), "whisper-id", Map())
    val response = Http(URL+"/devices/9901").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
    val putDevResp = parse(response.body).extract[Map[String,String]]
    if (putDevRespDisabled) assert(putDevResp.size === 0)
    else {
      assert(putDevResp.size === 1)
      assert(putDevResp.contains(SDRSPEC))
      var microTmpl = putDevResp.get(SDRSPEC).get     // the 2nd get turns the Some(val) into val
      assert(microTmpl.contains("""deployment":"""))
    }
  }

  /** Add a device with netspeed and arch amd64 */
  test("PUT /devices/9902 - netspeed") {
    val input = PutDevicesRequest("mytok", "rpi9902-netspeed-amd64", List(Microservice(NETSPEEDSPEC,1,"{json policy for 9902 netspeed}",List(
      Prop("arch","amd64","string","in"),
      Prop("memory","300","int",">="),
      Prop("version","1.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","=")))), "whisper-id", Map())
    val response = Http(URL+"/devices/9902").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
    val putDevResp = parse(response.body).extract[Map[String,String]]
    if (putDevRespDisabled) assert(putDevResp.size === 0)
    else {
      assert(putDevResp.size === 1)
      assert(putDevResp.contains(NETSPEEDSPEC))
      var microTmpl = putDevResp.get(NETSPEEDSPEC).get     // the 2nd get turns the Some(val) into val
      assert(microTmpl.contains("""deployment":"""))
    }
  }

  /** Try adding a device with invalid integer property */
  test("PUT /devices/9903 - bad integer property") {
    val input = PutDevicesRequest("mytok", "rpi9903-bad-int", List(Microservice(SDRSPEC,1,"{json policy for 9903 sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","400MB","int",">="),
      Prop("version","2.0.0","version","in"),
      Prop("dataVerification","true","boolean","=")))), "whisper-id", Map())
    val response = Http(URL+"/devices/9903").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val putDevResp = parse(response.body).extract[ApiResponse]
    assert(putDevResp.code === ApiResponseType.BAD_INPUT)
  }

  /** Try adding an invalid device body */
  test("PUT /devices/9903 - bad format") {
    val badJsonInput = """{
      "token": "foo",
      "xname": "rpi9903-bad-format",
      "xregisteredMicroservices": [
        {
          "url": """"+SDRSPEC+"""",
          "numAgreements": 1,
          "policy": "{json policy for 9903 sdr}",
          "properties": [
            {
              "name": "arch",
              "value": "arm",
              "propType": "string",
              "op": "in"
            }
          ]
        }
      ],
      "msgEndPoint": "whisper-id",
      "softwareVersions": {}
    }"""
    val response = Http(URL+"/devices/9903").postData(badJsonInput).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.BAD_INPUT)     // for now this is what is returned when the json-to-scala conversion fails
  }

  /** Try adding a device with invalid micro url - this succeeds if putDevRespDisabled */
  test("PUT /devices/9903 - bad micro url") {
    val input = PutDevicesRequest("mytok", "rpi9903-bad-url", List(Microservice(NOTTHERESPEC,1,"{json policy for 9903 sdr}",List(
      Prop("arch","arm","string","in"),
      Prop("memory","400","int",">="),
      Prop("version","2.0.0","version","in"),
      Prop("dataVerification","true","boolean","=")))), "whisper-id", Map())
    val response = Http(URL+"/devices/9903").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    if (putDevRespDisabled) {
      // if we don't have to get the micro template, we allow any micro url
      assert(response.code === HttpCode.PUT_OK)
      val putDevResp = parse(response.body).extract[Map[String,String]]
      assert(putDevResp.size === 0)
    } else {
      assert(response.code === HttpCode.BAD_INPUT)
      val putDevResp = parse(response.body).extract[ApiResponse]
      assert(putDevResp.code === ApiResponseType.BAD_INPUT)
    }
  }

  /** Add an agbot so we can test it viewing devices */
  test("PUT /agbots/"+agbotId) {
    val input = PutAgbotsRequest(agbotToken, agbotId+"name", "whisper-id")
    val response = Http(URL+"/agbots/"+agbotId).postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /devices") {
    // val response: HttpResponse[String] = Http(URL+"/v1/devices").headers(("Accept","application/json")).param("id","a").param("token","a").asString
    val response: HttpResponse[String] = Http(URL+"/devices").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetDevicesResponse]
    assert(getDevResp.devices.size === (3 + numExistingDevices + (if (putDevRespDisabled) 1 else 0)))

    assert(getDevResp.devices.contains(deviceId))
    var dev = getDevResp.devices.get(deviceId).get     // the 2nd get turns the Some(val) into val
    assert(dev.name === "rpi"+deviceId+"-normal")
    assert(dev.registeredMicroservices.length === 2)
    var micro: Microservice = dev.registeredMicroservices.find(m => m.url==SDRSPEC) match {
      case Some(m) => m
      case None => assert(false); null
    }
    assert(micro.url === SDRSPEC)
    assert(micro.policy === "{json policy for "+deviceId+" sdr}")
    var archProp = micro.properties.find(p => p.name=="arch").orNull
    assert((archProp !== null) && (archProp.name === "arch"))
    assert(archProp.value === "arm")
    var memProp = micro.properties.find(p => p.name=="memory").orNull
    assert((memProp !== null) && (memProp.value === "300"))
    assert(dev.softwareVersions.size === 1)
    assert(dev.softwareVersions.contains("horizon"))
    assert(dev.softwareVersions.get("horizon").get === "3.2.1")
    micro = dev.registeredMicroservices.find(m => m.url==NETSPEEDSPEC) match {
      case Some(m) => m
      case None => assert(false); null
    }
    assert(micro.properties.find(p => p.name=="cpus") === None)
    assert(micro.properties.find(p => p.name=="agreementProtocols") !== None)
    assert(dev.registeredMicroservices.find(m => m.url==PWSSPEC) === None)

    assert(getDevResp.devices.contains("9901"))
    dev = getDevResp.devices.get("9901").get     // the 2nd get turns the Some(val) into val
    assert(dev.name === "rpi9901-mem-400-vers-2")
    assert(dev.registeredMicroservices.length === 1)
    micro = dev.registeredMicroservices.head
    assert(micro.url === SDRSPEC)
    assert(micro.policy === "{json policy for 9901 sdr}")
    memProp = micro.properties.find(p => p.name=="memory").get
    assert(memProp.value === "400")
    memProp = micro.properties.find(p => p.name=="version").get
    assert(memProp.value === "2.0.0")
    assert(dev.softwareVersions.size === 0)

    assert(getDevResp.devices.contains("9902"))
    dev = getDevResp.devices.get("9902").get     // the 2nd get turns the Some(val) into val
    assert(dev.name === "rpi9902-netspeed-amd64")
    assert(dev.registeredMicroservices.length === 1)
    micro = dev.registeredMicroservices.head
    assert(micro.url === NETSPEEDSPEC)
    archProp = micro.properties.find(p => p.name=="arch").get
    assert(archProp.value === "amd64")

    info("GET /devices output verified")
  }

  test("GET /devices - filter owner and name") {
    val response: HttpResponse[String] = Http(URL+"/devices").headers(ACCEPT).headers(USERAUTH).param("owner",user).param("name","rpi%netspeed%amd64").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetDevicesResponse]
    assert(getDevResp.devices.size === 1)
    assert(getDevResp.devices.contains("9902"))
  }

  test("GET /devices - filter owner and idfilter") {
    val response: HttpResponse[String] = Http(URL+"/devices").headers(ACCEPT).headers(USERAUTH).param("owner",user).param("idfilter","990%").asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetDevicesResponse]
    assert(getDevResp.devices.size === (if (putDevRespDisabled) 4 else 3))
    assert(getDevResp.devices.contains(deviceId))
    assert(getDevResp.devices.contains("9901"))
    assert(getDevResp.devices.contains("9902"))
    if (putDevRespDisabled) assert(getDevResp.devices.contains("9902"))
  }

  test("GET /devices - bad creds") {
    // val response: HttpResponse[String] = Http(URL+"/v1/devices").headers(("Accept","application/json")).param("id","a").param("token","a").asString
    val response: HttpResponse[String] = Http(URL+"/devices").headers(ACCEPT).headers(BADAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BADCREDS)
  }

  test("GET /devices - by agbot") {
    val response: HttpResponse[String] = Http(URL+"/devices").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetDevicesResponse]
    // assert(getDevResp.devices.size === 3)     // since the other test suites are creating some of these too, we can not know how many there are right now
  }

  /** Heartbeat for device 9900 */
  test("POST /devices/"+deviceId+"/heartbeat") {
    val response = Http(URL+"/devices/"+deviceId+"/heartbeat").method("post").headers(ACCEPT).headers(DEVICEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.OK)
  }

  test("GET /devices/"+deviceId) {
    val response: HttpResponse[String] = Http(URL+"/devices/"+deviceId).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetDevicesResponse]
    // assert(getDevResp.devices.size === 1)    // since the other test suites are creating some of these too, we can not know how many there are right now

    assert(getDevResp.devices.contains(deviceId))
    var dev = getDevResp.devices.get(deviceId).get     // the 2nd get turns the Some(val) into val
    assert(dev.name === "rpi"+deviceId+"-normal")

    // Verify the lastHeartbeat from the POST heartbeat above is within a few seconds of now. Format is: 2016-09-29T13:04:56.850Z[UTC]
    val now: Long = System.currentTimeMillis / 1000     // seconds since 1/1/1970
    val lastHb = ZonedDateTime.parse(dev.lastHeartbeat).toEpochSecond
    assert(now - lastHb <= 3)    // should not now be more than 3 seconds from the time the heartbeat was done above

    assert(dev.registeredMicroservices.length === 2)
    val micro: Microservice = dev.registeredMicroservices.find(m => m.url==SDRSPEC) match {
      case Some(m) => m
      case None => assert(false); null
    }
    assert(micro.url === SDRSPEC)
    assert(micro.policy === "{json policy for "+deviceId+" sdr}")
    var archProp = micro.properties.find(p => p.name=="arch").orNull
    assert((archProp !== null) && (archProp.name === "arch"))
    assert(archProp.value === "arm")
    var memProp = micro.properties.find(p => p.name=="memory").orNull
    assert((memProp !== null) && (memProp.value === "300"))

    assert(dev.registeredMicroservices.find(m => m.url==NETSPEEDSPEC) !== None)

    info("GET /devices/"+deviceId+" output verified")
  }

  test("GET /devices/"+deviceId+" - as device") {
    val response: HttpResponse[String] = Http(URL+"/devices/"+deviceId).headers(ACCEPT).headers(DEVICEAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetDevicesResponse]
    assert(getDevResp.devices.size === 1)
  }

  test("GET /devices/"+deviceId+" - as device - encoded") {
    val response: HttpResponse[String] = Http(URL+"/devices/"+deviceId).headers(ACCEPT).headers(ENCODEDAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetDevicesResponse]
    assert(getDevResp.devices.size === 1)
    assert(getDevResp.devices.contains(deviceId))
    val dev = getDevResp.devices.get(deviceId).get     // the 2nd get turns the Some(val) into val
    assert(dev.name === "rpi"+deviceId+"-normal")
  }

  test("GET /devices/"+deviceId+" - as agbot") {
    val response: HttpResponse[String] = Http(URL+"/devices/"+deviceId).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetDevicesResponse]
    assert(getDevResp.devices.size === 1)
  }

  test("GET /devices/"+deviceId+" - as device, with token in URL parms, but no id") {
    val response: HttpResponse[String] = Http(URL+"/devices/"+deviceId+"?token="+deviceToken).headers(ACCEPT).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetDevicesResponse]
    assert(getDevResp.devices.size === 1)
  }

  test("GET /devices/"+deviceId+" - as user in the URL params") {
    val response: HttpResponse[String] = Http(URL+"/devices/"+deviceId+"?id="+user+"&token="+pw).headers(ACCEPT).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetDevicesResponse]
    assert(getDevResp.devices.size === 1)
  }

  test("GET /devices/9903 - should not be there") {
    val response: HttpResponse[String] = Http(URL+"/devices/9903").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    if (putDevRespDisabled) {
      // we we aren't checking the micro url, the put on 9903 above doesn't fail
      assert(response.code === HttpCode.OK)
      val getDevResp = parse(response.body).extract[GetDevicesResponse]
      assert(getDevResp.devices.size === 1)
    } else {
      assert(response.code === HttpCode.NOT_FOUND)
      val getDevResp = parse(response.body).extract[ApiResponse]
      assert(getDevResp.code === ApiResponseType.NOT_FOUND)
    }
  }

  test("POST /search/devices/ - all arm devices") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","2","int",">="),
      Prop("version","*","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchDevicesResponse]
    val devices = postSearchDevResp.devices
    assert(devices.length === 2)     // we created 2 arm devices
    assert(devices.filter(d => d.id==deviceId || d.id=="9901").length === 2)
    var dev = devices.find(d => d.id==deviceId).get     // the 2nd get turns the Some(val) into val
    assert(dev.name === "rpi"+deviceId+"-normal")
    assert(dev.microservices.length === 1)
    var micro = dev.microservices.head
    assert(micro.url === SDRSPEC)
    assert(micro.policy === "{json policy for "+deviceId+" sdr}")
    var archProp = micro.properties.find(p => p.name=="arch").orNull
    assert((archProp !== null) && (archProp.name === "arch"))
    assert(archProp.value === "arm")
  }

  test("POST /search/devices/ - netspeed arch amd64 - as agbot") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(NETSPEEDSPEC,List(
      Prop("arch","amd64","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","[1.0.0,2.0.0]","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchDevicesResponse]
    val devices = postSearchDevResp.devices
    assert(devices.length === 1)
    assert(devices.filter(d => d.id=="9902").length === 1)
  }

  test("POST /search/devices/ - netspeed arch * - as agbot") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(NETSPEEDSPEC,List(
      Prop("arch","*","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","[1.0.0,2.0.0]","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchDevicesResponse]
    val devices = postSearchDevResp.devices
    assert(devices.length === 2)
    assert(devices.filter(d => d.id==deviceId).length === 1)
    assert(devices.filter(d => d.id=="9902").length === 1)
  }

  test("POST /search/devices/ - arch list, mem 400, version 2.0.0") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","arm,amd64","list","in"),
      Prop("memory","400","int",">="),
      Prop("version","2.0.0,3.0.0","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","true","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchDevicesResponse]
    val devices = postSearchDevResp.devices
    assert(devices.length === 1)
    assert(devices.filter(d => d.id=="9901").length === 1)
  }

  /** Do not expect any matches on this search */
  test("POST /search/devices/ - data verification false") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","","wildcard","in"),
      Prop("memory","","wildcard",">="),
      Prop("version","0","version","in"),     // in osgi version format 0 means lower bound is 0 and upper bound infinity
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","false","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchDevicesResponse]
    assert(postSearchDevResp.devices.length === 0)
  }

  test("POST /search/devices/ - invalid propType") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","","stringx","in"),
      Prop("memory","","int",">="),
      Prop("version","","version","in"),
      Prop("dataVerification","","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("POST /search/devices/ - invalid op") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","","string","inx"),
      Prop("memory","","int",">="),
      Prop("version","","version","in"),
      Prop("dataVerification","","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("POST /search/devices/ - invalid version") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","*","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","1.2.3.4","version","in"),
      Prop("dataVerification","*","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("POST /search/devices/ - invalid boolean/op combo") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","","string","in"),
      Prop("memory","","int",">="),
      Prop("version","","version","in"),
      Prop("dataVerification","","boolean","in")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("POST /search/devices/ - invalid string/op combo") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","","string","="),
      Prop("memory","","int",">="),
      Prop("version","","version","in"),
      Prop("dataVerification","","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("POST /search/devices/ - invalid int/op combo") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","","string","in"),
      Prop("memory","","int","in"),
      Prop("version","","version","in"),
      Prop("dataVerification","","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  test("POST /search/devices/ - invalid version/op combo") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","","string","in"),
      Prop("memory","","int",">="),
      Prop("version","","version",">="),
      Prop("dataVerification","","boolean","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.BAD_INPUT)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.BAD_INPUT)
  }

  /** Add an agreement for device 9900 - as the device */
  test("PUT /devices/"+deviceId+"/agreements/9950 - as device") {
    val input = PutDeviceAgreementRequest(SDRSPEC, "signed")
    val response = Http(URL+"/devices/"+deviceId+"/agreements/9950").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(DEVICEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Update an agreement for device 9900 - as user */
  test("PUT /devices/"+deviceId+"/agreements/9950 - as user") {
    val input = PutDeviceAgreementRequest(SDRSPEC, "negotiating")
    val response = Http(URL+"/devices/"+deviceId+"/agreements/9950").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  test("GET /devices/"+deviceId+"/agreements - verify device agreement") {
    val response: HttpResponse[String] = Http(URL+"/devices/"+deviceId+"/agreements").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetDeviceAgreementsResponse]
    assert(getAgResp.agreements.size === 1)

    assert(getAgResp.agreements.contains("9950"))
    var ag = getAgResp.agreements.get("9950").get     // the 2nd get turns the Some(val) into val
    assert(ag.microservice === SDRSPEC)
    assert(ag.state === "negotiating")

    info("GET /devices/"+deviceId+"/agreements output verified")
  }

  test("GET /devices/"+deviceId+"/agreements/9950") {
    val response: HttpResponse[String] = Http(URL+"/devices/"+deviceId+"/agreements/9950").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetDeviceAgreementsResponse]
    assert(getAgResp.agreements.size === 1)

    assert(getAgResp.agreements.contains("9950"))
    var ag = getAgResp.agreements.get("9950").get     // the 2nd get turns the Some(val) into val
    assert(ag.microservice === SDRSPEC)
    assert(ag.state === "negotiating")

    info("GET /devices/"+deviceId+"/agreements/9950 output verified")
  }

  test("GET /devices/"+deviceId+"/agreements/9950 - as device") {
    val response: HttpResponse[String] = Http(URL+"/devices/"+deviceId+"/agreements/9950").headers(ACCEPT).headers(DEVICEAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.OK)
    val getAgResp = parse(response.body).extract[GetDeviceAgreementsResponse]
    assert(getAgResp.agreements.size === 1)

    info("GET /devices/"+deviceId+"/agreements/9950 as device output verified")
  }

  /** Run /search/devices again and we should get 1 less result, because 9900 is in contract */
  test("POST /search/devices/ - all arm devices, "+deviceId+" in agreement") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","","wildcard","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchDevicesResponse]
    val devices = postSearchDevResp.devices
    assert(devices.length === 1 || devices.length === 2)     // UsersSuite may have created 1
    assert(devices.filter(d => d.id=="9901").length === 1)
  }

  /** We should still find the netspeed MS on 9900, even though the sdr MS on 9900 is in agreement */
  test("POST /search/devices/ - netspeed arch arm, "+deviceId+" sdr in agreement - as agbot") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(NETSPEEDSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","[1.0.0,2.0.0]","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchDevicesResponse]
    val devices = postSearchDevResp.devices
    assert(devices.length === 1)
    assert(devices.filter(d => d.id==deviceId).length === 1)
  }

  /** Delete the agreement for device 9900 */
  test("DELETE /devices/"+deviceId+"/agreements/9950 - netspeed") {
    val response = Http(URL+"/devices/"+deviceId+"/agreements/9950").method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("DELETE 9950, code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)
  }

  /** Add an agreement for device 9900 for netspeed */
  test("PUT /devices/"+deviceId+"/agreements/9950 - netspeed") {
    val input = PutDeviceAgreementRequest(NETSPEEDSPEC, "signed")
    val response = Http(URL+"/devices/"+deviceId+"/agreements/9950").postData(write(input)).method("put").headers(CONTENT).headers(ACCEPT).headers(DEVICEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.PUT_OK)
  }

  /** Make sure we do not find the netspeed MS on 9900 now */
  test("POST /search/devices/ - netspeed arch arm, "+deviceId+" netspeed in agreement - as agbot") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(NETSPEEDSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","[1.0.0,2.0.0]","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchDevicesResponse]
    val devices = postSearchDevResp.devices
    assert(devices.length === 0)
  }

  /** We should still find the sdr MS on 9900, even though the netspeed MS on 9900 is in agreement */
  test("POST /search/devices/ - sdr arch arm, "+deviceId+" netspeed in agreement - as agbot") {
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","*","int",">="),
      Prop("version","[1.0.0,2.0.0]","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      86400, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    // info("code: "+response.code+", response.body: "+response.body)
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchDevicesResponse]
    val devices = postSearchDevResp.devices
    assert(devices.length === 2 || devices.length === 3)      // UsersSuite creates 1 too
    assert(devices.filter(d => d.id==deviceId).length === 1)
  }

  //TODO: add tests for searching for multiple MS URLs in 1 call

  /** Test the secondsStale parameter */
  test("POST /search/devices/ - all arm devices, but all stale") {
    Thread.sleep(1500)    // delay 1.5 seconds so other devices will be stale
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","2","int",">="),
      Prop("version","*","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      1, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchDevicesResponse]
    val devices = postSearchDevResp.devices
    assert(devices.length === 0)
}

  /** Heartbeat for device 9900 */
  test("POST /devices/"+deviceId+"/heartbeat - again") {
    val response = Http(URL+"/devices/"+deviceId+"/heartbeat").method("post").headers(ACCEPT).headers(DEVICEAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[ApiResponse]
    assert(postSearchDevResp.code === ApiResponseType.OK)
  }

  /** Test the secondsStale parameter */
  test("POST /search/devices/ - all arm devices, 1 not stale") {
    val secondsNotStale = if (usingPersistence) 3 else 1
    info("secondsNotStale: "+secondsNotStale)
    val input = PostSearchDevicesRequest(List(MicroserviceSearch(SDRSPEC,List(
      Prop("arch","arm","string","in"),
      Prop("memory","2","int",">="),
      Prop("version","*","version","in"),
      Prop("agreementProtocols",agProto,"list","in"),
      Prop("dataVerification","","wildcard","=")))),
      secondsNotStale, List[String](""), 0, 0)
    val response = Http(URL+"/search/devices").postData(write(input)).headers(CONTENT).headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.POST_OK)
    val postSearchDevResp = parse(response.body).extract[PostSearchDevicesResponse]
    val devices = postSearchDevResp.devices
    assert(devices.length === 1)
    assert(devices.filter(d => d.id==deviceId).length === 1)
  }

  test("DELETE /devices/9902 - explicit delete of 9902") {
    var response = Http(URL+"/devices/9902").method("delete").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.DELETED)

    response = Http(URL+"/devices/9902").headers(ACCEPT).headers(USERAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.NOT_FOUND)
  }

  /** Clean up, delete all the test devices */
  test("Cleanup - DELETE everything and confirm they are gone") {
    deleteAllUsers

    val response: HttpResponse[String] = Http(URL+"/devices").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: "+response.code)
    // info("code: "+response.code+", response.body: "+response.body)
    assert(response.code === HttpCode.OK)
    val getDevResp = parse(response.body).extract[GetDevicesResponse]
    assert(getDevResp.devices.size === numExistingDevices)
  }
}
