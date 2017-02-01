/** Services routes for all of the /devices api methods. */
package com.horizon.exchangeapi

import org.scalatra._
import slick.jdbc.PostgresProfile.api._
import org.scalatra.swagger._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import org.scalatra.json._
import org.slf4j._
import Access._
import BaseAccess._
import scala.util._
import scala.util.control.Breaks._
import scala.collection.immutable._
import scala.collection.mutable.{ListBuffer, Set => MutableSet, HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection
import scala.concurrent.ExecutionContext.Implicits.global
import scalaj.http._
import com.horizon.exchangeapi.tables._
import collection.JavaConversions.enumerationAsScalaIterator

//====== These are the input and output structures for /devices routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /devices */
case class GetDevicesResponse(devices: Map[String,Device], lastIndex: Int)
case class GetDeviceAttributeResponse(attribute: String, value: String)

/** Input for POST /search/devices */
case class PostSearchDevicesRequest(desiredMicroservices: List[MicroserviceSearch], secondsStale: Int, propertiesToReturn: List[String], startIndex: Int, numEntries: Int) {
  /** Halts the request with an error msg if the user input is invalid. */
  def validate = {
    for (m <- desiredMicroservices) {
      m.validate match {
        case Some(s) => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, s))
        case None => ;
      }
    }
  }

  /** Returns the micorservices that match the search criteria */
  def matches(devices: Map[String,Device], agHash: AgreementsHash)(implicit logger: Logger): PostSearchDevicesResponse = {
    // Build a hash of the current number of agreements for each device and microservice, so we can check them quickly
    // logger.trace(agHash.agHash.toString)

    // loop thru the existing devices and microservices in the DB
    //todo: should probably make this more FP style
    var devicesResp: List[DeviceResponse] = List()
    // for ((id,d) <- devices; if !ApiTime.isSecondsStale(d.lastHeartbeat,secondsStale) ) {   <-- the db query now filters out stale devices
    for ((id,d) <- devices) {
      var microsResp: List[Microservice] = List()
      for (m <- d.registeredMicroservices) { breakable {
        // do not even bother checking this against the search criteria if this micro is already at its agreement limit
        val agDev = agHash.agHash.get(id)
        agDev match {
          case Some(agDev) => val agNum = agDev.get(m.url)
            agNum match {
              case Some(agNum) => if (agNum >= m.numAgreements) break  // this is really a continue
              case None => ;      // no agreements for this microservice, nothing to do
            }
          case None => ;      // no agreements for this device, nothing to do
        }

        // we have 1 microservice from the db, now go thru all of the desired micros
        breakable { for (m2 <- desiredMicroservices) {
          if (m2.matches(m)) {
            microsResp = microsResp :+ m
            break
          }
        } }
      } }
      if (microsResp.length > 0) {
        // at least 1 micro from this device matched, so add this device to the response list
        devicesResp = devicesResp :+ DeviceResponse(id, d.name, microsResp, d.msgEndPoint, d.publicKey)
      }
    }
    // return the search result to the rest client
    PostSearchDevicesResponse(devicesResp, 0)
  }
}

case class DeviceResponse(id: String, name: String, microservices: List[Microservice], msgEndPoint: String, publicKey: String)
case class PostSearchDevicesResponse(devices: List[DeviceResponse], lastIndex: Int)

/** For backward compatibility for before i added the publicKey field */
case class PutDevicesRequestOld(token: String, name: String, registeredMicroservices: List[Microservice], msgEndPoint: String, softwareVersions: Map[String,String]) {
  def toPutDevicesRequest = PutDevicesRequest(token, name, registeredMicroservices, msgEndPoint, softwareVersions, "")
}

