package org.openhorizon.exchangeapi.route.agreementbot

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PATCH, PUT, Path}
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, DBProcessingError, IUser, Identity, Identity2, OrgAndId, Password, TAgbot}
import org.openhorizon.exchangeapi.table.agreementbot.{Agbot, AgbotRow, Agbots, AgbotsTQ}
import org.openhorizon.exchangeapi.table.resourcechange
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import org.openhorizon.exchangeapi.{ExchangeApiApp, table}
import org.openhorizon.exchangeapi.ExchangeApiApp.{cacheResourceIdentity, cacheResourceOwnership}
import org.openhorizon.exchangeapi.table.user.UsersTQ
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api._
import slick.lifted.MappedProjection

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
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
  def getAgreementBot(@Parameter(hidden = true) agreementBot: String,
                      @Parameter(hidden = true) identity: Identity2,
                      @Parameter(hidden = true) organization: String,
                      @Parameter(hidden = true) resource: String): Route = {
    logger.debug(s"GET /orgs/$organization/agbots/$agreementBot - By ${identity.resource}:${identity.role}")
    
    parameter("attribute".?) {
      attribute =>
        
        def isValidAttribute(attribute: String): Boolean =
          attribute match {
            case // "id" |
                 "lastheartbeat" |
                 "msgendpoint" |
                 "name" |
                 "orgid" |
                 "owner" |
                 "publickey" => true
            case _ => false
          }
        
        val baseAgbotQuery: Query[Agbots, AgbotRow, Seq] =
          AgbotsTQ.filter(agbots => agbots.id === resource &&
                                    agbots.orgid === organization)
                  .filterIf(!identity.isSuperUser &&
                            !identity.isHubAdmin &&
                            !identity.isMultiTenantAgbot)(agbots => agbots.orgid === identity.organization ||
                                                                    agbots.orgid === "IBM")
        
        attribute match {
          case Some(attribute) if attribute.nonEmpty && isValidAttribute(attribute.toLowerCase) =>
            val getAgbotAttribute: Query[MappedProjection[GetAgbotAttributeResponse, (String, String)], GetAgbotAttributeResponse, Seq] =
              for {
                agbotAttribute <-
                  if (attribute == "owner")
                    baseAgbotQuery.join(UsersTQ.map(users => (users.organization, users.user, users.username)))
                                  .on(_.owner === _._2)
                                  .map(agbots => (attribute, (agbots._2._1 ++ "/" ++ agbots._2._3)))
                  else
                    baseAgbotQuery.map(agbots =>
                                        (attribute,
                                          attribute.toLowerCase match {
                                           // case "id" => agbots.id
                                           case "lastheartbeat" => agbots.lastHeartbeat
                                           case "msgendpoint" => agbots.msgEndPoint
                                           case "name" => agbots.name
                                           // case "orgid" => agbots.orgid
                                           case "publickey" => agbots.publicKey
                                         }))
              } yield agbotAttribute.mapTo[GetAgbotAttributeResponse]
              
            complete({
              db.run(getAgbotAttribute.result.transactionally.asTry).map {
                case Success(agbotAtrribute) =>
                  if (agbotAtrribute.size == 1)
                    (HttpCode.OK, agbotAtrribute.head)
                  else if (agbotAtrribute.isEmpty)
                    (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
                  else
                    (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("invalid.input.agbot.not.found", resource)))
                case Failure(exception) =>
                  logger.error(cause = exception, message = s"GET /orgs/$organization/agbots/$agreementBot?attribute=${attribute}")
                  (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("error")))
              }
            })
          case _ =>
            val getAgbot: Query[MappedProjection[Agbot, (String, String, String, String, String, String, String, String)], Agbot, Seq] =
              for {
                agbot <-
                  baseAgbotQuery.join(UsersTQ.map(users => (users.organization, users.user, users.username)))
                                .on(_.owner === _._2)
                                .take(1)
                                .map(agbots =>
                                      (agbots._1.id,
                                       agbots._1.lastHeartbeat,
                                       agbots._1.msgEndPoint,
                                       agbots._1.name,
                                       agbots._1.orgid,
                                        (agbots._2._1 ++ "/" ++ agbots._2._3),
                                       agbots._1.publicKey,
                                       "***************"))
              } yield agbot.mapTo[Agbot]
          
            complete({
              db.run(getAgbot.result.transactionally.asTry).map {
                case Success(agbot) =>
                  if (agbot.size == 1)
                    (HttpCode.OK, GetAgbotsResponse(agbot.map(agreement_bots => agreement_bots.id -> agreement_bots).toMap))
                  else if (agbot.isEmpty)
                    (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
                  else
                    (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("invalid.input.agbot.not.found", resource)))
                case Failure(exception) =>
                  logger.error(cause = exception, message = s"GET /orgs/$organization/agbots/$agreementBot")
                  (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("error")))
              }
            })
        }
    }
  }
  
  // ========== PUT /orgs/{organization}/agbots/{agreementbot} ==============================================
  @PUT
  @Operation(summary = "Add/updates an Agreement Bot (AgBot)",
             description = "This must be called by the User to add an AgBot, and then can be called by that User or AgBot to update itself.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier."),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier")),
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
  def putAgreementBot(@Parameter(hidden = true) agreementBot: String,
                      @Parameter(hidden = true) identity: Identity2,
                      @Parameter(hidden = true) organization: String,
                      @Parameter(hidden = true) resource: String): Route =
    put {
      entity(as[PutAgbotsRequest]) {
        reqBody =>
          logger.debug(s"PUT /orgs/$organization/agbots/$agreementBot - By ${identity.resource}:${identity.role}")
          validateWithMsg(reqBody.getAnyProblem) {
            complete({
              val owner: Option[UUID] = identity.identifier
              val hashedTok: String = Password.hash(reqBody.token)
              db.run(AgbotsTQ.getNumOwned(identity.identifier.getOrElse(identity.owner.get))
                             .result
                             .flatMap({
                               xs =>
                                 logger.debug("PUT /orgs/" + organization + "/agbots/" + agreementBot + " num owned: " + xs)
                                 val numOwned: Int = xs
                                 val maxAgbots: Int = Configuration.getConfig.getInt("api.limits.maxAgbots")
                                 if (maxAgbots == 0 ||
                                     numOwned <= maxAgbots ||
                                     owner.isEmpty) { // when owner=="" we know it is only an update, otherwise we are not sure, but if they are already over the limit, stop them anyway
                                   val action =
                                     if (owner.isEmpty)
                                       reqBody.getDbUpdate(resource, organization, hashedTok)
                                     else
                                       reqBody.getDbUpsert(resource, organization, identity.identifier.get, hashedTok)
                                   action.asTry}
                                 else
                                   DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.max.limit.of.agbots", maxAgbots))).asTry})
                             .flatMap({
                               case Success(v) => // Add the resource to the resourcechanges table
                                 logger.debug(s"PUT /orgs/$organization/agbots/$agreementBot result: $v")
                                 resourcechange.ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOT, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                               case Failure(t) => DBIO.failed(t).asTry}))
                .map({
                  case Success(v) =>
                    logger.debug(s"PUT /orgs/$organization/agbots/$agreementBot updated in changes table: $v")
                    //TODO: AuthCache.putAgbotAndOwner(resource, hashedTok, reqBody.token, owner.get)
                    
                    Future { cacheResourceIdentity.remove(resource) }
                    
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
  def patchAgreementBot(@Parameter(hidden = true) agreementBot: String,
                        @Parameter(hidden = true) identity: Identity2,
                        @Parameter(hidden = true) organization: String,
                        @Parameter(hidden = true) resource: String): Route =
    patch {
      entity(as[PatchAgbotsRequest]) {
        reqBody =>
          logger.debug(s"PATCH /orgs/$organization/agbots/$agreementBot - By ${identity.resource}:${identity.role}")
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
                                     if (reqBody.token.isDefined) {
                                       // TODO: AuthCache.putAgbot(resource, hashedTok, reqBody.token.get) // We do not need to run putOwner because patch does not change the owner}
                                     }
                                     resourcechange.ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOT, ResChangeOperation.MODIFIED).insert.asTry
                                   }
                                   else
                                     DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("agbot.not.found", resource))).asTry
                                 case Failure(t) =>
                                   DBIO.failed(t).asTry}))
                    .map({
                      case Success(v) =>
                        Future { logger.debug(s"PATCH /orgs/$organization/agbots/$agreementBot updated in changes table: $v") }
                        
                        Future { cacheResourceIdentity.remove(resource) }
                        
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
  def deleteAgreementBot(@Parameter(hidden = true) agreementBot: String,
                         @Parameter(hidden = true) identity: Identity2,
                         @Parameter(hidden = true) organization: String,
                         @Parameter(hidden = true) resource: String): Route =
    delete {
      logger.debug(s"DELETE /orgs/$organization/agbots/$agreementBot - By ${identity.resource}:${identity.role}")
      complete({ // remove does *not* throw an exception if the key does not exist
        db.run(AgbotsTQ.getAgbot(resource)
                       .delete
                       .transactionally
                       .asTry
                       .flatMap({
                         case Success(v) =>
                           if (v > 0) { // there were no db errors, but determine if it actually found it or not
                             logger.debug(s"DELETE /orgs/$organization/agbots/$agreementBot result: $v")
                             // TODO: AuthCache.removeAgbotAndOwner(resource)
                             resourcechange.ResourceChange(0L,
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
              Future { logger.debug(s"DELETE /orgs/$organization/agbots/$agreementBot updated in changes table: $v") }
              
              Future { cacheResourceIdentity.remove(resource) }
              Future { cacheResourceOwnership.remove(organization, agreementBot, "agreement_bot") }
              
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
  
  
  def agreementBot(identity: Identity2): Route =
    path("orgs" / Segment / "agbots" / Segment) {
      (organization, agreementBot) =>
        val resource: String = OrgAndId(organization, agreementBot).toString
        val resource_type = "agreement_bot"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, agreementBot,resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
          }
        
        def routeMethods(owningResourceIdentity: Option[UUID] = None): Route =
          get {
            exchAuth(TAgbot(resource, owningResourceIdentity), Access.READ, validIdentity = identity) {
              _ =>
                getAgreementBot(agreementBot, identity, organization, resource)
            }
          } ~
          (delete | patch | put) {
            exchAuth(TAgbot(resource, owningResourceIdentity), Access.WRITE, validIdentity = identity) {
              _ =>
                deleteAgreementBot(agreementBot, identity, organization, resource) ~
                patchAgreementBot(agreementBot, identity, organization, resource) ~
                putAgreementBot(agreementBot, identity, organization, resource)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, _)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity))
        }
    }
}
