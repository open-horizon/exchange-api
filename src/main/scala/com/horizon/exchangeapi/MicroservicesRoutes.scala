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
//import java.net._

//====== These are the input and output structures for /microservices routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/microservices */
case class GetMicroservicesResponse(microservices: Map[String,Microservice], lastIndex: Int)
case class GetMicroserviceAttributeResponse(attribute: String, value: String)

/** Input format for POST /orgs/{orgid}/microservices or PUT /orgs/{orgid}/microservices/<microservice-id> */
case class PostPutMicroserviceRequest(label: String, description: String, public: Boolean, specRef: String, version: String, arch: String, sharable: String, downloadUrl: Option[String], matchHardware: Option[Map[String,String]], userInput: List[Map[String,String]], workloads: List[MDockerImages]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate() = {
    // Currently we do not want to force that the specRef is a valid URL
    //try { new URL(specRef) }
    //catch { case _: MalformedURLException => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "specRef is not valid URL format.")) }

    if (!Version(version).isValid) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "version '"+version+"' is not valid version format."))

    // Check that it is signed
    for (w <- workloads) {
      //if (w.deployment != "" && (w.deployment_signature == "" || w.torrent == "")) { halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "this microservice definition does not appear to be signed.")) }
      if (w.deployment != "" && w.deployment_signature == "") { halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "this microservice definition does not appear to be signed.")) }
    }
  }

  def formId(orgid: String) = MicroservicesTQ.formId(orgid, specRef, version, arch)

  def toMicroserviceRow(microservice: String, orgid: String, owner: String) = MicroserviceRow(microservice, orgid, owner, label, description, public, specRef, version, arch, sharable, downloadUrl.getOrElse(""), write(matchHardware), write(userInput), write(workloads), ApiTime.nowUTC)
}

case class PatchMicroserviceRequest(label: Option[String], description: Option[String], public: Option[Boolean], specRef: Option[String], version: Option[String], arch: Option[String], sharable: Option[String], downloadUrl: Option[String]) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the microservice, and the attribute name being updated. */
  def getDbUpdate(microservice: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    //todo: support updating more than 1 attribute
    // find the 1st attribute that was specified in the body and create a db action to update it for this microservice
    label match { case Some(lab) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.label,d.lastUpdated)).update((microservice, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.description,d.lastUpdated)).update((microservice, desc, lastUpdated)), "description"); case _ => ; }
    public match { case Some(pub) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.public,d.lastUpdated)).update((microservice, pub, lastUpdated)), "public"); case _ => ; }
    specRef match { case Some(spec) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.specRef,d.lastUpdated)).update((microservice, spec, lastUpdated)), "specRef"); case _ => ; }
    version match { case Some(ver) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.version,d.lastUpdated)).update((microservice, ver, lastUpdated)), "version"); case _ => ; }
    arch match { case Some(ar) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.arch,d.lastUpdated)).update((microservice, ar, lastUpdated)), "arch"); case _ => ; }
    sharable match { case Some(shr) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.sharable,d.lastUpdated)).update((microservice, shr, lastUpdated)), "sharable"); case _ => ; }
    downloadUrl match { case Some(url) => return ((for { d <- MicroservicesTQ.rows if d.microservice === microservice } yield (d.microservice,d.downloadUrl,d.lastUpdated)).update((microservice, url, lastUpdated)), "downloadUrl"); case _ => ; }
    return (null, null)
  }
}


/** Input format for PUT /orgs/{orgid}/microservices/{id}/keys/<key-id> */
case class PutMicroserviceKeyRequest(key: String) {
  def toMicroserviceKey = MicroserviceKey(key, ApiTime.nowUTC)
  def toMicroserviceKeyRow(microserviceId: String, keyId: String) = MicroserviceKeyRow(keyId, microserviceId, key, ApiTime.nowUTC)
  def validate(keyId: String) = {
    //if (keyId != formId) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the key id should be in the form keyOrgid_key"))
  }
}



