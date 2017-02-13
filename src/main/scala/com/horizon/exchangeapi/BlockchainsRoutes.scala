/** Services routes for all of the /bctypes api methods. */
package com.horizon.exchangeapi

import org.scalatra._
import slick.jdbc.PostgresProfile.api._
import org.scalatra.swagger._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.{read, write}
import org.scalatra.json._
import org.slf4j._
import Access._
// import BaseAccess._
import scala.util._
import scala.collection.immutable._
import scala.collection.mutable.{ListBuffer, Set => MutableSet, HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection
import com.horizon.exchangeapi.tables._

//====== These are the input and output structures for /bctypes routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /bctypes */
case class GetBctypesResponse(bctypes: Map[String,Bctype], lastIndex: Int)
case class GetBctypeAttributeResponse(attribute: String, value: String)

/** Input format for PUT /bctypes/<bctype-id> */
case class PutBctypeRequest(description: String, containerInfo: Map[String,String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate = {
    // if (msgEndPoint == "" && publicKey == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "either msgEndPoint or publicKey must be specified."))  <-- skipping this check because POST /devices/{id}/msgs checks for the publicKey
  }

  def toBctypeRow(bctype: String, owner: String) = BctypeRow(bctype, description, owner, write(containerInfo), ApiTime.nowUTC)
}

case class PatchBctypeRequest(description: Option[String], containerInfo: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the bctype, and the attribute name being updated. */
  def getDbUpdate(bctype: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    //todo: support updating more than 1 attribute
    // find the 1st attribute that was specified in the body and create a db action to update it for this bctype
    description match { case Some(description) => return ((for { d <- BctypesTQ.rows if d.bctype === bctype } yield (d.bctype,d.description,d.lastUpdated)).update((bctype, description, lastUpdated)), "description"); case _ => ; }
    containerInfo match {
      case Some(ci) => val cInfo = if (ci != "") write(containerInfo) else ""
        return ((for { d <- BctypesTQ.rows if d.bctype === bctype } yield (d.bctype,d.containerInfo,d.lastUpdated)).update((bctype, cInfo, lastUpdated)), "containerInfo")
      case _ => ;
    }
    return (null, null)
  }
}


/** Output format for GET /bctypes/{bctype}/blockchains */
case class GetBlockchainsResponse(blockchains: Map[String,Blockchain], lastIndex: Int)
case class GetBlockchainAttributeResponse(attribute: String, value: String)

/** Input format for PUT /bctypes/{bctype}/blockchains/<name> */
case class PutBlockchainRequest(description: String, bootNodes: List[String], genesis: List[String], networkId: List[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def toBlockchainRow(bctype: String, name: String, owner: String) = BlockchainRow(name, bctype, description, owner, write(bootNodes), write(genesis), write(networkId), ApiTime.nowUTC)
  // def toBlockchainRow(bctype: String, name: String, owner: String) = BlockchainRow(name, bctype, description, owner, "a", "b", "c", ApiTime.nowUTC)
}

case class PatchBlockchainRequest(description: Option[String], bootNodes: Option[List[String]], genesis: Option[List[String]], networkId: Option[List[String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the blockchain, and the attribute name being updated. */
  def getDbUpdate(bctype: String, name: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    //todo: support updating more than 1 attribute
    // find the 1st attribute that was specified in the body and create a db action to update it for this bctype
    description match { case Some(description) => return ((for { d <- BlockchainsTQ.rows if d.bctype === bctype && d.name === name } yield (d.name, d.bctype,d.description,d.lastUpdated)).update((name, bctype, description, lastUpdated)), "description"); case _ => ; }
    bootNodes match {
      case Some(bn) => val bNodes = if (bn != "") write(bootNodes) else ""
        return ((for { d <- BlockchainsTQ.rows if d.bctype === bctype && d.name === name } yield (d.name,d.bctype,d.bootNodes,d.lastUpdated)).update((name, bctype, bNodes, lastUpdated)), "bootNodes")
      case _ => ;
    }
    genesis match {
      case Some(g) => val gen = if (g != "") write(genesis) else ""
        return ((for { d <- BlockchainsTQ.rows if d.bctype === bctype && d.name === name } yield (d.name,d.bctype,d.genesis,d.lastUpdated)).update((name, bctype, gen, lastUpdated)), "genesis")
      case _ => ;
    }
    networkId match {
      case Some(n) => val netId = if (n != "") write(networkId) else ""
        return ((for { d <- BlockchainsTQ.rows if d.bctype === bctype && d.name === name } yield (d.name,d.bctype,d.networkId,d.lastUpdated)).update((name, bctype, netId, lastUpdated)), "networkId")
      case _ => ;
    }
    return (null, null)
  }
}



/** Implementation for all of the /bctypes routes */
trait BlockchainsRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  /* ====== GET /bctypes ================================ */
  val getBctypes =
    (apiOperation[GetBctypesResponse]("getBctypes")
      summary("Returns all blockchain types")
      notes("""Returns all Blockchain type definitions in the exchange DB. Can be run by any user, device, or agbot.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the device or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the device or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("bctype", DataType.String, Option[String]("Filter results to only include bctypes with this bctype (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("description", DataType.String, Option[String]("Filter results to only include bctypes with this description (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("definedBy", DataType.String, Option[String]("Filter results to only include bctypes defined by this user (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /bctypes. Can be called by anyone. */
  get("/bctypes", operation(getBctypes)) ({
    // val creds = validateUserOrId(BaseAccess.READ_ALL_BLOCKCHAINS)
    val ident = credsAndLog().authenticate().authorizeTo(TBctype("*"),Access.READ)
    var q = BctypesTQ.rows.subquery
    params.get("bctype").foreach(bctype => { if (bctype.contains("%")) q = q.filter(_.bctype like bctype) else q = q.filter(_.bctype === bctype) })
    params.get("description").foreach(description => { if (description.contains("%")) q = q.filter(_.description like description) else q = q.filter(_.description === description) })
    params.get("definedBy").foreach(definedBy => { if (definedBy.contains("%")) q = q.filter(_.definedBy like definedBy) else q = q.filter(_.definedBy === definedBy) })

    db.run(q.result).map({ list =>
      logger.debug("GET /bctypes result size: "+list.size)
      val bctypes = new MutableHashMap[String,Bctype]
      for (a <- list) bctypes.put(a.bctype, a.toBctype)
      GetBctypesResponse(bctypes.toMap, 0)
    })
  })

  /* ====== GET /bctypes/{bctype} ================================ */
  val getOneBctype =
    (apiOperation[GetBctypesResponse]("getOneBctype")
      summary("Returns a blockchain type")
      notes("""Returns the blockchain type with the specified type name in the exchange DB. Can be run by a user, device, or agbot.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("bctype", DataType.String, Option[String]("Blockchain type."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the device or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the device or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire bctype resource will be returned."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /bctypes/{bctype}. Can be called by anyone. */
  get("/bctypes/:bctype", operation(getOneBctype)) ({
    val bctype = swaggerHack("bctype")
    // val creds = validateUserOrId(BaseAccess.READ_ALL_BLOCKCHAINS)
    val ident = credsAndLog().authenticate().authorizeTo(TBctype(bctype),Access.READ)
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the bctype
        val q = BctypesTQ.getAttribute(bctype, attribute)       // get the proper db query for this attribute
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Bctype attribute name '"+attribute+"' is not an attribute of the bctype resource."))
        db.run(q.result).map({ list =>
          logger.trace("GET /bctypes/"+bctype+" attribute result: "+list.toString)
          if (list.size > 0) {
            GetBctypeAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")
          }
        })

      case None => ;  // Return the whole bctype resource
        db.run(BctypesTQ.getBctype(bctype).result).map({ list =>
          logger.debug("GET /bctypes/"+bctype+" result: "+list.toString)
          val bctypes = new MutableHashMap[String,Bctype]
          if (list.size > 0) for (a <- list) bctypes.put(a.bctype, a.toBctype)
          else resp.setStatus(HttpCode.NOT_FOUND)
          GetBctypesResponse(bctypes.toMap, 0)
        })
    }
  })

  // =========== PUT /bctypes/{bctype} ===============================
  val putBctypes =
    (apiOperation[ApiResponse]("putBctypes")
      summary "Adds/updates a blockchain type"
      notes """Adds a new blockchain type to the exchange DB, or updates an existing blockchain type. This can only be called by a user to create, and then only by that user to update. The **request body** structure:

```
{
  "description": "abc",       // description of the blockchain type
  "containerInfo": "{ ... }"
}
```"""
      parameters(
        Parameter("bctype", DataType.String, Option[String]("Blockchain type."), paramType=ParamType.Query),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutBctypeRequest],
          Option[String]("Bctype object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val putBctypes2 = (apiOperation[PutBctypeRequest]("putBctypes2") summary("a") notes("a"))  // for some bizarre reason, the PutBctypeRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PUT /bctype/{bctype}. Called by a user to create, must be called by same user to update. */
  put("/bctypes/:bctype", operation(putBctypes)) ({
    val bctype = swaggerHack("bctype")
    // val creds = validateUserOrId(BaseAccess.WRITE_ALL_BLOCKCHAINS)      // we currently only allow users to update BCs, but we will let the ACLs sort that out
    val ident = credsAndLog().authenticate().authorizeTo(TBctype(bctype),Access.WRITE)
    val bctypeReq = try { parse(request.body).extract[PutBctypeRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    bctypeReq.validate
    // val owner = if (isAuthenticatedUser(creds)) creds.id else ""
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    db.run(BctypesTQ.getNumOwned(owner).result.flatMap({ xs =>
      logger.debug("PUT /bctypes/"+bctype+" num owned: "+xs)
      val numOwned = xs
      val maxBlockchains = ExchConfig.getInt("api.limits.maxBlockchains")
      if (numOwned <= maxBlockchains) {    // we are not sure if this is a create of update, but if they are already over the limit, stop them anyway
        bctypeReq.toBctypeRow(bctype, owner).upsert.asTry
      }
      else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxBlockchains+ " bctypes")).asTry
    })).map({ xs =>
      logger.debug("PUT /bctypes/"+bctype+" result: "+xs.toString)
      xs match {
        case Success(v) => if (owner != "") AuthCache.bctypes.putOwner(bctype, owner)     // currently only users are allowed to create/update bc resources, so owner should never be blank
          resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "bctype added or updated")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, "bctype '"+bctype+"' not inserted or updated: "+t.getMessage)
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "bctype '"+bctype+"' not inserted or updated: "+t.toString)
          }
      }
    })
  })

  // =========== PATCH /bctypes/{bctype} ===============================
  val patchBctypes =
    (apiOperation[Map[String,String]]("patchBctypes")
      summary "Partially updates a blockchain type"
      notes """Updates one attribute of a blockchain type in the exchange DB. This can only be called by the user that originally created this bctype resource. The **request body** structure can include **1 of these attributes**:

```
{
  "description": "abc"       // description of the blockchain type
  "containerInfo": "{ ... }"
}
```

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the PATCH method to see the response format instead.**"""
      parameters(
        Parameter("bctype", DataType.String, Option[String]("Blockchain type."), paramType=ParamType.Query),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchBctypeRequest],
          Option[String]("Partial bctype object that contains an attribute to be updated in this bctype. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val patchBctypes2 = (apiOperation[PatchBctypeRequest]("patchBctypes2") summary("a") notes("a"))  // for some bizarre reason, the PatchBctypeRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PATCH /bctype/{bctype}. Must be called by the same user that created it. */
  patch("/bctypes/:bctype", operation(patchBctypes)) ({
    val bctype = swaggerHack("bctype")
    // val creds = validateUserOrId(BaseAccess.WRITE)
    val ident = credsAndLog().authenticate().authorizeTo(TBctype(bctype),Access.WRITE)
    val bctypeReq = try { parse(request.body).extract[PatchBctypeRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.trace("PATCH /bctypes/"+bctype+" input: "+bctypeReq.toString)
    val resp = response
    val (action, attrName) = bctypeReq.getDbUpdate(bctype)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid bctype attribute specified"))
    db.run(action.transactionally.asTry).map({ xs =>
      logger.debug("PATCH /bctypes/"+bctype+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "attribute '"+attrName+"' of bctype '"+bctype+"' updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "bctype '"+bctype+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "bctype '"+bctype+"' not inserted or updated: "+t.toString)
      }
    })
  })

  // =========== DELETE /bctypes/{bctype} ===============================
  val deleteBctypes =
    (apiOperation[ApiResponse]("deleteBctypes")
      summary "Deletes a blockchain type"
      notes "Deletes a blockchain type from the exchange DB, and deletes the blockchain definitions stored for this blockchain type. Can only be run by the owning user."
      parameters(
        Parameter("bctype", DataType.String, Option[String]("Blockchain type."), paramType=ParamType.Query),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /bctypes/{bctype}. Must be called by user. */
  delete("/bctypes/:bctype", operation(deleteBctypes)) ({
    val bctype = swaggerHack("bctype")
    // validateUserOrId(BaseAccess.WRITE)
    credsAndLog().authenticate().authorizeTo(TBctype(bctype),Access.WRITE)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(BctypesTQ.getBctype(bctype).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /bctypes/"+bctype+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.bctypes.removeOwner(bctype)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "bctype deleted")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "bctype '"+bctype+"' not found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "bctype '"+bctype+"' not deleted: "+t.toString)
      }
    })
  })

  /* ====== GET /bctypes/{bctype}/blockchains ================================ */
  val getBlockchains =
    (apiOperation[GetBlockchainsResponse]("getBlockchains")
      summary("Returns all blockchains of this blockchain type")
      notes("""Returns all blockchain instances that are this blockchain type. Can be run by any user, device, or agbot.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("bctype", DataType.String, Option[String]("Blockchain type."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the device or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Token of the bctype. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /bctypes/{bctype}/blockchains. Can be called by anyone to see all blockchains of this bctype. */
  get("/bctypes/:bctype/blockchains", operation(getBlockchains)) ({
    val bctype = swaggerHack("bctype")
    // validateUserOrId(BaseAccess.READ)
    credsAndLog().authenticate().authorizeTo(TBlockchain("*"),Access.READ)
    val resp = response
    db.run(BlockchainsTQ.getBlockchains(bctype).result).map({ list =>
      logger.debug("GET /bctypes/"+bctype+"/blockchains result size: "+list.size)
      logger.trace("GET /bctypes/"+bctype+"/blockchains result: "+list.toString)
      val blockchains = new MutableHashMap[String, Blockchain]
      if (list.size > 0) for (e <- list) { blockchains.put(e.name, e.toBlockchain) }
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetBlockchainsResponse(blockchains.toMap, 0)
    })
  })

  /* ====== GET /bctypes/{bctype}/blockchains/{name} ================================ */
  val getOneBlockchain =
    (apiOperation[GetBlockchainsResponse]("getOneBlockchain")
      summary("Returns a blockchain for a blockchain type")
      notes("""Returns the blockchain definition with the specified name for the specified blockchain type in the exchange DB. Can be run by any user, device, or agbot. **Because of a swagger bug this method can not be run via swagger.**

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("bctype", DataType.String, Option[String]("Blockchain type."), paramType=ParamType.Query),
        Parameter("name", DataType.String, Option[String]("Name of the blockchain."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the device or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Token of the bctype. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire blockchain resource will be returned."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /bctypes/{bctype}/blockchains/{name}. Can be called by anyone */
  get("/bctypes/:bctype/blockchains/:name", operation(getOneBlockchain)) ({
    val bctype = swaggerHack("bctype")   // but do not have a hack/fix for the name
    val name = params("name")
    val compositeId = name+"|"+bctype
    // validateUserOrId(BaseAccess.READ)
    credsAndLog().authenticate().authorizeTo(TBlockchain(compositeId),Access.READ)
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the blockchain
        val q = BlockchainsTQ.getAttribute(bctype, name, attribute)       // get the proper db query for this attribute
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Blockchain attribute name '"+attribute+"' is not an attribute of the blockchain resource."))
        db.run(q.result).map({ list =>
          logger.trace("GET /bctypes/"+bctype+"/blockchains/"+name+" attribute result: "+list.toString)
          if (list.size > 0) {
            GetBlockchainAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")
          }
        })

      case None => ;  // Return the whole blockchain resource
        db.run(BlockchainsTQ.getBlockchain(bctype, name).result).map({ list =>
          logger.debug("GET /bctypes/"+bctype+"/blockchains/"+name+" result: "+list.toString)
          val blockchains = new MutableHashMap[String, Blockchain]
          if (list.size > 0) for (e <- list) { blockchains.put(e.name, e.toBlockchain) }
          else resp.setStatus(HttpCode.NOT_FOUND)
          GetBlockchainsResponse(blockchains.toMap, 0)
        })
    }
  })

  // =========== PUT /bctypes/{bctype}/blockchains/{name} ===============================
  val putBlockchain =
    (apiOperation[ApiResponse]("putBlockchain")
      summary "Adds/updates a blockchain of a blockchain type"
      notes """Adds a new blockchain definition of a blockchain type to the exchange DB, or updates an existing blockchain definition. This can only be called by a user to create, and then only by that user to update. The **request body** structure:

```
{
  "description": "abc",
  "bootNodes": [ "url1", "url2", "url3" ],
  "genesis": [ "url1", "url2", "url3" ],
  "networkId": [ "url1", "url2", "url3" ]
}
```"""
      parameters(
        Parameter("bctype", DataType.String, Option[String]("Blockchain type."), paramType=ParamType.Query),
        Parameter("name", DataType.String, Option[String]("Name of the blockchain to be added/updated."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutBlockchainRequest],
          Option[String]("Blockchain object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val putBlockchain2 = (apiOperation[PutBlockchainRequest]("putBlockchain2") summary("a") notes("a"))  // for some bizarre reason, the PutBlockchainsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PUT /bctypes/{bctype}/blockchains/{name}. Called by user to add/update a bc definition. */
  put("/bctypes/:bctype/blockchains/:name", operation(putBlockchain)) ({
    val bctype = swaggerHack("bctype")
    val name = params("name")
    val compositeId = name+"|"+bctype
    // validateUserOrId(BaseAccess.WRITE)
    val ident = credsAndLog().authenticate().authorizeTo(TBlockchain(compositeId),Access.WRITE)
    val blockchain = try { parse(request.body).extract[PutBlockchainRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val resp = response
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    db.run(BlockchainsTQ.getNumOwned(owner).result.flatMap({ xs =>
      logger.debug("PUT /bctypes/"+bctype+"/blockchains/"+name+" num owned: "+xs)
      val numOwned = xs
      val maxBlockchains = ExchConfig.getInt("api.limits.maxBlockchains")
      if (numOwned <= maxBlockchains) {    // we are not sure if this is create or update, but if they are already over the limit, stop them anyway
        //todo: upsert does not work for this table due to this slick bug: https://github.com/slick/slick/issues/966. So we have to emulate it.
        // blockchain.toBlockchainRow(bctype, name, owner).upsert.asTry
        BlockchainsTQ.getBlockchain(bctype, name).result.asTry
      }
      else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxBlockchains+ " blockchains for this bctype")).asTry
    }).flatMap({ xs =>
      logger.debug("PUT /bctypes/"+bctype+"/blockchains/"+name+" get existing: "+xs.toString)
      xs match {
        case Success(v) => val bcExists = (v.size > 0)
          if (bcExists) { logger.debug("PUT /bctypes/"+bctype+"/blockchains/"+name+" updating existing row"); blockchain.toBlockchainRow(bctype, name, owner).update.asTry }
          else { logger.debug("PUT /bctypes/"+bctype+"/blockchains/"+name+" inserting new row"); blockchain.toBlockchainRow(bctype, name, owner).insert.asTry }
        case Failure(t) => DBIO.failed(t).asTry       // rethrow the error to the next step
      }
    })).map({ xs =>
      logger.debug("PUT /bctypes/"+bctype+"/blockchains/"+name+" result: "+xs.toString)
      xs match {
        case Success(v) => AuthCache.blockchains.putOwner(compositeId, owner)
          resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "blockchain added or updated")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, "blockchain '"+name+"' for bctype '"+bctype+"' not inserted or updated: "+t.getMessage)
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "blockchain '"+name+"' for bctype '"+bctype+"' not inserted or updated: "+t.toString)
          }
      }
    })
  })

  // =========== PATCH /bctypes/{bctype}/blockchains/{name} ===============================
  val patchBlockchain =
    (apiOperation[Map[String,String]]("patchBlockchain")
      summary "Partially updates a blockchain definition"
      notes """Updates one attribute of a blockchain instance in the exchange DB. This can only be called by the user that originally created this blockchain resource. The **request body** structure can include **1 of these attributes**:

```
{
  "description": "abc"
  "bootNodes": [ "url1", "url2", "url3" ]
  "genesis": [ "url1", "url2", "url3" ]
  "networkId": [ "url1", "url2", "url3" ]
}
```

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the PATCH method to see the response format instead.**"""
      parameters(
        Parameter("bctype", DataType.String, Option[String]("Blockchain type."), paramType=ParamType.Query),
        Parameter("name", DataType.String, Option[String]("Blockchain instance name."), paramType=ParamType.Query),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchBlockchainRequest],
          Option[String]("Partial blockchain object that contains an attribute to be updated in this blockchain. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val patchBlockchain2 = (apiOperation[PatchBlockchainRequest]("patchBlockchain2") summary("a") notes("a"))  // for some bizarre reason, the PatchBlockchainRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PATCH /bctype/{bctype}/blockchains/{name}. Must be called by the same user that created this bc. */
  patch("/bctypes/:bctype/blockchains/:name", operation(patchBlockchain)) ({
    val bctype = swaggerHack("bctype")
    val name = params("name")
    val compositeId = name+"|"+bctype
    // val creds = validateUserOrId(BaseAccess.WRITE)
    val ident = credsAndLog().authenticate().authorizeTo(TBlockchain(compositeId),Access.WRITE)
    val bcReq = try { parse(request.body).extract[PatchBlockchainRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.trace("PATCH /bctypes/"+bctype+"/blockchains/"+name+" input: "+bcReq.toString)
    val resp = response
    val (action, attrName) = bcReq.getDbUpdate(bctype, name)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid bctype attribute specified"))
    db.run(action.transactionally.asTry).map({ xs =>
      logger.debug("PATCH /bctypes/"+bctype+"/blockchains/"+name+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "attribute '"+attrName+"' of blockchain '"+name+"' updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "blockchain '"+name+"' for bctype '"+bctype+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "blockchain '"+name+"' not inserted or updated: "+t.toString)
      }
    })
  })

  // =========== DELETE /bctypes/{bctype}/blockchains/{name} ===============================
  val deleteBlockchain =
    (apiOperation[ApiResponse]("deleteBlockchain")
      summary "Deletes a blockchain of a blockchain type"
      notes "Deletes a blockchain definition of a blockchain type from the exchange DB. Can only be run by the owning user."
      parameters(
        Parameter("bctype", DataType.String, Option[String]("Blockchain type."), paramType=ParamType.Query),
        Parameter("name", DataType.String, Option[String]("Name of the blockchain to be deleted."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /bctypes/{bctype}/blockchains/{name}. */
  delete("/bctypes/:bctype/blockchains/:name", operation(deleteBlockchain)) ({
    val bctype = swaggerHack("bctype")
    val name = params("name")
    val compositeId = name+"|"+bctype
    // validateUserOrId(BaseAccess.WRITE)
    credsAndLog().authenticate().authorizeTo(TBlockchain(compositeId),Access.WRITE)
    val resp = response
    db.run(BlockchainsTQ.getBlockchain(bctype,name).delete.asTry).map({ xs =>
      logger.debug("DELETE /bctypes/"+bctype+"/blockchains/"+name+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.blockchains.removeOwner(compositeId)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "bctype blockchain deleted")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "blockchain '"+name+"' for bctype '"+bctype+"' not found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "blockchain '"+name+"' for bctype '"+bctype+"' not deleted: "+t.toString)
        }
    })
  })

}