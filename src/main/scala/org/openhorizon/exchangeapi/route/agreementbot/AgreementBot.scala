package org.openhorizon.exchangeapi.route.agreementbot

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives.{as, complete, entity, parameter, path, pathEnd, pathPrefix, validate, _}
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PATCH, PUT, Path}
import org.openhorizon.exchangeapi.auth.DBProcessingError
import org.openhorizon.exchangeapi.table.{Agbot, AgbotsTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.{Access, ApiRespType, ApiResponse, AuthCache, AuthenticationSupport, ExchConfig, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, IUser, Identity, OrgAndId, Password, TAgbot}
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/agbots/{agreementbot}")
trait AgreementBot extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // ========== GET /orgs/{organization}/agbots/{agreementbot} ==============================================
  @GET
  @Operation(summary = "Returns an Agreement Bot (AgBot)",
             description = "Returns the AgBot with the specified identifier. Can be run by a User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier."),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier"),
                     new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only a single attribute can be specified. If not specified, the entire node resource (including services) will be returned")),
             responses =
               Array(new responses.ApiResponse(responseCode = "200", description = "response body",
                                               content =
                                                 Array(new Content(examples =
                                                   Array(new ExampleObject(value = """{
  "agbots": {
    "orgid/agbotname": {
      "token": "string",
      "name": "string",
      "owner": "string",
      "msgEndPoint": "",
      "lastHeartbeat": "2020-05-27T19:01:10.713Z[UTC]",
      "publicKey": "string"
    }
  },
  "lastIndex": 0
}""")),
                                                                   mediaType = "application/json",
                                                                   schema = new Schema(implementation = classOf[GetAgbotsResponse])))),
                     new responses.ApiResponse(responseCode = "400", description = "bad input"),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot")
  def getAgreementBot(agreementBot: String,
                      identity: Identity,
                      organization: String,
                      resource: String): Route =
    parameter("attribute".?) {
      attribute =>
        logger.debug(s"Doing GET /orgs/$organization/agbots/$agreementBot")
        val q: PostgresProfile.api.Query[_, _, Seq] =
            if (attribute.isDefined)
              AgbotsTQ.getAttribute(resource, attribute.get)
            else
              null
        validate(attribute.isEmpty || q != null, ExchMsg.translate("agbot.name.not.in.resource")) {
            complete({
              logger.debug(s"GET /orgs/$organization/agbots/$agreementBot identity: ${identity.creds.id}") // can't display the whole ident object, because that contains the pw/token
              attribute match {
                case Some(attr) => // Only returning 1 attr of the agbot
                  db.run(q.result)
                    .map({
                      list => //logger.debug("GET /orgs/"+orgid+"/agbots/"+id+" attribute result: "+list.toString)
                        if (list.nonEmpty)
                          (HttpCode.OK, GetAgbotAttributeResponse(attr, list.head.toString))
                        else
                          (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found"))) // validateAccessToAgbot() will return ApiRespType.NOT_FOUND to the client so do that here for consistency
                    })
                case None => // Return the whole agbot, including the services
                  db.run(AgbotsTQ.getAgbot(resource).result)
                    .map({
                      list =>
                        logger.debug(s"GET /orgs/$organization/agbots result size: ${list.size}")
                        val agbots: Map[String, Agbot] = list.map(e => e.id -> e.toAgbot(identity.isSuperUser)).toMap
                        val code: StatusCode =
                          if (agbots.nonEmpty)
                            StatusCodes.OK
                          else
                            StatusCodes.NotFound
                        (code, GetAgbotsResponse(agbots, 0))
                    })
              }
            })
          }
    }
  
  // ========== PUT /orgs/{organization}/agbots/{agreementbot} ==============================================
  @PUT
  @Operation(summary = "Add/updates an Agreement Bot (AgBot)",
             description = "This must be called by the User to add an AgBot, and then can be called by that User or AgBot to update itself.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier."),
                     new Parameter(name = "agreeementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier")),
             requestBody =
               new RequestBody(content =
                 Array(new Content(examples =
                   Array(new ExampleObject(value = """{
  "token": "abc",
  "name": "myagbot",
  "publicKey": "ABCDEF"
}
""")),
                                   mediaType = "application/json",
                                   schema = new Schema(implementation = classOf[PutAgbotsRequest]))),
                                   required = true),
             responses =
               Array(new responses.ApiResponse(responseCode = "200", description = "resource add/updated - response body:",
                                               content =
                                                 Array(new Content(mediaType = "application/json",
                                                                   schema = new Schema(implementation = classOf[ApiResponse])))),
                     new responses.ApiResponse(responseCode = "400", description = "bad input"),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot")
  def putAgreementBot(agreementBot: String,
                      identity: Identity,
                      organization: String,
                      resource: String): Route =
    put {
      entity(as[PutAgbotsRequest]) {
        reqBody =>
          logger.debug(s"Doing PUT /orgs/$organization/agbots/$agreementBot")
          validateWithMsg(reqBody.getAnyProblem) {
            complete({
              val owner: String =
                identity match {
                  case IUser(creds) => creds.id;
                  case _ => ""
                }
              val hashedTok: String = Password.hash(reqBody.token)
              db.run(AgbotsTQ.getNumOwned(owner)
                             .result
                             .flatMap({
                               xs =>
                                 logger.debug("PUT /orgs/" + organization + "/agbots/" + agreementBot + " num owned: " + xs)
                                 val numOwned: Int = xs
                                 val maxAgbots: Int = ExchConfig.getInt("api.limits.maxAgbots")
                                 if (maxAgbots == 0 ||
                                     numOwned <= maxAgbots ||
                                     owner == "") { // when owner=="" we know it is only an update, otherwise we are not sure, but if they are already over the limit, stop them anyway
                                   val action =
                                     if (owner == "")
                                       reqBody.getDbUpdate(resource, organization, owner, hashedTok)
                                     else
                                       reqBody.getDbUpsert(resource, organization, owner, hashedTok)
                                   action.asTry}
                                 else
                                   DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.max.limit.of.agbots", maxAgbots))).asTry})
                             .flatMap({
                               case Success(v) => // Add the resource to the resourcechanges table
                                 logger.debug(s"PUT /orgs/$organization/agbots/$agreementBot result: $v")
                                 ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOT, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                               case Failure(t) => DBIO.failed(t).asTry}))
                .map({
                  case Success(v) =>
                    logger.debug(s"PUT /orgs/$organization/agbots/$agreementBot updated in changes table: $v")
                    AuthCache.putAgbotAndOwner(resource, hashedTok, reqBody.token, owner)
                    (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.added.updated")))
                  case Failure(t: DBProcessingError) =>
                    t.toComplete
                  case Failure(t: org.postgresql.util.PSQLException) =>
                    ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.not.inserted.or.updated", resource, t.toString))
                  case Failure(t) =>
                    (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.not.inserted.or.updated", resource, t.toString)))
                })
            })
          }
      }
    }
  
  // =========== PATCH /orgs/{organization}/agbots/{agreementbot} ===============================
  @PATCH
  @Operation(summary = "Updates one attribute of an Agreement Bo (Agbot)",
    description = "This can be called by the User or the AgBot.",
    parameters =
      Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier"),
            new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier")),
    requestBody =
      new RequestBody(description = "Specify only **one** of the following attributes",
                      required = true,
                      content =
                        Array(
                          new Content(examples =
                                            Array(
                                              new ExampleObject(value = """{
  "token": "abc",
  "name": "myagbot",
  "msgEndPoint": "string",
  "publicKey": "ABCDEF"
}
""")),
                                      mediaType = "application/json",
                                      schema = new Schema(implementation = classOf[PatchAgbotsRequest])))),
    responses =
      Array(new responses.ApiResponse(responseCode = "200", description = "resource updated - response body:",
                                      content =
                                        Array(new Content(mediaType = "application/json",
                                                          schema = new Schema(implementation = classOf[ApiResponse])))),
            new responses.ApiResponse(responseCode = "400", description = "bad input"),
            new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
            new responses.ApiResponse(responseCode = "403", description = "access denied"),
            new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot")
  def patchAgreementBot(agreementBot: String,
                        organization: String,
                        resource: String): Route =
    patch {
      entity(as[PatchAgbotsRequest]) {
        reqBody =>
          logger.debug(s"Doing PATCH /orgs/$organization/agbots/$agreementBot")
            validateWithMsg(reqBody.getAnyProblem) {
              complete({
                val hashedTok: String =
                  if (reqBody.token.isDefined)
                    Password.hash(reqBody.token.get)
                  else
                    "" // hash the token if that is what is being updated
                val (action, attrName) = reqBody.getDbUpdate(resource, organization, hashedTok)
                if (action == null)
                  (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.agbot.attribute.specified")))
                else
                  db.run(action.transactionally
                               .asTry
                               .flatMap({
                                 case Success(v) => // Add the resource to the resourcechanges table
                                   logger.debug(s"PATCH /orgs/$organization/agbots/$agreementBot result: $v")
                                   if (v.asInstanceOf[Int] > 0) { // there were no db errors, but determine if it actually found it or not
                                     if (reqBody.token.isDefined)
                                       AuthCache.putAgbot(resource, hashedTok, reqBody.token.get) // We do not need to run putOwner because patch does not change the owner
                                     ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOT, ResChangeOperation.MODIFIED).insert.asTry
                                   }
                                   else
                                     DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("agbot.not.found", resource))).asTry
                                 case Failure(t) =>
                                   DBIO.failed(t).asTry}))
                    .map({
                      case Success(v) =>
                        logger.debug(s"PATCH /orgs/$organization/agbots/$agreementBot updated in changes table: $v")
                        (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.attribute.updated", attrName, resource)))
                      case Failure(t: DBProcessingError) =>
                        t.toComplete
                      case Failure(t: org.postgresql.util.PSQLException) =>
                        ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.not.inserted.or.updated", resource, t.toString))
                      case Failure(t) =>
                        (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.not.inserted.or.updated", resource, t.toString)))
                    })
              })
            }
      }
    }
  
  // =========== DELETE /orgs/{organization}/agbots/{agreementbot} ===============================
  @DELETE
  @Operation(summary = "Deletes an Agreement Bot (AgBot)",
             description = "Also deletes the agreements stored for this AgBot (but does not actually cancel the agreements between the Nodes and AgBot). Can be run by the owning User or the Agbot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier"),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier")),
             responses =
               Array(new responses.ApiResponse(responseCode = "204", description = "deleted"),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot")
  def deleteAgreementBot(agreementBot: String,
                                 organization: String,
                                 resource: String): Route =
    delete {
      logger.debug(s"Doing DELETE /orgs/$organization/agbots/$agreementBot")
      complete({ // remove does *not* throw an exception if the key does not exist
        db.run(AgbotsTQ.getAgbot(resource)
                       .delete
                       .transactionally
                       .asTry
                       .flatMap({
                         case Success(v) =>
                           if (v > 0) { // there were no db errors, but determine if it actually found it or not
                             logger.debug(s"DELETE /orgs/$organization/agbots/$agreementBot result: $v")
                             AuthCache.removeAgbotAndOwner(resource)
                             ResourceChange(0L,
                                            organization,
                                            agreementBot,
                                            ResChangeCategory.AGBOT,
                                            public = false,
                                            ResChangeResource.AGBOT,
                                            ResChangeOperation.DELETED).insert.asTry
                           }
                           else
                             DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("agbot.not.found", resource))).asTry
                         case Failure(t) =>
                           DBIO.failed(t).asTry}))
          .map({
            case Success(v) =>
              logger.debug(s"DELETE /orgs/$organization/agbots/$agreementBot updated in changes table: $v")
              (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.deleted")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.not.deleted", resource, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.not.deleted", resource, t.toString)))
          })
      })
    }
  
  
  val agreementBot: Route =
    path("orgs" / Segment / "agbots" / Segment) {
      (organization, agreementBot) =>
        val resource: String = OrgAndId(organization, agreementBot).toString
        
        get {
          exchAuth(TAgbot(resource), Access.READ) {
            identity =>
              getAgreementBot(agreementBot, identity, organization, resource)
          }
        } ~
        (delete | patch | put) {
          exchAuth(TAgbot(resource), Access.WRITE) {
            identity =>
              deleteAgreementBot(agreementBot, organization, resource) ~
              patchAgreementBot(agreementBot, organization, resource) ~
              putAgreementBot(agreementBot, identity, organization, resource)
          }
        }
    }
}
