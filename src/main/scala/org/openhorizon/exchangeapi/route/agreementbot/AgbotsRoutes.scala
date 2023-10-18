/** Services routes for all of the /agbots api methods. */
package org.openhorizon.exchangeapi.route.agreementbot

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.json4s._
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, DBProcessingError, IUser, OrgAndId, Password, TAgbot}
import org.openhorizon.exchangeapi.table._
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchConfig, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.concurrent.ExecutionContext
import scala.util._

//====== These are the input and output structures for /agbots routes. Swagger and/or json seem to require they be outside the trait.

//final case class PostAgbotsIsRecentDataRequest(secondsStale: Int, agreementIds: List[String])     // the strings in the list are agreement ids
//final case class PostAgbotsIsRecentDataElement(agreementId: String, recentData: Boolean)


/** Implementation for all of the /agbots routes */
//@Path("/v1/orgs/{organization}/agbots")
trait AgbotsRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  //def agbotsRoutes: Route = //agbotDeleteAgreementRoute ~
                            //agbotDeleteAgreementsRoute ~
                            //agbotDeleteBusPolRoute ~
                            //agbotDeleteBusPolsRoute ~
                            //agbotDeleteMsgRoute ~
                            //agbotDeletePatRoute ~
                            //agbotDeletePatsRoute ~
                            //agbotDeleteRoute ~
                            //agbotGetAgreementRoute ~
                            //agbotGetAgreementsRoute ~
                            //agbotGetBusPolRoute ~
                            //agbotGetBusPolsRoute ~
                            //agbotGetMsgRoute ~
                            //agbotGetMsgsRoute ~
                            //agbotGetPatternRoute ~
                            //agbotGetPatternsRoute ~
                            //agbotGetRoute ~
                            //agbotHeartbeatRoute ~
                            //agbotPatchRoute ~
                            //agbotPostBusPolRoute ~
                            //agbotPostMsgRoute ~
                            //agbotPostPatRoute ~
                            //agbotPutAgreementRoute ~
                            //agbotPutRoute ~
                            //agbotsGetRoute
  
  /*
  /* ====== GET /orgs/{organization}/agbots ================================ */
  @GET
  @Path("")
  @Operation(summary = "Returns all agbots", description = "Returns all agbots (Agreement Bots). Can be run by any user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "idfilter", in = ParameterIn.QUERY, required = false, description = "Filter results to only include agbots with this id (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "name", in = ParameterIn.QUERY, required = false, description = "Filter results to only include agbots with this name (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include agbots with this owner (can include % for wildcard - the URL encoding for % is %25)")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
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
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetAgbotsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot")
  def agbotsGetRoute: Route = (path("orgs" / Segment / "agbots") & get & parameter("idfilter".?, "name".?, "owner".?)) { (orgid, idfilter, name, owner) =>
    logger.debug(s"Doing GET /orgs/$orgid/agbots")
    exchAuth(TAgbot(OrgAndId(orgid,"*").toString), Access.READ) { ident =>
      complete({
        logger.debug(s"GET /orgs/$orgid/agbots identity: ${ident.creds.id}") // can't display the whole ident object, because that contains the pw/token
        var q = AgbotsTQ.getAllAgbots(orgid)
        idfilter.foreach(id => { if (id.contains("%")) q = q.filter(_.id like id) else q = q.filter(_.id === id) })
        name.foreach(name => { if (name.contains("%")) q = q.filter(_.name like name) else q = q.filter(_.name === name) })
        owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
        db.run(q.result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/agbots result size: ${list.size}")
          val agbots: Map[String, Agbot] = list.map(e => e.id -> e.toAgbot(ident.isSuperUser)).toMap
          val code: StatusCode = if (agbots.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetAgbotsResponse(agbots, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{organization}/agbots/{id} ================================ */
  @GET
  @Path("{id}")
  @Operation(summary = "Returns an agbot", description = "Returns the agbot (Agreement Bot) with the specified id. Can be run by a user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire node resource (including services) will be returned")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
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
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetAgbotsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot")
  def agbotGetRoute: Route = (path("orgs" / Segment / "agbots" / Segment) & get & parameter("attribute".?)) {(orgid, id, attribute) =>
    logger.debug(s"Doing GET /orgs/$orgid/agbots/$id")
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TAgbot(compositeId), Access.READ) { ident =>
      val q = if (attribute.isDefined) AgbotsTQ.getAttribute(compositeId, attribute.get) else null
      validate(attribute.isEmpty || q!= null, ExchMsg.translate("agbot.name.not.in.resource")) {
        complete({
          logger.debug(s"GET /orgs/$orgid/agbots/$id identity: ${ident.creds.id}") // can't display the whole ident object, because that contains the pw/token
          attribute match {
            case Some(attr) => // Only returning 1 attr of the agbot
              db.run(q.result).map({ list =>
                //logger.debug("GET /orgs/"+orgid+"/agbots/"+id+" attribute result: "+list.toString)
                if (list.nonEmpty) (HttpCode.OK, GetAgbotAttributeResponse(attr, list.head.toString))
                else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))     // validateAccessToAgbot() will return ApiRespType.NOT_FOUND to the client so do that here for consistency
              })

            case None => // Return the whole agbot, including the services
              db.run(AgbotsTQ.getAgbot(compositeId).result).map({ list =>
                logger.debug(s"GET /orgs/$orgid/agbots result size: ${list.size}")
                val agbots: Map[String, Agbot] = list.map(e => e.id -> e.toAgbot(ident.isSuperUser)).toMap
                val code: StatusCode = if (agbots.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
                (code, GetAgbotsResponse(agbots, 0))
              })
          }
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  // =========== PUT /orgs/{organization}/agbots/{id} ===============================
  @PUT
  @Path("{id}")
  @Operation(
    summary = "Add/updates an agbot",
    description = "Adds a new agbot (Agreement Bot) to the exchange DB, or updates an existing agbot. This must be called by the user to add an agbot, and then can be called by that user or agbot to update itself.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "id",
        in = ParameterIn.PATH,
        description = "ID of the agbot."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "token": "abc",
  "name": "myagbot",
  "publicKey": "ABCDEF"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PutAgbotsRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "200",
        description = "resource add/updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
      ),
      new responses.ApiResponse(
        responseCode = "400",
        description = "bad input"
      ),
      new responses.ApiResponse(
        responseCode = "401",
        description = "invalid credentials"
      ),
      new responses.ApiResponse(
        responseCode = "403",
        description = "access denied"
      ),
      new responses.ApiResponse(
        responseCode = "404",
        description = "not found"
      )
    )
  )
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot")
  def agbotPutRoute: Route = (path("orgs" / Segment / "agbots" / Segment) & put & entity(as[PutAgbotsRequest])) { (orgid, id, reqBody) =>
    logger.debug(s"Doing PUT /orgs/$orgid/agbots/$id")
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TAgbot(compositeId), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val owner: String = ident match { case IUser(creds) => creds.id; case _ => "" }
          val hashedTok: String = Password.hash(reqBody.token)
          db.run(AgbotsTQ.getNumOwned(owner).result.flatMap({ xs =>
            logger.debug("PUT /orgs/"+orgid+"/agbots/"+id+" num owned: "+xs)
            val numOwned: Int = xs
            val maxAgbots: Int = ExchConfig.getInt("api.limits.maxAgbots")
            if (maxAgbots == 0 || numOwned <= maxAgbots || owner == "") {    // when owner=="" we know it is only an update, otherwise we are not sure, but if they are already over the limit, stop them anyway
              val action = if (owner == "") reqBody.getDbUpdate(compositeId, orgid, owner, hashedTok) else reqBody.getDbUpsert(compositeId, orgid, owner, hashedTok)
              action.asTry
            }
            else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.max.limit.of.agbots", maxAgbots) )).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug(s"PUT /orgs/$orgid/agbots/$id result: $v")
              ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOT, ResChangeOperation.CREATEDMODIFIED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug(s"PUT /orgs/$orgid/agbots/$id updated in changes table: $v")
              AuthCache.putAgbotAndOwner(compositeId, hashedTok, reqBody.token, owner)
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.added.updated")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.not.inserted.or.updated", compositeId, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.not.inserted.or.updated", compositeId, t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== PATCH /orgs/{organization}/agbots/{id} ===============================
  @PATCH
  @Path("{id}")
  @Operation(summary = "Updates 1 attribute of an agbot", description = "Updates some attributes of an agbot. This can be called by the user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot.")),
    requestBody = new RequestBody(description = "Specify only **one** of the following attributes", required = true, content = Array(new Content(examples = Array(
        new ExampleObject(
          value = """{
  "token": "abc",
  "name": "myagbot",
  "msgEndPoint": "string",
  "publicKey": "ABCDEF"
}
"""
        )
      ),
      mediaType = "application/json",
      schema = new Schema(implementation = classOf[PatchAgbotsRequest])
    ))),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "resource updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot")
  def agbotPatchRoute: Route = (path("orgs" / Segment / "agbots" / Segment) & patch & entity(as[PatchAgbotsRequest])) { (orgid, id, reqBody) =>
    logger.debug(s"Doing PATCH /orgs/$orgid/agbots/$id")
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TAgbot(compositeId), Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val hashedTok: String = if (reqBody.token.isDefined) Password.hash(reqBody.token.get) else "" // hash the token if that is what is being updated
          val (action, attrName) = reqBody.getDbUpdate(compositeId, orgid, hashedTok)
          if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.agbot.attribute.specified")))
          else db.run(action.transactionally.asTry.flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug(s"PATCH /orgs/$orgid/agbots/$id result: $v")
              if (v.asInstanceOf[Int] > 0) { // there were no db errors, but determine if it actually found it or not
                if (reqBody.token.isDefined) AuthCache.putAgbot(compositeId, hashedTok, reqBody.token.get) // We do not need to run putOwner because patch does not change the owner
                ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOT, ResChangeOperation.MODIFIED).insert.asTry
              } else {
                DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("agbot.not.found", compositeId))).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug(s"PATCH /orgs/$orgid/agbots/$id updated in changes table: $v")
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.attribute.updated", attrName, compositeId)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.not.inserted.or.updated", compositeId, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.not.inserted.or.updated", compositeId, t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/agbots/{id} ===============================
  @DELETE
  @Path("{id}")
  @Operation(summary = "Deletes an agbot", description = "Deletes an agbot (Agreement Bot), and deletes the agreements stored for this agbot (but does not actually cancel the agreements between the nodes and agbot). Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot")
  def agbotDeleteRoute: Route = (path("orgs" / Segment / "agbots" / Segment) & delete) { (orgid, id) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/agbots/$id")
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TAgbot(compositeId), Access.WRITE) { _ =>
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(AgbotsTQ.getAgbot(compositeId).delete.transactionally.asTry.flatMap({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              logger.debug(s"DELETE /orgs/$orgid/agbots/$id result: $v")
              AuthCache.removeAgbotAndOwner(compositeId)
              ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOT, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("agbot.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug(s"DELETE /orgs/$orgid/agbots/$id updated in changes table: $v")
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }
   */

  /*// =========== POST /orgs/{organization}/agbots/{agreementbot}/heartbeat ===============================
  @POST
  @Path("{id}/heartbeat")
  @Operation(summary = "Tells the exchange this agbot is still operating", description = "Lets the exchange know this agbot is still active. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot to be updated.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot")
  def agbotHeartbeatRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "heartbeat") & post) { (orgid, id) =>
    logger.debug(s"Doing POST /orgs/$orgid/users/$id/heartbeat")
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TAgbot(compositeId),Access.WRITE) { _ =>
      complete({
        db.run(AgbotsTQ.getLastHeartbeat(compositeId).update(ApiTime.nowUTC).asTry).map({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              logger.debug(s"POST /orgs/$orgid/users/$id/heartbeat result: $v")
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.updated")))
            } else {
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("agbot.not.found", compositeId)))
            }
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.not.updated", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.not.updated", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }*/

  /*
  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{organization}/agbots/{agreementbot}/patterns ================================ */
  @GET
  @Path("{id}/patterns")
  @Operation(summary = "Returns all patterns served by this agbot", description = "Returns all patterns that this agbot is finding nodes for to make agreements with them. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
            new Content(
              examples = Array(
                new ExampleObject(
                  value ="""{
  "patterns": {
    "pattern1": {
      "patternOrgid": "string",
      "pattern": "string",
      "nodeOrgid": "string",
      "lastUpdated": "2019-05-14T16:34:36.295Z[UTC]"
    },
    "pattern2": {
      "patternOrgid": "string",
      "pattern": "string",
      "nodeOrgid": "string",
      "lastUpdated": "2019-05-14T16:34:36.397Z[UTC]"
    }
  }
}
"""
                )
              ),
              mediaType = "application/json",
              schema = new Schema(implementation = classOf[GetAgbotPatternsResponse])
            )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/pattern")
  def agbotGetPatternsRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "patterns") & get) { (orgid, id) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TAgbot(compositeId),Access.READ) { _ =>
      complete({
        db.run(AgbotPatternsTQ.getPatterns(compositeId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/agbots/$id/patterns result size: ${list.size}")
          val patterns: Map[String, AgbotPattern] = list.map(e => e.patId -> e.toAgbotPattern).toMap
          val code: StatusCode = if (patterns.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetAgbotPatternsResponse(patterns))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{organization}/agbots/{agreementbot}/patterns/{patid} ================================ */
  @GET
  @Path("{id}/patterns/{patid}")
  @Operation(summary = "Returns a pattern this agbot is serving", description = "Returns the pattern with the specified patid for the specified agbot id. The patid should be in the form patternOrgid_pattern. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot."),
    new Parameter(name = "patid", in = ParameterIn.PATH, description = "ID of the pattern.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "patterns": {
    "patternname": {
      "patternOrgid": "string",
      "pattern": "string",
      "nodeOrgid": "string",
      "lastUpdated": "2019-05-14T16:34:36.397Z[UTC]"
    }
  }
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetAgbotPatternsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/pattern")
  def agbotGetPatternRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "patterns" / Segment) & get) { (orgid, id, patId) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TAgbot(compositeId),Access.READ) { _ =>
      complete({
        db.run(AgbotPatternsTQ.getPattern(compositeId, patId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/agbots/$id/patterns/$patId result size: ${list.size}")
          val patterns: Map[String, AgbotPattern] = list.map(e => e.patId -> e.toAgbotPattern).toMap
          val code: StatusCode = if (patterns.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetAgbotPatternsResponse(patterns))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{organization}/agbots/{agreementbot}/patterns ===============================
  @POST
  @Path("{id}/patterns")
  @Operation(
      summary = "Adds a pattern that the agbot should serve", 
      description = "Adds a new pattern and node org that this agbot should find nodes for to make agreements with them. This is called by the owning user or the agbot to give their information about the pattern.",
    parameters = Array(
      new Parameter(
          name = "organization",
          in = ParameterIn.PATH, 
          description = "Organization id."),
      new Parameter(
          name = "id", 
          in = ParameterIn.PATH, 
          description = "ID of the agbot to be updated.")),
    requestBody = new RequestBody(
        content = Array(
            new Content(
                examples = Array(
                    new ExampleObject(
                        value = """{
  "patternOrgid": "string",
  "pattern": "string",
  "nodeOrgid": "string"
}"""
                    )
                ), 
                mediaType = "application/json", 
                schema = new Schema(implementation = classOf[PostAgbotPatternRequest])
                )
            ), 
        required = true
        ),
    responses = Array(
      new responses.ApiResponse(
          responseCode = "201", 
          description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(
          responseCode = "401", 
          description = "invalid credentials"),
      new responses.ApiResponse(
          responseCode = "403", 
          description = "access denied"),
      new responses.ApiResponse(
          responseCode = "404", 
          description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/pattern")
  def agbotPostPatRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "patterns") & post & entity(as[PostAgbotPatternRequest])) { (orgid, id, reqBody) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TAgbot(compositeId),Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val patId: String = reqBody.formId
          db.run(PatternsTQ.getPattern(OrgAndId(reqBody.patternOrgid,reqBody.pattern).toString).length.result.asTry.flatMap({
            case Success(num) =>
              logger.debug("POST /orgs/" + orgid + "/agbots/" + id + "/patterns pattern validation: " + num)
              if (num > 0 || reqBody.pattern == "*") reqBody.toAgbotPatternRow(compositeId, patId).insert.asTry
              else DBIO.failed(new Throwable(ExchMsg.translate("pattern.not.in.exchange"))).asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("POST /orgs/" + orgid + "/agbots/" + id + "/patterns result: " + v)
              ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTPATTERNS, ResChangeOperation.CREATED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/agbots/" + id + "/patterns updated in changes table: " + v)
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.added", patId)))
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("pattern.foragbot.already.exists", patId, compositeId)))
              else if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.not.inserted", patId, compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.not.inserted", patId, compositeId, t.getServerErrorMessage))
            case Failure(t) =>
              if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.not.inserted", patId, compositeId, t.getMessage)))
              else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.inserted", patId, compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/agbots/{agreementbot}/patterns ===============================
  @DELETE
  @Path("{id}/patterns")
  @Operation(summary = "Deletes all patterns of an agbot", description = "Deletes all of the current patterns that this agbot was serving. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/pattern")
  def agbotDeletePatsRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "patterns") & delete) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TAgbot(compositeId), Access.WRITE) { _ =>
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(AgbotPatternsTQ.getPatterns(compositeId).delete.asTry.flatMap({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              // Add the resource to the resourcechanges table
              logger.debug("DELETE /agbots/" + id + "/patterns result: " + v)
              ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTPATTERNS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("patterns.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /agbots/" + id + "/patterns updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("patterns.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("patterns.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("patterns.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/agbots/{agreementbot}/patterns/{patid} ===============================
  @DELETE
  @Path("{id}/patterns/{patid}")
  @Operation(summary = "Deletes a pattern of an agbot", description = "Deletes a pattern that this agbot was serving. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot."),
    new Parameter(name = "patid", in = ParameterIn.PATH, description = "ID of the pattern to be deleted.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/pattern")
  def agbotDeletePatRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "patterns" / Segment) & delete) { (orgid, id, patId) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TAgbot(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(AgbotPatternsTQ.getPattern(compositeId,patId).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /agbots/" + id + "/patterns/" + patId + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTPATTERNS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.not.found", patId, compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /agbots/" + id + "/patterns/" + patId + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.pattern.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.not.deleted", patId, compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("pattern.not.deleted", patId, compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }
   */

  /*
  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{organization}/agbots/{agreementbot}/businesspols ================================ */
  @GET
  @Path("{id}/businesspols")
  @Operation(summary = "Returns all business policies served by this agbot", description = "Returns all business policies that this agbot is finding nodes for to make agreements with them. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
"businessPols" : {
  "buspolid": {
    "businessPolOrgid": "string",
    "businessPol": "string",
    "nodeOrgid" : "string",
    "lastUpdated": "string"
  }
}
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetAgbotBusinessPolsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/policy")
  def agbotGetBusPolsRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "businesspols") & get) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TAgbot(compositeId),Access.READ) { _ =>
      complete({
        db.run(AgbotBusinessPolsTQ.getBusinessPols(compositeId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/agbots/$id/businesspols result size: ${list.size}")
          val businessPols = list.map(e => e.busPolId -> e.toAgbotBusinessPol).toMap
          val code = if (businessPols.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetAgbotBusinessPolsResponse(businessPols))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{organization}/agbots/{agreementbot}/businesspols/{buspolid} ================================ */
  @GET
  @Path("{id}/businesspols/{buspolid}")
  @Operation(summary = "Returns a business policy this agbot is serving", description = "Returns the business policy with the specified patid for the specified agbot id. The patid should be in the form businessPolOrgid_businessPol. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot."),
      new Parameter(name = "buspolid", in = ParameterIn.PATH, description = "ID of the business policy.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
"businessPols" : {
  "buspolid": {
    "businessPolOrgid": "string",
    "businessPol": "string",
    "nodeOrgid" : "string",
    "lastUpdated": "string"
  }
}
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetAgbotBusinessPolsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/policy")
  def agbotGetBusPolRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "businesspols" / Segment) & get) { (orgid, id, busPolId) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TAgbot(compositeId),Access.READ) { _ =>
      complete({
        db.run(AgbotBusinessPolsTQ.getBusinessPol(compositeId, busPolId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/agbots/$id/businesspols/$busPolId result size: ${list.size}")
          val businessPols = list.map(e => e.busPolId -> e.toAgbotBusinessPol).toMap
          val code = if (businessPols.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetAgbotBusinessPolsResponse(businessPols))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{organization}/agbots/{agreementbot}/businesspols ===============================
  @POST
  @Path("{id}/businesspols")
  @Operation(
    summary = "Adds a business policy that the agbot should serve",
    description = "Adds a new business policy and node org that this agbot should find nodes for to make agreements with them. This is called by the owning user or the agbot to give their information about the business policy.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "id",
        in = ParameterIn.PATH,
        description = "ID of the agbot to be updated."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "businessPolOrgid": "string",
  "businessPol": "string",
  "nodeOrgid": "string"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostAgbotBusinessPolRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
      ),
      new responses.ApiResponse(
        responseCode = "401",
        description = "invalid credentials"
      ),
      new responses.ApiResponse(
        responseCode = "403",
        description = "access denied"
      ),
      new responses.ApiResponse(
        responseCode = "404",
        description = "not found"
      )
    )
  )
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/policy")
  def agbotPostBusPolRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "businesspols") & post & entity(as[PostAgbotBusinessPolRequest])) { (orgid, id, reqBody) =>
    val compositeId = OrgAndId(orgid, id).toString
    exchAuth(TAgbot(compositeId),Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val patId = reqBody.formId
          db.run(BusinessPoliciesTQ.getBusinessPolicy(OrgAndId(reqBody.businessPolOrgid,reqBody.businessPol).toString).length.result.asTry.flatMap({
            case Success(num) =>
              logger.debug("POST /orgs/" + orgid + "/agbots/" + id + "/businesspols business policy validation: " + num)
              if (num > 0 || reqBody.businessPol == "*") reqBody.toAgbotBusinessPolRow(compositeId, patId).insert.asTry
              else DBIO.failed(new Throwable(ExchMsg.translate("buspol.not.in.exchange"))).asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("POST /orgs/" + orgid + "/agbots/" + id + "/businesspols result: " + v)
              ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTBUSINESSPOLS, ResChangeOperation.CREATED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/agbots/" + id + "/businesspols updated in changes table: " + v)
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.added", patId)))
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("buspol.foragbot.already.exists", patId, compositeId)))
              else if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("buspol.not.inserted", patId, compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspol.not.inserted", patId, compositeId, t.getServerErrorMessage))
            case Failure(t) =>
              if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("buspol.not.inserted", patId, compositeId, t.getMessage)))
              else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.not.inserted", patId, compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/agbots/{agreementbot}/businesspols ===============================
  @DELETE
  @Path("{id}/businesspols")
  @Operation(summary = "Deletes all business policies of an agbot", description = "Deletes all of the current business policies that this agbot was serving. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/policy")
  def agbotDeleteBusPolsRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "businesspols") & delete) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TAgbot(compositeId), Access.WRITE) { _ =>
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(AgbotBusinessPolsTQ.getBusinessPols(compositeId).delete.asTry.flatMap({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              // Add the resource to the resourcechanges table
              logger.debug("DELETE /agbots/" + id + "/businesspols result: " + v)
              ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTBUSINESSPOLS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("buspols.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /agbots/" + id + "/businesspols updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspols.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspols.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("buspols.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/agbots/{agreementbot}/businesspols/{buspolid} ===============================
  @DELETE
  @Path("{id}/businesspols/{buspolid}")
  @Operation(summary = "Deletes a business policy of an agbot", description = "Deletes a business policy that this agbot was serving. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot."),
      new Parameter(name = "buspolid", in = ParameterIn.PATH, description = "ID of the business policy to be deleted.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/policy")
  def agbotDeleteBusPolRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "businesspols" / Segment) & delete) { (orgid, id, busPolId) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TAgbot(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(AgbotBusinessPolsTQ.getBusinessPol(compositeId,busPolId).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /agbots/" + id + "/businesspols/" + busPolId + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTBUSINESSPOLS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("buspol.not.found", busPolId, compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /agbots/" + id + "/businesspols/" + busPolId + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspol.not.deleted", busPolId, compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("buspol.not.deleted", busPolId, compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }
   */

  /*
  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{organization}/agbots/{agreementbot}/agreements ================================ */
  @GET
  @Path("{id}/agreements")
  @Operation(summary = "Returns all agreements this agbot is in", description = "Returns all agreements that this agbot is part of. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "agreements": {
    "agreementname": {
      "service": {
        "orgid": "string",
        "pattern": "string",
        "url": "string"
      },
      "state": "string",
      "lastUpdated": "2019-05-14T16:34:37.173Z[UTC]",
      "dataLastReceived": ""
    }
  },
  "lastIndex": 0
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetAgbotAgreementsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/agreement")
  def agbotGetAgreementsRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "agreements") & get) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TAgbot(compositeId),Access.READ) { _ =>
      complete({
        db.run(AgbotAgreementsTQ.getAgreements(compositeId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/agbots/$id/agreements result size: ${list.size}")
          val agreements = list.map(e => e.agrId -> e.toAgbotAgreement).toMap
          val code = if (agreements.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetAgbotAgreementsResponse(agreements, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{organization}/agbots/{agreementbot}/agreements/{agid} ================================ */
  @GET
  @Path("{id}/agreements/{agid}")
  @Operation(summary = "Returns an agreement for an agbot", description = "Returns the agreement with the specified agid for the specified agbot id. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot."),
      new Parameter(name = "agid", in = ParameterIn.PATH, description = "ID of the agreement.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "agreements": {
    "agreementname": {
      "service": {
        "orgid": "string",
        "pattern": "string",
        "url": "string"
      },
      "state": "string",
      "lastUpdated": "2019-05-14T16:34:37.173Z[UTC]",
      "dataLastReceived": ""
    }
  },
  "lastIndex": 0
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetAgbotAgreementsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/agreement")
  def agbotGetAgreementRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "agreements" / Segment) & get) { (orgid, id, agrId) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TAgbot(compositeId),Access.READ) { _ =>
      complete({
        db.run(AgbotAgreementsTQ.getAgreement(compositeId, agrId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/agbots/$id/agreements/$agrId result size: ${list.size}")
          val agreements = list.map(e => e.agrId -> e.toAgbotAgreement).toMap
          val code = if (agreements.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetAgbotAgreementsResponse(agreements, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== PUT /orgs/{organization}/agbots/{agreementbot}/agreements/{agid} ===============================
  @PUT
  @Path("{id}/agreements/{agid}")
  @Operation(
    summary = "Adds/updates an agreement of an agbot",
    description = "Adds a new agreement of an agbot to the exchange DB, or updates an existing agreement. This is called by the owning user or the agbot to give their information about the agreement.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "id",
        in = ParameterIn.PATH,
        description = "ID of the agbot to be updated."
      ),
      new Parameter(
        name = "agid",
        in = ParameterIn.PATH,
        description = "ID of the agreement to be added/updated."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "service": {
    "orgid": "string",
    "pattern": "string",
    "url": "string"
  },
  "state": "string"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PutAgbotAgreementRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
      ),
      new responses.ApiResponse(
        responseCode = "401",
        description = "invalid credentials"
      ),
      new responses.ApiResponse(
        responseCode = "403",
        description = "access denied"
      ),
      new responses.ApiResponse(
        responseCode = "404",
        description = "not found"
      )
    )
  )
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/agreement")
  def agbotPutAgreementRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "agreements" / Segment) & put & entity(as[PutAgbotAgreementRequest])) { (orgid, id, agrId, reqBody) =>
    val compositeId = OrgAndId(orgid, id).toString
    exchAuth(TAgbot(compositeId),Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val maxAgreements = ExchConfig.getInt("api.limits.maxAgreements")
          val getNumOwnedDbio = if (maxAgreements == 0) DBIO.successful(0) else AgbotAgreementsTQ.getNumOwned(compositeId).result // avoid DB read for this if there is no max
          db.run(getNumOwnedDbio.flatMap({ xs =>
            if (maxAgreements != 0) logger.debug("PUT /orgs/"+orgid+"/agbots/"+id+"/agreements/"+agrId+" num owned: "+xs)
            val numOwned = xs
            // we are not sure if this is create or update, but if they are already over the limit, stop them anyway
            if (maxAgreements == 0 || numOwned <= maxAgreements) reqBody.toAgbotAgreementRow(compositeId, agrId).upsert.asTry
            else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.max.limit.of.agreements", maxAgreements) )).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("PUT /orgs/" + orgid + "/agbots/" + id + "/agreements/" + agrId + " result: " + v)
              ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTAGREEMENTS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/agbots/" + id + "/agreements/" + agrId + " updated in changes table: " + v)
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("agreement.added.or.updated")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agreement.not.inserted.or.updated", agrId, compositeId, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agreement.not.inserted.or.updated", agrId, compositeId, t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/agbots/{agreementbot}/agreements ===============================
  @DELETE
  @Path("{id}/agreements")
  @Operation(summary = "Deletes all agreements of an agbot", description = "Deletes all of the current agreements of an agbot. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/agreement")
  def agbotDeleteAgreementsRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "agreements") & delete) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TAgbot(compositeId), Access.WRITE) { _ =>
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(AgbotAgreementsTQ.getAgreements(compositeId).delete.asTry.flatMap({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              // Add the resource to the resourcechanges table
              logger.debug("DELETE /agbots/" + id + "/agreements result: " + v)
              ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTAGREEMENTS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("no.agreements.found.for.agbot", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /agbots/" + id + "/agreements updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.agreements.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.agreements.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.agreements.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/agbots/{agreementbot}/agreements/{agid} ===============================
  @DELETE
  @Path("{id}/agreements/{agid}")
  @Operation(summary = "Deletes an agreement of an agbot", description = "Deletes an agreement of an agbot. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot."),
      new Parameter(name = "agid", in = ParameterIn.PATH, description = "ID of the agreement to be deleted.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/agreement")
  def agbotDeleteAgreementRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "agreements" / Segment) & delete) { (orgid, id, agrId) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TAgbot(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(AgbotAgreementsTQ.getAgreement(compositeId,agrId).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /agbots/" + id + "/agreements/" + agrId + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTAGREEMENTS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("agreement.for.agbot.not.found", agrId, compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /agbots/" + id + "/agreements/" + agrId + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.agreement.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agreement.for.agbot.not.deleted", agrId, compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agreement.for.agbot.not.deleted", agrId, compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }
   */
  
  /*
  // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  // =========== POST /orgs/{organization}/agbots/{agreementbot}/msgs ===============================
  @POST
  @Path("{id}/msgs")
  @Operation(
    summary = "Sends a msg from a node to an agbot",
    description = "Sends a msg from a node to an agbot. The node must 1st sign the msg (with its private key) and then encrypt the msg (with the agbots's public key). Can be run by any node.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "id",
        in = ParameterIn.PATH,
        description = "ID of the agbot to send a message to."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "message": "VW1RxzeEwTF0U7S96dIzSBQ/hRjyidqNvBzmMoZUW3hpd3hZDvs",
  "ttl": 86400
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostAgbotsMsgsRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
      ),
      new responses.ApiResponse(
        responseCode = "401",
        description = "invalid credentials"
      ),
      new responses.ApiResponse(
        responseCode = "403",
        description = "access denied"
      ),
      new responses.ApiResponse(
        responseCode = "404",
        description = "not found"
      )
    )
  )
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/message")
  def agbotPostMsgRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "msgs") & post & entity(as[PostAgbotsMsgsRequest])) { (orgid, id, reqBody) =>
    val compositeId = OrgAndId(orgid, id).toString
    exchAuth(TAgbot(compositeId),Access.SEND_MSG_TO_AGBOT) { ident =>
      complete({
        val nodeId = ident.creds.id      //somday: handle the case where the acls allow users to send msgs
        var msgNum = ""
        val maxMessagesInMailbox = ExchConfig.getInt("api.limits.maxMessagesInMailbox")
        val getNumOwnedDbio = if (maxMessagesInMailbox == 0) DBIO.successful(0) else AgbotMsgsTQ.getNumOwned(compositeId).result // avoid DB read for this if there is no max
        // Remove msgs whose TTL is past, then check the mailbox is not full, then get the node publicKey, then write the agbotmsgs row, all in the same db.run thread
        db.run(getNumOwnedDbio.flatMap({ xs =>
          if (maxMessagesInMailbox != 0) logger.debug("POST /orgs/"+orgid+"/agbots/"+id+"/msgs mailbox size: "+xs)
          val mailboxSize = xs
          if (maxMessagesInMailbox == 0 || mailboxSize < maxMessagesInMailbox) NodesTQ.getPublicKey(nodeId).result.asTry
          else DBIO.failed(new DBProcessingError(HttpCode.BAD_GW, ApiRespType.BAD_GW, ExchMsg.translate("agbot.mailbox.full", compositeId, maxMessagesInMailbox) )).asTry
        }).flatMap({
          case Success(v) =>
            logger.debug("POST /orgs/" + orgid + "/agbots/" + id + "/msgs node publickey result: " + v)
            val nodePubKey = v.head
            if (nodePubKey != "") AgbotMsgRow(0, compositeId, nodeId, nodePubKey, reqBody.message, ApiTime.nowUTC, ApiTime.futureUTC(reqBody.ttl)).insert.asTry
            else DBIO.failed(new DBProcessingError(HttpCode.BAD_INPUT, ApiRespType.BAD_INPUT, ExchMsg.translate("agbot.message.invalid.input"))).asTry
          case Failure(t) =>
            DBIO.failed(t).asTry // rethrow the error to the next step
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("POST /orgs/{organization}/agbots/" + id + "/msgs write row result: " + v)
            msgNum = v.toString
            ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTMSGS, ResChangeOperation.CREATED).insert.asTry
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("POST /orgs/{organization}/agbots/" + id + "/msgs updated in changes table: " + v)
            (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, "agbot msg " + msgNum + " inserted"))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            if (ExchangePosgtresErrorHandling.isKeyNotFoundError(t)) (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("agbot.message.agbotid.not.found", compositeId, t.getMessage)))
            else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.message.not.inserted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.message.not.inserted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{organization}/agbots/{agreementbot}/msgs ================================ */
  @GET
  @Path("{id}/msgs")
  @Operation(summary = "Returns all msgs sent to this agbot", description = "Returns all msgs that have been sent to this agbot. They will be returned in the order they were sent. All msgs that have been sent to this agbot will be returned, unless the agbot has deleted some, or some are past their TTL. Can be run by a user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot."),
      new Parameter(name = "maxmsgs", in = ParameterIn.QUERY, required = false, description = "Maximum number of messages returned. If this is less than the number of messages available, the oldest messages are returned. Defaults to unlimited.")
    ),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[GetAgbotMsgsResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/message")
  def agbotGetMsgsRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "msgs") & get & parameter("maxmsgs".?)) { (orgid, id, maxmsgsStrOpt) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TAgbot(compositeId),Access.READ) { _ =>
      validate(Try(maxmsgsStrOpt.map(_.toInt)).isSuccess, ExchMsg.translate("invalid.int.for.name", maxmsgsStrOpt.getOrElse(""), "maxmsgs")) {
        complete({
          // Set the query, including maxmsgs
          var maxIntOpt: Option[Int] = maxmsgsStrOpt.map(_.toInt)
          var query = AgbotMsgsTQ.getMsgs(compositeId).sortBy(_.msgId)
          if (maxIntOpt.getOrElse(0) > 0) query = query.take(maxIntOpt.get)
          // Get the msgs for this agbot
          db.run(query.result).map({ list =>
            logger.debug("GET /orgs/"+orgid+"/agbots/"+id+"/msgs result size: "+list.size)
            //logger.debug("GET /orgs/"+orgid+"/agbots/"+id+"/msgs result: "+list.toString)
            val msgs: List[AgbotMsg] = list.map(_.toAgbotMsg).toList
            val code: StatusCode = if (msgs.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetAgbotMsgsResponse(msgs, 0))
          })
        }) // end of complete
      }
    } // end of exchAuth
  }
  
  /* ====== GET /orgs/{organization}/agbots/{agreementbot}/msgs/{msgid} ================================ */
  @GET
  @Path("{id}/msgs/{msgid}")
  @Operation(description = "Returns A specific message that has been sent to this agreement-bot. Deleted/post-TTL (Time To Live) messages will not be returned. Can be run by a user or the agbot.",
             parameters = Array(new Parameter(description = "Agreement-bot id.",
                                              in = ParameterIn.PATH,
                                              name = "node",
                                              required = true),
                                new Parameter(description = "Message id.",
                                              in = ParameterIn.PATH,
                                              name = "msgid",
                                              required = true),
                                new Parameter(description = "Organization id.",
                                              in = ParameterIn.PATH,
                                              name = "organization",
                                              required = true)),
             responses = Array(new responses.ApiResponse(content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[GetAgbotMsgsResponse]))),
                                                         description = "response body",
                                                         responseCode = "200"),
                               new responses.ApiResponse(description = "bad input",
                                                         responseCode = "400"),
                               new responses.ApiResponse(description = "invalid credentials",
                                                         responseCode = "401"),
                               new responses.ApiResponse(description = "access denied",
                                                         responseCode = "403"),
                               new responses.ApiResponse(description = "not found",
                                                         responseCode = "404")),
             summary = "Returns A specific message that has been sent to this agreement-bot.")
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/message")
  def agbotGetMsgRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "msgs" / Segment) & get) {
    (orgid, id, msgid) =>
      val compositeId = OrgAndId(orgid, id).toString
      logger.debug("msgid.toInt: " + msgid.toInt)
      
      exchAuth(TAgbot(compositeId), Access.READ) {
        _ =>
          complete({
            db.run(
              AgbotMsgsTQ.getMsg(agbotId = compositeId,
                                 msgId = msgid.toInt)
                         .result
                         .map(
                           result =>
                             GetAgbotMsgsResponse(lastIndex = 0,
                                                  messages = result.map(
                                                               message =>
                                                                 AgbotMsg(message = message.message,
                                                                          msgId = message.msgId,
                                                                          nodeId = message.nodeId,
                                                                          nodePubKey = message.nodePubKey,
                                                                          timeExpires = message.timeExpires,
                                                                          timeSent = message.timeSent)).toList))
                         .asTry
              )
              .map({
                case Success(message) =>
                  if(message.messages.nonEmpty)
                    (HttpCode.OK, message)
                  else
                    (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
                case Failure(t) =>
                  (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", t.getMessage)))
              })
          }) // end of complete
      } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/agbots/{agreementbot}/msgs/{msgid} ===============================
  @DELETE
  @Path("{id}/msgs/{msgid}")
  @Operation(summary = "Deletes a msg of an agbot", description = "Deletes a message that was sent to an agbot. This should be done by the agbot after each msg is read. Can be run by the owning user or the agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot."),
      new Parameter(name = "msgid", in = ParameterIn.PATH, description = "ID of the msg to be deleted.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/message")
  def agbotDeleteMsgRoute: Route = (path("orgs" / Segment / "agbots" / Segment / "msgs" / Segment) & delete) { (orgid, id, msgIdStr) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TAgbot(compositeId), Access.WRITE) { _ =>
      complete({
        try {
          val msgId =  msgIdStr.toInt   // this can throw an exception, that's why this whole section is in a try/catch
          db.run(AgbotMsgsTQ.getMsg(compositeId,msgId).delete.asTry).map({
            case Success(v) =>
              logger.debug("DELETE /agbots/" + id + "/msgs/" + msgId + " updated in changes table: " + v)
              (HttpCode.DELETED,  ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.message.deleted")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.message.not.deleted", msgId, compositeId, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.message.not.deleted", msgId, compositeId, t.toString)))
          })
        } catch { case e: Exception => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("msgid.must.be.int", e))) }    // the specific exception is NumberFormatException
      }) // end of complete
    } // end of exchAuth
  }
   */

}
