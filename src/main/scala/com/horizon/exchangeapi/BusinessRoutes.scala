/** Services routes for all of the /orgs/{orgid}/business api methods. */
package com.horizon.exchangeapi

import com.horizon.exchangeapi.tables._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatra._
import org.scalatra.swagger._
import org.slf4j._
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}
import scala.util._
import scala.util.control.Breaks._

//====== These are the input and output structures for /orgs/{orgid}/business/policies routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/business/policies */
case class GetBusinessPoliciesResponse(businessPolicy: Map[String,BusinessPolicy], lastIndex: Int)
case class GetBusinessPolicyAttributeResponse(attribute: String, value: String)

/** Input format for POST/PUT /orgs/{orgid}/business/policies/<bus-pol-id> */
case class PostPutBusinessPolicyRequest(label: String, description: Option[String], service: BService, userInput: Option[List[OneUserInputService]], properties: Option[List[OneProperty]], constraints: Option[List[String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate(): Unit = {
    // Check the version syntax
    for (sv <- service.serviceVersions) {
      if (!Version(sv.version).isValid) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "version '" + sv.version + "' is not valid version format."))
    }
  }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: (DBIO[Vector[Int]], Vector[ServiceRef2]) = { BusinessPoliciesTQ.validateServiceIds(service, userInput.getOrElse(List())) }

  // The nodeHealth field is optional, so fill in a default in service if not specified. (Otherwise json4s will omit it in the DB and the GETs.)
  def defaultNodeHealth(service: BService): BService = {
    if (service.nodeHealth.nonEmpty) return service
    val nodeHealth2 = Some(Map("missing_heartbeat_interval" -> 600, "check_agreement_status" -> 120)) // provide defaults for node health
    return BService(service.name, service.org, service.arch, service.serviceVersions, nodeHealth2)
  }

  // Note: write() handles correctly the case where the optional fields are None.
  def getDbInsert(businessPolicy: String, orgid: String, owner: String): DBIO[_] = {
    BusinessPolicyRow(businessPolicy, orgid, owner, label, description.getOrElse(label), write(defaultNodeHealth(service)), write(userInput), write(properties), write(constraints), ApiTime.nowUTC, ApiTime.nowUTC).insert
  }

  def getDbUpdate(businessPolicy: String, orgid: String, owner: String): DBIO[_] = {
    BusinessPolicyRow(businessPolicy, orgid, owner, label, description.getOrElse(label), write(defaultNodeHealth(service)), write(userInput), write(properties), write(constraints), ApiTime.nowUTC, "").update
  }
}

case class PatchBusinessPolicyRequest(label: Option[String], description: Option[String], service: Option[BService], userInput: Option[List[OneUserInputService]], properties: Option[List[OneProperty]], constraints: Option[List[String]]) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the businessPolicy, and the attribute name being updated. */
  def getDbUpdate(businessPolicy: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    //todo: support updating more than 1 attribute
    // find the 1st attribute that was specified in the body and create a db action to update it for this businessPolicy
    label match { case Some(lab) => return ((for { d <- BusinessPoliciesTQ.rows if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.label,d.lastUpdated)).update((businessPolicy, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- BusinessPoliciesTQ.rows if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.description,d.lastUpdated)).update((businessPolicy, desc, lastUpdated)), "description"); case _ => ; }
    service match { case Some(svc) => return ((for {d <- BusinessPoliciesTQ.rows if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.service,d.lastUpdated)).update((businessPolicy, write(svc), lastUpdated)), "service"); case _ => ; }
    userInput match { case Some(input) => return ((for { d <- BusinessPoliciesTQ.rows if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.userInput,d.lastUpdated)).update((businessPolicy, write(input), lastUpdated)), "userInput"); case _ => ; }
    properties match { case Some(prop) => return ((for { d <- BusinessPoliciesTQ.rows if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.properties,d.lastUpdated)).update((businessPolicy, write(prop), lastUpdated)), "properties"); case _ => ; }
    constraints match { case Some(con) => return ((for { d <- BusinessPoliciesTQ.rows if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.constraints,d.lastUpdated)).update((businessPolicy, write(con), lastUpdated)), "constraints"); case _ => ; }
    return (null, null)
  }
}