/** Input format for PUT /devices/<device-id> */
case class PutDevicesRequest(token: String, name: String, registeredMicroservices: List[Microservice], msgEndPoint: String, softwareVersions: Map[String,String], publicKey: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  /** Halts the request with an error msg if the user input is invalid. */
  def validate = {
    // if (msgEndPoint == "" && publicKey == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "either msgEndPoint or publicKey must be specified."))  <-- skipping this check because POST /agbots/{id}/msgs checks for the publicKey
    for (m <- registeredMicroservices) {
      if (m.numAgreements != 1) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "invalid value "+m.numAgreements+" for numAgreements in "+m.url+". Currently it must always be 1."))
      m.validate match {
        case Some(s) => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, s))
        case None => ;
      }
    }
  }

  /** Get the db actions to insert or update all parts of the device */
  def getDbUpsert(id: String, owner: String): DBIO[_] = {
    // Accumulate the actions in a list, starting with the action to insert/update the device itself
    val actions = ListBuffer[DBIO[_]](DeviceRow(id, token, name, owner, msgEndPoint, write(softwareVersions), ApiTime.nowUTC, publicKey).upsert)
    val microsUrls = MutableSet[String]()    // the url attribute of the micros we are creating, so we can delete everthing else for this device
    val propsIds = MutableSet[String]()    // the propId attribute of the props we are creating, so we can delete everthing else for this device
    // Now add actions to insert/update the device's micros and props
    for (m <- registeredMicroservices) {
      actions += m.toMicroserviceRow(id).upsert
      microsUrls += m.url
      for (p <- m.properties) {
        actions += p.toPropRow(id,m.url).upsert
        propsIds += id+"|"+m.url+"|"+p.name
      }
    }
    // handle the case where they changed what micros or props this device has, form selects to delete all micros and props that reference this device that aren't in microsUrls, propsIds
    actions += PropsTQ.rows.filter(_.propId like id+"|%").filterNot(_.propId inSet propsIds).delete     // props that reference this device, but are not in the list we just created/updated
    actions += MicroservicesTQ.rows.filter(_.deviceId === id).filterNot(_.url inSet microsUrls).delete    // micros that reference this device, but are not in the list we just created/updated

    DBIO.seq(actions.toList: _*)      // convert the list of actions to a DBIO seq
  }

  /** Get the db actions to update all parts of the device. This is run, instead of getDbUpsert(), when it is a device doing it,
   * because we can't let a device create new devices. */
  def getDbUpdate(id: String, owner: String): DBIO[_] = {
    val actions = ListBuffer[DBIO[_]](DeviceRow(id, token, name, owner, msgEndPoint, write(softwareVersions), ApiTime.nowUTC, publicKey).update)
    val microsUrls = MutableSet[String]()    // the url attribute of the micros we are updating, so we can delete everthing else for this device
    val propsIds = MutableSet[String]()    // the propId attribute of the props we are updating, so we can delete everthing else for this device
    for (m <- registeredMicroservices) {
      actions += m.toMicroserviceRow(id).upsert     // only the device should be update (the rest should be upsert), because that's the only one we know exists
      microsUrls += m.url
      for (p <- m.properties) {
        actions += p.toPropRow(id,m.url).upsert
        propsIds += id+"|"+m.url+"|"+p.name
      }
    }
    // handle the case where they changed what micros or props this device has, form selects to delete all micros and props that reference this device that aren't in microsUrls, propsIds
    actions += PropsTQ.rows.filter(_.propId like id+"|%").filterNot(_.propId inSet propsIds).delete     // props that reference this device, but are not in the list we just updated
    actions += MicroservicesTQ.rows.filter(_.deviceId === id).filterNot(_.url inSet microsUrls).delete    // micros that reference this device, but are not in the list we just updated

    DBIO.seq(actions.toList: _*)
  }

  /** Returns the microservice templates for the registeredMicroservices in this object */
  def getMicroTemplates: Map[String,String] = {
    if (ExchConfig.getBoolean("api.microservices.disable")) return Map[String,String]()
    var resp = new MutableHashMap[String,String]()
    for (m <- registeredMicroservices) {
      // parse the microservice name out of the specRef url
      val R = ExchConfig.getString("api.specRef.prefix")+"(.*)"+ExchConfig.getString("api.specRef.suffix")+"/?"
      val microName = m.url match {
        case R.r(name) => name
        case _ => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Incorrect format for microservice url '"+m.url+"'"))
      }

      // find arch and version properties
      val arch = m.properties.find(p => p.name=="arch").map[String](p => p.value).orNull
      if (arch == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Arch property is not specified for microservice '"+m.url+"'"))
      val version = m.properties.find(p => p.name=="version").map[String](p => p.value).orNull
      if (version == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Version property is not specified for microservice '"+m.url+"'"))
      val versObj = Version(version)

      // Get the microservice template from softlayer object store
      val microTmplName = microName+"-"+arch+"-"+versObj
      val objStoreUrl = ExchConfig.getString("api.objStoreTmpls.prefix")+"/"+ExchConfig.getString("api.objStoreTmpls.microDir")+"/"+microTmplName+ExchConfig.getString("api.objStoreTmpls.suffix")
      var response: HttpResponse[String] = null
      try {     // the http request can throw java.net.SocketTimeoutException: connect timed out
        response = scalaj.http.Http(objStoreUrl).headers(("Accept","application/json")).asString
      } catch { case e: Exception => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "Exception thrown while trying to get '"+objStoreUrl+"' from Softlayer object storage: "+e)) }
      if (response.code != HttpCode.OK) halt(response.code, ApiResponse(ApiResponseType.BAD_INPUT, "Microservice template for '"+microTmplName+"' not found"))
      resp.put(m.url, response.body)
    }
    resp.toMap
  }
}

