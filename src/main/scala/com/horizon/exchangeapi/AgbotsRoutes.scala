/** Services routes for all of the /agbots api methods. */
package com.horizon.exchangeapi

import com.horizon.exchangeapi.tables._
import org.json4s._
import org.json4s.jackson.JsonMethods._
//import org.json4s.jackson.Serialization.write
import org.scalatra._
import org.scalatra.swagger._
import org.slf4j._
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}
import scala.util._

//====== These are the input and output structures for /agbots routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/agbots */
case class GetAgbotsResponse(agbots: Map[String,Agbot], lastIndex: Int)
case class GetAgbotAttributeResponse(attribute: String, value: String)

/** Input format for PUT /orgs/{orgid}/agbots/<agbot-id> */
case class PutAgbotsRequest(token: String, name: String, msgEndPoint: Option[String], publicKey: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate(): Unit = {
    if (token == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("token.specified.cannot.be.blank")))
  }

  /** Get the db queries to insert or update the agbot */
  def getDbUpsert(id: String, orgid: String, owner: String): DBIO[_] = AgbotRow(id, orgid, token, name, owner, msgEndPoint.getOrElse(""), ApiTime.nowUTC, publicKey).upsert

  /** Get the db queries to update the agbot */
  def getDbUpdate(id: String, orgid: String, owner: String): DBIO[_] = AgbotRow(id, orgid, token, name, owner, msgEndPoint.getOrElse(""), ApiTime.nowUTC, publicKey).update
}

case class PatchAgbotsRequest(token: Option[String], name: Option[String], msgEndPoint: Option[String], publicKey: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the agbot, and the attribute name being updated. */
  def getDbUpdate(id: String, orgid: String): (DBIO[_],String) = {
    val lastHeartbeat = ApiTime.nowUTC
    //todo: support updating more than 1 attribute
    // find the 1st attribute that was specified in the body and create a db action to update it for this agbot
    token match {
      case Some(token2) => if (token2 == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("token.cannot.be.empty.string")))
        val tok = /*if (Password.isHashed(token2)) token2 else*/ Password.hash(token2)
        return ((for { d <- AgbotsTQ.rows if d.id === id } yield (d.id,d.token,d.lastHeartbeat)).update((id, tok, lastHeartbeat)), "token")
      case _ => ;
    }
    name match { case Some(name2) => return ((for { d <- AgbotsTQ.rows if d.id === id } yield (d.id,d.name,d.lastHeartbeat)).update((id, name2, lastHeartbeat)), "name"); case _ => ; }
    msgEndPoint match { case Some(msgEndPoint2) => return ((for { d <- AgbotsTQ.rows if d.id === id } yield (d.id,d.msgEndPoint,d.lastHeartbeat)).update((id, msgEndPoint2, lastHeartbeat)), "msgEndPoint"); case _ => ; }
    publicKey match { case Some(publicKey2) => return ((for { d <- AgbotsTQ.rows if d.id === id } yield (d.id,d.publicKey,d.lastHeartbeat)).update((id, publicKey2, lastHeartbeat)), "publicKey"); case _ => ; }
    return (null, null)
  }
}


/** Output format for GET /orgs/{orgid}/agbots/{id}/patterns */
case class GetAgbotPatternsResponse(patterns: Map[String,AgbotPattern])

/** Input format for POST /orgs/{orgid}/agbots/{id}/patterns */
case class PostAgbotPatternRequest(patternOrgid: String, pattern: String, nodeOrgid: Option[String]) {
  def toAgbotPattern = AgbotPattern(patternOrgid, pattern, nodeOrgid.getOrElse(patternOrgid), ApiTime.nowUTC)
  def toAgbotPatternRow(agbotId: String, patId: String) = AgbotPatternRow(patId, agbotId, patternOrgid, pattern, nodeOrgid.getOrElse(patternOrgid), ApiTime.nowUTC)
  def formId = patternOrgid + "_" + pattern + "_" + nodeOrgid.getOrElse(patternOrgid)
  def validate() = {}
}


/** Output format for GET /orgs/{orgid}/agbots/{id}/businesspols */
case class GetAgbotBusinessPolsResponse(businessPols: Map[String,AgbotBusinessPol])

/** Input format for POST /orgs/{orgid}/agbots/{id}/businesspols */
case class PostAgbotBusinessPolRequest(businessPolOrgid: String, businessPol: String, nodeOrgid: Option[String]) {
  def toAgbotBusinessPol = AgbotBusinessPol(businessPolOrgid, businessPol, nodeOrgid.getOrElse(businessPolOrgid), ApiTime.nowUTC)
  def toAgbotBusinessPolRow(agbotId: String, busPolId: String) = AgbotBusinessPolRow(busPolId, agbotId, businessPolOrgid, businessPol, nodeOrgid.getOrElse(businessPolOrgid), ApiTime.nowUTC)
  def formId = businessPolOrgid + "_" + businessPol + "_" + nodeOrgid.getOrElse(businessPolOrgid)
  def validate() = {
    val nodeOrg = nodeOrgid.getOrElse(businessPolOrgid)
    if (nodeOrg != businessPolOrgid) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("node.org.must.equal.bus.pol.org")))
  }
}

/** Output format for GET /orgs/{orgid}/agbots/{id}/agreements */
case class GetAgbotAgreementsResponse(agreements: Map[String,AgbotAgreement], lastIndex: Int)

/** Input format for PUT /orgs/{orgid}/agbots/{id}/agreements/<agreement-id> */
case class PutAgbotAgreementRequest(service: AAService, state: String) {
  def validate() = { }

  def toAgbotAgreementRow(agbotId: String, agrId: String) = {
    AgbotAgreementRow(agrId, agbotId, service.orgid, service.pattern, service.url, state, ApiTime.nowUTC, "")
  }
}

case class PostAgbotsIsRecentDataRequest(secondsStale: Int, agreementIds: List[String])     // the strings in the list are agreement ids
case class PostAgbotsIsRecentDataElement(agreementId: String, recentData: Boolean)

case class PostAgreementsConfirmRequest(agreementId: String)


/** Input body for POST /orgs/{orgid}/agbots/{id}/msgs */
case class PostAgbotsMsgsRequest(message: String, ttl: Int)

