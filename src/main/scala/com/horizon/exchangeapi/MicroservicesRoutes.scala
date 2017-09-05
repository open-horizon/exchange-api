/** Services routes for all of the /microservices api methods. */
package com.horizon.exchangeapi

import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatra._
import org.scalatra.swagger._
import org.slf4j._
import slick.jdbc.PostgresProfile.api._
import com.horizon.exchangeapi.tables._
import scala.collection.immutable._
import scala.collection.mutable.{HashMap => MutableHashMap}
import scala.util._
import java.net._

//====== These are the input and output structures for /microservices routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /microservices */
case class GetMicroservicesResponse(microservices: Map[String,Microservice], lastIndex: Int)
case class GetMicroserviceAttributeResponse(attribute: String, value: String)

/** Input format for POST /microservices or PUT /microservices/<microservice-id> */
case class PostPutMicroserviceRequest(label: String, description: String, specRef: String, version: String, arch: String, sharable: String, downloadUrl: String, matchHardware: Map[String,String], userInput: List[Map[String,String]], workloads: List[Map[String,String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate() = {
    // Check the specRef is a valid URL
    try {
      new URL(specRef)
    } catch {
      case _: MalformedURLException => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "specRef is not valid URL format."))
    }

    if (!Version(version).isValid) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "version is not valid version format."))
  }

  def formId(): String = {
    // Remove the https:// from the beginning of specRef and replace troublesome chars with a dash. It has already been checked as a valid URL in validate().
    val specRef2 = """^[A-Za-z0-9+.-]*?://""".r replaceFirstIn (specRef, "")
    val specRef3 = """[$!*,;/?@&~=%]""".r replaceAllIn (specRef2, "-")     // I think possible chars in valid urls are: $_.+!*,;/?:@&~=%-
    return specRef3 + "_" + version + "_" + arch
  }

  def toMicroserviceRow(microservice: String, owner: String) = MicroserviceRow(microservice, owner, label, description, specRef, version, arch, sharable, downloadUrl, write(matchHardware), write(userInput), write(workloads), ApiTime.nowUTC)
}

case class PatchMicroserviceRequest(label: Option[String], description: Option[String], specRef: Option[String], version: Option[String], arch: Option[String], sharable: Option[String], downloadUrl: Option[String]) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the microservice, and the attribute name being updated. */
  def getDbUpdate(microservice: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    //todo: support updating more than 1 attribute
    // find the 1st attribute that was specified in the body and create a db action to update it for this microservice
    label match { case Some(lab) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.label,d.lastUpdated)).update((microservice, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.description,d.lastUpdated)).update((microservice, desc, lastUpdated)), "description"); case _ => ; }
    specRef match { case Some(spec) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.specRef,d.lastUpdated)).update((microservice, spec, lastUpdated)), "specRef"); case _ => ; }
    version match { case Some(ver) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.version,d.lastUpdated)).update((microservice, ver, lastUpdated)), "version"); case _ => ; }
    arch match { case Some(ar) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.arch,d.lastUpdated)).update((microservice, ar, lastUpdated)), "arch"); case _ => ; }
    sharable match { case Some(shr) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.sharable,d.lastUpdated)).update((microservice, shr, lastUpdated)), "sharable"); case _ => ; }
    downloadUrl match { case Some(url) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.downloadUrl,d.lastUpdated)).update((microservice, url, lastUpdated)), "downloadUrl"); case _ => ; }
    return (null, null)
  }
}