case class PatchDevicesRequest(token: Option[String], name: Option[String], msgEndPoint: Option[String], softwareVersions: Option[Map[String,String]], publicKey: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the device, and the attribute name being updated. */
  def getDbUpdate(id: String): (DBIO[_],String) = {
    val lastHeartbeat = ApiTime.nowUTC
    //todo: support updating more than 1 attribute, but i think slick does not support dynamic db field names
    // find the 1st non-blank attribute and create a db action to update it for this device
    token match {
      case Some(token) => if (token == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the token can not be set to the empty string"))
        val tok = if (Password.isHashed(token)) token else Password.hash(token)
        return ((for { d <- DevicesTQ.rows if d.id === id } yield (d.id,d.token,d.lastHeartbeat)).update((id, tok, lastHeartbeat)), "token")
      case _ => ;
    }
    softwareVersions match {
      case Some(swv) => val swVersions = if (swv != "") write(softwareVersions) else ""
        return ((for { d <- DevicesTQ.rows if d.id === id } yield (d.id,d.softwareVersions,d.lastHeartbeat)).update((id, swVersions, lastHeartbeat)), "softwareVersions")
      case _ => ;
    }
    name match { case Some(name) => return ((for { d <- DevicesTQ.rows if d.id === id } yield (d.id,d.name,d.lastHeartbeat)).update((id, name, lastHeartbeat)), "name"); case _ => ; }
    msgEndPoint match { case Some(msgEndPoint) => return ((for { d <- DevicesTQ.rows if d.id === id } yield (d.id,d.msgEndPoint,d.lastHeartbeat)).update((id, msgEndPoint, lastHeartbeat)), "msgEndPoint"); case _ => ; }
    publicKey match { case Some(publicKey) => return ((for { d <- DevicesTQ.rows if d.id === id } yield (d.id,d.publicKey,d.lastHeartbeat)).update((id, publicKey, lastHeartbeat)), "publicKey"); case _ => ; }
    return (null, null)
  }
}

/** Output for PUT /devices/<device-id> - contains the microservice templates */
// class PutDevicesResponse extends Map[String,String]    // key is micro url (specRef), value is micro template (download info)


/** Output format for GET /devices/{id}/agreements */
case class GetDeviceAgreementsResponse(agreements: Map[String,DeviceAgreement], lastIndex: Int)

/** Input format for PUT /devices/{id}/agreements/<agreement-id> */
case class PutDeviceAgreementRequest(microservice: String, state: String) {
  def toDeviceAgreement = DeviceAgreement(microservice, state, ApiTime.nowUTC)
  def toDeviceAgreementRow(deviceId: String, agId: String) = DeviceAgreementRow(agId, deviceId, microservice, state, ApiTime.nowUTC)
}


/** Input body for POST /devices/{id}/msgs */
case class PostDevicesMsgsRequest(message: String, ttl: Int)

/** Response for GET /devices/{id}/msgs */
case class GetDeviceMsgsResponse(messages: List[DeviceMsg], lastIndex: Int)


/** Implementation for all of the /devices routes */
trait DevicesRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  implicit def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats
  // implicit def formats: org.json4s.Formats{val dateFormat: org.json4s.DateFormat; val typeHints: org.json4s.TypeHints}

  /* ====== GET /devices ================================
    This is of type org.scalatra.swagger.SwaggerOperation
    apiOperation() is a method of org.scalatra.swagger.SwaggerSupport. It returns org.scalatra.swagger.SwaggerSupportSyntax$$OperationBuilder
    and then all of the other methods below that (summary, notes, etc.) are all part of OperationBuilder and return OperationBuilder.
    So instead of the infix invocation in the code below, we could alternatively code it like this:
    val getDevices = apiOperation[GetDevicesResponse]("getDevices").summary("Returns matching devices").notes("Based on the input selection criteria, returns the matching devices (RPis) in the exchange DB.")
  */
  val getDevices =
    (apiOperation[GetDevicesResponse]("getDevices")
      summary("Returns all devices")
      notes("""Returns all devices (RPis) in the exchange DB. Can be run by a user or agbot (but not a device).

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      // authorizations("basicAuth")
      parameters(
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("idfilter", DataType.String, Option[String]("Filter results to only include devices with this id (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("name", DataType.String, Option[String]("Filter results to only include devices with this name (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include devices with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      // this does not work, because scalatra will not give me the request.body on a GET
      // parameters(Parameter("body", DataType[GetDeviceRequest], Option[String]("Device search criteria"), paramType = ParamType.Body))
      )

  /** Handles GET /devices. Normally called by the user to see all devices.
   *  operation() is a method of org.scalatra.swagger.SwaggerSupport that takes SwaggerOperation and returns RouteTransformer
   */
  get("/devices", operation(getDevices)) ({
    // try {    // this try/catch does not get us much more than what scalatra does by default
    // I think the request member is of type org.eclipse.jetty.server.Request, which implements interfaces javax.servlet.http.HttpServletRequest and javax.servlet.ServletRequest
    val creds = validateAccessToDevice(BaseAccess.READ, "*")
    val superUser = isSuperUser(creds)
    // throw new IllegalArgumentException("arg 1 was wrong...")
    // The devices, microservices, and properties tables all combine to form the Device object, so we do joins to get them all.
    // Note: joinLeft is necessary here so that if no micros exist for a device, we still get the device (and likewise for the micro if no props exist).
    //    This means m and p below are wrapped in Option because they may not always be there
    var q = for {
      // ((d, m), p) <- DevicesTQ.rows joinLeft MicroservicesTQ.rows on (_.id === _.deviceId) joinLeft PropsTQ.rows on ( (dm, p) => { dm._1.id === p.deviceId && dm._2.map(_.url) === p.msUrl } )
      ((d, m), p) <- DevicesTQ.rows joinLeft MicroservicesTQ.rows on (_.id === _.deviceId) joinLeft PropsTQ.rows on (_._2.map(_.msId) === _.msId)
    } yield (d, m, p)

    // add filters
    params.get("idfilter").foreach(id => { if (id.contains("%")) q = q.filter(_._1.id like id) else q = q.filter(_._1.id === id) })
    params.get("name").foreach(name => { if (name.contains("%")) q = q.filter(_._1.name like name) else q = q.filter(_._1.name === name) })
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_._1.owner like owner) else q = q.filter(_._1.owner === owner) })

    db.run(q.result).map({ list =>
      logger.debug("GET /devices result size: "+list.size)
      val devices = DevicesTQ.parseJoin(superUser, list)
      GetDevicesResponse(devices, 0)
    })
    // } catch { case e: Exception => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "Oops! Somthing unexpected happened: "+e)) }
  })

  /* ====== GET /devices/{id} ================================ */
  val getOneDevice =
    (apiOperation[GetDevicesResponse]("getOneDevice")
      summary("Returns a device")
      notes("""Returns the device (RPi) with the specified id in the exchange DB. Can be run by that device, a user, or an agbot.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the device. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified, and it must be 1 of the direct attributes of the device resource (not of the microservices). If not specified, the entire device resource (including microservices) will be returned."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /devices/{id}. Normally called by the device to verify his own entry after a reboot. */
  get("/devices/:id", operation(getOneDevice)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    val creds = validateAccessToDevice(BaseAccess.READ, id)
    val superUser = isSuperUser(creds)
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the device
        val q = DevicesTQ.getAttribute(id, attribute)
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Device attribute name '"+attribute+"' is not an attribute of the device resource."))
        db.run(q.result).map({ list =>
          logger.trace("GET /devices/"+id+" attribute result: "+list.toString)
          if (list.size > 0) {
            GetDeviceAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")     // validateAccessToDevice() will return ApiResponseType.NOT_FOUND to the client so do that here for consistency
          }
        })

      case None => ;  // Return the whole device, including the microservices
        // The devices, microservices, and properties tables all combine to form the Device object, so we do joins to get them all.
        // Note: joinLeft is necessary here so that if no micros exist for a device, we still get the device (and likewise for the micro if no props exist).
        //    This means m and p below are wrapped in Option because sometimes they may not always be there
        val q = for {
          // (((d, m), p), s) <- DevicesTQ.rows joinLeft MicroservicesTQ.rows on (_.id === _.deviceId) joinLeft PropsTQ.rows on (_._2.map(_.msId) === _.msId) joinLeft SoftwareVersionsTQ.rows on (_._1._1.id === _.deviceId)
          ((d, m), p) <- DevicesTQ.rows joinLeft MicroservicesTQ.rows on (_.id === _.deviceId) joinLeft PropsTQ.rows on (_._2.map(_.msId) === _.msId)
          if d.id === id
        } yield (d, m, p)

        db.run(q.result).map({ list =>
          logger.trace("GET /devices/"+id+" result: "+list.toString)
          if (list.size > 0) {
            val devices = DevicesTQ.parseJoin(superUser, list)
            GetDevicesResponse(devices, 0)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")     // validateAccessToDevice() will return ApiResponseType.NOT_FOUND to the client so do that here for consistency
          }
        })
    }
  })

  // ======== POST /search/devices ========================
  val postSearchDevices =
    (apiOperation[PostSearchDevicesResponse]("postSearchDevices")
      summary("Returns matching devices")
      notes """Based on the input selection criteria, returns the matching devices (RPis) in the exchange DB. Can be run by a user or agbot (but not a device). The **request body** structure:

```
{
  "desiredMicroservices": [    // list of data microservices you are interested in
    {
      "url": "https://bluehorizon.network/documentation/sdr-device-api",
      "properties": [    // list of properties to match specific devices/microservices
        {
          "name": "arch",         // typical property names are: arch, version, dataVerification, memory
          "value": "arm",         // should always be a string (even for boolean and int). Use "*" for wildcard
          "propType": "string",   // valid types: string, list, version, boolean, int, or wildcard
          "op": "="               // =, <=, >=, or in
        }
      ]
    }
  ],
  "secondsStale": 60,     // max number of seconds since the exchange has heard from the device, 0 if you do not care
  "propertiesToReturn": [    // ignored right now
    "string"
  ],
  "startIndex": 0,    // for pagination, ignored right now
  "numEntries": 0    // ignored right now
}
```"""
      parameters(
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostSearchDevicesRequest],
          Option[String]("Search criteria to find matching devices in the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val postSearchDevices2 = (apiOperation[PostSearchDevicesRequest]("postSearchDevices2") summary("a") notes("a"))

  /** Handles POST /search/devices. Normally called by the agbot to search for available devices. */
  post("/search/devices", operation(postSearchDevices)) ({
    validateAccessToDevice(BaseAccess.READ, "*")
    val searchProps = try { parse(request.body).extract[PostSearchDevicesRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    searchProps.validate
    logger.debug("/search/devices criteria: "+searchProps.desiredMicroservices.toString)
    val resp = response
    // Narrow down the db query results as much as possible with db selects, then searchProps.matchesDbResults will do the rest
    var q = for {
      ((d, m), p) <- DevicesTQ.rows joinLeft MicroservicesTQ.rows on (_.id === _.deviceId) joinLeft PropsTQ.rows on (_._2.map(_.msId) === _.msId)
    } yield (d, m, p)
    // Also filter out devices that are too stale (have not heartbeated recently)
    if (searchProps.secondsStale > 0) q = q.filter(_._1.lastHeartbeat >= ApiTime.pastUTC(searchProps.secondsStale))

    var agHash: AgreementsHash = null
    db.run(DeviceAgreementsTQ.getAgreementsWithState.result.flatMap({ agList =>
      logger.debug("POST /search/devices aglist result size: "+agList.size)
      logger.trace("POST /search/devices aglist result: "+agList.toString)
      agHash = new AgreementsHash(agList)
      q.result      // queue up our device/ms/prop query next
    })).map({ list =>
      logger.debug("POST /search/devices result size: "+list.size)
      // logger.trace("POST /search/devices result: "+list.toString)
      // logger.trace("POST /search/devices agHash: "+agHash.agHash.toString)
      if (list.size > 0) resp.setStatus(HttpCode.POST_OK)   //todo: this check only works if there are no devices at all
      else resp.setStatus(HttpCode.NOT_FOUND)
      val devices = DevicesTQ.parseJoin(false, list)
      searchProps.matches(devices, agHash)
    })

    /* old way...
    db.run(q.result).map({ list =>
      logger.debug("POST /search/devices result size: "+list.size)
      val devices = DevicesTQ.parseJoin(false, list)
      db.run(DeviceAgreementsTQ.getAgreementsWithState.result).map({ agList =>
        resp.setStatus(HttpCode.POST_OK)
        searchProps.matches(devices, new AgreementsHash(agList))
      })
    })
    */
  })

  // =========== PUT /devices/{id} ===============================
  val putDevices =
    (apiOperation[Map[String,String]]("putDevices")
      summary "Adds/updates a device"
      notes """Adds a new device (RPi) to the exchange DB, or updates an existing device, and returns the microservice templates for the microservices being registered. This must be called by the user to add a device, and then can be called by that user or device to update itself. The **request body** structure:

```
{
  "token": "abc",       // device token, set by user when adding this device.
  "name": "rpi3",         // device name that you pick
  "registeredMicroservices": [    // list of data microservices you want to make available
    {
      "url": "https://bluehorizon.network/documentation/sdr-device-api",
      "numAgreements": 1,       // for now always set this to 1
      "policy": "{...}"     // the microservice policy file content as a json string blob
      "properties": [    // list of properties to help agbots search for this, or requirements on the agbot
        {
          "name": "arch",         // must at least include arch and version properties
          "value": "arm",         // should always be a string (even for boolean and int). Use "*" for wildcard
          "propType": "string",   // valid types: string, list, version, boolean, int, or wildcard
          "op": "="               // =, greater-than-or-equal-symbols, less-than-or-equal-symbols, or in (must use the same op as the agbot search)
        }
      ]
    }
  ],
  "msgEndPoint": "whisper-id",    // msg service endpoint id for this device to be contacted by agbots, empty string to use the built-in Exchange msg service
  "softwareVersions": {"horizon": "1.2.3"},      // various software versions on the device
  "publicKey"      // used by agbots to encrypt msgs sent to this device using the built-in Exchange msg service
}
```

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the PUT method to see the response format instead.**"""
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device to be added/updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the device. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutDevicesRequest],
          Option[String]("Device object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val putDevices2 = (apiOperation[PutDevicesRequest]("putDevices2") summary("a") notes("a"))  // for some bizarre reason, the PutDevicesRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PUT /device/{id}. Must be called by user to add device, normally called by device to update itself. */
  put("/devices/:id", operation(putDevices)) ({
    // consider writing a customer deserializer that will do error checking on the body, see: https://gist.github.com/fehguy/4191861#file-gistfile1-scala-L74
    val id = params("id")
    val creds = validateUserOrDeviceId(BaseAccess.WRITE, id)
    val device = try { parse(request.body).extract[PutDevicesRequest] }
    catch {
      case e: Exception => if (e.getMessage.contains("No usable value for publicKey")) {    // the specific exception is MappingException
          // try parsing again with the old structure
          val deviceOld = try { parse(request.body).extract[PutDevicesRequestOld] }
          catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
          deviceOld.toPutDevicesRequest
        }
        else halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e))
    }
    device.validate
    val owner = if (isAuthenticatedUser(creds)) creds.id else ""
    val microTmpls = device.getMicroTemplates      // do this before creating/updating the entry in db, in case it can not find the templates
    val resp = response
    db.run(DevicesTQ.getNumOwned(owner).result.flatMap({ xs =>
      logger.debug("PUT /devices/"+id+" num owned: "+xs)
      val numOwned = xs
      val maxDevices = ExchConfig.getInt("api.limits.maxDevices")
      if (numOwned <= maxDevices || owner == "") {    // when owner=="" we know it is only an update, otherwise we are not sure, but if they are already over the limit, stop them anyway
        val action = if (owner == "") device.getDbUpdate(id, owner) else device.getDbUpsert(id, owner)
        action.transactionally.asTry
      }
      else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxDevices+ " devices")).asTry
    })).map({ xs =>
      logger.debug("PUT /devices/"+id+" result: "+xs.toString)
      xs match {
        case Success(v) => if (device.token != "") AuthCache.devices.put(Creds(id, device.token))    // the token passed in to the cache should be the non-hashed one
          resp.setStatus(HttpCode.PUT_OK)
          microTmpls
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, "device '"+id+"' not inserted or updated: "+t.getMessage)
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "device '"+id+"' not inserted or updated: "+t.toString)
          }
      }
    })
  })

  // =========== PATCH /devices/{id} ===============================
  val patchDevices =
    (apiOperation[Map[String,String]]("patchDevices")
      summary "Partially updates a device"
      notes """Updates some attributes of a device (RPi) in the exchange DB. This can be called by the user or the device. The **request body** structure can include **1 of these attributes**:

```
{
  "token": "abc",       // device token, set by user when adding this device.
  "name": "rpi3",         // device name that you pick
  "msgEndPoint": "whisper-id",    // msg service endpoint id for this device to be contacted by agbots, empty string to use the built-in Exchange msg service
  "softwareVersions": {"horizon": "1.2.3"},      // various software versions on the device
  "publicKey"      // used by agbots to encrypt msgs sent to this device using the built-in Exchange msg service
}
```

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the PATCH method to see the response format instead.**"""
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device to be updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the device. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchDevicesRequest],
          Option[String]("Device object that contains attributes to updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val patchDevices2 = (apiOperation[PatchDevicesRequest]("patchDevices2") summary("a") notes("a"))  // for some bizarre reason, the PatchDevicesRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PATCH /device/{id}. Must be called by user to add device, normally called by device to update itself. */
  patch("/devices/:id", operation(patchDevices)) ({
    val id = params("id")
    val creds = validateUserOrDeviceId(BaseAccess.WRITE, id)
    val device = try { parse(request.body).extract[PatchDevicesRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.trace("PATCH /devices/"+id+" input: "+device.toString)
    val resp = response
    val (action, attrName) = device.getDbUpdate(id)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid device attribute specified"))
    db.run(action.transactionally.asTry).map({ xs =>
      logger.debug("PATCH /devices/"+id+" result: "+xs.toString)
      xs match {
        case Success(v) => device.token match { case Some(tok) if (tok != "") => AuthCache.devices.put(Creds(id, tok)); case _ => ; }    // the token passed in to the cache should be the non-hashed one
          resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "attribute '"+attrName+"' of device '"+id+"' updated")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "device '"+id+"' not inserted or updated: "+t.toString)
      }
    })
  })

/*
  // =========== DELETE /devices ===============================
  val deleteManyDevices =
    (apiOperation[ApiResponse]("deleteManyDevices")
      summary "Deletes some/all of my devices"
      notes "Deletes devices (RPi's) from the exchange DB based on the selection criteria provided, and deletes the agreements stored for this device (but does not actually cancel the agreements between the device and agbots). Can be run by the owning user or the device."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the device. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("The owner of the devices to be deleted."), paramType=ParamType.Query)
        // Parameter("idfilter", DataType.String, Option[String]("Filter results to only include devices with this id (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        // Parameter("name", DataType.String, Option[String]("Filter results to only include devices with this name (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /devices */
  delete("/devices", operation(deleteManyDevices)) ({
    validateUserOrDeviceId(BaseAccess.WRITE, "#")
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(DevicesTQ.getDeleteMineActions(owner).transactionally.asTry).map({ xs =>
      logger.debug("DELETE /devices result: "+xs.toString)
      xs match {
        case Success(v) => ;      // will get Success even if 0 are deleted
          AuthCache.devices.remove(id)
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "devices deleted from the exchange")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "devices not deleted: "+t.toString)
        }
    })
  })
