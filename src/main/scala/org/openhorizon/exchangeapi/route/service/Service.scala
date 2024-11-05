package org.openhorizon.exchangeapi.route.service

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, delete, entity, get, parameter, patch, path, put, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, DBProcessingError, IUser, Identity, OrgAndId, TService}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.service
import org.openhorizon.exchangeapi.table.service.{ServiceRef, ServiceRow, Services, ServicesTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, Version, VersionRange}
import slick.jdbc.PostgresProfile.api._

import java.lang.IllegalStateException
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import scala.util.control.Breaks.{break, breakable}

@Path("/v1/orgs/{organization}/services/{service}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "service")
trait Service extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ====== GET /orgs/{organization}/services/{service} ================================
  @GET
  @Operation(summary = "Returns a service", description = "Returns the service with the specified id. Can be run by a user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service id."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire service resource will be returned")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "services": {
    "orgid/servicename": {
      "owner": "string",
      "label": "string",
      "description": "blah blah",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "1.2.3",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [],
      "userInput": [],
      "deployment": "string",
      "deploymentSignature": "string",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-05-14T16:20:40.221Z[UTC]"
    },
    "orgid/servicename2": {
      "owner": "string",
      "label": "string",
      "description": "string",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "4.5.6",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [
        {
          "url": "string",
          "org": "string",
          "version": "[1.0.0,INFINITY)",
          "versionRange": "[1.0.0,INFINITY)",
          "arch": "string"
        }
      ],
      "userInput": [
        {
          "name": "foo",
          "label": "The Foo Value",
          "type": "string",
          "defaultValue": "bar"
        }
      ],
      "deployment": "string",
      "deploymentSignature": "string",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-05-14T16:20:40.680Z[UTC]"
    },
    "orgid/servicename3": {
      "owner": "string",
      "label": "string",
      "description": "fake",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "string",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [],
      "userInput": [],
      "deployment": "",
      "deploymentSignature": "",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-12-13T15:38:57.679Z[UTC]"
    }
  },
  "lastIndex": 0
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetServicesResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getService(@Parameter(hidden = true) organization: String,
                 @Parameter(hidden = true) resource: String,
                 @Parameter(hidden = true) service: String): Route =
    get {
      parameter("attribute".?) {
        attribute =>
          complete({
            attribute match {
              case Some(attribute) =>  // Only returning 1 attr of the service
                val q = ServicesTQ.getAttribute(resource, attribute) // get the proper db query for this attribute
                if (q == null)
                  (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("attribute.not.part.of.service", attribute)))
                else
                  db.run(q.result).map({
                    list =>
                      logger.debug("GET /orgs/" + organization + "/services/" + service + " attribute result: " + list.toString)
                      if (list.nonEmpty)
                        (HttpCode.OK, GetServiceAttributeResponse(attribute, list.head.toString))
                      else
                        (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
                  })
              case None =>  // Return the whole service resource
                db.run(ServicesTQ.getService(resource).result).map({
                  list =>
                    logger.debug("GET /orgs/" + organization + "/services result size: " + list.size)
                    val services: Map[String, org.openhorizon.exchangeapi.table.service.Service] = list.map(e => e.service -> e.toService).toMap
                    val code: StatusCode =
                      if(services.nonEmpty)
                        StatusCodes.OK
                      else
                        StatusCodes.NotFound
                    
                    (code, GetServicesResponse(services, 0))
                })
            }
          })
      }
    }

  // =========== PUT /orgs/{organization}/services/{service} ===============================
  @PUT
  @Operation(summary = "Updates a service", description = "Does a full replace of an existing service. See the description of the body fields in the POST method. This can only be called by the user that originally created it.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service id.")),
    requestBody = new RequestBody(content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "label": "Location for amd64",
  "description": "blah blah",
  "public": true,
  "documentation": "https://console.cloud.ibm.com/docs/services/edge-fabric/poc/sdr.html",
  "url": "github.com.open-horizon.examples.sdr2msghub",
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "singleton",
  "requiredServices": [
    {
      "org": "myorg",
      "url": "mydomain.com.gps",
      "version": "[1.0.0,INFINITY)",
      "arch": "amd64"
    }
  ],
  "userInput": [
    {
      "name": "foo",
      "label": "The Foo Value",
      "type": "string",
      "defaultValue": "bar"
    }
  ],
  "deployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\"}}}",
  "deploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",
  "clusterDeployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\"}}}",
  "clusterDeploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",
  "imageStore": {
    "storeType": "dockerRegistry"
  }
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PostPutServiceRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def putService(@Parameter(hidden = true) identity: Identity,
                 @Parameter(hidden = true) organization: String,
                 @Parameter(hidden = true) resource: String,
                 @Parameter(hidden = true) service: String): Route =
    put {
      entity(as[PostPutServiceRequest]) {
        reqBody =>
          validateWithMsg(reqBody.getAnyProblem(organization, resource)) {
            complete({
              val owner: String = identity match { case IUser(creds) => creds.id; case _ => "" }   // currently only users are allowed to create/update services, so owner will never be blank
              
              // Make a list of service searches for the required services. This can match more services than we need, because it wildcards the version.
              // We'll look for versions within the required ranges in the db access routine below.
              val svcIds: Seq[String] = reqBody.requiredServices.getOrElse(List()).map(s => ServicesTQ.formId(s.org, s.url, "%", s.arch) )
              val svcAction = if (svcIds.isEmpty) DBIO.successful(Vector())   // no services to look for
              else {
                // The inner map() and reduceLeft() OR together all of the likes to give to filter()
                ServicesTQ.filter(s => { svcIds.map(s.service like _).reduceLeft(_ || _) }).map(s => (s.orgid, s.url, s.version, s.arch)).result
              }
              
              db.run(svcAction.asTry.flatMap({
                case Success(rows) =>
                  logger.debug("POST /orgs/" + organization + "/services requiredServices validation: " + rows)
                  var invalidIndex: Int = -1
                  var invalidSvcRef: ServiceRef = ServiceRef("", "", Some(""), Some(""), "")
                  // rows is a sequence of some ServiceRow cols which is a superset of what we need. Go thru each requiredService in the request and make
                  // sure there is an service that matches the version range specified. If the requiredServices list is empty, this will fall thru and succeed.
                  breakable {
                    for ((svcRef, index) <- reqBody.requiredServices.getOrElse(List()).zipWithIndex) {
                      breakable {
                        for ((orgid, specRef, version, arch) <- rows) {
                          //logger.debug("orgid: "+orgid+", url: "+url+", version: "+version+", arch: "+arch)
                          val finalVersionRange: String = if (svcRef.versionRange.isEmpty) svcRef.version.getOrElse("") else svcRef.versionRange.getOrElse("")
                          if (specRef == svcRef.url && orgid == svcRef.org && arch == svcRef.arch && (Version(version) in VersionRange(finalVersionRange))) break() // we satisfied this apiSpec requirement so move on to the next
                        }
                        invalidIndex = index // we finished the inner loop but did not find a service that satisfied the requirement
                        invalidSvcRef = ServiceRef(svcRef.url, svcRef.org, svcRef.version, svcRef.versionRange, svcRef.arch)
                      } //  if we found a service that satisfies the requirment, it breaks to this line
                      if (invalidIndex >= 0) break() // an requiredService was not satisfied, so break out and return an error
                    }
                  }
                  if (invalidIndex < 0) reqBody.toServiceRow(resource, organization, owner).update.asTry // we are good, move on to the next step
                  else {
                    val errStr: String = ExchMsg.translate("req.service.not.in.exchange", invalidSvcRef.org, invalidSvcRef.url, invalidSvcRef.version, invalidSvcRef.arch)
                    DBIO.failed(new Throwable(errStr)).asTry
                  }
                case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
              }).flatMap({
                case Success(n) =>
                  // Add the resource to the resourcechanges table
                  logger.debug("PUT /orgs/" + organization + "/services/" + service + " result: " + n)
                  val numUpdated: Int = n.asInstanceOf[Int] // i think n is an AnyRef so we have to do this to get it to an int
                  if (numUpdated > 0) {
                    if (owner != "") AuthCache.putServiceOwner(resource, owner) // currently only users are allowed to update service resources, so owner should never be blank
                    AuthCache.putServiceIsPublic(resource, reqBody.public)
                    val serviceId: String = resource.substring(resource.indexOf("/") + 1, resource.length)
                    ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, reqBody.public, ResChangeResource.SERVICE, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                  } else {
                    DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
                  }
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(v) =>
                  logger.debug("PUT /orgs/" + organization + "/services/" + service + " updated in changes table: " + v)
                  (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.updated")))
                case Failure(t: DBProcessingError) =>
                  t.toComplete
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.not.updated", resource, t.getMessage)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.not.updated", resource, t.getMessage))
                case Failure(t) =>
                  if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.not.updated", resource, t.getMessage)))
                  else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("service.not.updated", resource, t.getMessage)))
              })
            })
          }
        }
  }

  // =========== PATCH /orgs/{organization}/services/{service} ===============================
  @PATCH
  @Operation(summary = "Updates 1 attribute of a service", description = "Updates one attribute of a service. This can only be called by the user that originally created this service resource.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name.")),
    requestBody = new RequestBody(description = "Specify only **one** of the attributes", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "label": "Location for amd64",
  "description": "blah blah",
  "public": true,
  "documentation": "https://console.cloud.ibm.com/docs/services/edge-fabric/poc/sdr.html",
  "url": "github.com.open-horizon.examples.sdr2msghub",
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "singleton",
  "requiredServices": [
    {
      "org": "myorg",
      "url": "mydomain.com.gps",
      "version": "[1.0.0,INFINITY)",
      "arch": "amd64"
    }
  ],
  "userInput": [
    {
      "name": "foo",
      "label": "The Foo Value",
      "type": "string",
      "defaultValue": "bar"
    }
  ],
  "deployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\"}}}",
  "deploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",
  "clusterDeployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\"}}}",
  "clusterDeploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",
  "imageStore": {
    "storeType": "dockerRegistry"
  }
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PatchServiceRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patchService(@Parameter(hidden = true) organization: String,
                   @Parameter(hidden = true) resource: String,
                   @Parameter(hidden = true) service: String): Route =
    patch {
      entity(as[PatchServiceRequest]) {
        reqBody =>
        logger.debug(s"Doing PATCH /orgs/$organization/services/$service")
          validateWithMsg(reqBody.getAnyProblem) {
            complete({
              val (action, attrName) = reqBody.getDbUpdate(resource, organization)
              if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.service.attr.specified")))
              else if (attrName == "url" || attrName == "version" || attrName == "arch") (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("cannot.patch.these.attributes")))
              else if (attrName == "sharable" && !SharableVals.values.map(_.toString).contains(reqBody.sharable.getOrElse(""))) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.value.for.sharable.attribute", reqBody.sharable.getOrElse(""))))
              else {
                // Make a list of service searches for the required services. This can match more services than we need, because it wildcards the version.
                // We'll look for versions within the required ranges in the db access routine below.
                val svcIds: Seq[String] = if (attrName == "requiredServices") reqBody.requiredServices.getOrElse(List()).map(s => ServicesTQ.formId(s.org, s.url, "%", s.arch)) else List()
                val svcAction = if (svcIds.isEmpty) DBIO.successful(Vector()) // no services to look for
                else {
                  // The inner map() and reduceLeft() OR together all of the likes to give to filter()
                  ServicesTQ.filter(s => {
                    svcIds.map(s.service like _).reduceLeft(_ || _)
                  }).map(s => (s.orgid, s.url, s.version, s.arch)).result
                }
                
                // First check that the requiredServices exist (if that is not what they are patching, this is a noop)
                //todo: add a step to update the owner, if different
                db.run(svcAction.transactionally.asTry.flatMap({
                  case Success(rows) =>
                    logger.debug("PATCH /orgs/" + organization + "/services requiredServices validation: " + rows)
                    var invalidIndex: Int = -1
                    var invalidSvcRef: ServiceRef = ServiceRef("", "", Some(""), Some(""), "")
                    // rows is a sequence of some ServiceRow cols which is a superset of what we need. Go thru each requiredService in the request and make
                    // sure there is an service that matches the version range specified. If the requiredServices list is empty, this will fall thru and succeed.
                    breakable {
                      for ((svcRef, index) <- reqBody.requiredServices.getOrElse(List()).zipWithIndex) {
                        breakable {
                          for ((orgid, url, version, arch) <- rows) {
                            //logger.debug("orgid: "+orgid+", url: "+url+", version: "+version+", arch: "+arch)
                            val finalVersionRange: String = if (svcRef.versionRange.isEmpty) svcRef.version.getOrElse("") else svcRef.versionRange.getOrElse("")
                            if (url == svcRef.url && orgid == svcRef.org && arch == svcRef.arch && (Version(version) in VersionRange(finalVersionRange))) break() // we satisfied this requiredService so move on to the next
                          }
                          invalidIndex = index // we finished the inner loop but did not find a service that satisfied the requirement
                          invalidSvcRef = ServiceRef(svcRef.url, svcRef.org, svcRef.version, svcRef.versionRange, svcRef.arch)
                        } //  if we found a service that satisfies the requirement, it breaks to this line
                        if (invalidIndex >= 0) break() // a requiredService was not satisfied, so break out of the outer loop and return an error
                      }
                    }
                    if (invalidIndex < 0) action.transactionally.asTry // we are good, move on to the real patch action
                    else {
                      val errStr: String = ExchMsg.translate("req.service.not.in.exchange", invalidSvcRef.org, invalidSvcRef.url, invalidSvcRef.version, invalidSvcRef.arch)
                      DBIO.failed(new Throwable(errStr)).asTry
                    }
                  case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
                }).flatMap({
                  case Success(v) =>
                    // Get the value of the public field
                    logger.debug("PUT /orgs/" + organization + "/services/" + service + " result: " + v)
                    val numUpdated: Int = v.asInstanceOf[Int] // v comes to us as type Any
                    if (numUpdated > 0) { // there were no db errors, but determine if it actually found it or not
                      if (attrName == "public") AuthCache.putServiceIsPublic(resource, reqBody.public.getOrElse(false))
                      ServicesTQ.getPublic(resource).result.asTry
                    } else {
                      DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
                    }
                  case Failure(t) => DBIO.failed(t).asTry
                }).flatMap({
                  case Success(public) =>
                    // Add the resource to the resourcechanges table
                    logger.debug("PUT /orgs/" + organization + "/services/" + service + " public field: " + public)
                    if (public.nonEmpty) {
                      val serviceId: String = resource.substring(resource.indexOf("/") + 1, resource.length)
                      var publicField = false
                      if (reqBody.public.isDefined) {
                        publicField = reqBody.public.getOrElse(false)
                      }
                      else {
                        publicField = public.head
                      }
                      ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, publicField, ResChangeResource.SERVICE, ResChangeOperation.MODIFIED).insert.asTry
                    } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
                  case Failure(t) => DBIO.failed(t).asTry
                })).map({
                  case Success(v) =>
                    logger.debug("PATCH /orgs/" + organization + "/services/" + service + " updated in changes table: " + v)
                    (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.attr.updated", attrName, resource)))
                  case Failure(t: DBProcessingError) =>
                    t.toComplete
                  case Failure(t: org.postgresql.util.PSQLException) =>
                    if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.not.updated", resource, t.getMessage)))
                    else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.not.updated", resource, t.getMessage))
                  case Failure(t) =>
                    if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.not.updated", resource, t.getMessage)))
                    else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("service.not.updated", resource, t.getMessage)))
                })
              }
            })
          }
        }
  }

  // =========== DELETE /orgs/{organization}/services/{service} ===============================
  @DELETE
  @Operation(summary = "Deletes a service", description = "Deletes a service. Can only be run by the owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteService(@Parameter(hidden = true) organization: String,
                    @Parameter(hidden = true) resource: String,
                    @Parameter(hidden = true) service: String): Route =
    delete {
      logger.debug(s"Doing DELETE /orgs/$organization/services/$service")
      
      complete({
        val findService: Query[Services, ServiceRow, Seq] =
          ServicesTQ.filter(service => (service.orgid === organization && service.service === resource))
        
        val deleteService =
          for {
             getPublicAttribute: Option[Boolean] <- Compiled(findService.map(_.public)).result.headOption
            
            _ <-
            if (getPublicAttribute.isEmpty)
              DBIO.failed(throw new NoSuchElementException())
            else
              DBIO.successful(())
            
            changesInserted <-
              ResourceChangesTQ +=
                ResourceChangeRow(category = ResChangeCategory.SERVICE.toString,
                                  changeId = 0L,
                                  id = resource.substring(resource.indexOf("/") + 1, resource.length),
                                  lastUpdated = ApiTime.nowUTCTimestamp,
                                  operation = ResChangeOperation.DELETED.toString,
                                  orgId = organization,
                                  public = getPublicAttribute.get.toString,
                                  resource = ResChangeResource.SERVICE.toString)
            
            _ <-
              if (changesInserted == 1)
                DBIO.successful(())
              else
                DBIO.failed(throw new IllegalStateException())
              
              _ <-
                try {
                  AuthCache.removeServiceIsPublic(resource)
                  AuthCache.removeServiceOwner(resource)
                  DBIO.successful(())
                }
                catch {
                  case _: Throwable => DBIO.failed(throw new IllegalStateException())
                }
            
            _ <- Compiled(findService).delete
            
          } yield()
          
        db.run(deleteService.transactionally.asTry)
          .map({
            case Success(_) =>
              (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.deleted")))
            case Failure(t: NoSuchElementException) =>
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.not.deleted", resource, t.toString))
            case Failure(t: IllegalStateException) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.not.deleted", resource, t.toString)))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.not.deleted", resource, t.toString)))
          })
        
        // remove does *not* throw an exception if the key does not exist
        /*var storedPublicField = false
        db.run(ServicesTQ.getPublic(resource).result.asTry.flatMap({
          case Success(public) =>
            // Get the value of the public field before doing the deletion
            logger.debug("DELETE /orgs/" + organization + "/services/" + service + " public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              ServicesTQ.getService(resource).delete.transactionally.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + organization + "/services/" + service + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              AuthCache.removeServiceOwner(resource)
              AuthCache.removeServiceIsPublic(resource)
              val serviceId: String = resource.substring(resource.indexOf("/") + 1, resource.length)
              ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, storedPublicField, ResChangeResource.SERVICE, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /orgs/" + organization + "/services/" + service + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.not.deleted", resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.not.deleted", resource, t.toString)))
        })*/
      })
  }
  
  val service: Route =
    path("orgs" / Segment / "services" / Segment) {
      (organization, service) =>
        val resource: String = OrgAndId(organization, service).toString
        
        (delete | patch | put) {
          exchAuth(TService(resource), Access.WRITE) {
            identity =>
              deleteService(organization, resource, service) ~
              patchService(organization, resource, service) ~
              putService(identity, organization, resource, service)
          }
        } ~
        get {
          exchAuth(TService(resource), Access.READ) {
            _ =>
              getService(organization, resource, service)
          }
        }
    }
}