/** Implementation for all of the /microservices routes */
trait MicroserviceRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  /* ====== GET /microservices ================================ */
  val getMicroservices =
    (apiOperation[GetMicroservicesResponse]("getMicroservices")
      summary("Returns all microservices")
      notes("""Returns all microservice definitions in the exchange DB. Can be run by any user, device, or agbot.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the device or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the device or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include microservices with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("specRef", DataType.String, Option[String]("Filter results to only include microservices with this specRef (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("version", DataType.String, Option[String]("Filter results to only include microservices with this version (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("arch", DataType.String, Option[String]("Filter results to only include microservices with this arch (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /microservices. Can be called by anyone. */
  get("/microservices", operation(getMicroservices)) ({
    credsAndLog().authenticate().authorizeTo(TMicroservice("*"),Access.READ)
    val resp = response
    var q = MicroservicesTQ.rows.subquery
    // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
    params.get("specRef").foreach(specRef => { if (specRef.contains("%")) q = q.filter(_.specRef like specRef) else q = q.filter(_.specRef === specRef) })
    params.get("version").foreach(version => { if (version.contains("%")) q = q.filter(_.version like version) else q = q.filter(_.version === version) })
    params.get("arch").foreach(arch => { if (arch.contains("%")) q = q.filter(_.arch like arch) else q = q.filter(_.arch === arch) })

    db.run(q.result).map({ list =>
      logger.debug("GET /microservices result size: "+list.size)
      val microservices = new MutableHashMap[String,Microservice]
      if (list.nonEmpty) for (a <- list) microservices.put(a.microservice, a.toMicroservice)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetMicroservicesResponse(microservices.toMap, 0)
    })
  })

  /* ====== GET /microservices/{microservice} ================================ */
  val getOneMicroservice =
    (apiOperation[GetMicroservicesResponse]("getOneMicroservice")
      summary("Returns a microservice")
      notes("""Returns the microservice with the specified id in the exchange DB. Can be run by a user, device, or agbot.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the device or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the device or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire microservice resource will be returned."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /microservices/{microservice}. Can be called by anyone. */
  get("/microservices/:microservice", operation(getOneMicroservice)) ({
    val microservice = swaggerHack("microservice")
    credsAndLog().authenticate().authorizeTo(TMicroservice(microservice),Access.READ)
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the microservice
        val q = MicroservicesTQ.getAttribute(microservice, attribute)       // get the proper db query for this attribute
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Microservice attribute name '"+attribute+"' is not an attribute of the microservice resource."))
        db.run(q.result).map({ list =>
          logger.trace("GET /microservices/"+microservice+" attribute result: "+list.toString)
          if (list.nonEmpty) {
            GetMicroserviceAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")
          }
        })

      case None => ;  // Return the whole microservice resource
        db.run(MicroservicesTQ.getMicroservice(microservice).result).map({ list =>
          logger.debug("GET /microservices/"+microservice+" result: "+list.toString)
          val microservices = new MutableHashMap[String,Microservice]
          if (list.nonEmpty) for (a <- list) microservices.put(a.microservice, a.toMicroservice)
          else resp.setStatus(HttpCode.NOT_FOUND)
          GetMicroservicesResponse(microservices.toMap, 0)
        })
    }
  })

  // =========== POST /microservices ===============================
  val postMicroservices =
    (apiOperation[ApiResponse]("postMicroservices")
      summary "Adds a microservice"
      notes """Creates a microservice resource. This can only be called by a user. The **request body** structure:

```
{
  "label": "GPS for x86_64",     // for the registration UI
  "description": "blah blah",
  "specRef": "https://bluehorizon.network/documentation/microservice/gps",   // the unique identifier of this MS
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "none",   // or: "singleton", "multiple"
  "downloadUrl": "",    // not used yet
  // Hints to the edge node about how to tell if it has physical sensors supported by the MS
  "matchHardware": {
    // Normally will only set 1 of these values
    "usbDeviceIds": ["1546:01a7"],
    "devFiles": ["/dev/ttyUSB*", "/dev/ttyACM*"]
  },
  // Values the device owner will be prompted for and will be set as env vars to the container. Can override env vars in workloads.deployment.
  "userInput": [
    {
      "name": "foo",
      "label": "The Foo Value",
      "type": "string",   // or: "int", "float", "list of strings"
      "defaultValue": "bar"
    }
  ],
  "workloads": [
    {
      "deployment": "{\"services\":{\"gps\":{\"image\":\"summit.hovitos.engineering/x86/gps:2.0.3\",\"privileged\":true,\"devices\":[\"/dev/bus/usb/001/001:/dev/bus/usb/001/001\"]}}}",
      "deployment_signature": "EURzSk=",
      "torrent": {
        "url": "https://images.bluehorizon.network/28f57c91243c56caaf0362deeb6620099a0ba1a3.torrent",
        "images": [
          {
            "file": "d98bfef9f76dee5b4321c4bc18243d9510f11655.tar.gz",
            "signature": "kckH14DUj3bXMu7hnQK="
          }
        ]
      }
    }
  ]
}
```"""
      parameters(
      Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Query),
      Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostPutMicroserviceRequest],
        Option[String]("Microservice object that needs to be updated in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      )
  val postMicroservices2 = (apiOperation[PostPutMicroserviceRequest]("postMicroservices2") summary("a") notes("a"))  // for some bizarre reason, the PostMicroserviceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles POST /microservice. Called by a user to update (must be same user that created it). */
  post("/microservices", operation(postMicroservices)) ({
//    val microservice = swaggerHack("microservice")
    val ident = credsAndLog().authenticate().authorizeTo(TMicroservice(""),Access.CREATE)
    val microserviceReq = try { parse(request.body).extract[PostPutMicroserviceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    microserviceReq.validate()
    val microservice = microserviceReq.formId()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    db.run(MicroservicesTQ.getNumOwned(owner).result.flatMap({ xs =>
      logger.debug("POST /microservices num owned by "+owner+": "+xs)
      val numOwned = xs
      val maxMicroservices = ExchConfig.getInt("api.limits.maxMicroservices")
      if (numOwned <= maxMicroservices) {    // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
        microserviceReq.toMicroserviceRow(microservice, owner).insert.asTry
      }
      else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxMicroservices+ " microservices")).asTry
    })).map({ xs =>
      logger.debug("POST /microservices result: "+xs.toString)
      xs match {
        case Success(_) => if (owner != "") AuthCache.microservices.putOwner(microservice, owner)     // currently only users are allowed to update microservice resources, so owner should never be blank
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "microservice '"+microservice+"' created")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "microservice '"+microservice+"' not created: "+t.getMessage)
        } else if (t.getMessage.contains("duplicate key value violates unique constraint")) {
          resp.setStatus(HttpCode.ALREADY_EXISTS)
          ApiResponse(ApiResponseType.ALREADY_EXISTS, "microservice '" + microservice + "' already exists: " + t.getMessage)
        } else {
          resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "microservice '"+microservice+"' not created: "+t.toString)
        }
      }
    })
  })

  // =========== PUT /microservices/{microservice} ===============================
  val putMicroservices =
    (apiOperation[ApiResponse]("putMicroservices")
      summary "Updates a microservice"
      notes """Does a full replace of an existing microservice. This can only be called by a user to create, and then only by that user to update. The **request body** structure:

```
{
  "label": "GPS for x86_64",     // for the registration UI
  "description": "blah blah"
  "specRef": "https://bluehorizon.network/documentation/microservice/gps",   // the unique identifier of this MS
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "none",   // or: "singleton", "multiple"
  "downloadUrl": "",    // not used yet
  // Hints to the edge node about how to tell if it has physical sensors supported by the MS
  "matchHardware": {
    // Normally will only set 1 of these values
    "usbDeviceIds": ["1546:01a7"],
    "devFiles": ["/dev/ttyUSB*", "/dev/ttyACM*"]
  },
  // Values the device owner will be prompted for and will be set as env vars to the container. Can override env vars in workloads.deployment.
  "userInput": [
    {
      "name": "foo",
      "label": "The Foo Value",
      "type": "string",   // or: "int", "float", "list of strings"
      "defaultValue": "bar"
    }
  ],
  "workloads": [
    {
      "deployment": "{\"services\":{\"gps\":{\"image\":\"summit.hovitos.engineering/x86/gps:2.0.3\",\"privileged\":true,\"devices\":[\"/dev/bus/usb/001/001:/dev/bus/usb/001/001\"]}}}",
      "deployment_signature": "EURzSk=",
      "torrent": {
        "url": "https://images.bluehorizon.network/28f57c91243c56caaf0362deeb6620099a0ba1a3.torrent",
        "images": [
          {
            "file": "d98bfef9f76dee5b4321c4bc18243d9510f11655.tar.gz",
            "signature": "kckH14DUj3bXMu7hnQK="
          }
        ]
      }
    }
  ]
}
```"""
      parameters(
      Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Query),
      Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostPutMicroserviceRequest],
        Option[String]("Microservice object that needs to be updated in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      )
  val putMicroservices2 = (apiOperation[PostPutMicroserviceRequest]("putMicroservices2") summary("a") notes("a"))  // for some bizarre reason, the PutMicroserviceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PUT /microservice/{microservice}. Called by a user to update (must be same user that created it). */
  put("/microservices/:microservice", operation(putMicroservices)) ({
    val microservice = swaggerHack("microservice")
    val ident = credsAndLog().authenticate().authorizeTo(TMicroservice(microservice),Access.WRITE)
    val microserviceReq = try { parse(request.body).extract[PostPutMicroserviceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    microserviceReq.validate()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    /* this is pure update, no creation if it does not exist
    db.run(MicroservicesTQ.getNumOwned(owner).result.flatMap({ xs =>
      logger.debug("PUT /microservices/"+microservice+" num owned: "+xs)
      val numOwned = xs
      val maxMicroservices = ExchConfig.getInt("api.limits.maxMicroservices")
      if (numOwned <= maxMicroservices) {    // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
        microserviceReq.toMicroserviceRow(microservice, owner).update.asTry
      }
      else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxMicroservices+ " microservices")).asTry
    })).map({ xs =>
    */
    db.run(microserviceReq.toMicroserviceRow(microservice, owner).update.asTry).map({ xs =>
      logger.debug("PUT /microservices/"+microservice+" result: "+xs.toString)
      xs match {
        case Success(n) => try {
            val numUpdated = n.toString.toInt     // i think n is an AnyRef so we have to do this to get it to an int
            if (numUpdated > 0) {
              if (owner != "") AuthCache.microservices.putOwner(microservice, owner)     // currently only users are allowed to update microservice resources, so owner should never be blank
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "microservice updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "microservice '"+microservice+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "microservice '"+microservice+"' not updated: "+e) }    // the specific exception is NumberFormatException
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "microservice '"+microservice+"' not updated: "+t.toString)
      }
    })
  })

  // =========== PATCH /microservices/{microservice} ===============================
  val patchMicroservices =
    (apiOperation[Map[String,String]]("patchMicroservices")
      summary "Partially updates a microservice"
      notes """Updates one attribute of a microservice in the exchange DB. This can only be called by the user that originally created this microservice resource. The **request body** structure can include **1 of these attributes**:

```
{
  "label": "GPS x86_64",     // for the registration UI
  "description": "blah blah"
  "specRef": "https://bluehorizon.network/documentation/microservice/gps",   // the unique identifier of this microservice
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "none",   // or: "singleton", "multiple"
  "downloadUrl": ""    // not used yet
}
```

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the PATCH method to see the response format instead.**"""
      parameters(
        Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Query),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchMicroserviceRequest],
          Option[String]("Partial microservice object that contains an attribute to be updated in this microservice. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val patchMicroservices2 = (apiOperation[PatchMicroserviceRequest]("patchMicroservices2") summary("a") notes("a"))  // for some bizarre reason, the PatchMicroserviceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PATCH /microservice/{microservice}. Must be called by the same user that created it. */
  patch("/microservices/:microservice", operation(patchMicroservices)) ({
    val microservice = swaggerHack("microservice")
    credsAndLog().authenticate().authorizeTo(TMicroservice(microservice),Access.WRITE)
    val microserviceReq = try { parse(request.body).extract[PatchMicroserviceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.trace("PATCH /microservices/"+microservice+" input: "+microserviceReq.toString)
    val resp = response
    val (action, attrName) = microserviceReq.getDbUpdate(microservice)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid microservice attribute specified"))
    db.run(action.transactionally.asTry).map({ xs =>
      logger.debug("PATCH /microservices/"+microservice+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "attribute '"+attrName+"' of microservice '"+microservice+"' updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "microservice '"+microservice+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "microservice '"+microservice+"' not updated: "+t.toString)
      }
    })
  })

  // =========== DELETE /microservices/{microservice} ===============================
  val deleteMicroservices =
    (apiOperation[ApiResponse]("deleteMicroservices")
      summary "Deletes a microservice"
      notes "Deletes a microservice from the exchange DB. Can only be run by the owning user."
      parameters(
        Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Query),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /microservices/{microservice}. Must be called by user. */
  delete("/microservices/:microservice", operation(deleteMicroservices)) ({
    val microservice = swaggerHack("microservice")
    credsAndLog().authenticate().authorizeTo(TMicroservice(microservice),Access.WRITE)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(MicroservicesTQ.getMicroservice(microservice).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /microservices/"+microservice+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.microservices.removeOwner(microservice)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "microservice deleted")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "microservice '"+microservice+"' not found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "microservice '"+microservice+"' not deleted: "+t.toString)
      }
    })
  })

}