/** Response for GET /orgs/{orgid}/agbots/{id}/msgs */
case class GetAgbotMsgsResponse(messages: List[AgbotMsg], lastIndex: Int)


/** Implementation for all of the /agbots routes */
trait AgbotsRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  /* ====== GET /orgs/{orgid}/agbots ================================ */
  val getAgbots =
    (apiOperation[GetAgbotsResponse]("getAgbots")
      summary("Returns all agbots")
      description("""Returns all agbots (Agreement Bots) in the exchange DB. Can be run by any user.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or  ID (orgid/agbotid) of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("idfilter", DataType.String, Option[String]("Filter results to only include agbots with this id (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("name", DataType.String, Option[String]("Filter results to only include agbots with this name (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include agbots with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/agbots", operation(getAgbots)) ({
    val orgid = params("orgid")
    val ident = authenticate().authorizeTo(TAgbot(OrgAndId(orgid,"*").toString),Access.READ)
    val superUser = ident.isSuperUser
    val resp = response
    //var q = AgbotsTQ.rows.subquery
    var q = AgbotsTQ.getAllAgbots(orgid)
    params.get("idfilter").foreach(id => { if (id.contains("%")) q = q.filter(_.id like id) else q = q.filter(_.id === id) })
    params.get("name").foreach(name => { if (name.contains("%")) q = q.filter(_.name like name) else q = q.filter(_.name === name) })
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })

    db.run(q.result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/agbots result size: "+list.size)
      val agbots = new MutableHashMap[String,Agbot]
      if (list.nonEmpty) for (a <- list) agbots.put(a.id, a.toAgbot(superUser))
      if (agbots.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetAgbotsResponse(agbots.toMap, 0)
    })
  })

  /* ====== GET /orgs/{orgid}/agbots/{id} ================================ */
  val getOneAgbot =
    (apiOperation[GetAgbotsResponse]("getOneAgbot")
      summary("Returns a agbot")
      description("""Returns the agbot (Agreement Bot) with the specified id in the exchange DB. Can be run by a user or the agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot."), paramType=ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire node resource (including services) will be returned."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/agbots/:id", operation(getOneAgbot)) ({
    val orgid = params("orgid")
    val id = params("id")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,id).toString
    val ident = authenticate().authorizeTo(TAgbot(compositeId),Access.READ)
    val superUser = ident.isSuperUser
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the agbot
        val q = AgbotsTQ.getAttribute(compositeId, attribute)
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("agbot.name.not.in.resource")))
        //        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Agbot attribute name '"+attribute+"' is not an attribute of the agbot resource."))
        db.run(q.result).map({ list =>
          //logger.trace("GET /orgs/"+orgid+"/agbots/"+id+" attribute result: "+list.toString)
          if (list.nonEmpty) {
            resp.setStatus(HttpCode.OK)
            GetAgbotAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("not.found"))     // validateAccessToAgbot() will return ApiResponseType.NOT_FOUND to the client so do that here for consistency
          }
        })

      case None => ;  // Return the whole agbot, including the services
        db.run(AgbotsTQ.getAgbot(compositeId).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/agbots/"+id+" result: "+list.toString)
          val agbots = new MutableHashMap[String,Agbot]
          if (list.nonEmpty) for (a <- list) agbots.put(a.id, a.toAgbot(superUser))
          if (agbots.nonEmpty) resp.setStatus(HttpCode.OK)
          else resp.setStatus(HttpCode.NOT_FOUND)
          GetAgbotsResponse(agbots.toMap, 0)
        })
    }
  })

  // =========== PUT /orgs/{orgid}/agbots/{id} ===============================
  val putAgbots =
    (apiOperation[ApiResponse]("putAgbots")
      summary "Adds/updates a agbot"
      description """Adds a new agbot (Agreement Bot) to the exchange DB, or updates an existing agbot. This must be called by the user to add a agbot, and then can be called by that user or agbot to update itself."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot to be added/updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutAgbotsRequest],
          Option[String]("Agbot object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putAgbots2 = (apiOperation[PutAgbotsRequest]("putAgbots2") summary("a") description("a"))  // for some bizarre reason, the PutAgbotsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/agbots/:id", operation(putAgbots)) ({
    val orgid = params("orgid")
    val id = params("id")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,id).toString
    val ident = authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    val agbot = try { parse(request.body).extract[PutAgbotsRequest] }
    catch {
      case e: Exception => /* Left here for reference, how to make a resource change backward compatible: if (e.getMessage.contains("No usable value for publicKey")) {    // the specific exception is MappingException
          // try parsing again with the old structure
          val agbotOld = try { parse(request.body).extract[PutAgbotsRequestOld] }
          catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
          agbotOld.toPutAgbotsRequest
        }
        else*/ halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e)))
    }
    agbot.validate()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    db.run(AgbotsTQ.getNumOwned(owner).result.flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/agbots/"+id+" num owned: "+xs)
      val numOwned = xs
      val maxAgbots = ExchConfig.getInt("api.limits.maxAgbots")
      if (maxAgbots == 0 || numOwned <= maxAgbots || owner == "") {    // when owner=="" we know it is only an update, otherwise we are not sure, but if they are already over the limit, stop them anyway
        val action = if (owner == "") agbot.getDbUpdate(compositeId, orgid, owner) else agbot.getDbUpsert(compositeId, orgid, owner)
        action.asTry
      }
      else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("over.max.limit.of.agbots", maxAgbots))).asTry
    //todo: insert another map() here to verify that patterns referenced actually exist
    })).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/agbots/"+id+" result: "+xs.toString)
      xs match {
        case Success(_) => AuthCache.agbots.putBoth(Creds(compositeId,agbot.token), owner)    // the token passed in to the cache should be the non-hashed one
          resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("agbot.added.updated"))
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("agbot.not.inserted.or.updated", compositeId, t.getMessage))
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("agbot.not.inserted.or.updated", compositeId, t.toString))
          }
      }
    })
  })

  // =========== PATCH /orgs/{orgid}/agbots/{id} ===============================
  val patchAgbots =
    (apiOperation[Map[String,String]]("patchAgbots")
      summary "Updates 1 attribute of an agbot"
      description """Updates some attributes of an agbot in the exchange DB. This can be called by the user or the agbot."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot to be updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchAgbotsRequest],
          Option[String]("Agbot object that contains attributes to updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val patchAgbots2 = (apiOperation[PatchAgbotsRequest]("patchAgbots2") summary("a") description("a"))  // for some bizarre reason, the PatchAgbotsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  patch("/orgs/:orgid/agbots/:id", operation(patchAgbots)) ({
    val orgid = params("orgid")
    val id = params("id")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,id).toString
    authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    val agbot = try { parse(request.body).extract[PatchAgbotsRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    //logger.trace("PATCH /orgs/"+orgid+"/agbots/"+id+" input: "+agbot.toString)
    val resp = response
    val (action, attrName) = agbot.getDbUpdate(compositeId, orgid)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("no.valid.agbot.attribute.specified")))
    db.run(action.transactionally.asTry).map({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/agbots/"+id+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              agbot.token match { case Some(tok) if (tok != "") => AuthCache.agbots.put(Creds(compositeId, tok)); case _ => ; }    // the token passed in to the cache should be the non-hashed one. We do not need to run putOwner because patch does not change the owner
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("agbot.attribute.updated", attrName, compositeId))
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("agbot.not.found", compositeId))
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("unexpected.result.from.update", e)) }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("agbot.not.inserted.or.updated", compositeId, t.toString))
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/agbots/{id} ===============================
  val deleteAgbots =
    (apiOperation[ApiResponse]("deleteAgbots")
      summary "Deletes a agbot"
      description "Deletes a agbot (Agreement Bot) from the exchange DB, and deletes the agreements stored for this agbot (but does not actually cancel the agreements between the nodes and agbot). Can be run by the owning user or the agbot."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/agbots/:id", operation(deleteAgbots)) ({
    val orgid = params("orgid")
    val id = params("id")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,id).toString
    authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(AgbotsTQ.getAgbot(compositeId).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/agbots/"+id+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.agbots.removeBoth(compositeId)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("agbot.deleted"))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("agbot.not.found", compositeId))
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("agbot.not.deleted", compositeId, t.toString))
        }
    })
  })

  // =========== POST /orgs/{orgid}/agbots/{id}/heartbeat ===============================
  val postAgbotsHeartbeat =
    (apiOperation[ApiResponse]("postAgbotsHeartbeat")
      summary "Tells the exchange this agbot is still operating"
      description "Lets the exchange know this agbot is still active. Can be run by the owning user or the agbot."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot to be updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  post("/orgs/:orgid/agbots/:id/heartbeat", operation(postAgbotsHeartbeat)) ({
    val orgid = params("orgid")
    val id = params("id")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,id).toString
    authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    val resp = response
    db.run(AgbotsTQ.getLastHeartbeat(compositeId).update(ApiTime.nowUTC).asTry).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/agbots/"+id+"/heartbeat result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.POST_OK)
              ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("agbot.updated"))
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("agbot.not.found", compositeId))
            }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("agbot.not.updated", compositeId, t.toString))
      }
    })
  })

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/agbots/{id}/patterns ================================ */
  val getAgbotPatterns =
    (apiOperation[GetAgbotPatternsResponse]("getAgbotPatterns")
      summary("Returns all patterns served by this agbot")
      description("""Returns all patterns that this agbot is finding nodes for to make agreements with them. Can be run by the owning user or the agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot."), paramType=ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/agbots/:id/patterns", operation(getAgbotPatterns)) ({
    val orgid = params("orgid")
    val id = params("id")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,id).toString
    authenticate().authorizeTo(TAgbot(compositeId),Access.READ)
    val resp = response
    db.run(AgbotPatternsTQ.getPatterns(compositeId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/agbots/"+id+"/patterns result size: "+list.size)
      //logger.trace("GET /orgs/"+orgid+"/agbots/"+id+"/patterns result: "+list.toString)
      val patterns = new MutableHashMap[String, AgbotPattern]
      if (list.nonEmpty) for (e <- list) { patterns.put(e.patId, e.toAgbotPattern) }
      if (patterns.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetAgbotPatternsResponse(patterns.toMap)
    })
  })

  /* ====== GET /orgs/{orgid}/agbots/{id}/patterns/{patid} ================================ */
  val getOneAgbotPattern =
    (apiOperation[GetAgbotPatternsResponse]("getOneAgbotPattern")
      summary("Returns a pattern this agbot is serving")
      description("""Returns the pattern with the specified patid for the specified agbot id. The patid should be in the form patternOrgid_pattern. Can be run by the owning user or the agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot."), paramType=ParamType.Path),
        Parameter("patid", DataType.String, Option[String]("ID of the pattern."), paramType=ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/agbots/:id/patterns/:patid", operation(getOneAgbotPattern)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    val patId = params("patid")
    authenticate().authorizeTo(TAgbot(compositeId),Access.READ)
    val resp = response
    db.run(AgbotPatternsTQ.getPattern(compositeId, patId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/agbots/"+id+"/patterns/"+patId+" result: "+list.toString)
      val patterns = new MutableHashMap[String, AgbotPattern]
      if (list.nonEmpty) for (e <- list) { patterns.put(e.patId, e.toAgbotPattern) }
      if (patterns.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetAgbotPatternsResponse(patterns.toMap)
    })
  })

  // =========== POST /orgs/{orgid}/agbots/{id}/patterns ===============================
  val postAgbotPattern =
    (apiOperation[ApiResponse]("postAgbotPattern")
      summary "Adds a pattern that the agbot should serve"
      description """Adds a new pattern and node org that this agbot should find nodes for to make agreements with them. This is called by the owning user or the agbot to give their information about the pattern.  The **request body** structure:

```
{
  "patternOrgid": "string",
  "pattern": "string",    // can be "*" to mean all patterns in the org
  "nodeOrgid": "string"   // optional, if omitted it defaults to patternOrgid
}
```"""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot wanting to add this pattern."), paramType = ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostAgbotPatternRequest],
        Option[String]("Pattern object that needs to be added to the exchange."),
        paramType = ParamType.Body)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"), ResponseMessage(HttpCode.ALREADY_EXISTS2,"already exists"))
      )
  val postAgbotPattern2 = (apiOperation[PostAgbotPatternRequest]("postPattern2") summary("a") description("a"))  // for some bizarre reason, the class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/agbots/:id/patterns", operation(postAgbotPattern)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    val pattern = try { parse(request.body).extract[PostAgbotPatternRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    pattern.validate()
    val patId = pattern.formId
    val resp = response
    db.run(PatternsTQ.getPattern(OrgAndId(pattern.patternOrgid,pattern.pattern).toString).length.result.asTry.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/agbots/"+id+"/patterns pattern validation: "+xs.toString)
      xs match {
        case Success(num) => if (num > 0 || pattern.pattern == "*") pattern.toAgbotPatternRow(compositeId, patId).insert.asTry
        else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("pattern.not.in.exchange"))).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/agbots/"+id+"/patterns result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("pattern.added", patId))
        case Failure(t) => if (t.getMessage.contains("duplicate key")) {
          resp.setStatus(HttpCode.ALREADY_EXISTS2)
          ApiResponse(ApiResponseType.ALREADY_EXISTS, ExchangeMessage.translateMessage("pattern.already.exists", patId, compositeId))
        } else if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("pattern.not.inserted", patId, compositeId, t.getMessage))
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("pattern.not.inserted", patId, compositeId, t.getMessage))
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/agbots/{id}/patterns ===============================
  val deleteAgbotAllPattern =
    (apiOperation[ApiResponse]("deleteAgbotAllPattern")
      summary "Deletes all patterns of a agbot"
      description "Deletes all of the current patterns that this agbot was serving. Can be run by the owning user or the agbot."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot for which the pattern is to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/agbots/:id/patterns", operation(deleteAgbotAllPattern)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    val resp = response
    db.run(AgbotPatternsTQ.getPatterns(compositeId).delete.asTry).map({ xs =>
      logger.debug("DELETE /agbots/"+id+"/patterns result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("patterns.deleted"))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("patterns.not.found", compositeId))
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("patterns.not.deleted", compositeId, t.toString))
        }
    })
  })

  // =========== DELETE /orgs/{orgid}/agbots/{id}/patterns/{patid} ===============================
  val deleteAgbotPattern =
    (apiOperation[ApiResponse]("deleteAgbotPattern")
      summary "Deletes a pattern of a agbot"
      description "Deletes a pattern that this agbot was serving. Can be run by the owning user or the agbot."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot for which the pattern is to be deleted."), paramType = ParamType.Path),
        Parameter("patid", DataType.String, Option[String]("ID of the pattern to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/agbots/:id/patterns/:patid", operation(deleteAgbotPattern)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    val patId = params("patid")
    authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    val resp = response
    db.run(AgbotPatternsTQ.getPattern(compositeId,patId).delete.asTry).map({ xs =>
      logger.debug("DELETE /agbots/"+id+"/patterns/"+patId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("agbot.pattern.deleted"))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("pattern.not.found", patId, compositeId))
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("pattern.not.deleted", patId, compositeId, t.toString))
        }
    })
  })

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/agbots/{id}/businesspols ================================ */
  val getAgbotBusinessPols =
    (apiOperation[GetAgbotBusinessPolsResponse]("getAgbotBusinessPols")
      summary("Returns all business policies served by this agbot")
      description("""Returns all business policies that this agbot is finding nodes for to make agreements with them. Can be run by the owning user or the agbot.""")
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot."), paramType=ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/agbots/:id/businesspols", operation(getAgbotBusinessPols)) ({
    val orgid = params("orgid")
    val id = params("id")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,id).toString
    authenticate().authorizeTo(TAgbot(compositeId),Access.READ)
    val resp = response
    db.run(AgbotBusinessPolsTQ.getBusinessPols(compositeId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/agbots/"+id+"/businesspols result size: "+list.size)
      //logger.trace("GET /orgs/"+orgid+"/agbots/"+id+"/businesspols result: "+list.toString)
      val businessPols = new MutableHashMap[String, AgbotBusinessPol]
      if (list.nonEmpty) for (e <- list) { businessPols.put(e.busPolId, e.toAgbotBusinessPol) }
      if (businessPols.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetAgbotBusinessPolsResponse(businessPols.toMap)
    })
  })

  /* ====== GET /orgs/{orgid}/agbots/{id}/businesspols/{buspolid} ================================ */
  val getOneAgbotBusinessPol =
    (apiOperation[GetAgbotBusinessPolsResponse]("getOneAgbotBusinessPol")
      summary("Returns a business policy this agbot is serving")
      description("""Returns the business policy with the specified buspolid for the specified agbot id. The buspolid should be in the form businessPolOrgid_businessPol. Can be run by the owning user or the agbot.""")
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot."), paramType=ParamType.Path),
      Parameter("buspolid", DataType.String, Option[String]("ID of the businessPol."), paramType=ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/agbots/:id/businesspols/:buspolid", operation(getOneAgbotBusinessPol)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    val busPolId = params("buspolid")
    authenticate().authorizeTo(TAgbot(compositeId),Access.READ)
    val resp = response
    db.run(AgbotBusinessPolsTQ.getBusinessPol(compositeId, busPolId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/agbots/"+id+"/businesspols/"+busPolId+" result: "+list.toString)
      val businessPols = new MutableHashMap[String, AgbotBusinessPol]
      if (list.nonEmpty) for (e <- list) { businessPols.put(e.busPolId, e.toAgbotBusinessPol) }
      if (businessPols.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetAgbotBusinessPolsResponse(businessPols.toMap)
    })
  })

  // =========== POST /orgs/{orgid}/agbots/{id}/businesspols ===============================
  val postAgbotBusinessPol =
    (apiOperation[ApiResponse]("postAgbotBusinessPol")
      summary "Adds a business policy that the agbot should serve"
      description """Adds a new business policy and node org that this agbot should find nodes for to make agreements with them. This is called by the owning user or the agbot to give their information about the businessPol.  The **request body** structure:

```
{
  "businessPolOrgid": "string",
  "businessPol": "string",    // can be "*" to mean all business policies in the org
  "nodeOrgid": "string"   // optional, if omitted it defaults to businessPolOrgid (currently it can *not* be different from businessPolOrgid)
}
```"""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot wanting to add this businessPol."), paramType = ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostAgbotBusinessPolRequest],
        Option[String]("BusinessPol object that needs to be added to the exchange."),
        paramType = ParamType.Body)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"), ResponseMessage(HttpCode.ALREADY_EXISTS2,"already exists"))
      )
  val postAgbotBusinessPol2 = (apiOperation[PostAgbotBusinessPolRequest]("postBusinessPol2") summary("a") description("a"))  // for some bizarre reason, the class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/agbots/:id/businesspols", operation(postAgbotBusinessPol)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    val businessPol = try { parse(request.body).extract[PostAgbotBusinessPolRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    businessPol.validate()
    val busPolId = businessPol.formId
    val resp = response
    db.run(BusinessPoliciesTQ.getBusinessPolicy(OrgAndId(businessPol.businessPolOrgid,businessPol.businessPol).toString).length.result.asTry.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/agbots/"+id+"/businesspols business policy validation: "+xs.toString)
      xs match {
        case Success(num) => if (num > 0 || businessPol.businessPol == "*") businessPol.toAgbotBusinessPolRow(compositeId, busPolId).insert.asTry
          else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("buspol.not.in.exchange"))).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/agbots/"+id+"/businesspols result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("buspol.added", busPolId))
        case Failure(t) => if (t.getMessage.contains("duplicate key")) {
          resp.setStatus(HttpCode.ALREADY_EXISTS2)
          ApiResponse(ApiResponseType.ALREADY_EXISTS, ExchangeMessage.translateMessage("buspol.already.exists", busPolId, compositeId))
        } else if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("buspol.not.inserted", busPolId, compositeId, t.getMessage))
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("buspol.not.inserted", busPolId, compositeId, t.getMessage))
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/agbots/{id}/businesspols ===============================
  val deleteAgbotAllBusinessPol =
    (apiOperation[ApiResponse]("deleteAgbotAllBusinessPol")
      summary "Deletes all business policies of a agbot"
      description "Deletes all of the current business policies that this agbot was serving. Can be run by the owning user or the agbot."
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot for which the business policy is to be deleted."), paramType = ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/agbots/:id/businesspols", operation(deleteAgbotAllBusinessPol)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    val resp = response
    db.run(AgbotBusinessPolsTQ.getBusinessPols(compositeId).delete.asTry).map({ xs =>
      logger.debug("DELETE /agbots/"+id+"/businesspols result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("buspols.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("buspols.not.found", compositeId))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("buspols.not.deleted", compositeId, t.toString))
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/agbots/{id}/businesspols/{buspolid} ===============================
  val deleteAgbotBusinessPol =
    (apiOperation[ApiResponse]("deleteAgbotBusinessPol")
      summary "Deletes a business policy of a agbot"
      description "Deletes a business policy that this agbot was serving. Can be run by the owning user or the agbot."
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot for which the business policy is to be deleted."), paramType = ParamType.Path),
      Parameter("buspolid", DataType.String, Option[String]("ID of the business policy to be deleted."), paramType = ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/agbots/:id/businesspols/:buspolid", operation(deleteAgbotBusinessPol)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    val busPolId = params("buspolid")
    authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    val resp = response
    db.run(AgbotBusinessPolsTQ.getBusinessPol(compositeId,busPolId).delete.asTry).map({ xs =>
      logger.debug("DELETE /agbots/"+id+"/businesspols/"+busPolId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("buspol.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("buspol.not.found", busPolId, compositeId))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("buspol.not.deleted", busPolId, compositeId, t.toString))
      }
    })
  })

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/agbots/{id}/agreements ================================ */
  val getAgbotAgreements =
    (apiOperation[GetAgbotAgreementsResponse]("getAgbotAgreements")
      summary("Returns all agreements this agbot is in")
      description("""Returns all agreements in the exchange DB that this agbot is part of. Can be run by the owning user or the agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot."), paramType=ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
    )

  get("/orgs/:orgid/agbots/:id/agreements", operation(getAgbotAgreements)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    authenticate().authorizeTo(TAgbot(compositeId),Access.READ)
    val resp = response
    db.run(AgbotAgreementsTQ.getAgreements(compositeId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/agbots/"+id+"/agreements result size: "+list.size)
      //logger.trace("GET /orgs/"+orgid+"/agbots/"+id+"/agreements result: "+list.toString)
      val agreements = new MutableHashMap[String, AgbotAgreement]
      if (list.nonEmpty) for (e <- list) { agreements.put(e.agrId, e.toAgbotAgreement) }
      if (agreements.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetAgbotAgreementsResponse(agreements.toMap, 0)
    })
  })

  /* ====== GET /orgs/{orgid}/agbots/{id}/agreements/{agid} ================================ */
  val getOneAgbotAgreement =
    (apiOperation[GetAgbotAgreementsResponse]("getOneAgbotAgreement")
      summary("Returns an agreement for a agbot")
      description("""Returns the agreement with the specified agid for the specified agbot id in the exchange DB. Can be run by the owning user or the agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot."), paramType=ParamType.Path),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement."), paramType=ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
    )

  get("/orgs/:orgid/agbots/:id/agreements/:agid", operation(getOneAgbotAgreement)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    val agrId = params("agid")
    authenticate().authorizeTo(TAgbot(compositeId),Access.READ)
    val resp = response
    db.run(AgbotAgreementsTQ.getAgreement(compositeId, agrId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/agbots/"+id+"/agreements/"+agrId+" result: "+list.toString)
      val agreements = new MutableHashMap[String, AgbotAgreement]
      if (list.nonEmpty) for (e <- list) { agreements.put(e.agrId, e.toAgbotAgreement) }
      if (agreements.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetAgbotAgreementsResponse(agreements.toMap, 0)
    })
  })

  // =========== PUT /orgs/{orgid}/agbots/{id}/agreements/{agid} ===============================
  val putAgbotAgreement =
    (apiOperation[ApiResponse]("putAgbotAgreement")
      summary "Adds/updates an agreement of a agbot"
      description """Adds a new agreement of a agbot to the exchange DB, or updates an existing agreement. This is called by the owning user or the agbot to give their information about the agreement."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot wanting to add/update this agreement."), paramType = ParamType.Path),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement to be added/updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutAgbotAgreementRequest],
          Option[String]("Agreement object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
    )
  val putAgbotAgreement2 = (apiOperation[PutAgbotAgreementRequest]("putAgreement2") summary("a") description("a"))  // for some bizarre reason, the PutAgreementsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/agbots/:id/agreements/:agid", operation(putAgbotAgreement)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    val agrId = params("agid")
    authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    val agreement = try { parse(request.body).extract[PutAgbotAgreementRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    agreement.validate()
    val resp = response
    db.run(AgbotAgreementsTQ.getNumOwned(compositeId).result.flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/agbots/"+id+"/agreements/"+agrId+" num owned: "+xs)
      val numOwned = xs
      val maxAgreements = ExchConfig.getInt("api.limits.maxAgreements")
      if (maxAgreements == 0 || numOwned <= maxAgreements) {    // we are not sure if this is create or update, but if they are already over the limit, stop them anyway
        agreement.toAgbotAgreementRow(compositeId, agrId).upsert.asTry
      }
      else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("over.max.limit.of.agreements", maxAgreements))).asTry
    })).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/agbots/"+id+"/agreements/"+agrId+" result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("agreement.added.or.updated"))
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("agreement.not.inserted.or.updated", agrId, compositeId, t.getMessage))
        } else {
          resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("agreement.not.inserted.or.updated", agrId, compositeId, t.toString))
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/agbots/{id}/agreements ===============================
  val deleteAgbotAllAgreement =
    (apiOperation[ApiResponse]("deleteAgbotAllAgreement")
      summary "Deletes all agreements of a agbot"
      description "Deletes all of the current agreements of a agbot from the exchange DB. Can be run by the owning user or the agbot."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot for which the agreement is to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
    )

  delete("/orgs/:orgid/agbots/:id/agreements", operation(deleteAgbotAllAgreement)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    val resp = response
    db.run(AgbotAgreementsTQ.getAgreements(compositeId).delete.asTry).map({ xs =>
      logger.debug("DELETE /agbots/"+id+"/agreements result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("agbot.agreements.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("no.agreements.found.for.agbot", compositeId))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("agbot.agreements.not.deleted", compositeId, t.toString))
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/agbots/{id}/agreements/{agid} ===============================
  val deleteAgbotAgreement =
    (apiOperation[ApiResponse]("deleteAgbotAgreement")
      summary "Deletes an agreement of a agbot"
      description "Deletes an agreement of a agbot from the exchange DB. Can be run by the owning user or the agbot."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot for which the agreement is to be deleted."), paramType = ParamType.Path),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
    )

  delete("/orgs/:orgid/agbots/:id/agreements/:agid", operation(deleteAgbotAgreement)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    val agrId = params("agid")
    authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    val resp = response
    db.run(AgbotAgreementsTQ.getAgreement(compositeId,agrId).delete.asTry).map({ xs =>
      logger.debug("DELETE /agbots/"+id+"/agreements/"+agrId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("agbot.agreement.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("agreement.for.agbot.not.found", agrId, compositeId))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("agreement.for.agbot.not.deleted", agrId, compositeId, t.toString))
      }
    })
  })


  /* Not using these for data verification, but might in the future...
  // =========== POST /agbots/{id}/dataheartbeat ===============================
  val postAgbotsDataHeartbeat =
    (apiOperation[ApiResponse]("postAgbotsDataHeartbeat")
      summary "Not supported yet - Tells the exchange that data has been received for these agreements"
      description "Lets the exchange know that data has just been received for this list of agreement IDs. This is normally run by a cloud data aggregation service that is registered as an agbot of the same exchange user account that owns the agbots that are contracting on behalf of a workload. Can be run by the owning user or any of the agbots owned by that user. The other agbot that negotiated this agreement id can run POST /agbots/{id}/isrecentdata check the dataLastReceived value of the agreement to determine if the agreement should be canceled (if data verification is enabled)."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot running this REST API method."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[List[String]],
          Option[String]("List of agreement IDs that have received data very recently."),
          paramType = ParamType.Body)
        )
      )
  post("/agbots/:id/dataheartbeat", operation(postAgbotsDataHeartbeat)) ({
    val id = params("id")
    // validateUserOrAgbotId(BaseAccess.DATA_HEARTBEAT, id)
    credsAndLog().authenticate().authorizeTo(TAgbot("#"),Access.DATA_HEARTBEAT_MY_AGBOTS).creds
    val agrIds = try { parse(request.body).extract[List[String]] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val agreementIds = agrIds.toSet

    need to implement persistence
    // Find the agreement ids in any of this user's agbots
    val owner = TempDb.agbots.get(id) match {       // 1st find owner (user)
      case Some(agbot) => agbot.owner
      case None => halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "agbot id '"+id+"' not found"))
    }
    val agbotIds = TempDb.agbots.toMap.filter(a => a._2.owner == owner).keys.toSet      // all the agbots owned by this user
    val agbotsAgreements = TempDb.agbotsAgreements.toMap.filter(a => agbotIds.contains(a._1) && a._2.keys.toSet.intersect(agreementIds).size > 0)    // agbotsAgreements is hash of hash: 1st key is agbot id, 2nd key is agreement id, value is agreement info
    // we still have the same hash of hash, but have reduced the top level to only agbot ids that contain these agreement ids
    if (agbotsAgreements.size == 0) halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "agreement IDs not found"))
    // println(agbotsAgreements)

    // Now update the dataLastReceived value in all of these agreement id objects
    for ((id,agrMap) <- agbotsAgreements) {
      for ((agid, agr) <- agrMap; if agreementIds.contains(agid)) {
        agrMap.put(agid, AgbotAgreement(agr.workload, agr.state, agr.lastUpdated, ApiTime.nowUTC))   // copy everything from the original entry except dataLastReceived
      }
    }
    status_=(HttpCode.POST_OK)
    ApiResponse(ApiResponseType.OK, "data heartbeats successful")
    status_=(HttpCode.NOT_IMPLEMENTED)
    ApiResponse(ApiResponseType.NOT_IMPLEMENTED, "data heartbeats not implemented yet")
  })

  // =========== POST /agbots/{id}/isrecentdata ===============================
  val postAgbotsIsRecentData =
    (apiOperation[List[PostAgbotsIsRecentDataElement]]("postAgbotsIsRecentData")
      summary "Not supported yet - Returns whether each agreement has received data or not"
      description "Queries the exchange to find out if each of the specified agreement IDs has had POST /agbots/{id}/dataheartbeat run on it recently (within secondsStale ago). This is normally run by agbots that are contracting on behalf of this workload to decide whether the agreement should be canceled or not. Can be run by the owning user or any of the agbots owned by that user."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot running this REST API method."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostAgbotsIsRecentDataRequest],
          Option[String]("List of agreement IDs that should be queried, and the time threshold to use."),
          paramType = ParamType.Body)
        )
      )
  val postAgbotsIsRecentData2 = (apiOperation[PostAgbotsIsRecentDataRequest]("postAgbotsIsRecentData2") summary("a") description("a"))
  post("/agbots/:id/isrecentdata", operation(postAgbotsIsRecentData)) ({
    val id = params("id")
    // validateUserOrAgbotId(BaseAccess.DATA_HEARTBEAT, id)
    credsAndLog().authenticate().authorizeTo(TAgbot(id),Access.READ).creds
    val req = try { parse(request.body).extract[PostAgbotsIsRecentDataRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val secondsStale = req.secondsStale
    val agreementIds = req.agreementIds.toSet

    need to implement persistence
    // Find the agreement ids in any of this user's agbots
    val owner = TempDb.agbots.get(id) match {       // 1st find owner (user)
      case Some(agbot) => agbot.owner
      case None => halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "agbot id '"+id+"' not found"))
    }
    val agbotIds = TempDb.agbots.toMap.filter(a => a._2.owner == owner).keys.toSet      // all the agbots owned by this user
    val agbotsAgreements = TempDb.agbotsAgreements.toMap.filter(a => agbotIds.contains(a._1) && a._2.keys.toSet.intersect(agreementIds).size > 0)    // agbotsAgreements is hash of hash: 1st key is agbot id, 2nd key is agreement id, value is agreement info
    // we still have the same hash of hash, but have reduced the top level to only agbot ids that contain these agreement ids
    if (agbotsAgreements.size == 0) halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "agreement IDs not found"))
    // println(agbotsAgreements)

    // Now compare the dataLastReceived value with the current time
    var resp = List[PostAgbotsIsRecentDataElement]()
    for ((id,agrMap) <- agbotsAgreements) {
      for ((agid, agr) <- agrMap; if agreementIds.contains(agid)) {
        val recentData = !ApiTime.isSecondsStale(agr.dataLastReceived,secondsStale)
        resp = resp :+ PostAgbotsIsRecentDataElement(agid, recentData)
      }
    }
    status_=(HttpCode.POST_OK)
    resp
    status_=(HttpCode.NOT_IMPLEMENTED)
    ApiResponse(ApiResponseType.NOT_IMPLEMENTED, "isrecentdata not implemented yet")
  })
  */

  // =========== POST /orgs/{orgid}/agreements/confirm ===============================
  val postAgreementsConfirm =
    (apiOperation[ApiResponse]("postAgreementsConfirm")
      summary "Confirms if this agbot agreement is active"
      description "Confirms whether or not this agreement id is valid, is owned by an agbot owned by this same username, and is a currently active agreement. Can only be run by an agbot or user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username or agbot id. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password or token of the user/agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostAgreementsConfirmRequest],
          Option[String]("Agreement ID that should be confirmed."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postAgreementsConfirm2 = (apiOperation[PostAgreementsConfirmRequest]("postAgreementsConfirm2") summary("a") description("a"))

  post("/orgs/:orgid/agreements/confirm", operation(postAgreementsConfirm)) ({
    val orgid = params("orgid")
    val ident = authenticate().authorizeTo(TAgbot(OrgAndId(orgid,"#").toString),Access.READ)
    val creds = ident.creds
    val req = try { parse(request.body).extract[PostAgreementsConfirmRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException

    val resp = response
    // val owner = if (isAuthenticatedUser(creds)) creds.id else ""
    val owner = ident match { case IUser(creds2) => creds2.id; case _ => "" }
    if (owner != "") {
      // the user invoked this rest method, so look for an agbot owned by this user with this agr id
      val agbotAgreementJoin = for {
        (agbot, agr) <- AgbotsTQ.rows joinLeft AgbotAgreementsTQ.rows on (_.id === _.agbotId)
        if agbot.owner === owner && agr.map(_.agrId) === req.agreementId
      } yield (agbot, agr)
      db.run(agbotAgreementJoin.result).map({ list =>
        logger.debug("POST /agreements/confirm of "+req.agreementId+" result: "+list.toString)
        // this list is tuples of (AgbotRow, Option(AgbotAgreementRow)) in which agbot.owner === owner && agr.agrId === req.agreementId
        if (list.nonEmpty && list.head._2.isDefined && list.head._2.get.state != "") {
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("agreement.active"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("agreement.not.found.not.active"))
        }
      })
    } else {
      // an agbot invoked this rest method, so look for the agbot with this id and for the agbot with this agr id, and see if they are owned by the same user
      val agbotAgreementJoin = for {
        (agbot, agr) <- AgbotsTQ.rows joinLeft AgbotAgreementsTQ.rows on (_.id === _.agbotId)
        if agbot.id === creds.id || agr.map(_.agrId) === req.agreementId
      } yield (agbot, agr)
      db.run(agbotAgreementJoin.result).map({ list =>
        logger.debug("POST /agreements/confirm of "+req.agreementId+" result: "+list.toString)
        if (list.nonEmpty) {
          // this list is tuples of (AgbotRow, Option(AgbotAgreementRow)) in which agbot.id === creds.id || agr.agrId === req.agreementId
          val agbot1 = list.find(r => r._1.id == creds.id).orNull
          val agbot2 = list.find(r => r._2.isDefined && r._2.get.agrId == req.agreementId).orNull
          if (agbot1 != null && agbot2 != null && agbot1._1.owner == agbot2._1.owner && agbot2._2.get.state != "") {
            resp.setStatus(HttpCode.POST_OK)
            ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("agreement.active"))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("agreement.not.found.not.active"))
          }
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("agreement.not.found.not.active"))
        }
      })
    }
  })

  // =========== POST /orgs/{orgid}/agbots/{id}/msgs ===============================
  val postAgbotsMsgs =
    (apiOperation[ApiResponse]("postAgbotsMsgs")
      summary "Sends a msg from a node to a agbot"
      description """Sends a msg from a node to a agbot. The node must 1st sign the msg (with its private key) and then encrypt the msg (with the agbots's public key). Can be run by any node."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot to send a msg to."), paramType = ParamType.Path),
        // Node id/token must be in the header
        Parameter("body", DataType[PostAgbotsMsgsRequest],
          Option[String]("Signed/encrypted message to send to the agbot. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postAgbotsMsgs2 = (apiOperation[PostAgbotsMsgsRequest]("postAgbotsMsgs2") summary("a") description("a"))

  // The credentials for this are usually a node id
  post("/orgs/:orgid/agbots/:id/msgs", operation(postAgbotsMsgs)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    val ident = authenticate().authorizeTo(TAgbot(compositeId),Access.SEND_MSG_TO_AGBOT)
    val nodeId = ident.creds.id      //todo: handle the case where the acls allow users to send msgs
    val msg = try { parse(request.body).extract[PostAgbotsMsgsRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    val resp = response
    // Remove msgs whose TTL is past, then check the mailbox is not full, then get the node publicKey, then write the agbotmsgs row, all in the same db.run thread
    db.run(AgbotMsgsTQ.getMsgsExpired.delete.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/agbots/"+id+"/msgs delete expired result: "+xs.toString)
      AgbotMsgsTQ.getNumOwned(compositeId).result
    }).flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/agbots/"+id+"/msgs mailbox size: "+xs)
      val mailboxSize = xs
      val maxMessagesInMailbox = ExchConfig.getInt("api.limits.maxMessagesInMailbox")
      if (maxMessagesInMailbox == 0 || mailboxSize < maxMessagesInMailbox) NodesTQ.getPublicKey(nodeId).result.asTry
      else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("agbot.mailbox.full", compositeId, maxMessagesInMailbox))).asTry
    }).flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/agbots/"+id+"/msgs node publickey result: "+xs.toString)
      xs match {
        case Success(v) => val nodePubKey = v.head
          if (nodePubKey != "") AgbotMsgRow(0, compositeId, nodeId, nodePubKey, msg.message, ApiTime.nowUTC, ApiTime.futureUTC(msg.ttl)).insert.asTry
          else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("agbot.message.invalid.input"))).asTry
        case Failure(t) => DBIO.failed(t).asTry       // rethrow the error to the next step
      }
    })).map({ xs =>
      logger.debug("POST /orgs/{orgid}/agbots/"+id+"/msgs write row result: "+xs.toString)
      xs match {
        case Success(v) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "agbot msg "+v+" inserted")
        case Failure(t) => if (t.getMessage.startsWith("Invalid Input:")) {
            resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("agbot.message.not.inserted", compositeId, t.getMessage))
          } else if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("agbot.message.not.inserted", compositeId, t.getMessage))
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("agbot.message.not.inserted", compositeId, t.toString))
          }
        }
    })
  })

  /* ====== GET /orgs/{orgid}/agbots/{id}/msgs ================================ */
  val getAgbotMsgs =
    (apiOperation[GetAgbotMsgsResponse]("getAgbotMsgs")
      summary("Returns all msgs sent to this agbot")
      description("""Returns all msgs that have been sent to this agbot. They will be returned in the order they were sent. All msgs that have been sent to this agbot will be returned, unless the agbot has deleted some, or some are past their TTL. Can be run by a user or the agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot."), paramType=ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/agbots/:id/msgs", operation(getAgbotMsgs)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    authenticate().authorizeTo(TAgbot(compositeId),Access.READ)
    val resp = response
    // Remove msgs whose TTL is past, and then get the msgs for this agbot
    db.run(AgbotMsgsTQ.getMsgsExpired.delete.flatMap({ xs =>
      logger.debug("GET /orgs/"+orgid+"/agbots/"+id+"/msgs delete expired result: "+xs.toString)
      AgbotMsgsTQ.getMsgs(compositeId).result
    })).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/agbots/"+id+"/msgs result size: "+list.size)
      //logger.trace("GET /orgs/"+orgid+"/agbots/"+id+"/msgs result: "+list.toString)
      val listSorted = list.sortWith(_.msgId < _.msgId)
      val msgs = new ListBuffer[AgbotMsg]
      if (listSorted.nonEmpty) for (m <- listSorted) { msgs += m.toAgbotMsg }
      if (msgs.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetAgbotMsgsResponse(msgs.toList, 0)
    })
  })

  // =========== DELETE /orgs/{orgid}/agbots/{id}/msgs/{msgid} ===============================
  val deleteAgbotMsg =
    (apiOperation[ApiResponse]("deleteAgbotMsg")
      summary "Deletes an msg of a agbot"
      description "Deletes an msg that was sent to a agbot. This should be done by the agbot after each msg is read. Can be run by the owning user or the agbot."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String](" ID (orgid/agbotid) of the agbot to be deleted."), paramType = ParamType.Path),
        Parameter("msgid", DataType.String, Option[String]("ID of the msg to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/agbots/:id/msgs/:msgid", operation(deleteAgbotMsg)) ({
    val orgid = params("orgid")
    val id = params("id")
    val compositeId = OrgAndId(orgid,id).toString
    val msgId = try { params("msgid").toInt } catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("msgid.must.be.int", e))) }    // the specific exception is NumberFormatException

    authenticate().authorizeTo(TAgbot(compositeId),Access.WRITE)
    val resp = response
    db.run(AgbotMsgsTQ.getMsg(compositeId,msgId).delete.asTry).map({ xs =>
      logger.debug("DELETE /agbots/"+id+"/msgs/"+msgId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("agbot.message.deleted"))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("agbot.message.not.found", msgId, compositeId))
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("agbot.message.not.deleted", msgId, compositeId, t.toString))
        }
    })
  })

}