*/

  // =========== DELETE /devices/{id} ===============================
  val deleteDevices =
    (apiOperation[ApiResponse]("deleteDevices")
      summary "Deletes a device"
      notes "Deletes a device (RPi) from the exchange DB, and deletes the agreements stored for this device (but does not actually cancel the agreements between the device and agbots). Can be run by the owning user or the device."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the device. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /devices/{id}. */
  delete("/devices/:id", operation(deleteDevices)) ({
    val id = params("id")
    validateUserOrDeviceId(BaseAccess.WRITE, id)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(DevicesTQ.getDeleteActions(id).transactionally.asTry).map({ xs =>
      logger.debug("DELETE /devices/"+id+" result: "+xs.toString)
      xs match {
        case Success(v) => AuthCache.devices.remove(id)  // not checking the num deleted because with a seq of actions we do not get the results of each action anyway
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "device deleted from the exchange")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "device '"+id+"' not deleted: "+t.toString)
        }
    })
  })

  // =========== POST /devices/{id}/heartbeat ===============================
  val postDevicesHeartbeat =
    (apiOperation[ApiResponse]("postDevicesHeartbeat")
      summary "Tells the exchange this device is still operating"
      notes "Lets the exchange know this device is still active so it is still a candidate for contracting. Can be run by the owning user or the device."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device to be updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the device. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /devices/{id}/heartbeat. */
  post("/devices/:id/heartbeat", operation(postDevicesHeartbeat)) ({
    val id = params("id")
    validateUserOrDeviceId(BaseAccess.WRITE, id)
    val resp = response
    db.run(DevicesTQ.getLastHeartbeat(id).update(ApiTime.nowUTC).asTry).map({ xs =>
      logger.debug("POST /devices/"+id+"/heartbeat result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {       // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.POST_OK)
              ApiResponse(ApiResponseType.OK, "device updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "device '"+id+"' not found")
            }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "device '"+id+"' not updated: "+t.toString)
        }
    })
  })

  /* ====== GET /devices/{id}/agreements ================================ */
  val getDeviceAgreements =
    (apiOperation[GetDeviceAgreementsResponse]("getDeviceAgreements")
      summary("Returns all agreements this device is in")
      notes("""Returns all agreements in the exchange DB that this device is part of. Can be run by a user or the device.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the device. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /devices/{id}/agreements. Normally called by the user to see all agreements of this device. */
  get("/devices/:id/agreements", operation(getDeviceAgreements)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    validateUserOrDeviceId(BaseAccess.READ, id)
    val resp = response
      db.run(DeviceAgreementsTQ.getAgreements(id).result).map({ list =>
        logger.debug("GET /devices/"+id+"/agreements result size: "+list.size)
        logger.trace("GET /devices/"+id+"/agreements result: "+list.toString)
        val agreements = new MutableHashMap[String, DeviceAgreement]
        if (list.size > 0) for (e <- list) { agreements.put(e.agId, e.toDeviceAgreement) }
        else resp.setStatus(HttpCode.NOT_FOUND)
        GetDeviceAgreementsResponse(agreements.toMap, 0)
      })
  })

  /* ====== GET /devices/{id}/agreements/{agid} ================================ */
  val getOneDeviceAgreement =
    (apiOperation[GetDeviceAgreementsResponse]("getOneDeviceAgreement")
      summary("Returns an agreement for a device")
      notes("""Returns the agreement with the specified agid for the specified device id in the exchange DB. Can be run by a user or the device. **Because of a swagger bug this method can not be run via swagger.**

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device."), paramType=ParamType.Query),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the device. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /devices/{id}/agreements/{agid}. */
  get("/devices/:id/agreements/:agid", operation(getOneDeviceAgreement)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")   // but do not have a hack/fix for the agid
    val agId = params("agid")
    validateUserOrDeviceId(BaseAccess.READ, id)
    val resp = response
    db.run(DeviceAgreementsTQ.getAgreement(id, agId).result).map({ list =>
      logger.debug("GET /devices/"+id+"/agreements/"+agId+" result: "+list.toString)
      val agreements = new MutableHashMap[String, DeviceAgreement]
      if (list.size > 0) for (e <- list) { agreements.put(e.agId, e.toDeviceAgreement) }
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetDeviceAgreementsResponse(agreements.toMap, 0)
    })
  })

  // =========== PUT /devices/{id}/agreements/{agid} ===============================
  val putDeviceAgreement =
    (apiOperation[ApiResponse]("putDeviceAgreement")
      summary "Adds/updates an agreement of a device"
      notes """Adds a new agreement of a device to the exchange DB, or updates an existing agreement. This is called by the
        device or owning user to give their information about the agreement. The **request body** structure:

```
{
  "microservice": "https://bluehorizon.network/documentation/sdr-device-api",    // url (API spec ref)
  "state": "negotiating"    // current agreement state: negotiating, signed, finalized, etc.
}
```"""
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device wanting to add/update this agreement."), paramType = ParamType.Query),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement to be added/updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the device. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutDeviceAgreementRequest],
          Option[String]("Agreement object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val putDeviceAgreement2 = (apiOperation[PutDeviceAgreementRequest]("putAgreement2") summary("a") notes("a"))  // for some bizarre reason, the PutAgreementsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PUT /devices/{id}/agreements/{agid}. Normally called by device to add/update itself. */
  put("/devices/:id/agreements/:agid", operation(putDeviceAgreement)) ({
    //todo: keep a running total of agreements for each MS so we can search quickly for available MSs
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    val agId = params("agid")
    validateUserOrDeviceId(BaseAccess.WRITE, id)
    val agreement = try { parse(request.body).extract[PutDeviceAgreementRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val resp = response
    db.run(DeviceAgreementsTQ.getNumOwned(id).result.flatMap({ xs =>
      logger.debug("PUT /devices/"+id+"/agreements/"+agId+" num owned: "+xs)
      val numOwned = xs
      val maxAgreements = ExchConfig.getInt("api.limits.maxAgreements")
      if (numOwned <= maxAgreements) {    // we are not sure if this is create or update, but if they are already over the limit, stop them anyway
        agreement.toDeviceAgreementRow(id, agId).upsert.asTry
      }
      else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxAgreements+ " agreements for this device")).asTry
    })).map({ xs =>
      logger.debug("PUT /devices/"+id+"/agreements/"+agId+" result: "+xs.toString)
      xs match {
        case Success(v) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "agreement added or updated")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, "agreement '"+agId+"' for device '"+id+"' not inserted or updated: "+t.getMessage)
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "agreement '"+agId+"' for device '"+id+"' not inserted or updated: "+t.toString)
        }
      }
    })
  })

  // =========== DELETE /devices/{id}/agreements ===============================
  val deleteDeviceAllAgreement =
    (apiOperation[ApiResponse]("deleteDeviceAllAgreement")
      summary "Deletes all agreements of a device"
      notes "Deletes all of the current agreements of a device from the exchange DB. Can be run by the owning user or the device."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device for which the agreement is to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the device. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /devices/{id}/agreements. */
  delete("/devices/:id/agreements", operation(deleteDeviceAllAgreement)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    validateUserOrDeviceId(BaseAccess.WRITE, id)
    val resp = response
    db.run(DeviceAgreementsTQ.getAgreements(id).delete.asTry).map({ xs =>
      logger.debug("DELETE /devices/"+id+"/agreements result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.DELETED)
              ApiResponse(ApiResponseType.OK, "device agreements deleted")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "no agreements for device '"+id+"' found")
            }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "agreements for device '"+id+"' not deleted: "+t.toString)
        }
    })
  })

  // =========== DELETE /devices/{id}/agreements/{agid} ===============================
  val deleteDeviceAgreement =
    (apiOperation[ApiResponse]("deleteDeviceAgreement")
      summary "Deletes an agreement of a device"
      notes "Deletes an agreement of a device from the exchange DB. Can be run by the owning user or the device."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device for which the agreement is to be deleted."), paramType = ParamType.Path),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the device. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /devices/{id}/agreements/{agid}. */
  delete("/devices/:id/agreements/:agid", operation(deleteDeviceAgreement)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    val agId = params("agid")
    validateUserOrDeviceId(BaseAccess.WRITE, id)
    val resp = response
    db.run(DeviceAgreementsTQ.getAgreement(id,agId).delete.asTry).map({ xs =>
      logger.debug("DELETE /devices/"+id+"/agreements/"+agId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.DELETED)
              ApiResponse(ApiResponseType.OK, "device agreement deleted")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "agreement '"+agId+"' for device '"+id+"' not found")
            }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "agreement '"+agId+"' for device '"+id+"' not deleted: "+t.toString)
        }
    })
  })

  // =========== POST /devices/{id}/msgs ===============================
  val postDevicesMsgs =
    (apiOperation[ApiResponse]("postDevicesMsgs")
      summary "Sends a msg from an agbot to a device"
      notes """Sends a msg from an agbot to a device. The agbot must 1st sign the msg (with its private key) and then encrypt the msg (with the device's public key). Can be run by any agbot. The **request body** structure:

```
{
  "message": "VW1RxzeEwTF0U7S96dIzSBQ/hRjyidqNvBzmMoZUW3hpd3hZDvs",    // msg to be sent to the device
  "ttl": 86400       // time-to-live of this msg, in seconds
}
```
      """
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device to send a msg to."), paramType = ParamType.Path),
        // Agbot id/token must be in the header
        Parameter("body", DataType[PostDevicesMsgsRequest],
          Option[String]("Signed/encrypted message to send to the device. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val postDevicesMsgs2 = (apiOperation[PostDevicesMsgsRequest]("postDevicesMsgs2") summary("a") notes("a"))

  /** Handles POST /devices/{id}/msgs. */
  post("/devices/:id/msgs", operation(postDevicesMsgs)) ({
    val devId = params("id")
    val creds = validateAgbotId(BaseAccess.SEND_MSG)
    val agbotId = creds.id
    val msg = try { parse(request.body).extract[PostDevicesMsgsRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val resp = response
    // Remove msgs whose TTL is past, then check the mailbox is not full, then get the agbot publicKey, then write the devmsgs row, all in the same db.run thread
    db.run(DeviceMsgsTQ.getMsgsExpired.delete.flatMap({ xs =>
      logger.debug("POST /devices/"+devId+"/msgs delete expired result: "+xs.toString)
      DeviceMsgsTQ.getNumOwned(devId).result
    }).flatMap({ xs =>
      logger.debug("POST /devices/"+devId+"/msgs mailbox size: "+xs)
      val mailboxSize = xs
      val maxMessagesInMailbox = ExchConfig.getInt("api.limits.maxMessagesInMailbox")
      if (mailboxSize < maxMessagesInMailbox) AgbotsTQ.getPublicKey(agbotId).result.asTry
      else DBIO.failed(new Throwable("Access Denied: the message mailbox of "+devId+" is full ("+maxMessagesInMailbox+ " messages)")).asTry
    }).flatMap({ xs =>
      logger.debug("POST /devices/"+devId+"/msgs agbot publickey result: "+xs.toString)
      xs match {
        case Success(v) => val agbotPubKey = v.head
          if (agbotPubKey != "") DeviceMsgRow(0, devId, agbotId, agbotPubKey, msg.message, ApiTime.nowUTC, ApiTime.futureUTC(msg.ttl)).insert.asTry
          else DBIO.failed(new Throwable("Invalid Input: the message sender must have their public key registered with the Exchange")).asTry
        case Failure(t) => DBIO.failed(t).asTry       // rethrow the error to the next step
      }
    })).map({ xs =>
      logger.debug("POST /devices/"+devId+"/msgs write row result: "+xs.toString)
      xs match {
        case Success(v) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "device msg "+v+" inserted")
        case Failure(t) => if (t.getMessage.startsWith("Invalid Input:")) {
            resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, "device '"+devId+"' msg not inserted: "+t.getMessage)
          } else if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, "device '"+devId+"' msg not inserted: "+t.getMessage)
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "device '"+devId+"' msg not inserted: "+t.toString)
          }
        }
    })
  })

  /* ====== GET /devices/{id}/msgs ================================ */
  val getDeviceMsgs =
    (apiOperation[GetDeviceMsgsResponse]("getDeviceMsgs")
      summary("Returns all msgs sent to this device")
      notes("""Returns all msgs that have been sent to this device. They will be returned in the order they were sent. All msgs that have been sent to this device will be returned, unless the device has deleted some, or some are past their TTL. Can be run by a user or the device.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the device. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /devices/{id}/msgs. Normally called by the user to see all msgs of this device. */
  get("/devices/:id/msgs", operation(getDeviceMsgs)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    validateUserOrDeviceId(BaseAccess.READ, id)
    val resp = response
    // Remove msgs whose TTL is past, and then get the msgs for this device
    db.run(DeviceMsgsTQ.getMsgsExpired.delete.flatMap({ xs =>
      logger.debug("GET /devices/"+id+"/msgs delete expired result: "+xs.toString)
      DeviceMsgsTQ.getMsgs(id).result
    })).map({ list =>
      logger.debug("GET /devices/"+id+"/msgs result size: "+list.size)
      logger.trace("GET /devices/"+id+"/msgs result: "+list.toString)
      val listSorted = list.sortWith(_.msgId < _.msgId)
      val msgs = new ListBuffer[DeviceMsg]
      if (listSorted.size > 0) for (m <- listSorted) { msgs += m.toDeviceMsg }
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetDeviceMsgsResponse(msgs.toList, 0)
    })
  })

  // =========== DELETE /devices/{id}/msgs/{msgid} ===============================
  val deleteDeviceMsg =
    (apiOperation[ApiResponse]("deleteDeviceMsg")
      summary "Deletes an msg of a device"
      notes "Deletes an msg that was sent to a device. This should be done by the device after each msg is read. Can be run by the owning user or the device."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the device to be deleted."), paramType = ParamType.Path),
        Parameter("msgid", DataType.String, Option[String]("ID of the msg to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the device. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /devices/{id}/msgs/{msgid}. */
  delete("/devices/:id/msgs/:msgid", operation(deleteDeviceMsg)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    val msgId = try { params("msgid").toInt } catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "msgid must be an integer: "+e)) }    // the specific exception is NumberFormatException
    validateUserOrDeviceId(BaseAccess.WRITE, id)
    val resp = response
    db.run(DeviceMsgsTQ.getMsg(id,msgId).delete.asTry).map({ xs =>
      logger.debug("DELETE /devices/"+id+"/msgs/"+msgId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.DELETED)
              ApiResponse(ApiResponseType.OK, "device msg deleted")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "msg '"+msgId+"' for device '"+id+"' not found")
            }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "msg '"+msgId+"' for device '"+id+"' not deleted: "+t.toString)
        }
    })
  })

}
