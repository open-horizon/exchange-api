package org.openhorizon.exchangeapi.route.deploymentpolicy

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{POST, Path}
import org.json4s.DefaultFormats
import org.json4s.jackson.{JsonMethods, Serialization}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.table.deploymentpolicy.search.{SearchOffsetPolicyAttributes, SearchOffsetPolicyTQ}
import org.openhorizon.exchangeapi.table.deploymentpolicy.{BService, BusinessPoliciesTQ}
import org.openhorizon.exchangeapi.table.node.{NodeType, NodesTQ}
import org.openhorizon.exchangeapi.table.node.agreement.NodeAgreementsTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.Compiled

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/business/policies/{policy}/search")
trait DeploymentPolicySearch extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ======== POST /org/{organization}/business/policies/{policy}/search ========================
  @POST
  @Operation(
    summary = "Returns matching nodes for this business policy",
    description = "Returns the matching nodes for this business policy that do not already have an agreement for the specified service. Can be run by a user or agbot (but not a node).",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "policy",
        in = ParameterIn.PATH,
        description = "Pattern name."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "changedSince": "123456L",
  "nodeOrgids": ["org1", "org2", "..."],
  "numEntries": 100,
  "session": "token"
}"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostBusinessPolicySearchRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        content = Array(new Content(mediaType = "application/json",
                                    schema = new Schema(implementation = classOf[PostBusinessPolicySearchResponse]))),
        description = "response body",
        responseCode = "201"
      ),
      new responses.ApiResponse(
        description = "bad request",
        responseCode = "400"
      ),
      new responses.ApiResponse(
        description = "invalid credentials",
        responseCode = "401"
      ),
      new responses.ApiResponse(
        description = "access denied",
        responseCode = "403"
      ),
      new responses.ApiResponse(
        description = "not found",
        responseCode = "404"
      ),
      new responses.ApiResponse(
        content = Array(new Content(mediaType = "application/json",
                                    schema = new Schema(implementation = classOf[PolicySearchResponseDesync]))),
        description = "old session",
        responseCode = "409"
      )
    )
  )
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment policy")
  def postDeploymentPolicySearch(@Parameter(hidden = true) deploymentPolicy: String,
                                 @Parameter(hidden = true) identity: Identity2,
                                 @Parameter(hidden = true) organization: String,
                                 @Parameter(hidden = true) resource: String,
                                 @Parameter(hidden = true) reqBody: PostBusinessPolicySearchRequest): Route = {
    Future { logger.debug(s"POST /org/${organization}/deployment/policies/${deploymentPolicy}/search - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
    Future { logger.debug(s"POST /org/${organization}/deployment/policies/${deploymentPolicy}/search - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - request-body: ${reqBody.toString}") }
    
    implicit val formats: DefaultFormats.type = DefaultFormats
    val nodeOrgids: Set[String] = reqBody.nodeOrgids.getOrElse(List(organization)).toSet
    
    def getService(deployPolicyService: String): BService = JsonMethods.parse(deployPolicyService).extract[BService]
    
    def getOptArch(service: BService): Option[String] =
      if(service.arch.equals("") ||
         service.arch.equals("*"))
        None
      else
        Option(service.arch)
    
    def getCurrentOffset(currentOffsetSession: Option[(Option[String], Option[String])]): Option[String] =
      if (currentOffsetSession.isDefined)
        currentOffsetSession.get._1
      else
        None
    
    def getCurrentSession(currentOffsetSession: Option[(Option[String], Option[String])]): Option[String] =
      if (currentOffsetSession.isDefined)
        currentOffsetSession.get._2
      else
        None
    
    // Figure out what our offset is going to be in this DB query.
    def getOffset(currentOffset: Option[String], currentSession: Option[String]): Option[String] =
      if (currentOffset.isEmpty && 0L < reqBody.changedSince) // New workflow for abgot and policy, use the changedSince they passed in
        Option(Instant.ofEpochSecond(reqBody.changedSince).toString)
      else if (currentOffset.isDefined &&
               currentSession.isDefined &&
               reqBody.session.isDefined &&
               currentSession.get.equals(reqBody.session.get)) // the session they passed in equals the session stored in the DB, so use that offset
               currentOffset
      else
        None // No previous pagination, so we don't limit how far back we look at the nodes
    
    /*
       Filter the nodes in the DB to return the nodes that:
        - the arch matches the service arch (including wildcards)
        - are not pre-created nodes from which the agent has never communicated to us yet
        - have changed since the offset or changedSince
        - are in the given list of node orgs
        - pattern is not set
        - have publicKey set (are registered)
        - does not have an agreement for this service with a non-empty state
       Notes:
        - about Slick usage: joinLeft returns node rows even if they don't have any agreements (which is why the agreement cols are Option() )
        - Live-lock will occur if the resulting number of nodes with the same lastUpdated is greater than the size of the page being returned. In that case we have no choice
          but to keep having the agbots call us with the same offset until that is no longer the case (because the agbots have processed some nodes and made agreements with them)
     */
    def nodes(optArch: Option[String], offset: Option[String], searchSvcUrl: String): Query[(Rep[String], Rep[String], Rep[String], Rep[String]), (String, String, String, String), Seq] =
      NodesTQ.filterOpt(optArch)((node, arch) => node.arch === arch)
             .filter(_.lastHeartbeat.isDefined) // do not return pre-created nodes from which the agent has never communicated to us yet
             // Note: since the timestamp for lastUpdated/changedSince/offset is an inexact boundary (i.e. there can be multiple nodes with the same lastlastUpdated value,
             //     some of which weren't returned last time), we err on the side of possibly returning some nodes we already returned, rather that possibly missing some nodes.
             .filterOpt(offset)((node, changedSince) => !(node.lastUpdated < changedSince)) // here changedSince is either currentOffset or converted reqBody.changedSince
             .filter(_.orgid inSet nodeOrgids)
             .filter(_.pattern === "")
             .filter(_.publicKey =!= "")
             .map(node => (node.id, node.lastUpdated, node.nodeType, node.publicKey))
              /*
                 The joinLeft will create rows like: node.id, node.lastUpdated, node.nodeType, node.publicKey, agreement.agrSvcUrl, agreement.nodeId, agreement.state
                 with a few caveats:
                   - only agreements which are for searchSvcUrl will be included
                   - if there is no agreement for searchSvcUrl for a node, the 3 agreement fields will be None
                   - if there are multiple agreements for searchSvcUrl for a node (not supposed to be, but could be), the node will be repeated in the output, but will be filtered out later on
               */
             .joinLeft(NodeAgreementsTQ.filter(_.agrSvcUrl === searchSvcUrl) // only join with agreements that are for this service, so we can filter those out below
                                       .map(agreement => (agreement.agrSvcUrl, agreement.nodeId, agreement.state)))
             .on((node, agreement) => node._1 === agreement._2) // (node.id === agreements.nodeId)
             .filter ({
               // Since we joined with agreements for this service, now we only keep nodes in our list that don't have any associated agreement or the agreement state is empty
               case (_, agreement) =>
                 agreement.map(_._2).isEmpty ||               // agreement.nodeId
                 agreement.map(_._1).getOrElse("") === "" ||  // agreement.agrSvcUrl
                 agreement.map(_._3).getOrElse("") === ""     // agreement.state
             })
             .sortBy(r => (r._1._2.asc, r._1._1.asc, r._2.getOrElse(("", "", ""))._1.asc.nullsFirst)) // (node.lastUpdated ASC, node.id ASC, agreements.agrSvcUrl ASC NULLS FIRST)
             .map(r => (r._1._1, r._1._2, r._1._3, r._1._4)) // (node.id, node.lastUpdated, node.nodeType, node.publicKey)
    
    // Build the DB query that includes the pagination and node/agreement filtering
    val pagination: DBIOAction[(List[(String, String, String, String)], Boolean), NoStream, Effect.Read with Effect with Effect.Write] =
      for {
        deployPolicyService <-
          Compiled(BusinessPoliciesTQ.getService(resource)).result.headOption
        
        _ <-
          if (deployPolicyService.isEmpty)
            DBIO.failed(new Throwable(ExchMsg.translate("business.policy.not.found", resource)))
          else
            DBIO.successful(())
        
        service: BService = getService(deployPolicyService.get)
        optArch: Option[String] = getOptArch(service)
        
        // Grab the offset and session that is in the DB from the last query of this agbot and policy
        // Note: the offset is a lastUpdated UTC timestamp, whereas reqBody.changedSince is Unix epoch seconds, but they have the same meaning.
        currentOffsetSession: Option[(Option[String], Option[String])] <-
          Compiled(SearchOffsetPolicyTQ.getOffsetSession(identity.resource, resource)).result.headOption // returns Option[(offset, session)]
        
        currentOffset: Option[String] = getCurrentOffset(currentOffsetSession)
        currentSession: Option[String] = getCurrentSession(currentOffsetSession)
        offset: Option[String] = getOffset(currentOffset, currentSession)
        
        // If this is a desynchronized agbot (one using a different session than is stored in the DB), setting desynchronization will cause us to return 409 to let the agbot know
        // it is out of sync with the other agbots, and we will also return the current session/offset that they should begin using.
        // In the case of catastrophic failure of the entire set of agbots, we will return Http code 409 to each of them, and they will each be redirected to using the session again.
        _ <-
          if (currentSession.isDefined &&
              reqBody.session.isDefined &&
              !currentSession.get.equals(reqBody.session.get))
            DBIO.failed(PolicySearchResponseDesync(agbot = identity.resource, offset = currentOffset, session = currentSession))
          else
            DBIO.successful(())
        
        // If paginating then limit the query to that number of rows, else return everything.
        nodesWoAgreements: List[(String, String, String, String)] <- {
          if (reqBody.numEntries.isDefined)
            Compiled(nodes(optArch = optArch, offset = offset, searchSvcUrl = OrgAndId(service.org, service.name).toString).take(reqBody.numEntries.getOrElse(0)))
          else
            Compiled(nodes(optArch = optArch, offset = offset, searchSvcUrl = OrgAndId(service.org, service.name).toString))
        }.result.map(List[(String, String, String, String)])
        
        // Decide what offset should be stored in our DB for the next agbot call.
        updateOffset: Option[String] =
          if (reqBody.numEntries.isDefined) {
            if (nodesWoAgreements.nonEmpty &&
                (currentOffset.isEmpty ||
                 (currentOffset.get < nodesWoAgreements.lastOption.get._2 && // nodesWoAgreements.lastOption.get._2 is the lastUpdated field of the last node in the list
                  nodesWoAgreements.sizeIs == reqBody.numEntries.get))) // Normal pagination case: we filled a page and the lastUpdated at the end of the page is newer than the offset.
              Option(nodesWoAgreements.lastOption.get._2)
            //todo: i think implied in this next condition is lastUpdated of the last row is the same as offset, because if that wasn't the case the previous if stmt would have been true. But that logic is pretty complex because there are several other parts to the condition, so it would be better to explicitly test for that.
            else if (currentOffset.isDefined &&
                     currentSession.isDefined &&
                     reqBody.session.isDefined &&
                     currentSession.get.equals(reqBody.session.get) &&
                     nodesWoAgreements.sizeIs == reqBody.numEntries.get) // Last row has the same lastUpdated as the current offset (i.e. all rows in the page have same lastUpdated).
              currentOffset // I think this is what is called live-lock above. We have no choice but to return the same offset as we used this time
            else // We didn't fill the page, so we are done with this session/workflow.
              None
          }
          else // We gave them everything, so the current session is over
            None
        
        // Return in the response body whether or not this query resulted in the offset being changed.
        isOffsetUpdated: Boolean =
          if (updateOffset.isEmpty ||
              (currentOffset.isDefined && currentOffset.get.equals(updateOffset.get)))
            false
          else
            true
        
        // Decide what session should be stored in our DB for the next call
        // Clear/continue/set/update offset and session for the next call.
        // This call structure works because the same datatype (Int) is yielded.
        // Could return 0 or 1 depending.
        sessionChanges <-
          if (reqBody.numEntries.isDefined &&
              reqBody.session.isDefined) {
            if (currentSession.isEmpty &&
                nodesWoAgreements.nonEmpty &&
                nodesWoAgreements.sizeIs == reqBody.numEntries.get) // New workflow. We didn't have a saved session and we only returned partial results, so save the session they gave us.
              SearchOffsetPolicyTQ += SearchOffsetPolicyAttributes(identity.resource, updateOffset, resource, reqBody.session)
            else if (currentSession.isDefined &&
                     currentSession.get.equals(reqBody.session.get) &&
                     nodesWoAgreements.sizeIs == reqBody.numEntries.get) // Continue workflow.
              Compiled(SearchOffsetPolicyTQ.filter(sessions => sessions.agbot === identity.resource &&
                                                               sessions.policy === resource &&
                                                               sessions.session === currentSession)
                                           .map(_.offset))
                                           .update(updateOffset)
            else // End of workflow.
              Compiled(SearchOffsetPolicyTQ.filter(sessions => sessions.agbot === identity.resource &&
                                                               sessions.policy === resource))
                                           .delete
          }
          else // No defined workflow. Either they didn't give us numEntries or didn't give us session
            Compiled(SearchOffsetPolicyTQ.filter(sessions => sessions.agbot === identity.resource &&
                                                             sessions.policy === resource))
                                         .delete
      } yield (nodesWoAgreements, isOffsetUpdated)
    
    complete({
      db.run(pagination.transactionally.asTry).map({
        case Success(results) =>
          Future { logger.debug(s"POST /org/${organization}/deployment/policies/${deploymentPolicy}/search - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - result: ${results._1}") }
          if(results._1.nonEmpty) { // results.nodesWoAgreements.nonEmpty.
            (HttpCode.POST_OK,
              PostBusinessPolicySearchResponse(
                results._1.map( // results.nodesWoAgreements
                  node =>
                    BusinessPolicyNodeResponse(
                      node._1,                              // node.id
                      node._3 match {                       // node.nodeType
                        case "" => NodeType.DEVICE.toString // "" -> "device"
                        case _ => node._3                   // Passthrough
                      },
                      node._4)),                            // node.publicKey
                results._2)) // results.isOffsetUpdated
          }
          else {
            Future { logger.debug(s"POST /org/${organization}/deployment/policies/${deploymentPolicy}/search - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - results._1.nonEmpty:${results._1.nonEmpty} - ${(HttpCode.NOT_FOUND, Serialization.write(PostBusinessPolicySearchResponse(List[BusinessPolicyNodeResponse](), results._2)))}") }
            (HttpCode.NOT_FOUND, PostBusinessPolicySearchResponse(List[BusinessPolicyNodeResponse](), results._2))
          } // results.isOffsetUpdated
        case Failure(exception: PolicySearchResponseDesync) =>
          Future { logger.debug(s"POST /org/${organization}/deployment/policies/${deploymentPolicy}/search - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.ALREADY_EXISTS2, Serialization.write(exception))}") }
          (HttpCode.ALREADY_EXISTS2, exception) // Throw Http code 409 - Conflict, return no results.
        case Failure(exception: org.postgresql.util.PSQLException) =>
          Future { logger.debug(s"POST /org/${organization}/deployment/policies/${deploymentPolicy}/search - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("invalid.input.message", exception.getMessage)))}") }
          ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("invalid.input.message", exception.getMessage))
        case Failure(exception) =>
          Future { logger.debug(s"POST /org/${organization}/deployment/policies/${deploymentPolicy}/search - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.BAD_INPUT, Serialization.write(ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", exception.getMessage))))}") }
          (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", exception.getMessage)))
      })
    })
  }
  
  def deploymentPolicySearch(identity: Identity2): Route =
    path("orgs" / Segment / ("business" | "deployment") / "policies" / Segment / "search") {
      (organization,
       deploymentPolicy) =>
        post {
          entity(as[PostBusinessPolicySearchRequest]) {
            reqBody =>
              val resource: String = OrgAndId(organization, deploymentPolicy).toString
              
              exchAuth(TNode(OrgAndId(organization, "*").toString), Access.READ, validIdentity = identity) {
                _ =>
                  validateWithMsg(if (!((!(reqBody.changedSince < 0L)) &&
                                        (reqBody.numEntries.isEmpty ||
                                         !(reqBody.numEntries.getOrElse(-1) < 0))))
                                    Option(ExchMsg.translate("bad.input"))
                                  else
                                    None) {
                    postDeploymentPolicySearch(deploymentPolicy,
                                               identity,
                                               organization,
                                               resource,
                                               reqBody)
                  }
              }
          }
        }
    }
}