/** Input for business policy-based search for nodes to make agreements with. */
case class PostBusinessPolicySearchRequest(nodeOrgids: Option[List[String]], changedSince: Long, startIndex: Option[Int], numEntries: Option[Int]) {
  def validate() = { }
}

// Tried this to have names on the tuple returned from the db, but didn't work...
case class BusinessPolicySearchHashElement(msgEndPoint: String, publicKey: String, noAgreementYet: Boolean)

case class BusinessPolicyNodeResponse(id: String, msgEndPoint: String, publicKey: String)
case class PostBusinessPolicySearchResponse(nodes: List[BusinessPolicyNodeResponse], lastIndex: Int)



/** Implementation for all of the /orgs/{orgid}/business/policies routes */
trait BusinessRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  /* ====== GET /orgs/{orgid}/business/policies ================================ */
  val getBusinessPolicies =
    (apiOperation[GetBusinessPoliciesResponse]("getBusinessPolicies")
      summary("Returns all business policies")
      description("""Returns all business policy definitions in this organization. Can be run by any user, node, or agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("idfilter", DataType.String, Option[String]("Filter results to only include business policies with this id (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include business policies with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("label", DataType.String, Option[String]("Filter results to only include business policies with this label (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("description", DataType.String, Option[String]("Filter results to only include business policies with this description (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/business/policies", operation(getBusinessPolicies)) ({
    val orgid = params("orgid")
    val ident = authenticate().authorizeTo(TBusiness(OrgAndId(orgid,"*").toString),Access.READ)
    val resp = response
    var q = BusinessPoliciesTQ.getAllBusinessPolicies(orgid)
    // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
    params.get("idfilter").foreach(id => { if (id.contains("%")) q = q.filter(_.businessPolicy like id) else q = q.filter(_.businessPolicy === id) })
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
    params.get("label").foreach(lab => { if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab) })
    params.get("description").foreach(desc => { if (desc.contains("%")) q = q.filter(_.description like desc) else q = q.filter(_.description === desc) })

    db.run(q.result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/business/policies result size: "+list.size)
      val businessPolicy = new MutableHashMap[String,BusinessPolicy]
      if (list.nonEmpty) for (a <- list) if (ident.getOrg == a.orgid || ident.isSuperUser || ident.isMultiTenantAgbot) businessPolicy.put(a.businessPolicy, a.toBusinessPolicy)
      if (businessPolicy.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetBusinessPoliciesResponse(businessPolicy.toMap, 0)
    })
  })

  /* ====== GET /orgs/{orgid}/business/policies/{policy} ================================ */
  val getOneBusinessPolicy =
    (apiOperation[GetBusinessPoliciesResponse]("getOneBusinessPolicy")
      summary("Returns a business policy")
      description("""Returns the business policy with the specified id in the exchange DB. Can be run by a user, node, or agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("policy", DataType.String, Option[String]("Business Policy name."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire business policy resource will be returned."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/business/policies/:policy", operation(getOneBusinessPolicy)) ({
    val orgid = params("orgid")
    val bareBusinessPolicy = params("policy")   // but do not have a hack/fix for the name
    val businessPolicy = OrgAndId(orgid,bareBusinessPolicy).toString
    authenticate().authorizeTo(TBusiness(businessPolicy),Access.READ)
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the businessPolicy
        val q = BusinessPoliciesTQ.getAttribute(businessPolicy, attribute)       // get the proper db query for this attribute
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Business Policy attribute name '"+attribute+"' is not an attribute of the business policy resource."))
        db.run(q.result).map({ list =>
          logger.trace("GET /orgs/"+orgid+"/business/policies/"+bareBusinessPolicy+" attribute result: "+list.toString)
          if (list.nonEmpty) {
            resp.setStatus(HttpCode.OK)
            GetBusinessPolicyAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")
          }
        })

      case None => ;  // Return the whole business policy resource
        db.run(BusinessPoliciesTQ.getBusinessPolicy(businessPolicy).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/business/policies/"+bareBusinessPolicy+" result: "+list.size)
          val businessPolicies = new MutableHashMap[String,BusinessPolicy]
          if (list.nonEmpty) for (a <- list) businessPolicies.put(a.businessPolicy, a.toBusinessPolicy)
          if (businessPolicies.nonEmpty) resp.setStatus(HttpCode.OK)
          else resp.setStatus(HttpCode.NOT_FOUND)
          GetBusinessPoliciesResponse(businessPolicies.toMap, 0)
        })
    }
  })

  // =========== POST /orgs/{orgid}/business/policies/{policy} ===============================
  val postBusinessPolicies =
    (apiOperation[ApiResponse]("postBusinessPolicies")
      summary "Adds a business policy"
      description """Creates a business policy resource. A business policy resource specifies the service that should be deployed based on the specified properties and constraints. This can only be called by a user. The **request body** structure:

```
// (remove all of the comments like this before using)
{
  "label": "name of the business policy",     // this will be displayed in the UI
  "description": "descriptive text",
  // The services that this business policy applies to. (The services must exist before creating this business policy.)
  "service": {
    "name": "mydomain.com.weather",
    "org": "myorg",
    "arch": "amd64",   // can be set to "*" or "" to mean all architectures
    // If multiple service versions are listed, Horizon will try to automatically upgrade nodes to the version with the lowest priority_value number
    "serviceVersions": [
      {
        "version": "1.0.1",
        "priority": {      // can be omitted
          "priority_value": 50,
          "retries": 1,
          "retry_durations": 3600,
          "verified_durations": 52
        },
        // When Horizon should upgrade nodes to newer service versions. Can be set to {} to take the default of immediate.
        "upgradePolicy": {      // can be omitted
          "lifecycle": "immediate",
          "time": "01:00AM"     // reserved for future use
        }
      }
    ],
    // If not using agbot node health check, this field can be set to {} or omitted completely.
    "nodeHealth": {      // can be omitted
      "missing_heartbeat_interval": 600,      // How long a node heartbeat can be missing before cancelling its agreements (in seconds)
      "check_agreement_status": 120        // How often to check that the node agreement entry still exists, and cancel agreement if not found (in seconds)
    }
  },
  // Override or set user input variables that are defined in the services used by this business policy.
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",        // omit or leave blank to mean all architectures
      "serviceVersionRange": "[0.0.0,INFINITY)",   // or omit to mean all versions
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  "properties": [
    {
      "name": "mypurpose",
      "value": "myservice-testing"
      "type": "string"   // optional, the type of the 'value': string, int, float, boolean, list of string, version
    }
  ],
  "constraints": [
    "a == b"
  ]
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("policy", DataType.String, Option[String]("Business Policy name."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutBusinessPolicyRequest],
        Option[String]("Business Policy object that needs to be updated in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      )
  val postBusinessPolicies2 = (apiOperation[PostPutBusinessPolicyRequest]("postBusinessPolicies2") summary("a") description("a"))  // for some bizarre reason, the PostBusinessPolicyRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/business/policies/:policy", operation(postBusinessPolicies)) ({
    val orgid = params("orgid")
    val bareBusinessPolicy = params("policy")   // but do not have a hack/fix for the name
    val businessPolicy = OrgAndId(orgid,bareBusinessPolicy).toString
    val ident = authenticate().authorizeTo(TBusiness(OrgAndId(orgid,"").toString),Access.CREATE)
    val policyReq = try { parse(request.body).extract[PostPutBusinessPolicyRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    policyReq.validate()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    val (valServiceIdActions, svcRefs) = policyReq.validateServiceIds  // to check that the services referenced exist
    db.run(valServiceIdActions.asTry.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/business/policies"+bareBusinessPolicy+" service validation: "+xs.toString)
      xs match {
        case Success(v) => var invalidIndex = -1    // v is a vector of Int (the length of each service query). If any are zero we should error out.
          breakable { for ( (len, index) <- v.zipWithIndex) {
            if (len <= 0) {
              invalidIndex = index
              break
            }
          } }
          if (invalidIndex < 0) BusinessPoliciesTQ.getNumOwned(owner).result.asTry
          else {
            val errStr = if (invalidIndex < svcRefs.length) "the following referenced service does not exist in the exchange: org="+svcRefs(invalidIndex).org+", url="+svcRefs(invalidIndex).url+", version="+svcRefs(invalidIndex).versionRange+", arch="+svcRefs(invalidIndex).arch
              else "the "+Nth(invalidIndex+1)+" referenced service does not exist in the exchange"
            DBIO.failed(new Throwable(errStr)).asTry
          }
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/business/policies"+bareBusinessPolicy+" num owned by "+owner+": "+xs)
      xs match {
        case Success(num) => val numOwned = num
          val maxBusinessPolicies = ExchConfig.getInt("api.limits.maxBusinessPolicies")
          if (maxBusinessPolicies == 0 || numOwned <= maxBusinessPolicies) {    // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
            policyReq.getDbInsert(businessPolicy, orgid, owner).asTry
          }
          else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxBusinessPolicies+ " business policies")).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/business/policies/"+bareBusinessPolicy+" result: "+xs.toString)
      xs match {
        case Success(_) => if (owner != "") AuthCache.business.putOwner(businessPolicy, owner)     // currently only users are allowed to update business policy resources, so owner should never be blank
          AuthCache.business.putIsPublic(businessPolicy, isPub = false)
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "business policy '"+businessPolicy+"' created")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "business policy '" + businessPolicy + "' not created: " + t.getMessage)
        } else if (t.getMessage.contains("duplicate key value violates unique constraint")) {
          resp.setStatus(HttpCode.ALREADY_EXISTS)
          ApiResponse(ApiResponseType.ALREADY_EXISTS, "business policy '" + businessPolicy + "' already exists: " + t.getMessage)
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "business policy '"+businessPolicy+"' not created: "+t.getMessage)
        }
      }
    })
  })

  // =========== PUT /orgs/{orgid}/business/policies/{policy} ===============================
  val putBusinessPolicies =
    (apiOperation[ApiResponse]("putBusinessPolicies")
      summary "Updates a business policy"
      description """Updates a business policy resource. This can only be called by the user that created it."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("policy", DataType.String, Option[String]("Business Policy name."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutBusinessPolicyRequest],
          Option[String]("Business Policy object that needs to be updated in the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putBusinessPolicies2 = (apiOperation[PostPutBusinessPolicyRequest]("putBusinessPolicies2") summary("a") description("a"))  // for some bizarre reason, the PutBusinessPolicyRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/business/policies/:policy", operation(putBusinessPolicies)) ({
    val orgid = params("orgid")
    val bareBusinessPolicy = params("policy")   // but do not have a hack/fix for the name
    val businessPolicy = OrgAndId(orgid,bareBusinessPolicy).toString
    val ident = authenticate().authorizeTo(TBusiness(businessPolicy),Access.WRITE)
    val policyReq = try { parse(request.body).extract[PostPutBusinessPolicyRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    policyReq.validate()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    val (valServiceIdActions, svcRefs) = policyReq.validateServiceIds  // to check that the services referenced exist
    db.run(valServiceIdActions.asTry.flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/business/policies"+bareBusinessPolicy+" service validation: "+xs.toString)
      xs match {
        case Success(v) => var invalidIndex = -1    // v is a vector of Int (the length of each service query). If any are zero we should error out.
          breakable { for ( (len, index) <- v.zipWithIndex) {
            if (len <= 0) {
              invalidIndex = index
              break
            }
          } }
          if (invalidIndex < 0) policyReq.getDbUpdate(businessPolicy, orgid, owner).asTry
          else {
            val errStr = if (invalidIndex < svcRefs.length) "the following referenced service does not exist in the exchange: org="+svcRefs(invalidIndex).org+", url="+svcRefs(invalidIndex).url+", version="+svcRefs(invalidIndex).versionRange+", arch="+svcRefs(invalidIndex).arch
              else "the "+Nth(invalidIndex+1)+" referenced service does not exist in the exchange"
            DBIO.failed(new Throwable(errStr)).asTry
          }
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/business/policies/"+bareBusinessPolicy+" result: "+xs.toString)
      xs match {
        case Success(n) => try {
            val numUpdated = n.toString.toInt     // i think n is an AnyRef so we have to do this to get it to an int
            if (numUpdated > 0) {
              if (owner != "") AuthCache.business.putOwner(businessPolicy, owner)     // currently only users are allowed to update business policy resources, so owner should never be blank
              AuthCache.business.putIsPublic(businessPolicy, isPub = false)
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "business policy updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "business policy '"+businessPolicy+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "business policy '"+businessPolicy+"' not updated: "+e) }    // the specific exception is NumberFormatException
        case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "business policy '"+businessPolicy+"' not updated: "+t.getMessage)
      }
    })
  })

  // =========== PATCH /orgs/{orgid}/business/policies/{policy} ===============================
  val patchBusinessPolicies =
    (apiOperation[Map[String,String]]("patchBusinessPolicies")
      summary "Updates 1 attribute of a business policy"
      description """Updates one attribute of a business policy in the exchange DB. This can only be called by the user that originally created this business policy resource."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("policy", DataType.String, Option[String]("Business Policy name."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchBusinessPolicyRequest],
          Option[String]("Partial business policy object that contains an attribute to be updated in this business policy. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val patchBusinessPolicies2 = (apiOperation[PatchBusinessPolicyRequest]("patchBusinessPolicies2") summary("a") description("a"))  // for some bizarre reason, the PatchBusinessPolicyRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  patch("/orgs/:orgid/business/policies/:policy", operation(patchBusinessPolicies)) ({
    val orgid = params("orgid")
    val bareBusinessPolicy = params("policy")   // but do not have a hack/fix for the name
    val businessPolicy = OrgAndId(orgid,bareBusinessPolicy).toString
    authenticate().authorizeTo(TBusiness(businessPolicy),Access.WRITE)
    val policyReq = try { parse(request.body).extract[PatchBusinessPolicyRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.trace("PATCH /orgs/"+orgid+"/business/policies/"+bareBusinessPolicy+" input: "+policyReq.toString)
    val resp = response
    val (action, attrName) = policyReq.getDbUpdate(businessPolicy, orgid)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid business policy attribute specified"))
    val (valServiceIdActions, svcRefs) = if (attrName == "service") BusinessPoliciesTQ.validateServiceIds(policyReq.service.get, List())
      else if (attrName == "userInput") BusinessPoliciesTQ.validateServiceIds(BService("","","",List(),None), policyReq.userInput.get)
      else (DBIO.successful(Vector()), Vector())
    db.run(valServiceIdActions.asTry.flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/business/policies"+bareBusinessPolicy+" service validation: "+xs.toString)
      xs match {
        case Success(v) => var invalidIndex = -1    // v is a vector of Int (the length of each service query). If any are zero we should error out.
          breakable { for ( (len, index) <- v.zipWithIndex) {
            if (len <= 0) {
              invalidIndex = index
              break
            }
          } }
          if (invalidIndex < 0) action.transactionally.asTry
          else {
            val errStr = if (invalidIndex < svcRefs.length) "the following referenced service does not exist in the exchange: org="+svcRefs(invalidIndex).org+", url="+svcRefs(invalidIndex).url+", version="+svcRefs(invalidIndex).versionRange+", arch="+svcRefs(invalidIndex).arch
              else "the "+Nth(invalidIndex+1)+" referenced service does not exist in the exchange"
            DBIO.failed(new Throwable(errStr)).asTry
          }
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/business/policies/"+bareBusinessPolicy+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "attribute '"+attrName+"' of business policy '"+businessPolicy+"' updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "business policy '"+businessPolicy+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "business policy '"+businessPolicy+"' not updated: "+t.getMessage)
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/business/policies/{policy} ===============================
  val deleteBusinessPolicies =
    (apiOperation[ApiResponse]("deleteBusinessPolicies")
      summary "Deletes a business policy"
      description "Deletes a business policy from the exchange DB. Can only be run by the owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("policy", DataType.String, Option[String]("Business Policy name."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/business/policies/:policy", operation(deleteBusinessPolicies)) ({
    val orgid = params("orgid")
    val bareBusinessPolicy = params("policy")   // but do not have a hack/fix for the name
    val businessPolicy = OrgAndId(orgid,bareBusinessPolicy).toString
    authenticate().authorizeTo(TBusiness(businessPolicy),Access.WRITE)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(BusinessPoliciesTQ.getBusinessPolicy(businessPolicy).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/business/policies/"+bareBusinessPolicy+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.business.removeOwner(businessPolicy)
            AuthCache.business.removeIsPublic(businessPolicy)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "business policy deleted")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "business policy '"+businessPolicy+"' not found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "business policy '"+businessPolicy+"' not deleted: "+t.toString)
      }
    })
  })

  // ======== POST /org/{orgid}/business/policies/{policy}/search ========================
  val postBusinessPolicySearch =
    (apiOperation[PostBusinessPolicySearchResponse]("postBusinessPolicySearch")
      summary("Returns matching nodes for this business policy")
      description """Returns the matching nodes for this business policy that do not already have an agreement for the specified service. Can be run by a user or agbot (but not a node). The **request body** structure:

```
{
  "nodeOrgids": [ "org1", "org2", "..." ],   // if not specified, defaults to the same org the business policy is in
  "changedSince": 123456,     // only return nodes that have changed since this unix epoch time, 0 if you want all relevant nodes
  "startIndex": 0,    // for pagination, ignored right now
  "numEntries": 0    // ignored right now
}
```"""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("policy", DataType.String, Option[String]("Business Policy id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostBusinessPolicySearchRequest],
        Option[String]("Search criteria to find matching nodes in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postBusinessPolicySearch2 = (apiOperation[PostBusinessPolicySearchRequest]("postBusinessPolicySearch2") summary("a") description("a"))

  /** Normally called by the agbot to search for available nodes. */
  post("/orgs/:orgid/business/policies/:policy/search", operation(postBusinessPolicySearch)) ({
    val orgid = params("orgid")
    val bareBusinessPolicy = params("policy")
    val businessPolicy = OrgAndId(orgid,bareBusinessPolicy).toString
    authenticate().authorizeTo(TNode(OrgAndId(orgid,"*").toString),Access.READ)
    val searchProps = try { parse(request.body).extract[PostBusinessPolicySearchRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    searchProps.validate()
    logger.debug("POST /orgs/"+orgid+"/business/policies/"+bareBusinessPolicy+"/search criteria: "+searchProps.toString)
    val nodeOrgids = searchProps.nodeOrgids.getOrElse(List(orgid)).toSet
    var searchSvcUrl = ""    // a composite value (org/url), will be set later in the db.run()
    val resp = response

    // First get the service out of the business policy
    db.run(BusinessPoliciesTQ.getService(businessPolicy).result.flatMap({ list =>
      logger.debug("POST /orgs/"+orgid+"/business/policies/"+bareBusinessPolicy+"/search getService size: "+list.size)
      if (list.nonEmpty) {
        // Finding the service was successful, form the query for the nodes for the next step
        val service = BusinessPoliciesTQ.getServiceFromString(list.head)    // we should have found only 1 business pol service string, now parse it to get service list
        searchSvcUrl = OrgAndId(service.org, service.name).toString
        /*
          Narrow down the db query results as much as possible by joining the Nodes and NodeAgreements tables and filtering.
          In english, the join gets: n.id, n.msgEndPoint, n.publicKey, a.serviceUrl, a.state
          The filters are: n is in the given list of node orgs, n.pattern is not set, the node is not stale, the node arch matches the service arch (the filter a.state=="" is applied later in our code below)
          After this we have to go thru all of the results and find nodes that do NOT have an agreement for searchSvcUrl.
          Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which is why the agreement cols are Option() )
        */
        val oldestTime = if (searchProps.changedSince > 0) ApiTime.thenUTC(searchProps.changedSince) else ApiTime.beginningUTC
        val nodeQuery =
          for {
            //todo: also check for update time of node agreement and node policy
            (n, a) <- NodesTQ.rows.filter(_.orgid inSet(nodeOrgids)).filter(_.pattern === "").filter(_.publicKey =!= "").filter(_.lastHeartbeat >= oldestTime).filter(n => {n.arch === service.arch || service.arch == "" || service.arch == "*"}) joinLeft NodeAgreementsTQ.rows on (_.id === _.nodeId)
          } yield (n.id, n.msgEndPoint, n.publicKey, a.map(_.agrSvcUrl), a.map(_.state))
        nodeQuery.result.asTry    // Now get the potential nodes to make agreements with
      }
      else DBIO.failed(new Throwable("business policy '"+businessPolicy+"' not found")).asTry
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/business/policies/"+bareBusinessPolicy+"/search result size: "+xs.getOrElse(Vector()).size)
      //logger.trace("POST /orgs/"+orgid+"/business/policies/"+bareBusinessPolicy+"/search result: "+xs.toString)
      logger.trace("POST /orgs/"+orgid+"/business/policies/"+bareBusinessPolicy+"/search: looking for nodes w/o agreement for '"+searchSvcUrl)
      xs match {
        case Success(list) => if (list.nonEmpty) {
          // Go thru the rows and build a hash of the nodes that do NOT have an agreement for our service
          //todo: filter on arch when node arch is available (support arch=* too)
          //todo: factor in num agreements??
          val nodeHash = new MutableHashMap[String,BusinessPolicySearchHashElement]     // key is node id, value noAgreementYet which is true if so far we haven't hit an agreement for our service for this node
          for ( (nodeid, msgEndPoint, publicKey, agrSvcUrlOpt, stateOpt) <- list ) {
            //logger.trace("nodeid: "+nodeid+", agrSvcUrlOpt: "+agrSvcUrlOpt.getOrElse("")+", searchSvcUrl: "+searchSvcUrl+", stateOpt: "+stateOpt.getOrElse(""))
            nodeHash.get(nodeid) match {
              // This node is already in the hash. Only replace it if this is an agreement for the service, because the absence of an agr for this svc isn't useful info
              case Some(_) => if (agrSvcUrlOpt.getOrElse("") == searchSvcUrl && stateOpt.getOrElse("") != "") { /*logger.trace("setting to false");*/ nodeHash.put(nodeid, BusinessPolicySearchHashElement(msgEndPoint, publicKey, noAgreementYet = false)) }  // this is no longer a candidate
              // This node is not yet in the hash. Add it with whatever value it has for agreement - this may be overridden later
              case None => val noAgr = if (agrSvcUrlOpt.getOrElse("") == searchSvcUrl && stateOpt.getOrElse("") != "") false else true
                nodeHash.put(nodeid, BusinessPolicySearchHashElement(msgEndPoint, publicKey, noAgr))   // this node not in the hash yet, add it
            }
          }
          // Convert our hash to the list response of the rest api
          //val respList = list.map( x => BusinessPolicyNodeResponse(x._1, x._2, x._3)).toList
          val respList = new ListBuffer[BusinessPolicyNodeResponse]
          for ( (k, v) <- nodeHash) if (v.noAgreementYet) respList += BusinessPolicyNodeResponse(k, v.msgEndPoint, v.publicKey)
          if (respList.nonEmpty) resp.setStatus(HttpCode.POST_OK)
          else resp.setStatus(HttpCode.NOT_FOUND)
          PostBusinessPolicySearchResponse(respList.toList, 0)
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          PostBusinessPolicySearchResponse(List[BusinessPolicyNodeResponse](), 0)
        }
        case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "invalid input: "+t.getMessage)
      }
    })
  })

}