/** Services routes for all of the /agbots api methods. */
package com.horizon.exchangeapi

import org.scalatra._
import slick.jdbc.PostgresProfile.api._
// import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatra.swagger._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.scalatra.json._
import org.slf4j._
import Access._
import BaseAccess._
import scala.util._
import scala.util.control.Breaks._
import scala.collection.immutable._
import scala.collection.mutable.{ListBuffer, Set => MutableSet, HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection
import com.horizon.exchangeapi.tables._

//====== These are the input and output structures for /agbots routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /agbots */
case class GetAgbotsResponse(agbots: Map[String,Agbot], lastIndex: Int)
case class GetAgbotAttributeResponse(attribute: String, value: String)

/** For backward compatibility for before i added the publicKey field */
case class PutAgbotsRequestOld(token: String, name: String, msgEndPoint: String) {
  def toPutAgbotsRequest = PutAgbotsRequest(token, name, msgEndPoint, "")
}

/** Input format for PUT /agbots/<agbot-id> */
case class PutAgbotsRequest(token: String, name: String, msgEndPoint: String, publicKey: String) {
  def validate = {
    // if (msgEndPoint == "" && publicKey == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "either msgEndPoint or publicKey must be specified."))  <-- skipping this check because POST /devices/{id}/msgs checks for the publicKey
  }

  /** Get the db queries to insert or update the agbot */
  def getDbUpsert(id: String, owner: String): DBIO[_] = AgbotRow(id, token, name, owner, msgEndPoint, ApiTime.nowUTC, publicKey).upsert

  /** Get the db queries to update the agbot */
  def getDbUpdate(id: String, owner: String): DBIO[_] = AgbotRow(id, token, name, owner, msgEndPoint, ApiTime.nowUTC, publicKey).update
}

case class PatchAgbotsRequest(token: Option[String], name: Option[String], msgEndPoint: Option[String], publicKey: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the agbot, and the attribute name being updated. */
  def getDbUpdate(id: String): (DBIO[_],String) = {
    val lastHeartbeat = ApiTime.nowUTC
    //todo: support updating more than 1 attribute
    // find the 1st attribute that was specified in the body and create a db action to update it for this agbot
    token match {
      case Some(token) => if (token == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the token can not be set to the empty string"))
        val tok = if (Password.isHashed(token)) token else Password.hash(token)
        return ((for { d <- AgbotsTQ.rows if d.id === id } yield (d.id,d.token,d.lastHeartbeat)).update((id, tok, lastHeartbeat)), "token")
      case _ => ;
    }
    name match { case Some(name) => return ((for { d <- AgbotsTQ.rows if d.id === id } yield (d.id,d.name,d.lastHeartbeat)).update((id, name, lastHeartbeat)), "name"); case _ => ; }
    msgEndPoint match { case Some(msgEndPoint) => return ((for { d <- AgbotsTQ.rows if d.id === id } yield (d.id,d.msgEndPoint,d.lastHeartbeat)).update((id, msgEndPoint, lastHeartbeat)), "msgEndPoint"); case _ => ; }
    publicKey match { case Some(publicKey) => return ((for { d <- AgbotsTQ.rows if d.id === id } yield (d.id,d.publicKey,d.lastHeartbeat)).update((id, publicKey, lastHeartbeat)), "publicKey"); case _ => ; }
    return (null, null)
  }
}


/** Output format for GET /agbots/{id}/agreements */
case class GetAgbotAgreementsResponse(agreements: Map[String,AgbotAgreement], lastIndex: Int)

/** Input format for PUT /agbots/{id}/agreements/<agreement-id> */
case class PutAgbotAgreementRequest(workload: String, state: String) {
  def toAgbotAgreement = AgbotAgreement(workload, state, ApiTime.nowUTC, "")
  def toAgbotAgreementRow(agbotId: String, agrId: String) = AgbotAgreementRow(agrId, agbotId, workload, state, ApiTime.nowUTC, "")
}

case class PostAgbotsIsRecentDataRequest(secondsStale: Int, agreementIds: List[String])     // the strings in the list are agreement ids
case class PostAgbotsIsRecentDataElement(agreementId: String, recentData: Boolean)

case class PostAgreementsConfirmRequest(agreementId: String)


/** Input body for POST /agbots/{id}/msgs */
case class PostAgbotsMsgsRequest(message: String, ttl: Int)

/** Response for GET /agbots/{id}/msgs */
case class GetAgbotMsgsResponse(messages: List[AgbotMsg], lastIndex: Int)


/** Implementation for all of the /agbots routes */
trait AgbotsRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  /* ====== GET /agbots ================================ */
  val getAgbots =
    (apiOperation[GetAgbotsResponse]("getAgbots")
      summary("Returns all agbots")
      notes("""Returns all agbots (Agreement Bots) in the exchange DB. Can be run by any user.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("idfilter", DataType.String, Option[String]("Filter results to only include agbots with this id (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("name", DataType.String, Option[String]("Filter results to only include agbots with this name (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include agbots with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /agbots. Normally called by the user to see all agbots. */
  get("/agbots", operation(getAgbots)) ({
    val creds = validateUserOrAgbotId(BaseAccess.READ, "*")
    val superUser = isSuperUser(creds)
    var q = AgbotsTQ.rows.subquery
    params.get("idfilter").foreach(id => { if (id.contains("%")) q = q.filter(_.id like id) else q = q.filter(_.id === id) })
    params.get("name").foreach(name => { if (name.contains("%")) q = q.filter(_.name like name) else q = q.filter(_.name === name) })
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })

    db.run(q.result).map({ list =>
      logger.debug("GET /agbots result size: "+list.size)
      val agbots = new MutableHashMap[String,Agbot]
      for (a <- list) agbots.put(a.id, a.toAgbot(superUser))
      GetAgbotsResponse(agbots.toMap, 0)
    })
  })

  /* ====== GET /agbots/{id} ================================ */
  val getOneAgbot =
    (apiOperation[GetAgbotsResponse]("getOneAgbot")
      summary("Returns a agbot")
      notes("""Returns the agbot (Agreement Bot) with the specified id in the exchange DB. Can be run by a user or the agbot.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire device resource (including microservices) will be returned."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /agbots/{id}. Normally called by the agbot to verify its own entry after a reboot. */
  get("/agbots/:id", operation(getOneAgbot)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    val creds = validateUserOrAgbotId(BaseAccess.READ, id)
    val superUser = isSuperUser(creds)
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the agbot
        val q = AgbotsTQ.getAttribute(id, attribute)
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Agbot attribute name '"+attribute+"' is not an attribute of the agbot resource."))
        db.run(q.result).map({ list =>
          logger.trace("GET /agbots/"+id+" attribute result: "+list.toString)
          if (list.size > 0) {
            GetAgbotAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")     // validateAccessToAgbot() will return ApiResponseType.NOT_FOUND to the client so do that here for consistency
          }
        })

      case None => ;  // Return the whole agbot, including the microservices
        db.run(AgbotsTQ.getAgbot(id).result).map({ list =>
          logger.debug("GET /agbots/"+id+" result: "+list.toString)
          val agbots = new MutableHashMap[String,Agbot]
          if (list.size > 0) for (a <- list) agbots.put(a.id, a.toAgbot(superUser))
          else resp.setStatus(HttpCode.NOT_FOUND)
          GetAgbotsResponse(agbots.toMap, 0)
        })
    }
  })

  // =========== PUT /agbots/{id} ===============================
  val putAgbots =
    (apiOperation[ApiResponse]("putAgbots")
      summary "Adds/updates a agbot"
      notes """Adds a new agbot (Agreement Bot) to the exchange DB, or updates an existing agbot. This must be called by the user to add a agbot, and then can be called by that user or agbot to update itself. The **request body** structure:

```
{
  "token": "abc",       // agbot token, set by user when adding this agbot. When the agbot is running this to update the agbot it can set this to the empty string
  "name": "agbot3",         // agbot name that you pick
  "msgEndPoint": "whisper-id",    // msg service endpoint id for this agbot to be contacted by agbots, empty string to use the built-in Exchange msg service
  "publicKey"      // used by devices to encrypt msgs sent to this agbot using the built-in Exchange msg service
}
```"""
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot to be added/updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutAgbotsRequest],
          Option[String]("Agbot object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val putAgbots2 = (apiOperation[PutAgbotsRequest]("putAgbots2") summary("a") notes("a"))  // for some bizarre reason, the PutAgbotsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PUT /agbot/{id}. Normally called by agbot to add/update itself. */
  put("/agbots/:id", operation(putAgbots)) ({
    val id = params("id")
    val creds = validateUserOrAgbotId(BaseAccess.WRITE, id)
    val agbot = try { parse(request.body).extract[PutAgbotsRequest] }
    catch {
      case e: Exception => if (e.getMessage.contains("No usable value for publicKey")) {    // the specific exception is MappingException
          // try parsing again with the old structure
          val agbotOld = try { parse(request.body).extract[PutAgbotsRequestOld] }
          catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
          agbotOld.toPutAgbotsRequest
        }
        else halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e))
    }
    agbot.validate
    val owner = if (isAuthenticatedUser(creds)) creds.id else ""
    val resp = response
    db.run(AgbotsTQ.getNumOwned(owner).result.flatMap({ xs =>
      logger.debug("PUT /agbots/"+id+" num owned: "+xs)
      val numOwned = xs
      val maxAgbots = ExchConfig.getInt("api.limits.maxAgbots")
      if (numOwned <= maxAgbots || owner == "") {    // when owner=="" we know it is only an update, otherwise we are not sure, but if they are already over the limit, stop them anyway
        val action = if (owner == "") agbot.getDbUpdate(id, owner) else agbot.getDbUpsert(id, owner)
        action.asTry
      }
      else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxAgbots+ " agbots")).asTry
    })).map({ xs =>
      logger.debug("PUT /agbots/"+id+" result: "+xs.toString)
      xs match {
        case Success(v) => if (agbot.token != "") AuthCache.agbots.put(Creds(id, agbot.token))    // the token passed in to the cache should be the non-hashed one
          resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "agbot added or updated")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, "agbot '"+id+"' not inserted or updated: "+t.getMessage)
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "agbot '"+id+"' not inserted or updated: "+t.toString)
          }
      }
    })
  })

  // =========== PATCH /agbots/{id} ===============================
  val patchAgbots =
    (apiOperation[Map[String,String]]("patchAgbots")
      summary "Partially updates an agbot"
      notes """Updates some attributes of an agbot in the exchange DB. This can be called by the user or the agbot. The **request body** structure can include **1 of these attributes**:

```
{
  "token": "abc",       // agbot token, set by user when adding this agbot.
  "name": "rpi3",         // agbot name that you pick
  "msgEndPoint": "whisper-id",    // msg service endpoint id for this agbot to be contacted by agbots, empty string to use the built-in Exchange msg service
  "publicKey"      // used by agbots to encrypt msgs sent to this agbot using the built-in Exchange msg service
}
```

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the PATCH method to see the response format instead.**"""
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot to be updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchAgbotsRequest],
          Option[String]("Agbot object that contains attributes to updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val patchAgbots2 = (apiOperation[PatchAgbotsRequest]("patchAgbots2") summary("a") notes("a"))  // for some bizarre reason, the PatchAgbotsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PATCH /agbot/{id}. Must be called by user to add agbot, normally called by agbot to update itself. */
  patch("/agbots/:id", operation(patchAgbots)) ({
    val id = params("id")
    val creds = validateUserOrAgbotId(BaseAccess.WRITE, id)
    val agbot = try { parse(request.body).extract[PatchAgbotsRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.trace("PATCH /agbots/"+id+" input: "+agbot.toString)
    val resp = response
    val (action, attrName) = agbot.getDbUpdate(id)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid agbot attribute specified"))
    db.run(action.transactionally.asTry).map({ xs =>
      logger.debug("PATCH /agbots/"+id+" result: "+xs.toString)
      xs match {
        case Success(v) => agbot.token match { case Some(tok) if (tok != "") => AuthCache.agbots.put(Creds(id, tok)); case _ => ; }    // the token passed in to the cache should be the non-hashed one
          resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "attribute '"+attrName+"' of agbot '"+id+"' updated")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "agbot '"+id+"' not inserted or updated: "+t.toString)
      }
    })
  })

  // =========== DELETE /agbots/{id} ===============================
  val deleteAgbots =
    (apiOperation[ApiResponse]("deleteAgbots")
      summary "Deletes a agbot"
      notes "Deletes a agbot (Agreement Bot) from the exchange DB, and deletes the agreements stored for this agbot (but does not actually cancel the agreements between the devices and agbot). Can be run by the owning user or the agbot."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /agbots/{id}. */
  delete("/agbots/:id", operation(deleteAgbots)) ({
    val id = params("id")
    validateUserOrAgbotId(BaseAccess.WRITE, id)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(AgbotsTQ.getDeleteActions(id).transactionally.asTry).map({ xs =>
      logger.debug("DELETE /agbots/"+id+" result: "+xs.toString)
      xs match {
        case Success(v) => ;
          AuthCache.agbots.remove(id)
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "agbot deleted")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)    // not considered an error, because they wanted the resource gone and it is
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "agbot '"+id+"' not deleted: "+t.toString)
        }
    })
  })

  // =========== POST /agbots/{id}/heartbeat ===============================
  val postAgbotsHeartbeat =
    (apiOperation[ApiResponse]("postAgbotsHeartbeat")
      summary "Tells the exchange this agbot is still operating"
      notes "Lets the exchange know this agbot is still active. Can be run by the owning user or the agbot."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot to be updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /agbots/{id}/heartbeat. */
  post("/agbots/:id/heartbeat", operation(postAgbotsHeartbeat)) ({
    val id = params("id")
    validateUserOrAgbotId(BaseAccess.WRITE, id)
    val resp = response
    db.run(AgbotsTQ.getLastHeartbeat(id).update(ApiTime.nowUTC).asTry).map({ xs =>
      logger.debug("POST /agbots/"+id+"/heartbeat result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.POST_OK)
              ApiResponse(ApiResponseType.OK, "agbot updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "agbot '"+id+"' not found")
            }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "agbot '"+id+"' not updated: "+t.toString)
      }
    })
  })

  /* ====== GET /agbots/{id}/agreements ================================ */
  val getAgbotAgreements =
    (apiOperation[GetAgbotAgreementsResponse]("getAgbotAgreements")
      summary("Returns all agreements this agbot is in")
      notes("""Returns all agreements in the exchange DB that this agbot is part of. Can be run by the owning user or the agbot.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /agbots/{id}/agreements. Normally called by the user to see all agreements of this agbot. */
  get("/agbots/:id/agreements", operation(getAgbotAgreements)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    validateUserOrAgbotId(BaseAccess.READ, id)
    val resp = response
    db.run(AgbotAgreementsTQ.getAgreements(id).result).map({ list =>
      logger.debug("GET /agbots/"+id+"/agreements result size: "+list.size)
      logger.trace("GET /agbots/"+id+"/agreements result: "+list.toString)
      val agreements = new MutableHashMap[String, AgbotAgreement]
      if (list.size > 0) for (e <- list) { agreements.put(e.agrId, e.toAgbotAgreement) }
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetAgbotAgreementsResponse(agreements.toMap, 0)
    })
  })

  /* ====== GET /agbots/{id}/agreements/{agid} ================================ */
  val getOneAgbotAgreement =
    (apiOperation[GetAgbotAgreementsResponse]("getOneAgbotAgreement")
      summary("Returns an agreement for a agbot")
      notes("""Returns the agreement with the specified agid for the specified agbot id in the exchange DB. Can be run by the owning user or the agbot. **Because of a swagger bug this method can not be run via swagger.**

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot."), paramType=ParamType.Query),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /agbots/{id}/agreements/{agid}. */
  get("/agbots/:id/agreements/:agid", operation(getOneAgbotAgreement)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")   // but do not have a hack/fix for the agid
    val agrId = params("agid")
    validateUserOrAgbotId(BaseAccess.READ, id)
    val resp = response
    db.run(AgbotAgreementsTQ.getAgreement(id, agrId).result).map({ list =>
      logger.debug("GET /agbots/"+id+"/agreements/"+agrId+" result: "+list.toString)
      val agreements = new MutableHashMap[String, AgbotAgreement]
      if (list.size > 0) for (e <- list) { agreements.put(e.agrId, e.toAgbotAgreement) }
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetAgbotAgreementsResponse(agreements.toMap, 0)
    })
  })

  // =========== PUT /agbots/{id}/agreements/{agid} ===============================
  val putAgbotAgreement =
    (apiOperation[ApiResponse]("putAgbotAgreement")
      summary "Adds/updates an agreement of a agbot"
      notes """Adds a new agreement of a agbot to the exchange DB, or updates an existing agreement. This is called by the owning user or the
        agbot to give their information about the agreement. The **request body** structure:

```
{
  "workload": "sdr-arm.json",    // workload template name
  "state": "negotiating"    // current agreement state: negotiating, signed, finalized, etc.
}
```"""
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot wanting to add/update this agreement."), paramType = ParamType.Query),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement to be added/updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutAgbotAgreementRequest],
          Option[String]("Agreement object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val putAgbotAgreement2 = (apiOperation[PutAgbotAgreementRequest]("putAgreement2") summary("a") notes("a"))  // for some bizarre reason, the PutAgreementsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PUT /agbots/{id}/agreements/{agid}. Normally called by agbot to add/update itself. */
  put("/agbots/:id/agreements/:agid", operation(putAgbotAgreement)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    val agrId = params("agid")
    validateUserOrAgbotId(BaseAccess.WRITE, id)
    val agreement = try { parse(request.body).extract[PutAgbotAgreementRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val resp = response
    db.run(AgbotAgreementsTQ.getNumOwned(id).result.flatMap({ xs =>
      logger.debug("PUT /agbots/"+id+"/agreements/"+agrId+" num owned: "+xs)
      val numOwned = xs
      val maxAgreements = ExchConfig.getInt("api.limits.maxAgreements")
      if (numOwned <= maxAgreements) {    // we are not sure if this is create or update, but if they are already over the limit, stop them anyway
        agreement.toAgbotAgreementRow(id, agrId).upsert.asTry
      }
      else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxAgreements+ " agreements for this agbot")).asTry
    })).map({ xs =>
      logger.debug("PUT /agbots/"+id+"/agreements/"+agrId+" result: "+xs.toString)
      xs match {
        case Success(v) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "agreement added or updated")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, "agreement '"+agrId+"' for agbot '"+id+"' not inserted or updated: "+t.getMessage)
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "agreement '"+agrId+"' for agbot '"+id+"' not inserted or updated: "+t.toString)
          }
      }
    })
  })

  // =========== DELETE /agbots/{id}/agreements ===============================
  val deleteAgbotAllAgreement =
    (apiOperation[ApiResponse]("deleteAgbotAllAgreement")
      summary "Deletes all agreements of a agbot"
      notes "Deletes all of the current agreements of a agbot from the exchange DB. Can be run by the owning user or the agbot."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot for which the agreement is to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /agbots/{id}/agreements. */
  delete("/agbots/:id/agreements", operation(deleteAgbotAllAgreement)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    validateUserOrAgbotId(BaseAccess.WRITE, id)
    val resp = response
    db.run(AgbotAgreementsTQ.getAgreements(id).delete.asTry).map({ xs =>
      logger.debug("DELETE /agbots/"+id+"/agreements result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.DELETED)
              ApiResponse(ApiResponseType.OK, "agbot agreements deleted")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "no agreements for agbot '"+id+"' found")
            }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "agreements for agbot '"+id+"' not deleted: "+t.toString)
        }
    })
  })

  // =========== DELETE /agbots/{id}/agreements/{agid} ===============================
  val deleteAgbotAgreement =
    (apiOperation[ApiResponse]("deleteAgbotAgreement")
      summary "Deletes an agreement of a agbot"
      notes "Deletes an agreement of a agbot from the exchange DB. Can be run by the owning user or the agbot."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot for which the agreement is to be deleted."), paramType = ParamType.Path),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /agbots/{id}/agreements/{agid}. */
  delete("/agbots/:id/agreements/:agid", operation(deleteAgbotAgreement)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    val agrId = params("agid")
    validateUserOrAgbotId(BaseAccess.WRITE, params("id"))
    val resp = response
    db.run(AgbotAgreementsTQ.getAgreement(id,agrId).delete.asTry).map({ xs =>
      logger.debug("DELETE /agbots/"+id+"/agreements/"+agrId+" result: "+xs.toString)
      xs match {
        case Success(v) => try {        // there were no db errors, but determine if it actually found it or not
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "agbot agreement deleted")
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from agbot agreement delete: "+e) }    // the specific exception is NumberFormatException
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "agreement '"+agrId+"' for agbot '"+id+"' not deleted: "+t.toString)
        }
    })
  })

  // =========== POST /agbots/{id}/dataheartbeat ===============================
  val postAgbotsDataHeartbeat =
    (apiOperation[ApiResponse]("postAgbotsDataHeartbeat")
      summary "Not supported yet - Tells the exchange that data has been received for these agreements"
      notes "Lets the exchange know that data has just been received for this list of agreement IDs. This is normally run by a cloud data aggregation service that is registered as an agbot of the same exchange user account that owns the agbots that are contracting on behalf of a workload. Can be run by the owning user or any of the agbots owned by that user. The other agbot that negotiated this agreement id can run POST /agbots/{id}/isrecentdata check the dataLastReceived value of the agreement to determine if the agreement should be canceled (if data verification is enabled)."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot running this REST API method."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[List[String]],
          Option[String]("List of agreement IDs that have received data very recently."),
          paramType = ParamType.Body)
        )
      )

  /** Handles POST /agbots/{id}/dataheartbeat. */
  post("/agbots/:id/dataheartbeat", operation(postAgbotsDataHeartbeat)) ({
    val id = params("id")
    validateUserOrAgbotId(BaseAccess.DATA_HEARTBEAT, id)
    val agrIds = try { parse(request.body).extract[List[String]] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val agreementIds = agrIds.toSet

    /*todo: implement persistence
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
    */
    status_=(HttpCode.NOT_IMPLEMENTED)
    ApiResponse(ApiResponseType.NOT_IMPLEMENTED, "data heartbeats not implemented yet")
  })

  // =========== POST /agbots/{id}/isrecentdata ===============================
  val postAgbotsIsRecentData =
    (apiOperation[List[PostAgbotsIsRecentDataElement]]("postAgbotsIsRecentData")
      summary "Not supported yet - Returns whether each agreement has received data or not"
      notes "Queries the exchange to find out if each of the specified agreement IDs has had POST /agbots/{id}/dataheartbeat run on it recently (within secondsStale ago). This is normally run by agbots that are contracting on behalf of this workload to decide whether the agreement should be canceled or not. Can be run by the owning user or any of the agbots owned by that user."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot running this REST API method."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostAgbotsIsRecentDataRequest],
          Option[String]("List of agreement IDs that should be queried, and the time threshold to use."),
          paramType = ParamType.Body)
        )
      )
  val postAgbotsIsRecentData2 = (apiOperation[PostAgbotsIsRecentDataRequest]("postAgbotsIsRecentData2") summary("a") notes("a"))

  /** Handles POST /agbots/{id}/isrecentdata. */
  post("/agbots/:id/isrecentdata", operation(postAgbotsIsRecentData)) ({
    val id = params("id")
    validateUserOrAgbotId(BaseAccess.DATA_HEARTBEAT, id)
    val req = try { parse(request.body).extract[PostAgbotsIsRecentDataRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val secondsStale = req.secondsStale
    val agreementIds = req.agreementIds.toSet

    /*todo: implement persistence
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
    */
    status_=(HttpCode.NOT_IMPLEMENTED)
    ApiResponse(ApiResponseType.NOT_IMPLEMENTED, "isrecentdata not implemented yet")
  })

  // =========== POST /agreements/confirm ===============================
  val postAgreementsConfirm =
    (apiOperation[ApiResponse]("postAgreementsConfirm")
      summary "Confirms if this agbot agreement is active"
      notes "Confirms whether or not this agreement id is valid, is owned by an agbot owned by this same username, and is a currently active agreement. Can only be run by an agbot or user."
      parameters(
        Parameter("username", DataType.String, Option[String]("Username or agbot id. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password or token of the user/agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostAgreementsConfirmRequest],
          Option[String]("Agreement ID that should be confirmed."),
          paramType = ParamType.Body)
        )
      )
  val postAgreementsConfirm2 = (apiOperation[PostAgreementsConfirmRequest]("postAgreementsConfirm2") summary("a") notes("a"))

  /** Handles POST /agreements/confirm. */
  post("/agreements/confirm", operation(postAgreementsConfirm)) ({
    val creds = validateUserOrAgbotId(BaseAccess.AGREEMENT_CONFIRM, "#")
    val req = try { parse(request.body).extract[PostAgreementsConfirmRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException

    val resp = response
    val owner = if (isAuthenticatedUser(creds)) creds.id else ""
    if (owner != "") {
      // the user invoked this rest method, so look for an agbot owned by this user with this agr id
      val agbotAgreementJoin = for {
        (agbot, agr) <- AgbotsTQ.rows joinLeft AgbotAgreementsTQ.rows on (_.id === _.agbotId)
        if agbot.owner === owner && agr.map(_.agrId) === req.agreementId
      } yield (agbot, agr)
      db.run(agbotAgreementJoin.result).map({ list =>
        logger.debug("POST /agreements/confirm of "+req.agreementId+" result: "+list.toString)
        // this list is tuples of (AgbotRow, Option(AgbotAgreementRow)) in which agbot.owner === owner && agr.agrId === req.agreementId
        if (list.size > 0 && !list.head._2.isEmpty && list.head._2.get.state != "") {
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "agreement active")
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "agreement not found or not active")
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
        if (list.size > 0) {
          // this list is tuples of (AgbotRow, Option(AgbotAgreementRow)) in which agbot.id === creds.id || agr.agrId === req.agreementId
          val agbot1 = list.find(r => r._1.id == creds.id).orNull
          val agbot2 = list.find(r => !r._2.isEmpty && r._2.get.agrId == req.agreementId).orNull
          if (agbot1 != null && agbot2 != null && agbot1._1.owner == agbot2._1.owner && agbot2._2.get.state != "") {
            resp.setStatus(HttpCode.POST_OK)
            ApiResponse(ApiResponseType.OK, "agreement active")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "agreement not found or not active")
          }
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "agreement not found or not active")
        }
      })
    }
  })

  // =========== POST /agbots/{id}/msgs ===============================
  val postAgbotsMsgs =
    (apiOperation[ApiResponse]("postAgbotsMsgs")
      summary "Sends a msg from a device to a agbot"
      notes """Sends a msg from a device to a agbot. The device must 1st sign the msg (with its private key) and then encrypt the msg (with the agbots's public key). Can be run by any device. The **request body** structure:

```
{
  "message": "VW1RxzeEwTF0U7S96dIzSBQ/hRjyidqNvBzmMoZUW3hpd3hZDvs",     // msg to be sent to the agbot
  "ttl": 86400       // time-to-live of this msg, in seconds
}
```
      """
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot to send a msg to."), paramType = ParamType.Path),
        // Device id/token must be in the header
        Parameter("body", DataType[PostAgbotsMsgsRequest],
          Option[String]("Signed/encrypted message to send to the agbot. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val postAgbotsMsgs2 = (apiOperation[PostAgbotsMsgsRequest]("postAgbotsMsgs2") summary("a") notes("a"))

  /** Handles POST /agbots/{id}/msgs. */
  post("/agbots/:id/msgs", operation(postAgbotsMsgs)) ({
    val agbotId = params("id")
    val creds = validateDeviceId(BaseAccess.SEND_MSG)
    val devId = creds.id
    val msg = try { parse(request.body).extract[PostAgbotsMsgsRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val resp = response
    // Remove msgs whose TTL is past, then check the mailbox is not full, then get the device publicKey, then write the agbotmsgs row, all in the same db.run thread
    db.run(AgbotMsgsTQ.getMsgsExpired.delete.flatMap({ xs =>
      logger.debug("POST /agbots/"+agbotId+"/msgs delete expired result: "+xs.toString)
      AgbotMsgsTQ.getNumOwned(agbotId).result
    }).flatMap({ xs =>
      logger.debug("POST /agbots/"+agbotId+"/msgs mailbox size: "+xs)
      val mailboxSize = xs
      val maxMessagesInMailbox = ExchConfig.getInt("api.limits.maxMessagesInMailbox")
      if (mailboxSize < maxMessagesInMailbox) DevicesTQ.getPublicKey(devId).result.asTry
      else DBIO.failed(new Throwable("Access Denied: the message mailbox of "+agbotId+" is full ("+maxMessagesInMailbox+ " messages)")).asTry
    }).flatMap({ xs =>
      logger.debug("POST /agbots/"+agbotId+"/msgs device publickey result: "+xs.toString)
      xs match {
        case Success(v) => val devicePubKey = v.head
          if (devicePubKey != "") AgbotMsgRow(0, agbotId, devId, devicePubKey, msg.message, ApiTime.nowUTC, ApiTime.futureUTC(msg.ttl)).insert.asTry
          else DBIO.failed(new Throwable("Invalid Input: the message sender must have their public key registered with the Exchange")).asTry
        case Failure(t) => DBIO.failed(t).asTry       // rethrow the error to the next step
      }
    })).map({ xs =>
      logger.debug("POST /agbots/"+agbotId+"/msgs write row result: "+xs.toString)
      xs match {
        case Success(v) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "agbot msg "+v+" inserted")
        case Failure(t) => if (t.getMessage.startsWith("Invalid Input:")) {
            resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, "agbot '"+agbotId+"' msg not inserted: "+t.getMessage)
          } else if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, "agbot '"+agbotId+"' msg not inserted: "+t.getMessage)
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "agbot '"+agbotId+"' msg not inserted: "+t.toString)
          }
        }
    })
  })

  /* ====== GET /agbots/{id}/msgs ================================ */
  val getAgbotMsgs =
    (apiOperation[GetAgbotMsgsResponse]("getAgbotMsgs")
      summary("Returns all msgs sent to this agbot")
      notes("""Returns all msgs that have been sent to this agbot. They will be returned in the order they were sent. All msgs that have been sent to this agbot will be returned, unless the agbot has deleted some, or some are past their TTL. Can be run by a user or the agbot.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /agbots/{id}/msgs. Normally called by the user to see all msgs of this agbot. */
  get("/agbots/:id/msgs", operation(getAgbotMsgs)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    validateUserOrAgbotId(BaseAccess.READ, id)
    val resp = response
    // Remove msgs whose TTL is past, and then get the msgs for this agbot
    db.run(AgbotMsgsTQ.getMsgsExpired.delete.flatMap({ xs =>
      logger.debug("GET /agbots/"+id+"/msgs delete expired result: "+xs.toString)
      AgbotMsgsTQ.getMsgs(id).result
    })).map({ list =>
      logger.debug("GET /agbots/"+id+"/msgs result size: "+list.size)
      logger.trace("GET /agbots/"+id+"/msgs result: "+list.toString)
      val listSorted = list.sortWith(_.msgId < _.msgId)
      val msgs = new ListBuffer[AgbotMsg]
      if (listSorted.size > 0) for (m <- listSorted) { msgs += m.toAgbotMsg }
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetAgbotMsgsResponse(msgs.toList, 0)
    })
  })

  // =========== DELETE /agbots/{id}/msgs/{msgid} ===============================
  val deleteAgbotMsg =
    (apiOperation[ApiResponse]("deleteAgbotMsg")
      summary "Deletes an msg of a agbot"
      notes "Deletes an msg that was sent to a agbot. This should be done by the agbot after each msg is read. Can be run by the owning user or the agbot."
      parameters(
        Parameter("id", DataType.String, Option[String]("ID of the agbot to be deleted."), paramType = ParamType.Path),
        Parameter("msgid", DataType.String, Option[String]("ID of the msg to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /agbots/{id}/msgs/{msgid}. */
  delete("/agbots/:id/msgs/:msgid", operation(deleteAgbotMsg)) ({
    val id = if (params("id") == "{id}") swaggerHack("id") else params("id")
    val msgId = try { params("msgid").toInt } catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "msgid must be an integer: "+e)) }    // the specific exception is NumberFormatException
    validateUserOrAgbotId(BaseAccess.WRITE, id)
    val resp = response
    db.run(AgbotMsgsTQ.getMsg(id,msgId).delete.asTry).map({ xs =>
      logger.debug("DELETE /agbots/"+id+"/msgs/"+msgId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.DELETED)
              ApiResponse(ApiResponseType.OK, "agbot msg deleted")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "msg '"+msgId+"' for agbot '"+id+"' not found")
            }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "msg '"+msgId+"' for agbot '"+id+"' not deleted: "+t.toString)
        }
    })
  })

}