/** Implementation for all of the /microservices routes */
trait MicroserviceRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  /* ====== GET /orgs/{orgid}/microservices ================================ */
  val getMicroservices =
    (apiOperation[GetMicroservicesResponse]("getMicroservices")
      summary("Returns all microservices")
      description("""Returns all microservice definitions in this org. Can be run by any user, node, or agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include microservices with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("public", DataType.String, Option[String]("Filter results to only include microservices with this public setting"), paramType=ParamType.Query, required=false),
        Parameter("specRef", DataType.String, Option[String]("Filter results to only include microservices with this specRef (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("version", DataType.String, Option[String]("Filter results to only include microservices with this version (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("arch", DataType.String, Option[String]("Filter results to only include microservices with this arch (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/microservices", operation(getMicroservices)) ({
    val orgid = params("orgid")
    val ident = credsAndLog().authenticate().authorizeTo(TMicroservice(OrgAndId(orgid,"*").toString),Access.READ)
    val resp = response
    //var q = MicroservicesTQ.rows.subquery
    var q = MicroservicesTQ.getAllMicroservices(orgid)
    // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
    params.get("public").foreach(public => { if (public.toLowerCase == "true") q = q.filter(_.public === true) else q = q.filter(_.public === false) })
    params.get("specRef").foreach(specRef => { if (specRef.contains("%")) q = q.filter(_.specRef like specRef) else q = q.filter(_.specRef === specRef) })
    params.get("version").foreach(version => { if (version.contains("%")) q = q.filter(_.version like version) else q = q.filter(_.version === version) })
    params.get("arch").foreach(arch => { if (arch.contains("%")) q = q.filter(_.arch like arch) else q = q.filter(_.arch === arch) })

    db.run(q.result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/microservices result size: "+list.size)
      val microservices = new MutableHashMap[String,Microservice]
      if (list.nonEmpty) for (a <- list) if (ident.getOrg == a.orgid || a.public || ident.isSuperUser || ident.isMultiTenantAgbot) microservices.put(a.microservice, a.toMicroservice)
      if (microservices.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetMicroservicesResponse(microservices.toMap, 0)
    })
  })

  /* ====== GET /orgs/{orgid}/microservices/{microservice} ================================ */
  val getOneMicroservice =
    (apiOperation[GetMicroservicesResponse]("getOneMicroservice")
      summary("Returns a microservice")
      description("""Returns the microservice with the specified id in the exchange DB. Can be run by a user, node, or agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire microservice resource will be returned."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/microservices/:microservice", operation(getOneMicroservice)) ({
    val orgid = params("orgid")
    val bareMicro = params("microservice")   // but do not have a hack/fix for the name
    val microservice = OrgAndId(orgid,bareMicro).toString
    credsAndLog().authenticate().authorizeTo(TMicroservice(microservice),Access.READ)
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the microservice
        val q = MicroservicesTQ.getAttribute(microservice, attribute)       // get the proper db query for this attribute
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Microservice attribute name '"+attribute+"' is not an attribute of the microservice resource."))
        db.run(q.result).map({ list =>
          logger.trace("GET /orgs/"+orgid+"/microservices/"+bareMicro+" attribute result: "+list.toString)
          if (list.nonEmpty) {
            resp.setStatus(HttpCode.OK)
            GetMicroserviceAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")
          }
        })

      case None => ;  // Return the whole microservice resource
        db.run(MicroservicesTQ.getMicroservice(microservice).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/microservices/"+bareMicro+" result: "+list.toString)
          val microservices = new MutableHashMap[String,Microservice]
          if (list.nonEmpty) for (a <- list) microservices.put(a.microservice, a.toMicroservice)
          if (microservices.nonEmpty) resp.setStatus(HttpCode.OK)
          else resp.setStatus(HttpCode.NOT_FOUND)
          GetMicroservicesResponse(microservices.toMap, 0)
        })
    }
  })

  // =========== POST /orgs/{orgid}/microservices ===============================
  val postMicroservices =
    (apiOperation[ApiResponse]("postMicroservices")
      summary "Adds a microservice"
      description """Creates a microservice resource. A microservice provides access to node data or services that can be used by potentially multiple workloads. The microservice resource contains the metadata that Horizon needs to deploy the docker images that implement this microservice. If public is set to true, the microservice can be shared across organizations. This can only be called by a user. The **request body** structure:

```
// (remove all of the comments like this before using)
{
  "label": "GPS for x86_64",     // this will be displayed in the node registration UI
  "description": "blah blah",
  "public": true,       // whether or not it can be viewed by other organizations
  "specRef": "https://bluehorizon.network/documentation/microservice/gps",   // the unique identifier of this MS
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "exclusive",   // or: "single", "multiple"
  "downloadUrl": "",    // reserved for future use, can be omitted
  "matchHardware": {},    // reserved for future use, can be omitted (will be hints to the node about how to tell if it has the physical sensors required by this MS
  // Values the node owner will be prompted for and will be set as env vars to the container.
  "userInput": [
    {
      "name": "foo",
      "label": "The Foo Value",
      "type": "string",   // or: "int", "float", "string list"
      "defaultValue": "bar"
    }
  ],
  // The docker images that will be deployed on edge nodes for this microservice
  "workloads": [
    {
      "deployment": "{\"services\":{\"gps\":{\"image\":\"summit.hovitos.engineering/x86/gps:2.0.3\",\"privileged\":true,\"nodes\":[\"/dev/bus/usb/001/001:/dev/bus/usb/001/001\"]}}}",
      "deployment_signature": "EURzSk=",     // filled in by the Horizon signing process
      "torrent": "{\"url\":\"https://images.bluehorizon.network/139e5b32f271e43698565ff0a37c525609f86178.json\",\"signature\":\"L6/iZxGXloE=\"}"     // filled in by the Horizon signing process
    }
  ]
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutMicroserviceRequest],
          Option[String]("Microservice object that needs to be updated in the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postMicroservices2 = (apiOperation[PostPutMicroserviceRequest]("postMicroservices2") summary("a") description("a"))  // for some bizarre reason, the PostMicroserviceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/microservices", operation(postMicroservices)) ({
    val orgid = params("orgid")
    val ident = credsAndLog().authenticate().authorizeTo(TMicroservice(OrgAndId(orgid,"").toString),Access.CREATE)
    val microserviceReq = try { parse(request.body).extract[PostPutMicroserviceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    microserviceReq.validate()
    val microservice = microserviceReq.formId(orgid)
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    db.run(MicroservicesTQ.getNumOwned(owner).result.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/microservices num owned by "+owner+": "+xs)
      val numOwned = xs
      val maxMicroservices = ExchConfig.getInt("api.limits.maxMicroservices")
      if (maxMicroservices == 0 || numOwned <= maxMicroservices) {    // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
        microserviceReq.toMicroserviceRow(microservice, orgid, owner).insert.asTry
      }
      else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxMicroservices+ " microservices")).asTry
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/microservices result: "+xs.toString)
      xs match {
        case Success(_) => if (owner != "") AuthCache.microservices.putOwner(microservice, owner)     // currently only users are allowed to update microservice resources, so owner should never be blank
          AuthCache.microservices.putIsPublic(microservice, microserviceReq.public)
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

  // =========== PUT /orgs/{orgid}/microservices/{microservice} ===============================
  val putMicroservices =
    (apiOperation[ApiResponse]("putMicroservices")
      summary "Updates a microservice"
      description """Does a full replace of an existing microservice. This can only be called by the user that originally created it."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutMicroserviceRequest],
          Option[String]("Microservice object that needs to be updated in the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putMicroservices2 = (apiOperation[PostPutMicroserviceRequest]("putMicroservices2") summary("a") description("a"))  // for some bizarre reason, the PutMicroserviceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/microservices/:microservice", operation(putMicroservices)) ({
    val orgid = params("orgid")
    val bareMicro = params("microservice")   // but do not have a hack/fix for the name
    val microservice = OrgAndId(orgid,bareMicro).toString
    val ident = credsAndLog().authenticate().authorizeTo(TMicroservice(microservice),Access.WRITE)
    val microserviceReq = try { parse(request.body).extract[PostPutMicroserviceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    microserviceReq.validate()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    db.run(microserviceReq.toMicroserviceRow(microservice, orgid, owner).update.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/microservices/"+bareMicro+" result: "+xs.toString)
      xs match {
        case Success(n) => try {
            val numUpdated = n.toString.toInt     // i think n is an AnyRef so we have to do this to get it to an int
            if (numUpdated > 0) {
              if (owner != "") AuthCache.microservices.putOwner(microservice, owner)     // currently only users are allowed to update microservice resources, so owner should never be blank
              AuthCache.microservices.putIsPublic(microservice, microserviceReq.public)
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

  // =========== PATCH /orgs/{orgid}/microservices/{microservice} ===============================
  val patchMicroservices =
    (apiOperation[Map[String,String]]("patchMicroservices")
      summary "Updates 1 attribute of a microservice"
      description """Updates one attribute of a microservice in the exchange DB. This can only be called by the user that originally created this microservice resource."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchMicroserviceRequest],
          Option[String]("Partial microservice object that contains an attribute to be updated in this microservice. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val patchMicroservices2 = (apiOperation[PatchMicroserviceRequest]("patchMicroservices2") summary("a") description("a"))  // for some bizarre reason, the PatchMicroserviceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  patch("/orgs/:orgid/microservices/:microservice", operation(patchMicroservices)) ({
    val orgid = params("orgid")
    val bareMicro = params("microservice")   // but do not have a hack/fix for the name
    val microservice = OrgAndId(orgid,bareMicro).toString
    credsAndLog().authenticate().authorizeTo(TMicroservice(microservice),Access.WRITE)
    val microserviceReq = try { parse(request.body).extract[PatchMicroserviceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.trace("PATCH /orgs/"+orgid+"/microservices/"+bareMicro+" input: "+microserviceReq.toString)
    val resp = response
    val (action, attrName) = microserviceReq.getDbUpdate(microservice, orgid)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid microservice attribute specified"))
    db.run(action.transactionally.asTry).map({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/microservices/"+bareMicro+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              if (attrName == "public") AuthCache.microservices.putIsPublic(microservice, microserviceReq.public.getOrElse(false))
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

  // =========== DELETE /orgs/{orgid}/microservices/{microservice} ===============================
  val deleteMicroservices =
    (apiOperation[ApiResponse]("deleteMicroservices")
      summary "Deletes a microservice"
      description "Deletes a microservice from the exchange DB. Can only be run by the owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/microservices/:microservice", operation(deleteMicroservices)) ({
    val orgid = params("orgid")
    val bareMicro = params("microservice")   // but do not have a hack/fix for the name
    val microservice = OrgAndId(orgid,bareMicro).toString
    credsAndLog().authenticate().authorizeTo(TMicroservice(microservice),Access.WRITE)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(MicroservicesTQ.getMicroservice(microservice).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/microservices/"+bareMicro+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.microservices.removeOwner(microservice)
            AuthCache.microservices.removeIsPublic(microservice)
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

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/microservices/{microservice}/keys ================================ */
  val getMicroserviceKeys =
    (apiOperation[List[String]]("getMicroserviceKeys")
      summary "Returns all keys/certs for this microservice"
      description """Returns all the signing public keys/certs for this microservice. Can be run by any credentials able to view the microservice."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/microservices/:microservice/keys", operation(getMicroserviceKeys)) ({
    val orgid = params("orgid")
    val microservice = params("microservice")
    val compositeId = OrgAndId(orgid,microservice).toString
    credsAndLog().authenticate().authorizeTo(TMicroservice(compositeId),Access.READ)
    val resp = response
    db.run(MicroserviceKeysTQ.getKeys(compositeId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/microservices/"+microservice+"/keys result size: "+list.size)
      //logger.trace("GET /orgs/"+orgid+"/microservices/"+id+"/keys result: "+list.toString)
      if (list.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      list.map(_.keyId)
    })
  })

  /* ====== GET /orgs/{orgid}/microservices/{microservice}/keys/{keyid} ================================ */
  val getOneMicroserviceKey =
    (apiOperation[String]("getOneMicroserviceKey")
      summary "Returns a key/cert for this microservice"
      description """Returns the signing public key/cert with the specified keyid for this microservice. The raw content of the key/cert is returned, not json. Can be run by any credentials able to view the microservice."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      produces "text/plain"
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/microservices/:microservice/keys/:keyid", operation(getOneMicroserviceKey)) ({
    val orgid = params("orgid")
    val microservice = params("microservice")
    val compositeId = OrgAndId(orgid,microservice).toString
    val keyId = params("keyid")
    credsAndLog().authenticate().authorizeTo(TMicroservice(compositeId),Access.READ)
    val resp = response
    db.run(MicroserviceKeysTQ.getKey(compositeId, keyId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/microservices/"+microservice+"/keys/"+keyId+" result: "+list.size)
      if (list.nonEmpty) {
        // Return the raw key, not json
        resp.setStatus(HttpCode.OK)
        resp.setHeader("Content-Disposition", "attachment; filename="+keyId)
        resp.setHeader("Content-Type", "text/plain")
        resp.setHeader("Content-Length", list.head.key.length.toString)
        list.head.key
      }
      else {
        resp.setStatus(HttpCode.NOT_FOUND)
        ApiResponse(ApiResponseType.NOT_FOUND, "key '"+keyId+"' not found")
      }
    })
  })

  // =========== PUT /orgs/{orgid}/microservices/{microservice}/keys/{keyid} ===============================
  val putMicroserviceKey =
    (apiOperation[ApiResponse]("putMicroserviceKey")
      summary "Adds/updates a key/cert for the microservice"
      description """Adds a new signing public key/cert, or updates an existing key/cert, for this microservice. This can only be run by the microservice owning user. Note that the input body is just the bytes of the key/cert (not the typical json), so the 'Content-Type' header must be set to 'text/plain'."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[String],
          Option[String]("Key object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putMicroserviceKey2 = (apiOperation[String]("putKey2") summary("a") description("a"))  // for some bizarre reason, the PutKeysRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/microservices/:microservice/keys/:keyid", operation(putMicroserviceKey)) ({
    val orgid = params("orgid")
    val microservice = params("microservice")
    val compositeId = OrgAndId(orgid,microservice).toString
    val keyId = params("keyid")
    credsAndLog().authenticate().authorizeTo(TMicroservice(compositeId),Access.WRITE)
    val keyReq = PutMicroserviceKeyRequest(request.body)
    //val keyReq = try { parse(request.body).extract[PutMicroserviceKeyRequest] }
    //catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    keyReq.validate(keyId)
    val resp = response
    db.run(keyReq.toMicroserviceKeyRow(compositeId, keyId).upsert.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/microservices/"+microservice+"/keys/"+keyId+" result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "key added or updated")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "key '"+keyId+"' for microservice '"+compositeId+"' not inserted or updated: "+t.getMessage)
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "key '"+keyId+"' for microservice '"+compositeId+"' not inserted or updated: "+t.getMessage)
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/microservices/{microservice}/keys ===============================
  val deleteMicroserviceAllKey =
    (apiOperation[ApiResponse]("deleteMicroserviceAllKey")
      summary "Deletes all keys of a microservice"
      description "Deletes all of the current keys/certs for this microservice. This can only be run by the microservice owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
    )

  delete("/orgs/:orgid/microservices/:microservice/keys", operation(deleteMicroserviceAllKey)) ({
    val orgid = params("orgid")
    val microservice = params("microservice")
    val compositeId = OrgAndId(orgid,microservice).toString
    credsAndLog().authenticate().authorizeTo(TMicroservice(compositeId),Access.WRITE)
    val resp = response
    db.run(MicroserviceKeysTQ.getKeys(compositeId).delete.asTry).map({ xs =>
      logger.debug("DELETE /microservices/"+microservice+"/keys result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "microservice keys deleted")
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "no keys for microservice '"+compositeId+"' found")
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "keys for microservice '"+compositeId+"' not deleted: "+t.toString)
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/microservices/{microservice}/keys/{keyid} ===============================
  val deleteMicroserviceKey =
    (apiOperation[ApiResponse]("deleteMicroserviceKey")
      summary "Deletes a key of a microservice"
      description "Deletes a key/cert for this microservice. This can only be run by the microservice owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Microservice id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/microservices/:microservice/keys/:keyid", operation(deleteMicroserviceKey)) ({
    val orgid = params("orgid")
    val microservice = params("microservice")
    val compositeId = OrgAndId(orgid,microservice).toString
    val keyId = params("keyid")
    credsAndLog().authenticate().authorizeTo(TMicroservice(compositeId),Access.WRITE)
    val resp = response
    db.run(MicroserviceKeysTQ.getKey(compositeId,keyId).delete.asTry).map({ xs =>
      logger.debug("DELETE /microservices/"+microservice+"/keys/"+keyId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "microservice key deleted")
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "key '"+keyId+"' for microservice '"+compositeId+"' not found")
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "key '"+keyId+"' for microservice '"+compositeId+"' not deleted: "+t.toString)
      }
    })
  })

}